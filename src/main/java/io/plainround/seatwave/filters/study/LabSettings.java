package io.plainround.seatwave.filters.study;

import java.io.IOException;
import java.io.Reader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

/**
 * The lab database's settings, read once at start-up from {@code config/lab.properties}. Every key
 * is required and range-checked, and all problems are reported together in one message, so a reader
 * fixes the file once. The password is read from the file the settings name, only when a program
 * connects, so a dry run needs no password. Immutable and thread-safe.
 *
 * @param jdbcUrl the lab database, on the loopback address only
 * @param user the role the programs log in as (Module 13's seatwave_owner)
 * @param passwordFile the file that holds the password, read by {@link #password()}
 * @param poolSize connections in the pool
 * @param connectionTimeoutMillis how long a check waits for a pooled connection
 * @param socketTimeoutSeconds how long the driver waits for one answer from the database
 * @param studyChecks checks per measured phase of the study
 * @param studyProbes never-added names probed against each filter for its rate
 */
public record LabSettings(
    String jdbcUrl,
    String user,
    Path passwordFile,
    int poolSize,
    int connectionTimeoutMillis,
    int socketTimeoutSeconds,
    int studyChecks,
    int studyProbes) {

  private static final String LOOPBACK_URL_PREFIX = "jdbc:postgresql://127.0.0.1:";
  // compose.yaml allows PostgreSQL's default 100 connections; the lab needs a handful.
  private static final int MOST_CONNECTIONS = 16;
  // HikariCP refuses a connection timeout below 250 ms.
  private static final int LEAST_TIMEOUT_MILLIS = 250;
  private static final int MOST_TIMEOUT_MILLIS = 60_000;
  private static final int MOST_SOCKET_SECONDS = 600;
  private static final int MOST_CHECKS = 10_000_000;

  /**
   * Reads the password from its file, at the moment a program connects.
   *
   * @throws UncheckedIOException if the file cannot be read, naming the file and how to make it
   */
  public String password() {
    try {
      return Files.readString(passwordFile, StandardCharsets.UTF_8).strip();
    } catch (IOException readFailure) {
      throw new UncheckedIOException(
          "cannot read the password file "
              + passwordFile.toAbsolutePath()
              + "; scripts/stack.sh up --run writes it",
          readFailure);
    }
  }

  /**
   * Reads and validates the settings file.
   *
   * @throws IllegalArgumentException listing every missing or out-of-range key
   * @throws UncheckedIOException if the settings file cannot be read
   */
  public static LabSettings load(Path settingsFile) {
    Properties properties = new Properties();
    try (Reader reader = Files.newBufferedReader(settingsFile, StandardCharsets.UTF_8)) {
      properties.load(reader);
    } catch (IOException readFailure) {
      throw new UncheckedIOException(
          "cannot read settings file " + settingsFile.toAbsolutePath(), readFailure);
    }
    List<String> problems = new ArrayList<>();
    String jdbcUrl = text(properties, "db.url", problems);
    if (!jdbcUrl.isEmpty() && !jdbcUrl.startsWith(LOOPBACK_URL_PREFIX)) {
      problems.add("db.url must point at the lab database on 127.0.0.1, was " + jdbcUrl);
    }
    String user = text(properties, "db.user", problems);
    String passwordFile = text(properties, "db.passwordFile", problems);
    int poolSize = number(properties, "db.poolSize", 1, MOST_CONNECTIONS, problems);
    int connectionTimeout =
        number(
            properties,
            "db.connectionTimeoutMillis",
            LEAST_TIMEOUT_MILLIS,
            MOST_TIMEOUT_MILLIS,
            problems);
    int socketTimeout =
        number(properties, "db.socketTimeoutSeconds", 1, MOST_SOCKET_SECONDS, problems);
    int checks = number(properties, "study.checks", 1, MOST_CHECKS, problems);
    int probes = number(properties, "study.probes", 1, MOST_CHECKS, problems);
    if (!problems.isEmpty()) {
      throw new IllegalArgumentException(
          "settings file "
              + settingsFile.toAbsolutePath()
              + " has problems: "
              + String.join("; ", problems));
    }
    return new LabSettings(
        jdbcUrl,
        user,
        Path.of(passwordFile),
        poolSize,
        connectionTimeout,
        socketTimeout,
        checks,
        probes);
  }

  private static String text(Properties properties, String key, List<String> problems) {
    String value = properties.getProperty(key, "").strip();
    if (value.isEmpty()) {
      problems.add(key + " is missing");
    }
    return value;
  }

  private static int number(
      Properties properties, String key, int smallest, int largest, List<String> problems) {
    String value = text(properties, key, problems);
    if (value.isEmpty()) {
      return smallest;
    }
    try {
      int parsed = Integer.parseInt(value);
      if (parsed < smallest || parsed > largest) {
        problems.add(key + " must be from " + smallest + " to " + largest + ", was " + parsed);
      }
      return parsed;
    } catch (NumberFormatException notANumber) {
      problems.add(key + " must be a whole number, was '" + value + "'");
      return smallest;
    }
  }
}
