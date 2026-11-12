package io.plainround.seatwave.filters.labs;

import io.plainround.seatwave.filters.sketch.HyperLogLog;
import java.util.HashSet;
import java.util.Locale;
import java.util.Random;
import java.util.Set;

/**
 * Lab 3: counts unique visitors per event for a generated stream with HyperLogLog and with an exact
 * set, and compares memory and error.
 *
 * <p>The exact set's memory is read from the heap before and after it is built, each time after a
 * requested garbage collection: approximate, but the same order of size on every run.
 */
public final class UniqueVisitorsLabCli {

  private static final long STREAM_SEED = 2021L;
  private static final long SKETCH_SEED = 0x5ea7_3a7eL;
  private static final int PRECISION = 14;
  private static final String[] EVENT_NAMES = {"club night", "arena night", "national tour day"};
  private static final int[] UNIQUE_VISITORS = {1_500, 200_000, 2_000_000};
  private static final int VISITS_BOUND = 10;
  private static final int BITS_PER_PACKED_REGISTER = 6; // enough for ranks up to 63
  private static final double BYTES_PER_MEGABYTE = 1e6;

  private UniqueVisitorsLabCli() {}

  /** Prints one row per event: visits, exact and estimated uniques, error and memory. */
  public static void main(String[] arguments) {
    Random random = new Random(STREAM_SEED);
    System.out.println(
        "event              | visits      | exact     | HLL       | error   |"
            + " exact set MB | HLL bytes (packed)");
    for (int eventIndex = 0; eventIndex < EVENT_NAMES.length; eventIndex++) {
      long heapBefore = usedHeapAfterCollection();
      Set<String> exact = new HashSet<>();
      HyperLogLog sketch = new HyperLogLog(PRECISION, SKETCH_SEED);
      long visits = 0;
      for (int visitorIndex = 1; visitorIndex <= UNIQUE_VISITORS[eventIndex]; visitorIndex++) {
        String visitor = "session_" + eventIndex + "_" + visitorIndex;
        int visitorVisits = 1 + random.nextInt(VISITS_BOUND);
        for (int visit = 0; visit < visitorVisits; visit++) {
          exact.add(visitor);
          sketch.add(visitor);
        }
        visits += visitorVisits;
      }
      long exactSetBytes = usedHeapAfterCollection() - heapBefore;
      long estimate = sketch.estimate();
      System.out.printf(
          Locale.ROOT,
          "%-18s | %,11d | %,9d | %,9d | %+.2f%% | %12.1f | %,d (%,d)%n",
          EVENT_NAMES[eventIndex],
          visits,
          exact.size(),
          estimate,
          100.0 * (estimate - exact.size()) / exact.size(),
          exactSetBytes / BYTES_PER_MEGABYTE,
          sketch.memoryBytes(),
          sketch.memoryBytes() * BITS_PER_PACKED_REGISTER / Byte.SIZE);
    }
  }

  private static long usedHeapAfterCollection() {
    Runtime runtime = Runtime.getRuntime();
    System.gc(); // a request, not a guarantee: hence "approximate" in the class comment
    return runtime.totalMemory() - runtime.freeMemory();
  }
}
