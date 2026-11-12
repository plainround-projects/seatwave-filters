package io.plainround.seatwave.filters.signup;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.function.Consumer;
import javax.sql.DataSource;

/**
 * {@link UsernameStore} on Module 13's {@code customers} table. Thread-safe if the {@link
 * DataSource} is (a pool such as Module 10's is).
 */
public final class JdbcUsernameStore implements UsernameStore {

  // Served by the unique index one_customer_per_ref: one B-tree descent (Module 14, R5).
  private static final String IS_TAKEN_SQL = "SELECT 1 FROM customers WHERE customer_ref = ?";
  // In key order of the index, so a rebuild reads leaves in sequence, not the heap at random.
  private static final String ALL_USERNAMES_SQL =
      "SELECT customer_ref FROM customers ORDER BY customer_ref";
  // PostgreSQL's driver streams rows in batches of this size only with autocommit off; otherwise
  // it loads every row into memory, which for a billion names would exhaust the heap.
  private static final int REBUILD_FETCH_SIZE = 10_000;

  private final DataSource dataSource;

  /** Creates a store on {@code dataSource}, which is shared and never closed here. */
  public JdbcUsernameStore(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  @Override
  public boolean isTaken(String username) {
    Objects.requireNonNull(username, "username");
    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement = connection.prepareStatement(IS_TAKEN_SQL)) {
      statement.setString(1, username);
      try (ResultSet rows = statement.executeQuery()) {
        return rows.next();
      }
    } catch (SQLException failure) {
      throw new IllegalStateException("could not read customers.customer_ref", failure);
    }
  }

  @Override
  public long forEachUsername(Consumer<String> sink) {
    Objects.requireNonNull(sink, "sink");
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      // Read-only and one snapshot: the scan sees the table as of its start (Module 13's MVCC);
      // sign-ups after that moment reach the filter through UsernameCheck.recordSignUp.
      connection.setReadOnly(true);
      try (PreparedStatement statement = connection.prepareStatement(ALL_USERNAMES_SQL)) {
        statement.setFetchSize(REBUILD_FETCH_SIZE);
        long passed = 0;
        try (ResultSet rows = statement.executeQuery()) {
          while (rows.next()) {
            sink.accept(rows.getString(1));
            passed++;
          }
        }
        connection.commit();
        return passed;
      }
    } catch (SQLException failure) {
      throw new IllegalStateException(
          "could not scan customers.customer_ref for the filter", failure);
    }
  }
}
