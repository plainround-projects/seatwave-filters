package io.plainround.seatwave.filters.signup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import io.plainround.seatwave.filters.bloom.BloomFilter;
import io.plainround.seatwave.filters.bloom.BloomSizing;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.stream.IntStream;
import org.junit.jupiter.api.Test;

class UsernameCheckTest {

  private static final long SEED = 0x5ea7_3a7eL;
  private static final int NAMES = 10_000;
  private static final BloomSizing SIZING = BloomSizing.forTarget(NAMES, 0.01);

  @Test
  void rejectsANameThatBreaksTheFormatWithoutReadingTheStore() {
    CountingStore store = new CountingStore(names(NAMES));
    UsernameCheck check = new UsernameCheck(store);

    UsernameCheck.Availability answer = check.check("o'kafor");

    assertThat(answer).isInstanceOf(UsernameCheck.Availability.Rejected.class);
    assertThat(store.reads).isZero();
    assertThat(check.counts().checks()).isZero();
  }

  @Test
  void rejectsANameLongerThanSixtyFourCharacters() {
    UsernameCheck check = new UsernameCheck(new CountingStore(names(NAMES)));

    assertThat(check.check("a".repeat(65))).isInstanceOf(UsernameCheck.Availability.Rejected.class);
  }

  @Test
  void readsTheStoreForEveryCheckWhileNoFilterIsInstalled() {
    CountingStore store = new CountingStore(names(NAMES));
    UsernameCheck check = new UsernameCheck(store);

    UsernameCheck.Availability free = check.check("guest_1");
    UsernameCheck.Availability taken = check.check("fan_1");

    assertThat(free).isEqualTo(new UsernameCheck.Availability.Free(false));
    assertThat(taken).isInstanceOf(UsernameCheck.Availability.Taken.class);
    assertThat(store.reads).isEqualTo(2);
    assertThat(check.counts().falsePositives()).isZero();
  }

  @Test
  void neverCallsATakenNameFreeAfterARebuild() {
    UsernameCheck check = new UsernameCheck(new CountingStore(names(NAMES)));
    check.rebuild(SIZING, SEED);

    long calledFree =
        names(NAMES).stream()
            .filter(name -> check.check(name) instanceof UsernameCheck.Availability.Free)
            .count();

    assertThat(calledFree).isZero();
  }

  @Test
  void answersMostFreeNamesFromTheFilterAndCountsTheRestAsFalsePositives() {
    CountingStore store = new CountingStore(names(NAMES));
    UsernameCheck check = new UsernameCheck(store);
    check.rebuild(SIZING, SEED);
    store.reads = 0;

    IntStream.rangeClosed(1, NAMES).forEach(index -> check.check("guest_" + index));

    UsernameCheck.CheckCounts counts = check.counts();
    assertThat(counts.answeredFromFilter() + counts.falsePositives()).isEqualTo(NAMES);
    assertThat(counts.storeReads()).isEqualTo(counts.falsePositives()).isEqualTo(store.reads);
    assertThat(counts.measuredFalsePositiveRate()).isBetween(0.005, 0.015);
  }

  @Test
  void aNameCommittedDuringTheScanIsInTheNewFilter() {
    // The store commits "okafor_77" while the rebuild's scan runs, after the scan's snapshot, and
    // the sign-up path calls recordSignUp, as the service does after its commit.
    List<String> snapshot = names(NAMES);
    ScanHookStore store = new ScanHookStore(snapshot);
    UsernameCheck check = new UsernameCheck(store);
    store.duringScan =
        () -> {
          store.committed.add("okafor_77");
          check.recordSignUp("okafor_77");
        };

    check.rebuild(SIZING, SEED);

    assertThat(check.check("okafor_77")).isInstanceOf(UsernameCheck.Availability.Taken.class);
    assertThat(check.installedFilter().orElseThrow().mightContain("okafor_77")).isTrue();
  }

  @Test
  void aSignUpAfterTheRebuildReachesTheInstalledFilter() {
    UsernameCheck check = new UsernameCheck(new CountingStore(names(NAMES)));
    check.rebuild(SIZING, SEED);

    check.recordSignUp("okafor_77");

    assertThat(check.installedFilter().orElseThrow().mightContain("okafor_77")).isTrue();
  }

