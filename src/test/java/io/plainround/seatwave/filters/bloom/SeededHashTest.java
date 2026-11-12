package io.plainround.seatwave.filters.bloom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Arrays;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class SeededHashTest {

  private static final long SEED = 0x5ea7_3a7eL;
  // Not a power of two and not a multiple of 64, so a signed remainder would show as negative.
  private static final long ODD_BIT_COUNT = 1_000_003L;
  private static final int HASH_COUNT = 7;
  private static final int NAMES = 100_000;

  @Test
  void keepsEveryPositionInsideTheArrayEvenWhenTheSumOverflows() {
    boolean allInside =
        IntStream.rangeClosed(1, NAMES)
            .mapToObj(
                index -> SeededHash.bitPositions("fan_" + index, SEED, ODD_BIT_COUNT, HASH_COUNT))
            .flatMapToLong(Arrays::stream)
            .allMatch(position -> position >= 0 && position < ODD_BIT_COUNT);

    assertThat(allInside).isTrue();
  }

  @Test
  void givesTheSamePositionsForTheSameNameAndSeed() {
    assertThat(SeededHash.bitPositions("okafor_77", SEED, ODD_BIT_COUNT, HASH_COUNT))
        .containsExactly(SeededHash.bitPositions("okafor_77", SEED, ODD_BIT_COUNT, HASH_COUNT));
  }

  @Test
  void givesAnUnrelatedHashForAnotherSeed() {
    assertThat(SeededHash.hash64("okafor_77", SEED))
        .isNotEqualTo(SeededHash.hash64("okafor_77", SEED + 1));
  }

  @Test
  void rejectsAZeroBitCount() {
    assertThatThrownBy(() -> SeededHash.bitPositions("fan_1", SEED, 0, HASH_COUNT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bitCount");
  }
}
