package io.plainround.seatwave.filters.study;

import com.zaxxer.hikari.HikariDataSource;
import io.plainround.seatwave.filters.bloom.BloomFilter;
import io.plainround.seatwave.filters.bloom.BloomSizing;
import io.plainround.seatwave.filters.signup.JdbcUsernameStore;
import io.plainround.seatwave.filters.signup.UsernameCheck;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import javax.sql.DataSource;

/**
 * The reduced-scale study of Section 8: on a {@code customers} table that {@link LoadUsernamesCli}
 * filled with {@code fan_1} to {@code fan_N}, it measures the unique index's size per name, what a
 * username check costs with and without the filter in front, the filter's real false-positive rate
 * against its design, and the rebuild time per million names; then it prints the arithmetic that
 * scales each number to the module's 1,000,000,000 names, labeled as estimated.
 *
 * <p>Free names are {@code fan_<random>x}: never added, and spread over the whole index like the
 * variations fans really type, so a check for one descends to a random leaf, as a taken name does.
 */
public final class UsernameStudyCli {

  private static final Path SETTINGS = Path.of("config/lab.properties");
  private static final long MIX_SEED = 21L; // fixed, so every run checks the same names
  private static final long WARM_UP_SEED = 22L;
  private static final long SECOND_WARM_UP_SEED = 23L;
  private static final long FILTER_SEED = 0x5ea7_3a7eL; // a per-deployment secret in production
  private static final double FREE_SHARE = 0.90; // Section 7: 90 percent of checks are free names
  private static final double TARGET_RATE = 0.01;
  // Section 7 sizes the filter for next year's count: 1,200,000,000 for today's 1,000,000,000.
  private static final double DESIGN_HEADROOM = 1.2;
  private static final long MODULE_NAMES = 1_000_000_000L;
  private static final double PEAK_CHECKS_PER_SECOND = 5_000; // Section 7's sign-up peak
  // PostgreSQL's cumulative statistics reach the views when a backend has been idle for up to a
  // second; three seconds of quiet leaves margin.
  private static final Duration STATISTICS_FLUSH = Duration.ofSeconds(3);
  private static final double NANOS_PER_MILLI = 1e6;
  private static final double NANOS_PER_SECOND = 1e9;
  private static final double MILLION = 1e6;
  private static final double SECONDS_PER_MINUTE = 60;
  private static final double BYTES_PER_MB = 1e6;
  private static final double PERCENTILE_50 = 0.50;
  private static final double PERCENTILE_99 = 0.99;

  private static final String INDEX_FACTS =
      """
      SELECT s.tree_level, s.index_size, s.internal_pages, s.leaf_pages, s.avg_leaf_density,
             pg_relation_size('customers'), current_setting('shared_buffers'),
             (SELECT setting::bigint * 8192 FROM pg_settings WHERE name = 'shared_buffers')
      FROM pgstatindex('one_customer_per_ref') AS s\
      """;
  private static final String INDEX_BLOCKS =
      "SELECT idx_blks_read, idx_blks_hit FROM pg_statio_user_indexes"
          + " WHERE indexrelname = 'one_customer_per_ref'";
  private static final String HIGHEST_ID = "SELECT max(customer_id) FROM customers";

  private UsernameStudyCli() {}

  /** One check's time and whether it read the database. */
  private record Timing(long nanos, boolean readTheStore) {}

  /** What one phase of checks measured. */
  private record Phase(
      UsernameCheck.CheckCounts counts, List<Timing> timings, long blocksRead, long blocksHit) {}

  /** Runs the study and prints one line per measurement. */
  public static void main(String[] arguments) throws InterruptedException {
    LabSettings settings = LabSettings.load(SETTINGS);
    try (HikariDataSource pool = LabDatabase.openPool(settings)) {
      run(pool, settings);
    }
  }

  private static void run(DataSource pool, LabSettings settings) throws InterruptedException {
    long names = single(pool, HIGHEST_ID);
    double indexBytes = printIndexFacts(pool, names);
    JdbcUsernameStore store = new JdbcUsernameStore(pool);
    int checks = settings.studyChecks();

    warmUp(store, names, checks, WARM_UP_SEED);
    Phase without = measure(new UsernameCheck(store), mix(names, checks, MIX_SEED), pool);
    printPhase("no filter", without);

    UsernameCheck check = new UsernameCheck(store);
    BloomSizing design = BloomSizing.forTarget(Math.round(names * DESIGN_HEADROOM), TARGET_RATE);
    long started = System.nanoTime();
    long loaded = check.rebuild(design, FILTER_SEED);
    long rebuildNanos = System.nanoTime() - started;
    printRebuild("rebuild, design 1.2 n", loaded, rebuildNanos, design);
    BloomSizing exact = BloomSizing.forTarget(names, TARGET_RATE);
    BloomFilter exactFilter = new BloomFilter(exact, FILTER_SEED);
    started = System.nanoTime();
    loaded = store.forEachUsername(exactFilter::add);
    printRebuild("rebuild, design n", loaded, System.nanoTime() - started, exact);

    // The scans read the index in order and leave its last leaves in the cache; random checks
    // again bring it back to the state the first phase measured.
    warmUp(store, names, checks, SECOND_WARM_UP_SEED);
    Phase with = measure(check, mix(names, checks, MIX_SEED), pool);
    printPhase("filter", with);

    BloomFilter designFilter = check.installedFilter().orElseThrow();
    double designRate = probe(designFilter, settings.studyProbes(), names);
    double exactRate = probe(exactFilter, settings.studyProbes(), names);
    printProbe("design 1.2 n", designFilter, designRate, names, settings.studyProbes());
    printProbe("design n", exactFilter, exactRate, names, settings.studyProbes());
    printScaled(indexBytes / names, without, with, designRate);
    printRebuildScaled(rebuildNanos, loaded);
  }

