package io.plainround.seatwave.filters.sketch;

import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.stream.Collectors;

/**
 * Finds the most-viewed seats of a skewed stream of seat views with a count-min sketch and a small
 * heap of candidates, and compares them with exact counts.
 *
 * <p>The stream: 500 arena events on sale, 20,000 seats each (Module 13's sections A to T of
 * 1,000), popularity falling as 1 / rank both across events and across seats (a Zipf law), which is
 * an assumption, not a measurement of fans.
 */
public final class TopSeatsCli {

  private static final long STREAM_SEED = 21L;
  private static final long SKETCH_SEED = 0x5ea7_3a7eL;
  private static final int EVENTS = 500;
  private static final int SEATS_PER_EVENT = 20_000;
  private static final int SEATS_PER_SECTION = 1_000;
  private static final int VIEWS = 5_000_000;
  private static final double EPSILON = 0.0001; // error: 0.01 percent of all views
  private static final double DELTA = 0.001; // probability the error is exceeded
  private static final int TOP = 10;

  private TopSeatsCli() {}

  /** Prints the sketch's size and bound, its top seats against the exact top, and its errors. */
  public static void main(String[] arguments) {
    Random random = new Random(STREAM_SEED);
    double[] eventCdf = zipfCdf(EVENTS);
    double[] seatCdf = zipfCdf(SEATS_PER_EVENT);
    CountMinSketch sketch = CountMinSketch.forError(EPSILON, DELTA, SKETCH_SEED);
    Map<String, Long> exact = new HashMap<>();
    Map<String, Long> candidates = new HashMap<>();
    PriorityQueue<String> smallestCandidateFirst =
        new PriorityQueue<>(Comparator.comparingLong(candidates::get));

    for (int viewIndex = 0; viewIndex < VIEWS; viewIndex++) {
      String seatKey = seatKey(sample(eventCdf, random), sample(seatCdf, random));
      sketch.add(seatKey, 1);
      exact.merge(seatKey, 1L, Long::sum);
      long estimate = sketch.estimate(seatKey);
      if (candidates.containsKey(seatKey)) {
        smallestCandidateFirst.remove(seatKey); // re-insert, so the heap sees the new estimate
        candidates.put(seatKey, estimate);
        smallestCandidateFirst.add(seatKey);
      } else if (candidates.size() < TOP
          || estimate > candidates.get(smallestCandidateFirst.peek())) {
        if (candidates.size() == TOP) {
          candidates.remove(smallestCandidateFirst.poll());
        }
        candidates.put(seatKey, estimate);
        smallestCandidateFirst.add(seatKey);
      }
    }

    long bound = Math.round(EPSILON * sketch.totalCount());
    System.out.printf(
        Locale.ROOT,
        "sketch: width %,d x depth %d = %,d counters, %,d bytes | views %,d | distinct seats viewed"
            + " %,d | bound: overcount <= %,d views with probability >= %.3f%n",
        sketch.width(),
        sketch.depth(),
        sketch.width() * sketch.depth(),
        sketch.memoryBytes(),
        sketch.totalCount(),
        exact.size(),
        bound,
        1 - DELTA);

    List<String> exactTop =
        exact.entrySet().stream()
            .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
            .limit(TOP)
            .map(Map.Entry::getKey)
            .toList();
    List<String> sketchTop =
        candidates.keySet().stream()
            .sorted(Comparator.comparingLong(candidates::get).reversed())
            .toList();
    System.out.println("rank  sketch top          estimate  true      | exact top           true");
    for (int rank = 0; rank < TOP; rank++) {
      String found = sketchTop.get(rank);
      String truth = exactTop.get(rank);
      System.out.printf(
          Locale.ROOT,
          "%4d  %-18s  %,8d  %,8d  | %-18s  %,8d%n",
          rank + 1,
          found,
          sketch.estimate(found),
          exact.get(found),
          truth,
          exact.get(truth));
    }
    long agreeing = sketchTop.stream().filter(exactTop::contains).count();
    Map<Boolean, Long> withinBound =
        exact.entrySet().stream()
            .collect(
                Collectors.partitioningBy(
                    entry -> sketch.estimate(entry.getKey()) - entry.getValue() <= bound,
                    Collectors.counting()));
    long maxOvercount =
        exact.entrySet().stream()
            .mapToLong(entry -> sketch.estimate(entry.getKey()) - entry.getValue())
            .max()
            .orElse(0);
    System.out.printf(
        Locale.ROOT,
        "top-%d agreement %d of %d | seats over the bound %,d of %,d | largest overcount %,d views"
            + " | undercounts 0 by construction%n",
        TOP,
        agreeing,
        TOP,
        withinBound.get(false),
        exact.size(),
        maxOvercount);
  }

  private static String seatKey(int eventRank, int seatRank) {
    char section = (char) ('A' + (seatRank - 1) / SEATS_PER_SECTION);
    return "event-" + eventRank + ":" + section + "-" + ((seatRank - 1) % SEATS_PER_SECTION + 1);
  }

  private static double[] zipfCdf(int ranks) {
    double[] cumulative = new double[ranks];
    double running = 0;
    for (int rank = 1; rank <= ranks; rank++) {
      running += 1.0 / rank;
      cumulative[rank - 1] = running;
    }
    for (int rank = 0; rank < ranks; rank++) {
      cumulative[rank] /= running;
    }
    return cumulative;
  }

  private static int sample(double[] cumulative, Random random) {
    int found = Arrays.binarySearch(cumulative, random.nextDouble());
    int index = found >= 0 ? found : -found - 1;
    return Math.min(index, cumulative.length - 1) + 1; // rounding can leave the last sum below 1
  }
}
