package io.plainround.seatwave.filters.study;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.zaxxer.hikari.HikariDataSource;
import io.plainround.seatwave.filters.bloom.BloomFilter;
import io.plainround.seatwave.filters.bloom.BloomSizing;
import io.plainround.seatwave.filters.signup.JdbcUsernameStore;
import io.plainround.seatwave.filters.signup.UsernameCheck;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.LongStream;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * The username store, the loader and the filter's rebuild on a real PostgreSQL 18.6: the fresh
 * database that scripts/stack.sh test starts. Loads 20,000 names once for all tests.
 */
@Tag("stack")
class UsernameStoreStackTest {

  private static final Path SETTINGS = Path.of("config/lab.properties");
  private static final long NAMES = 20_000;
  private static final long SEED = 0x5ea7_3a7eL;

  private static HikariDataSource pool;
  private static JdbcUsernameStore store;

  @BeforeAll
  static void loadTwentyThousandNames() {
    pool = LabDatabase.openPool(LabSettings.load(SETTINGS));
    new UsernameLoader(pool).load(NAMES);
    store = new JdbcUsernameStore(pool);
  }

  @AfterAll
  static void closeThePool() {
    pool.close();
  }

  @Test
  void findsALoadedNameAndNotANameNobodyHas() {
    assertThat(store.isTaken("fan_1")).isTrue();
    assertThat(store.isTaken("fan_" + NAMES)).isTrue();
    assertThat(store.isTaken("fan_1x")).isFalse();
  }

  @Test
  void scansEveryNameOnceInTheIndexsOrder() {
    List<String> scanned = new ArrayList<>();

    long passed = store.forEachUsername(scanned::add);

    assertThat(passed).isEqualTo(NAMES);
    assertThat(scanned).doesNotHaveDuplicates().contains("fan_1", "fan_" + NAMES);
    assertThat(scanned).isEqualTo(databaseOrder());
  }

  @Test
  void aFilterRebuiltFromTheTableNeverCallsALoadedNameFree() {
    UsernameCheck check = new UsernameCheck(store);
    check.rebuild(BloomSizing.forTarget(NAMES, 0.01), SEED);
    BloomFilter filter = check.installedFilter().orElseThrow();

    long falseNegatives =
        LongStream.rangeClosed(1, NAMES)
            .filter(index -> !filter.mightContain("fan_" + index))
            .count();

    assertThat(falseNegatives).isZero();
    assertThat(filter.addCalls()).isEqualTo(NAMES);
  }

  @Test
  void theUniqueIndexRefusesADuplicateWhateverTheFilterSaid() throws SQLException {
    try (Connection connection = pool.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                "INSERT INTO customers (customer_ref, created_at) VALUES (?, now())")) {
      insert.setString(1, "fan_1");

      assertThatThrownBy(insert::executeUpdate)
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("one_customer_per_ref");
    }
  }

  @Test
  void theFormatRuleRefusesANameWithASpaceInTheDatabaseToo() throws SQLException {
    try (Connection connection = pool.getConnection();
        PreparedStatement insert =
            connection.prepareStatement(
                "INSERT INTO customers (customer_ref, created_at) VALUES (?, now())")) {
      insert.setString(1, "fan 1");

      assertThatThrownBy(insert::executeUpdate)
          .isInstanceOf(SQLException.class)
          .hasMessageContaining("customer_ref_format");
    }
  }

  @Test
  void aStoreWhoseDatabaseIsGoneFailsLoudlyAndTheOldFilterStays() {
    HikariDataSource closed = LabDatabase.openPool(LabSettings.load(SETTINGS));
    UsernameCheck check = new UsernameCheck(new JdbcUsernameStore(closed));
    check.rebuild(BloomSizing.forTarget(NAMES, 0.01), SEED);
    BloomFilter old = check.installedFilter().orElseThrow();
    closed.close();

    assertThatThrownBy(() -> check.rebuild(BloomSizing.forTarget(NAMES, 0.01), SEED))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("could not scan customers.customer_ref");
    assertThatThrownBy(() -> new JdbcUsernameStore(closed).isTaken("fan_1"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("could not read customers.customer_ref");
    assertThat(check.installedFilter()).containsSame(old);
  }

  private static List<String> databaseOrder() {
    List<String> ordered = new ArrayList<>();
    try (Connection connection = pool.getConnection();
        PreparedStatement select =
            connection.prepareStatement(
                "SELECT customer_ref FROM customers ORDER BY customer_ref");
        ResultSet rows = select.executeQuery()) {
      while (rows.next()) {
        ordered.add(rows.getString(1));
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("could not read customers in order", failure);
    }
    return ordered;
  }
}
