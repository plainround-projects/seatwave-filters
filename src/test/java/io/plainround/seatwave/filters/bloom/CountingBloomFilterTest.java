package io.plainround.seatwave.filters.bloom;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class CountingBloomFilterTest {

  private static final long SEED = 0x5ea7_3a7eL;
  private static final int NAMES = 100_000;
  private static final int REMOVED = 10_000;
  private static final BloomSizing SIZING = BloomSizing.forTarget(NAMES, 0.01);
  private static final int ADDS_PAST_SATURATION = 20; // a 4-bit counter holds at most 15

  @Test
  void removingNamesThatWereAddedLeavesEveryOtherNamePresent() {
    CountingBloomFilter filter = filled();

    IntStream.rangeClosed(1, REMOVED).forEach(index -> filter.remove("fan_" + index));

    long falseNegatives =
        IntStream.rangeClosed(REMOVED + 1, NAMES)
            .filter(index -> !filter.mightContain("fan_" + index))
            .count();
    assertThat(falseNegatives).isZero();
  }

  @Test
  void aRemovedNameIsUsuallyReportedAbsent() {
    CountingBloomFilter filter = filled();

    IntStream.rangeClosed(1, REMOVED).forEach(index -> filter.remove("fan_" + index));

    long stillMaybe =
        IntStream.rangeClosed(1, REMOVED)
            .filter(index -> filter.mightContain("fan_" + index))
            .count();
    assertThat(stillMaybe).isLessThan(REMOVED / 20);
  }

  @Test
  void refusesToRemoveANameItCanProveWasNeverAdded() {
    CountingBloomFilter filter = new CountingBloomFilter(SIZING, SEED);
    filter.add("fan_1");

    boolean removed = filter.remove("guest_1");

    assertThat(removed).isFalse();
    assertThat(filter.mightContain("fan_1")).isTrue();
  }

  @Test
  void aSaturatedCounterIsNeverDecremented() {
    CountingBloomFilter filter = new CountingBloomFilter(SIZING, SEED);
    IntStream.range(0, ADDS_PAST_SATURATION).forEach(add -> filter.add("fan_1"));

    IntStream.range(0, ADDS_PAST_SATURATION).forEach(remove -> filter.remove("fan_1"));

    assertThat(filter.mightContain("fan_1")).isTrue();
  }

  @Test
  void costsFourTimesThePlainFiltersMemory() {
    CountingBloomFilter filter = new CountingBloomFilter(SIZING, SEED);

    assertThat(filter.memoryBytes()).isEqualTo(4 * SIZING.memoryBytes());
  }

  private static CountingBloomFilter filled() {
    CountingBloomFilter filter = new CountingBloomFilter(SIZING, SEED);
    IntStream.rangeClosed(1, NAMES).forEach(index -> filter.add("fan_" + index));
    return filter;
  }
}
