package io.plainround.seatwave.filters.labs;

import io.plainround.seatwave.filters.bloom.BloomFilter;
import io.plainround.seatwave.filters.bloom.BloomSizing;
import java.util.Locale;

/** Lab 1: size and build a filter for 10 million names at 1 percent, and measure its rate. */
public final class TenMillionNamesCli {

  private static final long FILTER_SEED = 0x5ea7_3a7eL;
  private static final int NAMES = 10_000_000;
  private static final double TARGET_RATE = 0.01;
  private static final double BYTES_PER_MEGABYTE = 1e6;
  private static final double NANOS_PER_SECOND = 1e9;

  private TenMillionNamesCli() {}

  /** Prints the sizing, then the measured false negatives and false positives. */
  public static void main(String[] arguments) {
    BloomSizing sizing = BloomSizing.forTarget(NAMES, TARGET_RATE);
    System.out.printf(
        Locale.ROOT,
        "sizing: n=%,d p=%s -> m=%,d bits (%.2f bits per name), k=%d, %.2f MB%n",
        NAMES,
        Double.toString(TARGET_RATE),
        sizing.bitCount(),
        sizing.bitsPerItem(),
        sizing.hashCount(),
        sizing.memoryBytes() / BYTES_PER_MEGABYTE);

    BloomFilter filter = new BloomFilter(sizing, FILTER_SEED);
    long started = System.nanoTime();
    for (int nameIndex = 1; nameIndex <= NAMES; nameIndex++) {
      filter.add("fan_" + nameIndex);
    }
    double buildSeconds = (System.nanoTime() - started) / NANOS_PER_SECOND;

    long falseNegatives = 0;
    for (int nameIndex = 1; nameIndex <= NAMES; nameIndex++) {
      if (!filter.mightContain("fan_" + nameIndex)) {
        falseNegatives++;
      }
    }
    long falsePositives = 0;
    for (int probeIndex = 1; probeIndex <= NAMES; probeIndex++) {
      if (filter.mightContain("guest_" + probeIndex)) {
        falsePositives++;
      }
    }
    System.out.printf(
        Locale.ROOT,
        "built in %.2f s | fill ratio %.4f | false negatives %d of %,d | false positives %,d of %,d"
            + " = %.5f | theory %.5f%n",
        buildSeconds,
        filter.fillRatio(),
        falseNegatives,
        NAMES,
        falsePositives,
        NAMES,
        (double) falsePositives / NAMES,
        sizing.theoreticalFalsePositiveRate(NAMES));
  }
}
