package io.plainround.seatwave.filters.signup;

import io.plainround.seatwave.filters.bloom.BloomSizing;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Runs SeatWave's sign-up mix against an in-memory store with and without the filter, counts the
 * database reads avoided, and shows what a filter built from a stale snapshot gets wrong.
 *
 * <p>Teaching code: the store is a HashSet, where production has {@link JdbcUsernameStore}; the
 * counts are the same, the times are not.
 */
public final class UsernameCheckCli {

  private static final long RANDOM_SEED = 21L; // fixed, so every run prints the same counts
  private static final long FILTER_SEED = 0x5ea7_3a7eL; // a per-deployment secret in production
  private static final int EXISTING_USERNAMES = 1_000_000;
  private static final int CHECKS = 100_000;
  private static final double FREE_SHARE = 0.90; // Section 7: 90 percent of checks are free names
  private static final double TARGET_RATE = 0.01;
  private static final int MISSED_BY_SNAPSHOT = 10_000; // sign-ups after the stale snapshot
  private static final int MIN_NAME_LENGTH = 5;
  private static final int EXTRA_NAME_LENGTH = 8; // names of 5 to 12 characters
  private static final String NAME_ALPHABET = "abcdefghijklmnopqrstuvwxyz0123456789_";
  private static final double NANOS_PER_MILLI = 1_000_000.0;

  private UsernameCheckCli() {}

  /** Prints three runs: no filter, a correct filter, and a filter from a stale snapshot. */
  public static void main(String[] arguments) {
    Random random = new Random(RANDOM_SEED);
    List<String> existing = uniqueNames(random, EXISTING_USERNAMES, Set.of());
    Set<String> existingSet = new HashSet<>(existing);
    List<String> freeNames = uniqueNames(random, (int) (CHECKS * FREE_SHARE), existingSet);
    List<String> mix = new ArrayList<>(freeNames);
    for (int takenIndex = 0; takenIndex < CHECKS - freeNames.size(); takenIndex++) {
      mix.add(existing.get(random.nextInt(existing.size())));
    }
    Collections.shuffle(mix, random);
    BloomSizing sizing = BloomSizing.forTarget(EXISTING_USERNAMES, TARGET_RATE);

    CountingStore plainStore = new CountingStore(existing, existing.size());
    UsernameCheck withoutFilter = new UsernameCheck(plainStore);
    mix.forEach(withoutFilter::check);
    report("no filter", withoutFilter.counts());

    CountingStore filteredStore = new CountingStore(existing, existing.size());
    UsernameCheck withFilter = new UsernameCheck(filteredStore);
    long started = System.nanoTime();
    long loaded = withFilter.rebuild(sizing, FILTER_SEED);
    double rebuildMillis = (System.nanoTime() - started) / NANOS_PER_MILLI;
    mix.forEach(withFilter::check);
    report("filter", withFilter.counts());
    System.out.printf(
        Locale.ROOT,
        "rebuild: %,d names in %.0f ms (in memory; the database scan is not included)%n",
        loaded,
        rebuildMillis);
    System.out.printf(
        Locale.ROOT,
        "filter: %,d bits, k = %d, %.2f MB, fill ratio %.3f%n",
        sizing.bitCount(),
        sizing.hashCount(),
        sizing.memoryBytes() / 1e6,
        withFilter.installedFilter().orElseThrow().fillRatio());

    int snapshotSize = existing.size() - MISSED_BY_SNAPSHOT;
    UsernameCheck staleCheck = new UsernameCheck(new CountingStore(existing, snapshotSize));
    staleCheck.rebuild(sizing, FILTER_SEED);
    long wronglyFree =
        existing.subList(snapshotSize, existing.size()).stream()
            .filter(name -> staleCheck.check(name) instanceof UsernameCheck.Availability.Free)
            .count();
    System.out.printf(
        Locale.ROOT,
        "stale snapshot: %,d names missing, %,d of them called free (false negatives)%n",
        MISSED_BY_SNAPSHOT,
        wronglyFree);
  }

  private static void report(String label, UsernameCheck.CheckCounts counts) {
    System.out.printf(
        Locale.ROOT,
        "%-9s checks %,d | answered from filter %,d | database reads %,d | false positives %,d"
            + " | measured rate %.4f%n",
        label,
        counts.checks(),
        counts.answeredFromFilter(),
        counts.storeReads(),
        counts.falsePositives(),
        counts.measuredFalsePositiveRate());
  }

  private static List<String> uniqueNames(Random random, int count, Set<String> avoid) {
    Set<String> seen = new HashSet<>();
    List<String> names = new ArrayList<>(count);
    while (names.size() < count) {
      int length = MIN_NAME_LENGTH + random.nextInt(EXTRA_NAME_LENGTH);
      StringBuilder name = new StringBuilder(length);
      for (int charIndex = 0; charIndex < length; charIndex++) {
        name.append(NAME_ALPHABET.charAt(random.nextInt(NAME_ALPHABET.length())));
      }
      String candidate = name.toString();
      if (!avoid.contains(candidate) && seen.add(candidate)) {
        names.add(candidate);
      }
    }
    return names;
  }

  /** A store that answers from a set; its scan can stop early, like a stale backup. */
  private static final class CountingStore implements UsernameStore {
    private final Set<String> usernames;
    private final List<String> scanOrder;
    private final int scanLimit;

    CountingStore(List<String> usernames, int scanLimit) {
      this.usernames = new HashSet<>(usernames);
      this.scanOrder = List.copyOf(usernames);
      this.scanLimit = scanLimit;
    }

    @Override
    public boolean isTaken(String username) {
      return usernames.contains(username);
    }

    @Override
    public long forEachUsername(Consumer<String> sink) {
      scanOrder.subList(0, scanLimit).forEach(sink);
      return scanLimit;
    }
  }
}
