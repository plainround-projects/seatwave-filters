package io.plainround.seatwave.filters.bloom;

import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * One seeded 64-bit hash of a string, and the k bit positions a Bloom filter derives from it.
 *
 * <p>Every structure in this project (the Bloom filter, the counting filter, HyperLogLog and
 * count-min) hashes through this class, so "the same key gives the same positions" is decided in
 * one place. Stateless and thread-safe.
 */
public final class SeededHash {

  // FNV-1a's published 64-bit offset basis and prime: a simple byte-at-a-time hash.
  private static final long FNV_OFFSET_BASIS = 0xcbf29ce484222325L;
  private static final long FNV_PRIME = 0x100000001b3L;
  // MurmurHash3's 64-bit finalizer constants: they spread every input bit over all 64 output
  // bits, which FNV alone does poorly for short, similar strings such as fan_1 and fan_2.
  private static final long FINALIZER_MULTIPLIER_ONE = 0xff51afd7ed558ccdL;
  private static final long FINALIZER_MULTIPLIER_TWO = 0xc4ceb9fe1a85ec53L;
  private static final int FINALIZER_SHIFT = 33;
  // Any odd constant works; it makes the second hash differ from the first for the same key.
  private static final long SECOND_HASH_SALT = 0x9e3779b97f4a7c15L;
  private static final int BYTE_MASK = 0xFF;

  private SeededHash() {}

  /**
   * Hashes the UTF-8 bytes of {@code key} with {@code seed}.
   *
   * @return a 64-bit hash; a different seed gives an unrelated hash for the same key
   * @throws NullPointerException if {@code key} is null
   */
  public static long hash64(String key, long seed) {
    Objects.requireNonNull(key, "key");
    long state = FNV_OFFSET_BASIS ^ seed;
    for (byte keyByte : key.getBytes(StandardCharsets.UTF_8)) {
      state ^= keyByte & BYTE_MASK;
      state *= FNV_PRIME;
    }
    // The seed enters twice: in the start state, and again between two rounds of mixing, so two
    // seeds give unrelated hashes even for keys whose FNV states differ in only a few bits.
    return finalizeBits(finalizeBits(state) ^ (seed * SECOND_HASH_SALT));
  }

  /**
   * The {@code hashCount} bit positions of {@code key} in an array of {@code bitCount} bits.
   *
   * <p>Double hashing (Kirsch and Mitzenmacher, 2006): position i is (h1 + i * h2) mod m, so k
   * positions cost two hashes, not k. Adding and looking up both call this method, which is why a
   * lookup always checks exactly the bits an add set.
   *
   * @throws IllegalArgumentException if {@code bitCount} or {@code hashCount} is not positive
   */
  public static long[] bitPositions(String key, long seed, long bitCount, int hashCount) {
    if (bitCount <= 0 || hashCount <= 0) {
      throw new IllegalArgumentException(
          "bitCount and hashCount must be positive, got " + bitCount + " and " + hashCount);
    }
    long firstHash = hash64(key, seed);
    // Odd, so the step is never 0 and never shares the factor 2 with an even bit count.
    long secondHash = finalizeBits(firstHash ^ SECOND_HASH_SALT) | 1L;
    long[] positions = new long[hashCount];
    for (int hashIndex = 0; hashIndex < hashCount; hashIndex++) {
      // Unsigned remainder: the sum overflows on purpose and must still land in [0, bitCount).
      positions[hashIndex] = Long.remainderUnsigned(firstHash + hashIndex * secondHash, bitCount);
    }
    return positions;
  }

  private static long finalizeBits(long value) {
    long mixed = value;
    mixed ^= mixed >>> FINALIZER_SHIFT;
    mixed *= FINALIZER_MULTIPLIER_ONE;
    mixed ^= mixed >>> FINALIZER_SHIFT;
    mixed *= FINALIZER_MULTIPLIER_TWO;
    mixed ^= mixed >>> FINALIZER_SHIFT;
    return mixed;
  }
}
