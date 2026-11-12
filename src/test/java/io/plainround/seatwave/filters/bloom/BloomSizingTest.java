package io.plainround.seatwave.filters.bloom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class BloomSizingTest {

  private static final double ONE_PERCENT = 0.01;

  @Test
  void sizesTheModulesDesignCountAtOnePercentIn1437Point8Megabytes() {
    BloomSizing sizing = BloomSizing.forTarget(1_200_000_000L, ONE_PERCENT);

    assertThat(sizing.bitCount()).isEqualTo(11_502_070_080L);
    assertThat(sizing.hashCount()).isEqualTo(7);
    assertThat(sizing.memoryBytes()).isEqualTo(1_437_758_760L);
  }

  @Test
  void roundsTheBitCountUpToWholeLongs() {
    BloomSizing sizing = BloomSizing.forTarget(10_000_000L, ONE_PERCENT);

    assertThat(sizing.bitCount()).isEqualTo(95_850_624L);
    assertThat(sizing.bitCount() % BloomSizing.BITS_PER_WORD).isZero();
  }

  @Test
  void predictsAHigherRateOncePastTheDesignCount() {
    BloomSizing sizing = BloomSizing.forTarget(1_000_000_000L, ONE_PERCENT);

    assertThat(sizing.theoreticalFalsePositiveRate(1_200_000_000L)).isBetween(0.0230, 0.0232);
  }

  @Test
  void rejectsAFilterLargerThanAnArrayOfLongsCanHold() {
    assertThatThrownBy(() -> BloomSizing.forTarget(20_000_000_000L, ONE_PERCENT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("bitCount");
  }

  @Test
  void rejectsADesignCountOfZero() {
    assertThatThrownBy(() -> BloomSizing.forTarget(0, ONE_PERCENT))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("designItems");
  }

  @Test
  void rejectsMoreThanThirtyHashFunctions() {
    assertThatThrownBy(() -> new BloomSizing(1, ONE_PERCENT, 64, 31))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("hashCount");
  }
}
