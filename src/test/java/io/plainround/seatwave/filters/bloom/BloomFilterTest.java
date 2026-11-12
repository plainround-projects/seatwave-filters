package io.plainround.seatwave.filters.bloom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class BloomFilterTest {

  private static final long SEED = 0x5ea7_3a7eL;
  private static final int MILLION = 1_000_000;
  private static final double TARGET_RATE = 0.01;
  // About 10,000 false positives are expected in a million probes, with a standard deviation of
  // about 100 (a binomial count); ten of them is 10 percent of the rate. 20 percent is a wide
  // margin for a correct filter and still fails one whose hashing or sizing is wrong.
  private static final double ALLOWED_RELATIVE_DEVIATION = 0.20;
  // Eight threads adding 125,000 names each: the shape of race-demo.jsh, where a plain
  // read-modify-write of a long lost 162 to 217 names per run. Four rounds, each on a new filter,
  // make a lost bit near certain to show if the atomic OR is ever replaced.
  private static final int THREADS = 8;
  private static final int NAMES_PER_THREAD = 125_000;
  private static final int ROUNDS = 4;

  @Test
  void neverReportsAbsentForAnyOfAMillionInsertedNames() {
    BloomFilter filter = new BloomFilter(BloomSizing.forTarget(MILLION, TARGET_RATE), SEED);
    IntStream.rangeClosed(1, MILLION).forEach(index -> filter.add("fan_" + index));

    long falseNegatives =
        IntStream.rangeClosed(1, MILLION)
            .filter(index -> !filter.mightContain("fan_" + index))
            .count();

    assertThat(falseNegatives).isZero();
  }

  @Test
  void falsePositiveRateOnAMillionAbsentNamesMatchesTheory() {
    BloomSizing sizing = BloomSizing.forTarget(MILLION, TARGET_RATE);
    BloomFilter filter = new BloomFilter(sizing, SEED);
    IntStream.rangeClosed(1, MILLION).forEach(index -> filter.add("fan_" + index));

    long falsePositives =
        IntStream.rangeClosed(1, MILLION)
            .filter(index -> filter.mightContain("guest_" + index))
            .count();

    double theory = sizing.theoreticalFalsePositiveRate(MILLION);
    assertThat((double) falsePositives / MILLION)
        .isBetween(
            theory * (1 - ALLOWED_RELATIVE_DEVIATION), theory * (1 + ALLOWED_RELATIVE_DEVIATION));
  }

  @Test
  void sizesOneBillionNamesAtOnePercentWithSevenHashesInAboutTenBitsEach() {
    BloomSizing sizing = BloomSizing.forTarget(1_000_000_000L, TARGET_RATE);

    assertThat(sizing.hashCount()).isEqualTo(7);
    assertThat(sizing.bitsPerItem()).isBetween(9.58, 9.59);
  }

  @Test
  void rejectsATargetRateOutsideZeroToOne() {
    assertThatThrownBy(() -> BloomSizing.forTarget(MILLION, 1.0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("rate");
  }

  @Test
  void concurrentAddsFromEightThreadsLoseNoName() throws Exception {
    long falseNegatives = 0;
    for (int round = 0; round < ROUNDS; round++) {
      BloomFilter filter =
          new BloomFilter(BloomSizing.forTarget(THREADS * NAMES_PER_THREAD, TARGET_RATE), SEED);
      addFromAllThreadsAtOnce(filter);

      falseNegatives +=
          IntStream.rangeClosed(1, THREADS * NAMES_PER_THREAD)
              .filter(index -> !filter.mightContain("fan_" + index))
              .count();
    }

    assertThat(falseNegatives).isZero();
  }

  @Test
  void addingANameAgainChangesOnlyTheAddCount() {
    BloomFilter filter = new BloomFilter(BloomSizing.forTarget(MILLION, TARGET_RATE), SEED);
    filter.add("fan_1");
    double fillAfterOne = filter.fillRatio();

    filter.add("fan_1");

    assertThat(filter.fillRatio()).isEqualTo(fillAfterOne);
    assertThat(filter.addCalls()).isEqualTo(2);
  }

  @Test
  void anEmptyFilterReportsEveryNameAbsent() {
    BloomFilter filter = new BloomFilter(BloomSizing.forTarget(MILLION, TARGET_RATE), SEED);

    long maybes =
        IntStream.rangeClosed(1, MILLION / 100)
            .filter(index -> filter.mightContain("fan_" + index))
            .count();

    assertThat(maybes).isZero();
    assertThat(filter.fillRatio()).isZero();
  }

  private static void addFromAllThreadsAtOnce(BloomFilter filter) throws Exception {
    CountDownLatch startTogether = new CountDownLatch(1);
    List<Future<?>> adders = new ArrayList<>();
    // Platform threads, one per adder, so the adds really run at the same time on several cores.
    try (ExecutorService pool = Executors.newFixedThreadPool(THREADS)) {
      for (int thread = 0; thread < THREADS; thread++) {
        int first = thread * NAMES_PER_THREAD + 1;
        adders.add(
            pool.submit(() -> {
              startTogether.await();
              for (int name = first; name < first + NAMES_PER_THREAD; name++) {
                filter.add("fan_" + name);
              }
              return null;
            }));
      }
      startTogether.countDown();
      for (Future<?> adder : adders) {
        adder.get(); // rethrows a failure inside an adder
      }
    }
  }
}
