package io.plainround.seatwave.filters.study;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Duration;
import java.util.Objects;
import javax.sql.DataSource;

/**
 * Replaces Module 13's {@code customers} table in the lab database with the names {@code fan_1} to
 * {@code fan_N}, then builds its two indexes and vacuums it. Destructive by design: only {@link
 * LoadUsernamesCli} calls it, after a dry run and a confirmation.
 *
 * <p>The table is created, filled and indexed in one transaction. With {@code wal_level=minimal}
 * (compose.yaml) PostgreSQL then writes no log records for the rows or the index builds, which
 * keeps the load off Docker's shared disk. Not thread-safe; one load at a time.
 */
public final class UsernameLoader {

  /** The most names one load accepts: 200,000,000 is about a fifth of the module's billion. */
  public static final long MOST_NAMES = 200_000_000L;

  // Batches of 5,000,000 rows keep every statement well inside the driver's socket timeout.
  private static final long ROWS_PER_BATCH = 5_000_000L;
  // Enough memory for the index sort to run in a few large runs rather than many small ones.
  private static final String MAINTENANCE_MEMORY = "SET LOCAL maintenance_work_mem = '256MB'";

  private static final String CREATE_TABLE =
      """
      CREATE TABLE customers (
        customer_id BIGINT GENERATED ALWAYS AS IDENTITY,
        customer_ref VARCHAR(64) NOT NULL,
        created_at TIMESTAMPTZ NOT NULL,
        CONSTRAINT customer_ref_format CHECK (customer_ref ~ '^[A-Za-z0-9_-]{1,64}$')
      )\
      """;
  // Rows are made inside the database: no name travels over the connection.
  private static final String INSERT_BATCH =
      """
      INSERT INTO customers (customer_id, customer_ref, created_at) OVERRIDING SYSTEM VALUE
      SELECT g, 'fan_' || g, TIMESTAMPTZ '2026-01-01 00:00:00+00' + g * INTERVAL '1 second'
      FROM generate_series(?::bigint, ?::bigint) AS g\
      """;
  private static final String PRIMARY_KEY =
      "ALTER TABLE customers ADD CONSTRAINT customers_pkey PRIMARY KEY (customer_id)";
  // Built after the rows, in one sorted pass, so its leaves are filled to the default 90 percent,
  // the fill Module 14's size estimate assumes.
  private static final String UNIQUE_INDEX =
      "CREATE UNIQUE INDEX one_customer_per_ref ON customers (customer_ref)";
  private static final String UNIQUE_CONSTRAINT =
      "ALTER TABLE customers ADD CONSTRAINT one_customer_per_ref UNIQUE USING INDEX"
          + " one_customer_per_ref";
  private static final String RESTART_IDENTITY =
      "ALTER TABLE customers ALTER COLUMN customer_id RESTART WITH ";
  // Sets the visibility map, so a check by name is an index-only scan that never reads the table.
  private static final String VACUUM = "VACUUM (ANALYZE) customers";

  private final DataSource dataSource;

  /** Creates a loader on {@code dataSource}, which is shared and never closed here. */
  public UsernameLoader(DataSource dataSource) {
    this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
  }

  /** How long each step of a load took. */
  public record LoadReport(long names, Duration rows, Duration indexes, Duration vacuum) {}

  /**
   * Drops {@code customers}, loads {@code fan_1} to {@code fan_<names>}, indexes and vacuums it.
   *
   * @throws IllegalArgumentException if {@code names} is outside 1 to {@link #MOST_NAMES}
   * @throws IllegalStateException if the database refuses a step; the transaction is rolled back
   */
  public LoadReport load(long names) {
    if (names < 1 || names > MOST_NAMES) {
      throw new IllegalArgumentException(
          "names must be from 1 to " + MOST_NAMES + ", got " + names);
    }
    try (Connection connection = dataSource.getConnection()) {
      connection.setAutoCommit(false);
      long started = System.nanoTime();
      try (Statement statement = connection.createStatement()) {
        statement.execute(MAINTENANCE_MEMORY);
        statement.execute("DROP TABLE IF EXISTS customers");
        statement.execute(CREATE_TABLE);
      }
      insertRows(connection, names);
      long rowsDone = System.nanoTime();
      try (Statement statement = connection.createStatement()) {
        statement.execute(PRIMARY_KEY);
        statement.execute(UNIQUE_INDEX);
        statement.execute(UNIQUE_CONSTRAINT);
        // The next real sign-up gets the next identifier, as on Module 13's table.
        statement.execute(RESTART_IDENTITY + Math.addExact(names, 1));
      }
      connection.commit();
      long indexesDone = System.nanoTime();
      connection.setAutoCommit(true); // VACUUM cannot run inside a transaction
      try (Statement statement = connection.createStatement()) {
        statement.execute(VACUUM);
      }
      long vacuumDone = System.nanoTime();
      return new LoadReport(
          names,
          Duration.ofNanos(rowsDone - started),
          Duration.ofNanos(indexesDone - rowsDone),
          Duration.ofNanos(vacuumDone - indexesDone));
    } catch (SQLException failure) {
      throw new IllegalStateException("could not load " + names + " names into customers", failure);
    }
  }

  private static void insertRows(Connection connection, long names) throws SQLException {
    try (PreparedStatement insert = connection.prepareStatement(INSERT_BATCH)) {
      for (long first = 1; first <= names; first += ROWS_PER_BATCH) {
        insert.setLong(1, first);
        insert.setLong(2, Math.min(names, first + ROWS_PER_BATCH - 1));
        insert.executeUpdate();
      }
    }
  }
}
