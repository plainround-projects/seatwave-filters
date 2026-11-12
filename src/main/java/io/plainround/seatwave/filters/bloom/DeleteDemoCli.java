package io.plainround.seatwave.filters.bloom;

import java.util.BitSet;
import java.util.Locale;

/**
 * Shows why a plain Bloom filter cannot delete: clearing a deleted name's bits also clears bits
 * other names share, which turns them into false negatives. A counting filter deletes safely, at
 * four times the memory, as long as only names really added are removed.
 *
 * <p>The plain filter here is a {@link BitSet} driven by the same {@link SeededHash} positions as
 * {@link BloomFilter}, because {@link BloomFilter} deliberately has no way to clear a bit.
 */
public final class DeleteDemoCli {

  private static final long FILTER_SEED = 0x5ea7_3a7eL;
  private static final int ADDED = 100_000;
  private static final int DELETED = 10_000; // fan_1 to fan_10000 close their accounts
  private static final int STRANGERS_TO_REMOVE = 1_000;
  private static final int STRANGER_SEARCH_LIMIT = 10_000_000;
  private static final double TARGET_RATE = 0.01;

  private DeleteDemoCli() {}

  /** Prints the false negatives after deletes on a plain and on a counting filter. */
  public static void main(String[] arguments) {
    BloomSizing sizing = BloomSizing.forTarget(ADDED, TARGET_RATE);
    System.out.printf(
        Locale.ROOT,
        "sizing: %,d names at %s: m=%,d bits, k=%d%n",
        ADDED,
        Double.toString(TARGET_RATE),
        sizing.bitCount(),
        sizing.hashCount());

    BitSet plainBits = new BitSet(Math.toIntExact(sizing.bitCount()));
    for (int itemIndex = 1; itemIndex <= ADDED; itemIndex++) {
      for (long position : positions(sizing, "fan_" + itemIndex)) {
        plainBits.set((int) position);
      }
    }
    for (int itemIndex = 1; itemIndex <= DELETED; itemIndex++) {
      for (long position : positions(sizing, "fan_" + itemIndex)) {
        plainBits.clear((int) position); // the naive delete
      }
    }
    long plainFalseNegatives = 0;
    for (int itemIndex = DELETED + 1; itemIndex <= ADDED; itemIndex++) {
      if (!allSet(plainBits, positions(sizing, "fan_" + itemIndex))) {
        plainFalseNegatives++;
      }
    }
    System.out.printf(
        Locale.ROOT,
        "plain filter (%,d bytes): after clearing %,d names, %,d of the %,d remaining names"
            + " are reported absent%n",
        sizing.memoryBytes(),
        DELETED,
        plainFalseNegatives,
        ADDED - DELETED);

    CountingBloomFilter counting = new CountingBloomFilter(sizing, FILTER_SEED);
    for (int itemIndex = 1; itemIndex <= ADDED; itemIndex++) {
      counting.add("fan_" + itemIndex);
    }
    for (int itemIndex = 1; itemIndex <= DELETED; itemIndex++) {
      counting.remove("fan_" + itemIndex);
    }
    System.out.printf(
        Locale.ROOT,
        "counting filter (%,d bytes): after removing %,d names, %,d remaining names reported"
            + " absent%n",
        counting.memoryBytes(),
        DELETED,
        countMissing(counting));

    // The misuse: removing names that were never added but that the filter calls "maybe".
    int strangersRemoved = 0;
    for (int probeIndex = 1;
        probeIndex <= STRANGER_SEARCH_LIMIT && strangersRemoved < STRANGERS_TO_REMOVE;
        probeIndex++) {
      if (counting.remove("guest_" + probeIndex)) {
        strangersRemoved++;
      }
    }
    System.out.printf(
        Locale.ROOT,
        "counting filter, after removing %,d names that were never added: %,d remaining names"
            + " reported absent%n",
        strangersRemoved,
        countMissing(counting));
  }

  private static long[] positions(BloomSizing sizing, String item) {
    return SeededHash.bitPositions(item, FILTER_SEED, sizing.bitCount(), sizing.hashCount());
  }

  private static boolean allSet(BitSet bits, long[] positions) {
    for (long position : positions) {
      if (!bits.get((int) position)) {
        return false;
      }
    }
    return true;
  }

  private static long countMissing(CountingBloomFilter counting) {
    long missing = 0;
    for (int itemIndex = DELETED + 1; itemIndex <= ADDED; itemIndex++) {
      if (!counting.mightContain("fan_" + itemIndex)) {
        missing++;
      }
    }
    return missing;
  }
}
