package io.plainround.seatwave.filters.sketch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class HyperLogLogTest {

  private static final long SEED = 0x5ea7_3a7eL;
  private static final int PRECISION = 14;
  private static final int DISTINCT = 1_000_000;
  private static final int REPEATS = 3;
  // Three standard errors: a correct sketch stays inside with probability about 99.7 percent, and
  // the fixed seed makes the outcome the same on every run.
  private static final double STANDARD_ERRORS_ALLOWED = 3;
  // 200 visitors, far below 2.5 times 16,384 registers: the linear-counting range.
  private static final long CLUB_VISITORS = 200;
  private static final long CLUB_MARGIN = 2;

  @Test
  void estimatesAMillionDistinctVisitorsWithinThreeStandardErrors() {
    HyperLogLog sketch = new HyperLogLog(PRECISION, SEED);
    for (int visitor = 1; visitor <= DISTINCT; visitor++) {
      sketch.add("visitor_" + visitor);
    }

    double relativeError = Math.abs(sketch.estimate() - DISTINCT) / (double) DISTINCT;

    assertThat(relativeError).isLessThan(STANDARD_ERRORS_ALLOWED * sketch.standardError());
  }

  @Test
  void repeatedVisitsDoNotChangeTheEstimate() {
    HyperLogLog once = new HyperLogLog(PRECISION, SEED);
    HyperLogLog repeated = new HyperLogLog(PRECISION, SEED);
    for (int visitor = 1; visitor <= DISTINCT; visitor++) {
      once.add("visitor_" + visitor);
      for (int repeat = 0; repeat < REPEATS; repeat++) {
        repeated.add("visitor_" + visitor);
      }
    }

    assertThat(repeated.estimate()).isEqualTo(once.estimate());
  }

  @Test
  void mergedSketchesEstimateTheUnionNotTheSum() {
    HyperLogLog firstInstance = new HyperLogLog(PRECISION, SEED);
    HyperLogLog secondInstance = new HyperLogLog(PRECISION, SEED);
    HyperLogLog everything = new HyperLogLog(PRECISION, SEED);
    for (int visitor = 1; visitor <= DISTINCT; visitor++) {
      // Every visitor reaches the first instance, every second one also the second.
      firstInstance.add("visitor_" + visitor);
      everything.add("visitor_" + visitor);
      if (visitor % 2 == 0) {
        secondInstance.add("visitor_" + visitor);
      }
    }

    firstInstance.merge(secondInstance);

    assertThat(firstInstance.estimate()).isEqualTo(everything.estimate());
  }

  @Test
  void countsASmallClubNightWithLinearCountingWithinOnePercent() {
    HyperLogLog sketch = new HyperLogLog(PRECISION, SEED);
    for (int visitor = 1; visitor <= CLUB_VISITORS; visitor++) {
      sketch.add("visitor_" + visitor);
    }

    assertThat(sketch.estimate())
        .isBetween(CLUB_VISITORS - CLUB_MARGIN, CLUB_VISITORS + CLUB_MARGIN);
  }

  @Test
  void refusesToMergeASketchWithAnotherSeed() {
    HyperLogLog sketch = new HyperLogLog(PRECISION, SEED);

    assertThatThrownBy(() -> sketch.merge(new HyperLogLog(PRECISION, SEED + 1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("seed");
  }

  @Test
  void refusesAPrecisionAboveEighteen() {
    assertThatThrownBy(() -> new HyperLogLog(HyperLogLog.MAX_PRECISION + 1, SEED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("precision");
  }

  @Test
  void statesItsStandardErrorAsOnePointZeroFourOverTheRootOfTheRegisters() {
    HyperLogLog sketch = new HyperLogLog(PRECISION, SEED);

    assertThat(sketch.standardError()).isEqualTo(0.008125);
    assertThat(sketch.memoryBytes()).isEqualTo(16_384);
  }
}
