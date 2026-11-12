package io.plainround.seatwave.filters.sketch;

import java.util.HashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Counts unique visitors per event with HyperLogLog and with an exact set, then measures how the
 * error depends on the number of registers and not on the number of visitors, and merges the
 * sketches of three instances.
 *
 * <p>Deterministic: fixed seeds. Visitor identifiers are synthetic (visitor_1, visitor_2, ...);
 * production would add an opaque session identifier, never a personal one.
 */
public final class VisitorCountCli {

  private static final long STREAM_SEED = 21L;
  private static final long SKETCH_SEED = 0x5ea7_3a7eL;
  private static final int PRECISION = 14; // 16,384 registers, Redis's choice too
  private static final int[] EVENT_VISITORS = {200, 20_000, 200_000, 2_000_000};
  private static final int VIEWS_PER_VISITOR_BOUND = 10; // each visitor opens 1 to 10 pages
  private static final int[] SWEEP_PRECISIONS = {10, 12, 14, 16};
  private static final int SWEEP_VISITORS = 1_000_000;
  private static final int SWEEP_TRIALS = 40;
  private static final int INSTANCES = 3; // Module 10's three SeatWave instances

  private VisitorCountCli() {}

  /** Prints the per-event counts, the error sweep and the merge. */
  public static void main(String[] arguments) {
    Random random = new Random(STREAM_SEED);
    System.out.println("== per event, precision 14 (16,384 registers, 16,384 bytes)");
    for (int visitors : EVENT_VISITORS) {
      HyperLogLog sketch = new HyperLogLog(PRECISION, SKETCH_SEED);
      Set<String> exact = new HashSet<>();
      long views = 0;
      for (int visitorIndex = 1; visitorIndex <= visitors; visitorIndex++) {
        String visitor = "visitor_" + visitorIndex;
        int visitorViews = 1 + random.nextInt(VIEWS_PER_VISITOR_BOUND);
        for (int viewIndex = 0; viewIndex < visitorViews; viewIndex++) {
          sketch.add(visitor); // repeats change nothing: that is the point
          exact.add(visitor);
        }
        views += visitorViews;
      }
      long estimate = sketch.estimate();
      System.out.printf(
          Locale.ROOT,
          "views %,11d | exact unique %,9d | HLL %,9d | error %+.3f%%%n",
          views,
          exact.size(),
          estimate,
          100.0 * (estimate - exact.size()) / exact.size());
    }

    System.out.printf(
        Locale.ROOT,
        "== error against registers: %,d distinct visitors, %d seeds each%n",
        SWEEP_VISITORS,
        SWEEP_TRIALS);
    for (int precision : SWEEP_PRECISIONS) {
      double sumOfSquares = 0;
      double worst = 0;
      for (int trial = 0; trial < SWEEP_TRIALS; trial++) {
        HyperLogLog sketch = new HyperLogLog(precision, SKETCH_SEED + trial);
        for (int visitorIndex = 1; visitorIndex <= SWEEP_VISITORS; visitorIndex++) {
          sketch.add("visitor_" + visitorIndex);
        }
        double relativeError = (sketch.estimate() - SWEEP_VISITORS) / (double) SWEEP_VISITORS;
        sumOfSquares += relativeError * relativeError;
        worst = Math.max(worst, Math.abs(relativeError));
      }
      HyperLogLog sized = new HyperLogLog(precision, SKETCH_SEED);
      System.out.printf(
          Locale.ROOT,
          "precision %2d | registers %,7d | bytes %,7d | theory 1.04/sqrt(m) %.3f%% |"
              + " measured RMS %.3f%% | worst %.3f%%%n",
          precision,
          sized.memoryBytes(),
          sized.memoryBytes(),
          100 * sized.standardError(),
          100 * Math.sqrt(sumOfSquares / SWEEP_TRIALS),
          100 * worst);
    }

    System.out.println("== three instances, one sketch each, merged");
    HyperLogLog merged = new HyperLogLog(PRECISION, SKETCH_SEED);
    HyperLogLog[] perInstance = new HyperLogLog[INSTANCES];
    for (int instance = 0; instance < INSTANCES; instance++) {
      perInstance[instance] = new HyperLogLog(PRECISION, SKETCH_SEED);
    }
    for (int visitorIndex = 1; visitorIndex <= SWEEP_VISITORS; visitorIndex++) {
      // A visitor's requests are spread over instances, so most visitors reach two or three.
      for (int instance = 0; instance < INSTANCES; instance++) {
        if (random.nextBoolean()) {
          perInstance[instance].add("visitor_" + visitorIndex);
        }
      }
      perInstance[visitorIndex % INSTANCES].add("visitor_" + visitorIndex);
    }
    long sumOfInstanceCounts = 0;
    for (HyperLogLog instanceSketch : perInstance) {
      sumOfInstanceCounts += instanceSketch.estimate();
      merged.merge(instanceSketch);
    }
    System.out.printf(
        Locale.ROOT,
        "true unique %,d | sum of instance estimates %,d (double counts) | merged estimate %,d%n",
        SWEEP_VISITORS,
        sumOfInstanceCounts,
        merged.estimate());
  }
}
