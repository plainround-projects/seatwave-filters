package io.plainround.seatwave.filters.bloom;

import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

/**
 * A Bloom filter of strings on an array of longs: it answers "definitely absent" or "maybe
 * present".
 *
 * <p>The invariant: {@link #mightContain} never returns false for a string that was {@link #add
 * added}. Two facts keep it, and no method can break either: add and lookup compute positions with
 * the same call ({@link SeededHash#bitPositions}), and bits are only ever set, never cleared, so
 * this class has no remove. Deletes need {@link CountingBloomFilter}.
 *
 * <p>Thread-safe and lock-free: each bit is set with an atomic OR, so concurrent sign-ups never
 * lose each other's bits, and a lookup that races an add can only miss bits that add has not set
 * yet, which is correct, because that add has not returned.
 */
public final class BloomFilter {

  private static final int WORD_INDEX_SHIFT = 6; // 2^6 = 64 bits per long
  private static final long BIT_INDEX_MASK = BloomSizing.BITS_PER_WORD - 1L;

  private final BloomSizing sizing;
  private final long seed;
  private final AtomicLongArray words;
  private final AtomicLong addCalls = new AtomicLong();

  /**
   * Creates an empty filter.
   *
   * @param sizing the bits and hash functions, already validated by {@link BloomSizing}
   * @param seed the hash seed; a secret, random seed per deployment defeats precomputed attack
   *     lists (Section 10), and every instance that shares a filter must use the same one
   */
  public BloomFilter(BloomSizing sizing, long seed) {
    this.sizing = Objects.requireNonNull(sizing, "sizing");
    this.seed = seed;
    this.words =
        new AtomicLongArray(Math.toIntExact(sizing.bitCount() / BloomSizing.BITS_PER_WORD));
  }

  /** Adds {@code item}. Adding the same item twice changes nothing but {@link #addCalls}. */
  public void add(String item) {
    for (long position :
        SeededHash.bitPositions(item, seed, sizing.bitCount(), sizing.hashCount())) {
      long bit = 1L << (position & BIT_INDEX_MASK);
      words.accumulateAndGet(
          (int) (position >>> WORD_INDEX_SHIFT), bit, (current, mask) -> current | mask);
    }
    addCalls.incrementAndGet();
  }

  /**
   * False means {@code item} was certainly never added; true means it may have been.
   *
   * @throws NullPointerException if {@code item} is null
   */
  public boolean mightContain(String item) {
    for (long position :
        SeededHash.bitPositions(item, seed, sizing.bitCount(), sizing.hashCount())) {
      long bit = 1L << (position & BIT_INDEX_MASK);
      if ((words.get((int) (position >>> WORD_INDEX_SHIFT)) & bit) == 0) {
        return false; // one zero bit proves the item was never added
      }
    }
    return true;
  }

  /** The share of bits set, from 0 to 1; about 0.5 at the design count when k is optimal. */
  public double fillRatio() {
    long setBits = 0;
    for (int wordIndex = 0; wordIndex < words.length(); wordIndex++) {
      setBits += Long.bitCount(words.get(wordIndex));
    }
    return (double) setBits / sizing.bitCount();
  }

  /** Calls to {@link #add}, duplicates included; compare it with the design count. */
  public long addCalls() {
    return addCalls.get();
  }

  /** The sizing this filter was built with. */
  public BloomSizing sizing() {
    return sizing;
  }
}
