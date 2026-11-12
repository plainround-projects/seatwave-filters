package io.plainround.seatwave.filters.bloom;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Locale;
import java.util.function.ToLongFunction;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Times one hash of a username three ways on this machine: {@link SeededHash#hash64} (FNV-1a with a
 * finalizer, the filter's hash), {@link SeededHash#bitPositions} (that hash plus the 7 positions of
 * a filter at 1 percent), and HMAC-SHA-256 from {@code javax.crypto} (a keyed cryptographic hash
 * that Section 10 names as the defense against an attacker who can measure answers).
 *
 * <p>Teaching code, not a JMH benchmark: each way runs warm-up rounds first, so the JIT compiler
 * has compiled it, then timed rounds over the same million names; it prints the median round, and
 * every result feeds a checksum that is printed, so the compiler cannot drop the work. The numbers
 * are for one thread on the machine that ran it.
 */
public final class HmacCostCli {

  private static final int NAMES = 1_000_000;
  private static final int WARM_UP_ROUNDS = 5;
  private static final int TIMED_ROUNDS = 9;
  // 32 random bytes: as long as HMAC-SHA-256's output, the key length RFC 2104 recommends at least.
  private static final int KEY_BYTES = 32;
  private static final long FILTER_SEED = 0x5ea7_3a7eL;
  // Section 7's design: 1,200,000,000 names at 1 percent.
  private static final BloomSizing DESIGN = BloomSizing.forTarget(1_200_000_000L, 0.01);
  private static final String HMAC = "HmacSHA256";
  private static final int BYTE_BITS = 8;
  private static final int LONG_BYTES = Long.BYTES;
  private static final int BYTE_MASK = 0xFF;

  private HmacCostCli() {}

  /** Prints the median nanoseconds per name of each way, and their ratio. */
  public static void main(String[] arguments) throws GeneralSecurityException {
    String[] names = new String[NAMES];
    for (int nameIndex = 0; nameIndex < NAMES; nameIndex++) {
      names[nameIndex] = "fan_" + (nameIndex + 1);
    }
    byte[] key = new byte[KEY_BYTES];
    new SecureRandom().nextBytes(key); // a throwaway key; never printed
    Mac mac = Mac.getInstance(HMAC);
    mac.init(new SecretKeySpec(key, HMAC));

    double fnv = medianNanosPerName(names, name -> SeededHash.hash64(name, FILTER_SEED));
    double positions =
        medianNanosPerName(
            names,
            name ->
                SeededHash.bitPositions(name, FILTER_SEED, DESIGN.bitCount(), DESIGN.hashCount())[
                    DESIGN.hashCount() - 1]);
    double hmac =
        medianNanosPerName(
            names, name -> firstLong(mac.doFinal(name.getBytes(StandardCharsets.UTF_8))));
    System.out.printf(
        Locale.ROOT,
        "%s, %s; one thread, %,d names fan_1 to fan_%d, median of %d rounds after %d warm-up"
            + " rounds%n",
        System.getProperty("java.vm.name"),
        Runtime.version(),
        NAMES,
        NAMES,
        TIMED_ROUNDS,
        WARM_UP_ROUNDS);
    System.out.printf(
        Locale.ROOT, "SeededHash.hash64 (FNV-1a and finalizer): %.1f ns a name%n", fnv);
    System.out.printf(
        Locale.ROOT,
        "SeededHash.bitPositions, k = %d: %.1f ns a name%n",
        DESIGN.hashCount(),
        positions);
    System.out.printf(Locale.ROOT, "HMAC-SHA-256 (javax.crypto): %.1f ns a name%n", hmac);
    System.out.printf(
        Locale.ROOT,
        "HMAC-SHA-256 against hash64: %.1f times; against hash64 plus the %d positions: %.1f"
            + " times%n",
        hmac / fnv,
        DESIGN.hashCount(),
        hmac / positions);
  }

  private static double medianNanosPerName(String[] names, ToLongFunction<String> hash) {
    double[] rounds = new double[TIMED_ROUNDS];
    long checksum = 0;
    for (int round = 0; round < WARM_UP_ROUNDS + TIMED_ROUNDS; round++) {
      long started = System.nanoTime();
      for (String name : names) {
        checksum += hash.applyAsLong(name); // a sum: an even number of rounds would cancel a XOR
      }
      long nanos = System.nanoTime() - started;
      if (round >= WARM_UP_ROUNDS) {
        rounds[round - WARM_UP_ROUNDS] = (double) nanos / names.length;
      }
    }
    Arrays.sort(rounds);
    System.out.printf(Locale.ROOT, "(checksum %016x)%n", checksum);
    return rounds[TIMED_ROUNDS / 2];
  }

  private static long firstLong(byte[] digest) {
    long value = 0;
    for (int byteIndex = 0; byteIndex < LONG_BYTES; byteIndex++) {
      value = (value << BYTE_BITS) | (digest[byteIndex] & BYTE_MASK);
    }
    return value;
  }
}
