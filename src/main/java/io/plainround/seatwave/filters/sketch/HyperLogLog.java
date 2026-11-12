package io.plainround.seatwave.filters.sketch;

import io.plainround.seatwave.filters.bloom.SeededHash;

/**
 * HyperLogLog (Flajolet, Fusy, Gandouet and Meunier, 2007): estimates how many distinct strings
 * were added, in 2^precision registers, with a standard error of about 1.04 / sqrt(registers)
 * whatever the count.
 *
 * <p>The idea: hash each item; the first {@code precision} bits pick a register, and the register
 * keeps the longest run of leading zeros seen in the remaining bits. A run of r zeros turns up
 * about once in 2^r distinct items, so long runs mean many items. Duplicates hash identically and
 * change nothing, which is why it counts distinct items.
 *
 * <p>Teaching simplification: one byte per register, where production code packs 6 bits, and the
 * raw estimate of the paper without the bias tables of later variants such as HyperLogLog++. Not
 * thread-safe: confine one sketch to one thread and {@link #merge} sketches from several.
 */
public final class HyperLogLog {

  /** Smallest and largest precision accepted: 16 registers to 262,144. */
  public static final int MIN_PRECISION = 4;

  public static final int MAX_PRECISION = 18;

  private static final double STANDARD_ERROR_FACTOR = 1.04; // from the paper's analysis
  // The paper's bias constants alpha for 16, 32 and 64 registers, and its formula above that.
  private static final double ALPHA_16 = 0.673;
  private static final double ALPHA_32 = 0.697;
  private static final double ALPHA_64 = 0.709;
  private static final double ALPHA_LARGE_NUMERATOR = 0.7213;
  private static final double ALPHA_LARGE_CORRECTION = 1.079;
  private static final int REGISTERS_16 = 16;
  private static final int REGISTERS_32 = 32;
  private static final int REGISTERS_64 = 64;
  // Below 2.5 times the register count the raw estimate is biased; the paper switches to linear
  // counting on empty registers there.
  private static final double SMALL_RANGE_FACTOR = 2.5;

  private final int precision;
  private final long seed;
  private final byte[] registers;

  /**
   * Creates an empty sketch.
   *
   * @throws IllegalArgumentException if {@code precision} is outside 4 to 18
   */
  public HyperLogLog(int precision, long seed) {
    if (precision < MIN_PRECISION || precision > MAX_PRECISION) {
      throw new IllegalArgumentException(
          "precision must be " + MIN_PRECISION + " to " + MAX_PRECISION + ", got " + precision);
    }
    this.precision = precision;
    this.seed = seed;
    this.registers = new byte[1 << precision];
  }

  /** Adds {@code item}; adding it again changes nothing. */
  public void add(String item) {
    long hash = SeededHash.hash64(item, seed);
    int registerIndex = (int) (hash >>> (Long.SIZE - precision));
    long remainingBits = hash << precision;
    // Position of the first 1 bit in what is left, counted from 1; all zeros gives the maximum.
    int rank = Math.min(Long.numberOfLeadingZeros(remainingBits), Long.SIZE - precision) + 1;
    if (rank > registers[registerIndex]) {
      registers[registerIndex] = (byte) rank;
    }
  }

  /** The estimated number of distinct items added. */
  public long estimate() {
    int registerCount = registers.length;
    double sumOfPowers = 0;
    int emptyRegisters = 0;
    for (byte rank : registers) {
      sumOfPowers += Math.scalb(1.0, -rank); // 2^-rank, a harmonic mean in the making
      if (rank == 0) {
        emptyRegisters++;
      }
    }
    double raw = alpha(registerCount) * registerCount * registerCount / sumOfPowers;
    if (raw <= SMALL_RANGE_FACTOR * registerCount && emptyRegisters > 0) {
      return Math.round(registerCount * Math.log((double) registerCount / emptyRegisters));
    }
    return Math.round(raw);
  }

  /**
   * Folds {@code other} into this sketch: afterwards it estimates the union of both inputs.
   *
   * @throws IllegalArgumentException if the sketches differ in precision or seed, because their
   *     registers would then mean different things
   */
  public void merge(HyperLogLog other) {
    if (other.precision != precision || other.seed != seed) {
      throw new IllegalArgumentException("only sketches with the same precision and seed merge");
    }
    for (int registerIndex = 0; registerIndex < registers.length; registerIndex++) {
      registers[registerIndex] =
          (byte) Math.max(registers[registerIndex], other.registers[registerIndex]);
    }
  }

  /** The expected relative standard error, 1.04 / sqrt(registers). */
  public double standardError() {
    return STANDARD_ERROR_FACTOR / Math.sqrt(registers.length);
  }

  /** Memory of the registers, in bytes (one byte each here). */
  public int memoryBytes() {
    return registers.length;
  }

  private static double alpha(int registerCount) {
    return switch (registerCount) {
      case REGISTERS_16 -> ALPHA_16;
      case REGISTERS_32 -> ALPHA_32;
      case REGISTERS_64 -> ALPHA_64;
      default -> ALPHA_LARGE_NUMERATOR / (1 + ALPHA_LARGE_CORRECTION / registerCount);
    };
  }
}