  @Test
  void aStoreFailureDuringARebuildLeavesTheOldFilterInPlace() {
    ScanHookStore store = new ScanHookStore(names(NAMES));
    UsernameCheck check = new UsernameCheck(store);
    check.rebuild(SIZING, SEED);
    BloomFilter old = check.installedFilter().orElseThrow();
    store.duringScan =
        () -> {
          throw new IllegalStateException("could not scan customers.customer_ref for the filter");
        };

    assertThatThrownBy(() -> check.rebuild(SIZING, SEED))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("could not scan");

    assertThat(check.installedFilter()).containsSame(old);
    assertThat(check.check("fan_1")).isInstanceOf(UsernameCheck.Availability.Taken.class);
  }

  @Test
  void aFailedFirstRebuildLeavesNoFilterSoEveryCheckReadsTheStore() {
    ScanHookStore store = new ScanHookStore(names(NAMES));
    store.duringScan =
        () -> {
          throw new IllegalStateException("could not scan customers.customer_ref for the filter");
        };
    UsernameCheck check = new UsernameCheck(store);

    assertThatThrownBy(() -> check.rebuild(SIZING, SEED)).isInstanceOf(IllegalStateException.class);

    assertThat(check.installedFilter()).isEmpty();
    assertThat(check.check("guest_1")).isEqualTo(new UsernameCheck.Availability.Free(false));
  }

  @Test
  void aSignUpAfterAFailedRebuildDoesNotReachTheAbandonedFilter() {
    ScanHookStore store = new ScanHookStore(names(NAMES));
    UsernameCheck check = new UsernameCheck(store);
    store.duringScan =
        () -> {
          throw new IllegalStateException("could not scan customers.customer_ref for the filter");
        };
    assertThatThrownBy(() -> check.rebuild(SIZING, SEED)).isInstanceOf(IllegalStateException.class);

    check.recordSignUp("okafor_77");

    assertThat(check.installedFilter()).isEmpty();
  }

  @Test
  void aStoreFailureOnACheckIsNeverAnsweredFree() {
    ScanHookStore store = new ScanHookStore(names(NAMES));
    store.isDown = true;
    UsernameCheck check = new UsernameCheck(store);

    assertThatThrownBy(() -> check.check("guest_1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("could not read");
  }

  private static List<String> names(int count) {
    List<String> names = new ArrayList<>(count);
    for (int index = 1; index <= count; index++) {
      names.add("fan_" + index);
    }
    return names;
  }

  /** A store over a fixed set that counts its reads. Confined to one test thread. */
  private static final class CountingStore implements UsernameStore {
    private final Set<String> usernames;
    private long reads;

    CountingStore(List<String> usernames) {
      this.usernames = Set.copyOf(usernames);
    }

    @Override
    public boolean isTaken(String username) {
      reads++;
      return usernames.contains(username);
    }

    @Override
    public long forEachUsername(Consumer<String> sink) {
      usernames.forEach(sink);
      return usernames.size();
    }
  }

  /**
   * A store whose scan runs a hook halfway, to commit a name or to fail like a dropped connection.
   */
  private static final class ScanHookStore implements UsernameStore {
    private final List<String> snapshot;
    private final Set<String> committed = ConcurrentHashMap.newKeySet();
    private Runnable duringScan = () -> {};
    private boolean isDown;

    ScanHookStore(List<String> snapshot) {
      this.snapshot = List.copyOf(snapshot);
      committed.addAll(snapshot);
    }

    @Override
    public boolean isTaken(String username) {
      if (isDown) {
        throw new IllegalStateException("could not read customers.customer_ref");
      }
      return committed.contains(username);
    }

    @Override
    public long forEachUsername(Consumer<String> sink) {
      int half = snapshot.size() / 2;
      snapshot.subList(0, half).forEach(sink);
      duringScan.run();
      // The scan's snapshot was taken before the hook: it never sees a name committed during it.
      snapshot.subList(half, snapshot.size()).forEach(sink);
      return snapshot.size();
    }
  }
}
