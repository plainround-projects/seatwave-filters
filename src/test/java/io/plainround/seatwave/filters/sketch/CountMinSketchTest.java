package io.plainround.seatwave.filters.sketch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.Random;
import org.junit.jupiter.api.Test;

class CountMinSketchTest {

  private static final long SEED = 0x5ea7_3a7eL;
  private static final double EPSILON = 0.001;
  private static final double DELTA = 0.01;
  private static final int VIEWS = 200_000;
  private static final int SEATS = 50_000;
  private static final long STREAM_SEED = 21L;

  @Test
  void sizesTheModulesSketchAt27183By7() {
    CountMinSketch sketch = CountMinSketch.forError(0.0001, 0.001, SEED);

    assertThat(sketch.width()).isEqualTo(27_183);
    assertThat(sketch.depth()).isEqualTo(7);
    assertThat(sketch.memoryBytes()).isEqualTo(1_522_248);
  }

  @Test
  void neverUndercountsAnySeat() {
    CountMinSketch sketch = CountMinSketch.forError(EPSILON, DELTA, SEED);
    Map<String, Long> exact = skewedStream(sketch);

    long undercounted =
        exact.entrySet().stream()
            .filter(seat -> sketch.estimate(seat.getKey()) < seat.getValue())
            .count();

    assertThat(undercounted).isZero();
  }

  @Test
  void overcountsBeyondEpsilonTimesTheTotalForAtMostAFewSeats() {
    CountMinSketch sketch = CountMinSketch.forError(EPSILON, DELTA, SEED);
    Map<String, Long> exact = skewedStream(sketch);
    double bound = EPSILON * sketch.totalCount();

    long beyond =
        exact.entrySet().stream()
            .filter(seat -> sketch.estimate(seat.getKey()) - seat.getValue() > bound)
            .count();

    assertThat(sketch.totalCount()).isEqualTo(VIEWS);
    assertThat((double) beyond / exact.size()).isLessThanOrEqualTo(DELTA);
  }

  @Test
  void refusesACountOfZero() {
    CountMinSketch sketch = CountMinSketch.forError(EPSILON, DELTA, SEED);

    assertThatThrownBy(() -> sketch.add("event-1:A-1", 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
  }

  @Test
  void refusesAnEpsilonOfOne() {
    assertThatThrownBy(() -> CountMinSketch.forError(1.0, DELTA, SEED))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("epsilon");
  }

  /** Adds a stream where seat r is viewed about in proportion to 1 / r; returns the true counts. */
  private static Map<String, Long> skewedStream(CountMinSketch sketch) {
    Random random = new Random(STREAM_SEED);
    Map<String, Long> exact = new HashMap<>();
    for (int view = 0; view < VIEWS; view++) {
      // 1 / r popularity: the reciprocal of a uniform draw, capped at the seat count.
      int rank = (int) Math.min(SEATS, Math.floor(1 / (1 - random.nextDouble())));
      String seat = "event-1:seat-" + rank;
      sketch.add(seat, 1);
      exact.merge(seat, 1L, Long::sum);
    }
    return exact;
  }
}
