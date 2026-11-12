package io.plainround.seatwave.filters.signup;

import io.plainround.seatwave.filters.bloom.BloomFilter;
import io.plainround.seatwave.filters.bloom.BloomSizing;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.regex.Pattern;

/**
 * SeatWave's username-taken check: a Bloom filter in front of {@link UsernameStore}.
 *
 * <p>The rule that keeps it correct is "fail safe on maybe": only a filter "no" is answered from
 * memory; every "maybe", and every check while no filter is installed, reads the store. The check
 * is advisory: the sign-up insert itself is still refused by the unique index {@code
 * one_customer_per_ref} if two fans race for one name. Thread-safe.
 */
public final class UsernameCheck {

  /** The answer to one check. */
  public sealed interface Availability {
    /** The name is free; {@code answeredFromFilter} says whether the database was spared. */
    record Free(boolean answeredFromFilter) implements Availability {}

    /** A customer already has this name. */
    record Taken() implements Availability {}

    /** The name breaks Module 13's {@code customer_ref_format} rule, so nothing was read. */
    record Rejected(String reason) implements Availability {}
  }

  /** Counters since start-up, for the operations metrics of Section 10. */
  public record CheckCounts(
      long checks, long answeredFromFilter, long storeReads, long falsePositives) {
    /** Free names the filter called "maybe", over all free names: the measured rate. */
    public double measuredFalsePositiveRate() {
      long freeNames = answeredFromFilter + falsePositives;
      return freeNames == 0 ? 0 : (double) falsePositives / freeNames;
    }
  }

  // Module 13's check constraint customer_ref_format, applied before any work.
  private static final int MAX_USERNAME_LENGTH = 64;
  private static final Pattern USERNAME_FORMAT = Pattern.compile("[A-Za-z0-9_-]{1,64}");

  private final UsernameStore store;
  private final AtomicReference<BloomFilter> installed = new AtomicReference<>();
  private final AtomicReference<BloomFilter> beingBuilt = new AtomicReference<>();
  private final AtomicLong checks = new AtomicLong();
  private final AtomicLong answeredFromFilter = new AtomicLong();
  private final AtomicLong storeReads = new AtomicLong();
  private final AtomicLong falsePositives = new AtomicLong();

  /** Creates a check with no filter yet: until {@link #rebuild} succeeds, every check reads. */
  public UsernameCheck(UsernameStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  /** Validates {@code candidate}, then answers from the filter or the store. */
  public Availability check(String candidate) {
    Objects.requireNonNull(candidate, "candidate");
    if (candidate.length() > MAX_USERNAME_LENGTH || !USERNAME_FORMAT.matcher(candidate).matches()) {
      return new Availability.Rejected("1 to 64 letters, digits, '_' or '-'");
    }
    checks.incrementAndGet();
    Optional<BloomFilter> filter = Optional.ofNullable(installed.get());
    if (filter.isPresent() && !filter.get().mightContain(candidate)) {
      answeredFromFilter.incrementAndGet();
      return new Availability.Free(true);
    }
    storeReads.incrementAndGet();
    if (store.isTaken(candidate)) {
      return new Availability.Taken();
    }
    if (filter.isPresent()) {
      falsePositives.incrementAndGet(); // the filter said maybe, the database said free
    }
    return new Availability.Free(false);
  }

  /**
   * Call after the sign-up insert has committed, never before: a filter that learns a name the
   * database then refuses only costs one extra read later, but one that misses a committed name
   * would say "free" for a taken name.
   */
  public void recordSignUp(String username) {
    // The filter being built is read first. Read the other way round, a rebuild could install its
    // filter and clear beingBuilt between the two reads, and the name would reach only the old
    // filter while the new one, which scanned before the commit, would call it free.
    Optional.ofNullable(beingBuilt.get()).ifPresent(filter -> filter.add(username));
    Optional.ofNullable(installed.get()).ifPresent(filter -> filter.add(username));
  }

  /**
   * Builds a new filter from every name in the store and installs it.
   *
   * <p>The new filter receives sign-ups from before the scan starts, so a name committed while the
   * scan runs is in it either way. If the scan fails, the old filter (or none) stays, and checks
   * keep reading the store: slower, never wrong.
   *
   * @return the number of names loaded
   */
  public long rebuild(BloomSizing sizing, long seed) {
    BloomFilter fresh = new BloomFilter(sizing, seed);
    beingBuilt.set(fresh);
    try {
      long loaded = store.forEachUsername(fresh::add);
      installed.set(fresh);
      return loaded;
    } finally {
      beingBuilt.set(null);
    }
  }

  /** The installed filter, if any, for its fill ratio and add count. */
  public Optional<BloomFilter> installedFilter() {
    return Optional.ofNullable(installed.get());
  }

  /** A snapshot of the counters. */
  public CheckCounts counts() {
    return new CheckCounts(
        checks.get(), answeredFromFilter.get(), storeReads.get(), falsePositives.get());
  }
}