  private static double printIndexFacts(DataSource pool, long names) {
    try (Connection connection = pool.getConnection();
        PreparedStatement statement = connection.prepareStatement(INDEX_FACTS);
        ResultSet facts = statement.executeQuery()) {
      facts.next();
      long indexBytes = facts.getLong(2);
      long leafPages = facts.getLong(4);
      long cacheBytes = facts.getLong(8);
      System.out.printf(
          Locale.ROOT,
          "names: %,d (fan_1 to fan_%d)%n"
              + "one_customer_per_ref: %,d bytes, %.1f MB; %,d leaf pages, %,d internal; levels %d"
              + " above the leaves; leaves %.2f percent full%n"
              + "per name: %.2f bytes of index, %.1f names per leaf; table customers %.1f MB%n"
              + "shared_buffers: %s, %.3f of the index%n",
          names,
          names,
          indexBytes,
          indexBytes / BYTES_PER_MB,
          leafPages,
          facts.getLong(3),
          facts.getInt(1),
          facts.getDouble(5),
          (double) indexBytes / names,
          (double) names / leafPages,
          facts.getLong(6) / BYTES_PER_MB,
          facts.getString(7),
          (double) cacheBytes / indexBytes);
      return indexBytes;
    } catch (SQLException failure) {
      throw new IllegalStateException("could not read the index's size", failure);
    }
  }

  private static List<String> mix(long names, int checks, long seed) {
    Random random = new Random(seed);
    List<String> candidates = new ArrayList<>(checks);
    for (int checkIndex = 0; checkIndex < checks; checkIndex++) {
      String taken = "fan_" + (1 + random.nextLong(names));
      candidates.add(random.nextDouble() < FREE_SHARE ? taken + "x" : taken);
    }
    return candidates;
  }

  private static void warmUp(JdbcUsernameStore store, long names, int checks, long seed) {
    long started = System.nanoTime();
    mix(names, checks, seed).forEach(store::isTaken);
    System.out.printf(
        Locale.ROOT,
        "warm-up: %,d random lookups in %.1f s%n",
        checks,
        (System.nanoTime() - started) / NANOS_PER_SECOND);
  }

  private static Phase measure(UsernameCheck check, List<String> candidates, DataSource pool)
      throws InterruptedException {
    long[] before = blocks(pool);
    List<Timing> timings = new ArrayList<>(candidates.size());
    for (String candidate : candidates) {
      long started = System.nanoTime();
      UsernameCheck.Availability answer = check.check(candidate);
      long nanos = System.nanoTime() - started;
      boolean fromFilter =
          answer instanceof UsernameCheck.Availability.Free free && free.answeredFromFilter();
      timings.add(new Timing(nanos, !fromFilter));
    }
    Thread.sleep(STATISTICS_FLUSH);
    long[] after = blocks(pool);
    return new Phase(check.counts(), timings, after[0] - before[0], after[1] - before[1]);
  }

  private static long[] blocks(DataSource pool) {
    try (Connection connection = pool.getConnection();
        PreparedStatement statement = connection.prepareStatement(INDEX_BLOCKS);
        ResultSet row = statement.executeQuery()) {
      row.next();
      return new long[] {row.getLong(1), row.getLong(2)};
    } catch (SQLException failure) {
      throw new IllegalStateException("could not read the index's block counters", failure);
    }
  }

  private static void printPhase(String label, Phase phase) {
    UsernameCheck.CheckCounts counts = phase.counts();
    long totalNanos = phase.timings().stream().mapToLong(Timing::nanos).sum();
    long[] storeNanos = sorted(phase.timings(), true);
    long[] filterNanos = sorted(phase.timings(), false);
    System.out.printf(
        Locale.ROOT,
        "%-9s checks %,d | database reads %,d | false positives %,d | live rate %.4f | %.1f s in"
            + " all, %.3f ms a check%n",
        label,
        counts.checks(),
        counts.storeReads(),
        counts.falsePositives(),
        counts.measuredFalsePositiveRate(),
        totalNanos / NANOS_PER_SECOND,
        totalNanos / NANOS_PER_MILLI / counts.checks());
    System.out.printf(
        Locale.ROOT,
        "%-9s database read p50 %.3f ms, p99 %.3f ms | answered from filter p50 %s | index pages"
            + " per read: %.3f from the cache, %.3f from outside it%n",
        label,
        percentile(storeNanos, PERCENTILE_50) / NANOS_PER_MILLI,
        percentile(storeNanos, PERCENTILE_99) / NANOS_PER_MILLI,
        filterNanos.length == 0
            ? "none (no filter)"
            : String.format(
                Locale.ROOT, "%.4f ms", percentile(filterNanos, PERCENTILE_50) / NANOS_PER_MILLI),
        (double) phase.blocksHit() / counts.storeReads(),
        (double) phase.blocksRead() / counts.storeReads());
  }

