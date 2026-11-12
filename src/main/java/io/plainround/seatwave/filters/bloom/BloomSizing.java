package io.plainround.seatwave.filters.bloom;

/**
 * The size of a Bloom filter: how many items it was designed for, the false-positive rate it
 * targets, and the bits and hash functions that follow from them.
 *
 * <p>An instance cannot exist with a size the filter cannot hold: the compact constructor rejects
 * it. Immutable and thread-safe.
 *
 * @param designItems the number of items the filter is sized for (n)
 * @param targetFalsePositiveRate the false-positive rate wanted at {@code designItems} (p)
 * @param bitCount the bits in the array (m)
 * @param hashCount the hash functions per item (k)
 */
public record BloomSizing(
    long designItems, double targetFalsePositiveRate, long bitCount, int hashCount) {

  /** One long holds 64 bits. */
  public static final int BITS_PER_WORD = Long.SIZE;

  /** A Java array holds at most Integer.MAX_VALUE longs, so this is the largest filter. */
  public static final long MAX_BIT_COUNT = (long) Integer.MAX_VALUE * BITS_PER_WORD;

  // Above about 30 hash functions a lookup costs more than the rate it buys; real filters use 3 to
  // 20, and a target that asks for more is a mistake in the inputs.
  private static final int MAX_HASH_COUNT = 30;
  private static final double LN_2 = Math.log(2);

  /** Rejects sizes the filter cannot hold or that make no sense. */
  public BloomSizing {
    if (designItems <= 0) {
      throw new IllegalArgumentException("designItems must be positive, got " + designItems);
    }
    if (!(targetFalsePositiveRate > 0 && targetFalsePositiveRate < 1)) {
      throw new IllegalArgumentException(
          "targetFalsePositiveRate must be between 0 and 1, got " + targetFalsePositiveRate);
    }
    if (bitCount <= 0 || bitCount > MAX_BIT_COUNT) {
      throw new IllegalArgumentException(
          "bitCount must be between 1 and " + MAX_BIT_COUNT + ", got " + bitCount);
    }
    if (hashCount <= 0 || hashCount > MAX_HASH_COUNT) {
      throw new IllegalArgumentException(
          "hashCount must be between 1 and " + MAX_HASH_COUNT + ", got " + hashCount);
    }
  }

  /**
   * Sizes a filter for {@code designItems} at {@code targetFalsePositiveRate}.
   *
   * <p>m = -n ln p / (ln 2)^2 bits, rounded up to whole longs; k = (m / n) ln 2, rounded to the
   * nearest whole number and at least 1.
   *
   * @throws IllegalArgumentException if the inputs are out of range or the filter would not fit
   */
  public static BloomSizing forTarget(long designItems, double targetFalsePositiveRate) {
    if (designItems <= 0 || !(targetFalsePositiveRate > 0 && targetFalsePositiveRate < 1)) {
      throw new IllegalArgumentException(
          "need designItems > 0 and 0 < rate < 1, got "
              + designItems
              + " and "
              + targetFalsePositiveRate);
    }
    double exactBits = -designItems * Math.log(targetFalsePositiveRate) / (LN_2 * LN_2);
    long words = (long) Math.ceil(exactBits / BITS_PER_WORD);
    long bitCount = Math.multiplyExact(words, BITS_PER_WORD);
    int hashCount = (int) Math.max(1, Math.round((double) bitCount / designItems * LN_2));
    return new BloomSizing(designItems, targetFalsePositiveRate, bitCount, hashCount);
  }

  /** Bits per designed item, m / n. */
  public double bitsPerItem() {
    return (double) bitCount / designItems;
  }

  /** Memory of the bit array alone, in bytes. */
  public long memoryBytes() {
    return bitCount / Byte.SIZE;
  }

  /**
   * The false-positive rate theory predicts after {@code insertedItems} distinct inserts: (1 -
   * e^(-k n / m))^k.
   */
  public double theoreticalFalsePositiveRate(long insertedItems) {
    double shareOfBitsStillZero = Math.exp(-(double) hashCount * insertedItems / bitCount);
    return Math.pow(1 - shareOfBitsStillZero, hashCount);
  }
}
