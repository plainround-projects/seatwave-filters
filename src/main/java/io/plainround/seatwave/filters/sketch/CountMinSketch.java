package io.plainround.seatwave.filters.sketch;

import io.plainround.seatwave.filters.bloom.SeededHash;

/**
 * Count-min sketch (Cormode and Muthukrishnan, 2005): estimates how often each string was added, in
 * depth rows of width counters.
 *
 * <p>Each add increments one counter per row, chosen by that row's hash. An estimate is the
 * smallest of the item's counters: every counter is the item's true count plus whatever other items
 * collided into it, so the estimate never undercounts. With width = ceil(e / epsilon) and depth =
 * ceil(ln(1 / delta)), it overcounts by more than epsilon times the total of all adds with
 * probability at most delta. Not thread-safe; one sketch per thread, or a lock around it.
 */
public final class CountMinSketch {

  private final int width;
  private final int depth;
  private final long seed;
  private final long[] counters;
  private long totalCount;

  private CountMinSketch(int width, int depth, long seed) {
    this.width = width;
    this.depth = depth;
    this.seed = seed;
    this.counters = new long[Math.multiplyExact(width, depth)];
  }

  /**
   * Sizes a sketch for error {@code epsilon} (a share of all adds) with failure probability {@code
   * delta}.
   *
   * @throws IllegalArgumentException if either is outside (0, 1) or the sketch would not fit
   */
  public static CountMinSketch forError(double epsilon, double delta, long seed) {
    if (!(epsilon > 0 && epsilon < 1) || !(delta > 0 && delta < 1)) {
      throw new IllegalArgumentException(
          "epsilon and delta must be between 0 and 1, got " + epsilon + " and " + delta);
    }
    int width = Math.toIntExact((long) Math.ceil(Math.E / epsilon));
    int depth = Math.toIntExact((long) Math.ceil(Math.log(1 / delta)));
    return new CountMinSketch(width, depth, seed);
  }

  /**
   * Adds {@code count} occurrences of {@code item}.
   *
   * @throws IllegalArgumentException if {@code count} is not positive (a sketch cannot subtract
   *     without losing its never-undercount guarantee)
   */
  public void add(String item, long count) {
    if (count <= 0) {
      throw new IllegalArgumentException("count must be positive, got " + count);
    }
    for (int row = 0; row < depth; row++) {
      int cell = row * width + column(item, row);
      counters[cell] = Math.addExact(counters[cell], count);
    }
    totalCount = Math.addExact(totalCount, count);
  }

  /** The estimated count of {@code item}: never below the true count. */
  public long estimate(String item) {
    long smallest = Long.MAX_VALUE;
    for (int row = 0; row < depth; row++) {
      smallest = Math.min(smallest, counters[row * width + column(item, row)]);
    }
    return smallest;
  }

  /** The total of all adds, N in the error bound epsilon times N. */
  public long totalCount() {
    return totalCount;
  }

  /** Counters per row. */
  public int width() {
    return width;
  }

  /** Rows. */
  public int depth() {
    return depth;
  }

  /** Memory of the counters, in bytes. */
  public long memoryBytes() {
    return (long) counters.length * Long.BYTES;
  }

  private int column(String item, int row) {
    // A different seed per row gives each row its own, independent hash function.
    return (int) Long.remainderUnsigned(SeededHash.hash64(item, seed + row), width);
  }
}