  private static long[] sorted(List<Timing> timings, boolean readTheStore) {
    long[] nanos =
        timings.stream()
            .filter(timing -> timing.readTheStore() == readTheStore)
            .mapToLong(Timing::nanos)
            .toArray();
    Arrays.sort(nanos);
    return nanos;
  }

  private static double percentile(long[] sortedNanos, double share) {
    int index = (int) Math.min(sortedNanos.length - 1, Math.ceil(share * sortedNanos.length) - 1);
    return sortedNanos[Math.max(0, index)];
  }

  private static void printRebuild(String label, long loaded, long nanos, BloomSizing sizing) {
    System.out.printf(
        Locale.ROOT,
        "%s: %,d names scanned and added in %.1f s, %.0f ms per million names | m=%,d, k=%d,"
            + " %.1f MB%n",
        label,
        loaded,
        nanos / NANOS_PER_SECOND,
        nanos / NANOS_PER_MILLI / (loaded / MILLION),
        sizing.bitCount(),
        sizing.hashCount(),
        sizing.memoryBytes() / BYTES_PER_MB);
  }

  private static double probe(BloomFilter filter, int probes, long names) {
    long falsePositives = 0;
    for (long probeIndex = 1; probeIndex <= probes; probeIndex++) {
      // Never added: the suffix y is not a digit, and the probes are spread like the mix's names.
      if (filter.mightContain("fan_" + (1 + (probeIndex * names / probes) % names) + "y")) {
        falsePositives++;
      }
    }
    return (double) falsePositives / probes;
  }

  private static void printProbe(
      String label, BloomFilter filter, double measuredRate, long names, int probes) {
    BloomSizing sizing = filter.sizing();
    System.out.printf(
        Locale.ROOT,
        "probe, %s: %,d never-added names, measured rate %.5f | theory %.5f at %,d names |"
            + " target %.2f at %,d | fill %.4f%n",
        label,
        probes,
        measuredRate,
        sizing.theoreticalFalsePositiveRate(names),
        names,
        sizing.targetFalsePositiveRate(),
        sizing.designItems(),
        filter.fillRatio());
  }

  private static void printScaled(
      double indexBytesPerName, Phase without, Phase with, double designRate) {
    double missesPerRead = (double) without.blocksRead() / without.counts().storeReads();
    double freeChecks = PEAK_CHECKS_PER_SECOND * FREE_SHARE;
    double readsWithFilter = PEAK_CHECKS_PER_SECOND - freeChecks + freeChecks * designRate;
    System.out.printf(
        Locale.ROOT,
        "scaled to %,d names (estimated): index %,.0f MB at %.2f bytes a name | at %,.0f checks"
            + " a second with %.0f percent free: database reads %,.0f without the filter, %,.0f"
            + " with it | index pages from outside the cache, at this cache's share of the index:"
            + " %,.0f and %,.0f a second%n",
        MODULE_NAMES,
        indexBytesPerName * MODULE_NAMES / BYTES_PER_MB,
        indexBytesPerName,
        PEAK_CHECKS_PER_SECOND,
        FREE_SHARE * 100,
        PEAK_CHECKS_PER_SECOND,
        readsWithFilter,
        PEAK_CHECKS_PER_SECOND * missesPerRead,
        readsWithFilter * missesPerRead);
    double withMisses = (double) with.blocksRead() / with.counts().storeReads();
    System.out.printf(
        Locale.ROOT,
        "check: index pages from outside the cache per database read, filter phase %.3f against"
            + " %.3f without it%n",
        withMisses,
        missesPerRead);
  }

  private static void printRebuildScaled(long nanos, long loaded) {
    double secondsAtModuleSize = nanos / NANOS_PER_SECOND * MODULE_NAMES / loaded;
    System.out.printf(
        Locale.ROOT,
        "scaled to %,d names (estimated): rebuild %,.0f s, about %.1f minutes, at this run's rate%n",
        MODULE_NAMES,
        secondsAtModuleSize,
        secondsAtModuleSize / SECONDS_PER_MINUTE);
  }

  private static long single(DataSource pool, String sql) {
    try (Connection connection = pool.getConnection();
        PreparedStatement statement = connection.prepareStatement(sql);
        ResultSet row = statement.executeQuery()) {
      if (!row.next() || row.getLong(1) == 0) {
        throw new IllegalStateException(
            "customers is empty; load it first with ./gradlew loadUsernames");
      }
      return row.getLong(1);
    } catch (SQLException failure) {
      throw new IllegalStateException("could not count customers; is the stack up?", failure);
    }
  }
}
