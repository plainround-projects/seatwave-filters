package io.plainround.seatwave.filters.labs;

import com.zaxxer.hikari.HikariDataSource;
import io.plainround.seatwave.filters.bloom.BloomSizing;
import io.plainround.seatwave.filters.signup.JdbcUsernameStore;
import io.plainround.seatwave.filters.signup.UsernameCheck;
import io.plainround.seatwave.filters.signup.UsernameStore;
import io.plainround.seatwave.filters.study.LabDatabase;
import io.plainround.seatwave.filters.study.LabSettings;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Lab 2: puts the filter in front of the username check and counts the database reads it avoids,
 * for four shares of free names, at 5,000 checks a second.
 *
 * <p>Without arguments the store is an in-memory set. With {@code --postgres} it is {@link
 * JdbcUsernameStore} on the lab database, which must hold exactly {@code fan_1} to {@code
 * fan_1000000} ({@code ./gradlew loadUsernames --args="1000000 --run --yes"}); the counts are the
 * same, and the run adds the time the database reads took, with and without the filter.
 */
public final class ReadsAvoidedCli {

  private static final long RANDOM_SEED = 21L;
  private static final long FILTER_SEED = 0x5ea7_3a7eL;
  private static final int EXISTING = 1_000_000;
  private static final int CHECKS = 100_000;
  private static final double TARGET_RATE = 0.01;
  private static final double[] FREE_SHARES = {0.10, 0.50, 0.90, 0.99};
  private static final double CHECKS_PER_SECOND = 5_000; // Section 7's peak sign-up checks
  private static final Path SETTINGS = Path.of("config/lab.properties");
  private static final double NANOS_PER_SECOND = 1e9;
  private static final double NANOS_PER_MILLI = 1e6;
  private static final double MEDIAN = 0.5;

  private ReadsAvoidedCli() {}

  /** Prints, per share of free names, the reads with and without the filter. */
  public static void main(String[] arguments) {
    if (List.of(arguments).equals(List.of("--postgres"))) {
      try (HikariDataSource pool = LabDatabase.openPool(LabSettings.load(SETTINGS))) {
        JdbcUsernameStore store = new JdbcUsernameStore(pool);
        requireTheLabNames(store);
        run(new TimedStore(store), true);
      }
      return;
    }
    if (arguments.length > 0) {
      System.err.println("usage: ./gradlew readsAvoided [--args=--postgres]");
      System.exit(2);
    }
    Set<String> existing = new HashSet<>();
    for (int nameIndex = 1; nameIndex <= EXISTING; nameIndex++) {
      existing.add("fan_" + nameIndex);
    }
    run(new TimedStore(new SetStore(existing)), false);
  }

  private static void run(TimedStore store, boolean showTimes) {
    Random random = new Random(RANDOM_SEED);
    System.out.println(
        "free share | checks  | taken checks | db reads with filter | avoided | reads/s at 5,000"
            + " checks/s without -> with");
    for (double freeShare : FREE_SHARES) {
      List<String> candidates = new ArrayList<>(CHECKS);
      int takenChecks = 0;
      for (int checkIndex = 1; checkIndex <= CHECKS; checkIndex++) {
        // A free name is one no fan has; a taken one is a random existing fan_N.
        boolean isFree = random.nextDouble() < freeShare;
        if (!isFree) {
          takenChecks++;
        }
        candidates.add(isFree ? "guest_" + checkIndex : "fan_" + (1 + random.nextInt(EXISTING)));
      }
      UsernameCheck check = new UsernameCheck(store);
      check.rebuild(BloomSizing.forTarget(EXISTING, TARGET_RATE), FILTER_SEED);
      store.reset();
      candidates.forEach(check::check);
      UsernameCheck.CheckCounts counts = check.counts();
      double readShare = (double) counts.storeReads() / counts.checks();
      System.out.printf(
          Locale.ROOT,
          "%10.2f | %,7d | %,12d | %,20d | %6.1f%% | %,.0f -> %,.0f%n",
          freeShare,
          counts.checks(),
          takenChecks,
          counts.storeReads(),
          100 * (1 - readShare),
          CHECKS_PER_SECOND,
          CHECKS_PER_SECOND * readShare);
      if (showTimes) {
        String withFilter = store.describe();
        store.reset();
        candidates.forEach(new UsernameCheck(store)::check);
        System.out.printf(
            Locale.ROOT,
            "           database time: with the filter %s; without it %s%n",
            withFilter,
            store.describe());
      }
    }
  }

  private static void requireTheLabNames(UsernameStore store) {
    boolean expected =
        store.isTaken("fan_1")
            && store.isTaken("fan_" + EXISTING)
            && !store.isTaken("fan_" + (EXISTING + 1));
    if (!expected) {
      throw new IllegalStateException(
          "customers must hold exactly fan_1 to fan_"
              + EXISTING
              + "; load them with"
              + " ./gradlew loadUsernames --args=\""
              + EXISTING
              + " --run --yes\"");
    }
  }

  /** Times every read of the store it wraps. Confined to the one thread that runs the lab. */
  private static final class TimedStore implements UsernameStore {
    private final UsernameStore store;
    private final List<Long> readNanos = new ArrayList<>();

    TimedStore(UsernameStore store) {
      this.store = store;
    }

    @Override
    public boolean isTaken(String username) {
      long started = System.nanoTime();
      boolean taken = store.isTaken(username);
      readNanos.add(System.nanoTime() - started);
      return taken;
    }

    @Override
    public long forEachUsername(Consumer<String> sink) {
      return store.forEachUsername(sink);
    }

    void reset() {
      readNanos.clear();
    }

    String describe() {
      long[] sorted = readNanos.stream().mapToLong(Long::longValue).sorted().toArray();
      double total = Arrays.stream(sorted).sum() / NANOS_PER_SECOND;
      double median =
          sorted.length == 0 ? 0 : sorted[(int) (sorted.length * MEDIAN)] / NANOS_PER_MILLI;
      return String.format(
          Locale.ROOT, "%,d reads, %.2f s, p50 %.3f ms a read", sorted.length, total, median);
    }
  }

  /** The system of record as a set; production uses JdbcUsernameStore. */
  private static final class SetStore implements UsernameStore {
    private final Set<String> usernames;

    SetStore(Set<String> usernames) {
      this.usernames = Set.copyOf(usernames);
    }

    @Override
    public boolean isTaken(String username) {
      return usernames.contains(username);
    }

    @Override
    public long forEachUsername(Consumer<String> sink) {
      usernames.forEach(sink);
      return usernames.size();
    }
  }
}
