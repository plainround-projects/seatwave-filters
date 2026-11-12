package io.plainround.seatwave.filters.bloom;

import java.util.Objects;

/**
 * A counting Bloom filter: a 4-bit counter where a plain filter has one bit, so an item can be
 * removed by decrementing its k counters.
 *
 * <p>It costs four times the memory of a plain filter of the same size. A counter that reaches 15
 * sticks there and is never decremented again: decrementing a counter that overflowed could reach 0
 * while other items still use it, which would be a false negative.
 *
 * <p>Remove only an item that was really added (check the database first): removing a stranger
 * decrements counters that belong to other items. Thread-safe through one lock held for a few array
 * operations.
 */
public final class CountingBloomFilter {

  private static final int BITS_PER_COUNTER = 4;
  private static final int COUNTERS_PER_WORD = BloomSizing.BITS_PER_WORD / BITS_PER_COUNTER;
  private static final long COUNTER_MASK = 0xFL;
  private static final long SATURATED = COUNTER_MASK; // 15, the largest 4-bit value

  private final BloomSizing sizing;
  private final long seed;
  private final long[] words;

  /** Creates an empty filter with one 4-bit counter per bit of {@code sizing}. */
  public CountingBloomFilter(BloomSizing sizing, long seed) {
    this.sizing = Objects.requireNonNull(sizing, "sizing");
    this.seed = seed;
    this.words = new long[Math.toIntExact(Math.ceilDiv(sizing.bitCount(), COUNTERS_PER_WORD))];
  }

  /** Adds {@code item}: increments each of its counters that is not saturated. */
  public synchronized void add(String item) {
    for (long position : positions(item)) {
      long counter = counterAt(position);
      if (counter < SATURATED) {
        setCounter(position, counter + 1);
      }
    }
  }

  /**
   * Removes one earlier add of {@code item}.
   *
   * @return false, and changes nothing, if the filter proves {@code item} was never added
   */
  public synchronized boolean remove(String item) {
    long[] positions = positions(item);
    for (long position : positions) {
      if (counterAt(position) == 0) {
        return false;
      }
    }
    for (long position : positions) {
      long counter = counterAt(position);
      if (counter < SATURATED) { // a saturated counter has lost count; leave it set for ever
        setCounter(position, counter - 1);
      }
    }
    return true;
  }

  /** False means {@code item} is certainly not in the filter; true means it may be. */
  public synchronized boolean mightContain(String item) {
    for (long position : positions(item)) {
      if (counterAt(position) == 0) {
        return false;
      }
    }
    return true;
  }

  /** Memory of the counter array, in bytes: four times a plain filter's. */
  public long memoryBytes() {
    return (long) words.length * Long.BYTES;
  }

  private long[] positions(String item) {
    return SeededHash.bitPositions(item, seed, sizing.bitCount(), sizing.hashCount());
  }

  private long counterAt(long position) {
    int shift = (int) (position % COUNTERS_PER_WORD) * BITS_PER_COUNTER;
    return (words[(int) (position / COUNTERS_PER_WORD)] >>> shift) & COUNTER_MASK;
  }

  private void setCounter(long position, long value) {
    int wordIndex = (int) (position / COUNTERS_PER_WORD);
    int shift = (int) (position % COUNTERS_PER_WORD) * BITS_PER_COUNTER;
    words[wordIndex] = (words[wordIndex] & ~(COUNTER_MASK << shift)) | (value << shift);
  }
}
