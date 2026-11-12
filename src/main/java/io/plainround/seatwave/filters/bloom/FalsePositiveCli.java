package io.plainround.seatwave.filters.bloom;

import java.util.Locale;

/**
 * Sizes Bloom filters from item count and target rate, then measures: insert n names, check every
 * one is found (no false negatives), probe n names never inserted, and compare the share called
 * "maybe" with theory. Ends with a filter filled past its design count.
 *
 * <p>Names follow Module 7's customer strings: fan_1, fan_2, ... are inserted; guest_1, guest_2,
 * ... are never inserted. Deterministic: fixed seed, fixed names, so counts repeat exactly; only
 * the times vary with the machine.
 */
public final class FalsePositiveCli {

  private static final long FILTER_SEED = 0x5ea7_3a7eL;
  private static final long BILLION = 1_000_000_000L;
  private static final long TEN_MILLION = 10_000_000L;
  private static final double[] SIZING_TARGETS = {0.1, 0.01, 0.001, 0.0001};
  private static final int MEASURED_ITEMS = 1_000_000;
  private static final double[] MEASURED_TARGETS = {0.01, 0.001};
  private static final double[] OVERFILL_FACTORS = {1.0, 1.5, 2.0, 3.0};
  private static final double BYTES_PER_MEGABYTE = 1e6;
  private static final double NANOS_PER_SECOND = 1e9;
  private static final double MILLION = 1e6;

  private FalsePositiveCli() {}

  /** Prints the sizing table, the measured rates, and the overfilled filter. */
  public static void main(String[] arguments) {
    System.out.println("== sizing: 1,000,000,000 usernames");
    System.out.println("target     bits/item  k   memory MB   theory at n");
    for (double target : SIZING_TARGETS) {
      printSizing(BloomSizing.forTarget(BILLION, target));
    }
    System.out.println("== sizing: 10,000,000 usernames (Lab 1)");
    printSizing(BloomSizing.forTarget(TEN_MILLION, MEASURED_TARGETS[0]));

    System.out.println("== measured: 1,000,000 inserted, 1,000,000 never inserted");
    for (double target : MEASURED_TARGETS) {
      measure(BloomSizing.forTarget(MEASURED_ITEMS, target), MEASURED_ITEMS);
    }

    System.out.println("== overfilled: sized for 1,000,000 at 0.01, filled past it");
    BloomSizing designed = BloomSizing.forTarget(MEASURED_ITEMS, MEASURED_TARGETS[0]);
    for (double factor : OVERFILL_FACTORS) {
      measure(designed, (int) (MEASURED_ITEMS * factor));
    }
  }

  private static void printSizing(BloomSizing sizing) {
    System.out.printf(
        Locale.ROOT,
        "%-9s  %9.2f  %-2d  %10.1f  %.5f%n",
        Double.toString(sizing.targetFalsePositiveRate()),
        sizing.bitsPerItem(),
        sizing.hashCount(),
        sizing.memoryBytes() / BYTES_PER_MEGABYTE,
        sizing.theoreticalFalsePositiveRate(sizing.designItems()));
  }

  private static void measure(BloomSizing sizing, int insertedItems) {
    BloomFilter filter = new BloomFilter(sizing, FILTER_SEED);
    long buildStarted = System.nanoTime();
    for (int itemIndex = 1; itemIndex <= insertedItems; itemIndex++) {
      filter.add("fan_" + itemIndex);
    }
    long buildNanos = System.nanoTime() - buildStarted;

    // The invariant, checked on every inserted name: one miss here is a bug, not bad luck.
    long falseNegatives = 0;
    for (int itemIndex = 1; itemIndex <= insertedItems; itemIndex++) {
      if (!filter.mightContain("fan_" + itemIndex)) {
        falseNegatives++;
      }
    }

    long falsePositives = 0;
    long probeStarted = System.nanoTime();
    for (int probeIndex = 1; probeIndex <= MEASURED_ITEMS; probeIndex++) {
      if (filter.mightContain("guest_" + probeIndex)) {
        falsePositives++;
      }
    }
    long probeNanos = System.nanoTime() - probeStarted;

    System.out.printf(
        Locale.ROOT,
        "design %,d at %s (m=%,d, k=%d, %.2f MB) | inserted %,d | fill %.3f | false negatives %d |"
            + " false positives %,d of %,d = %.5f | theory %.5f | %.0f ns/add | %.1f M lookups/s%n",
        sizing.designItems(),
        Double.toString(sizing.targetFalsePositiveRate()),
        sizing.bitCount(),
        sizing.hashCount(),
        sizing.memoryBytes() / BYTES_PER_MEGABYTE,
        insertedItems,
        filter.fillRatio(),
        falseNegatives,
        falsePositives,
        MEASURED_ITEMS,
        (double) falsePositives / MEASURED_ITEMS,
        sizing.theoreticalFalsePositiveRate(insertedItems),
        (double) buildNanos / insertedItems,
        MEASURED_ITEMS / (probeNanos / NANOS_PER_SECOND) / MILLION);
  }
}
