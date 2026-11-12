package io.plainround.seatwave.filters.study;

import com.zaxxer.hikari.HikariDataSource;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * Loads {@code fan_1} to {@code fan_N} into the lab database's {@code customers} table, replacing
 * whatever it held. A dry run unless {@code --run} is given; {@code --run} asks for confirmation on
 * the terminal unless {@code --yes} is also given.
 *
 * <p>Usage: {@code ./gradlew loadUsernames --args="<names> [--run] [--yes]"}.
 */
public final class LoadUsernamesCli {

  private static final Path SETTINGS = Path.of("config/lab.properties");
  private static final double NANOS_PER_SECOND = 1e9;
  private static final int EXIT_USAGE = 2;

  private LoadUsernamesCli() {}

  /** Parses the arguments, prints the plan, and loads only on {@code --run}. */
  public static void main(String[] arguments) {
    List<String> words = List.of(arguments);
    if (words.isEmpty()
        || !words.subList(1, words.size()).stream().allMatch(LoadUsernamesCli::isOption)) {
      usage("give the number of names first, then --run and --yes if wanted");
      return;
    }
    long names;
    try {
      names = Long.parseLong(words.getFirst());
    } catch (NumberFormatException notANumber) {
      usage("the number of names must be a whole number, was '" + words.getFirst() + "'");
      return;
    }
    if (names < 1 || names > UsernameLoader.MOST_NAMES) {
      usage(
          "the number of names must be from 1 to " + UsernameLoader.MOST_NAMES + ", was " + names);
      return;
    }
    LabSettings settings = LabSettings.load(SETTINGS);
    System.out.printf(
        Locale.ROOT,
        "plan: drop customers in %s and load fan_1 to fan_%d, then index and vacuum it%n",
        settings.jdbcUrl(),
        names);
    if (!words.contains("--run")) {
      System.out.println("dry run: nothing changed. Add --run to do it.");
      return;
    }
    if (!words.contains("--yes") && !confirmed()) {
      System.out.println("not confirmed; nothing changed");
      System.exit(1);
    }
    try (HikariDataSource pool = LabDatabase.openPool(settings)) {
      UsernameLoader.LoadReport report = new UsernameLoader(pool).load(names);
      System.out.printf(
          Locale.ROOT,
          "%s loaded %,d names: rows %.1f s, indexes %.1f s, vacuum %.1f s%n",
          Instant.now(),
          report.names(),
          report.rows().toNanos() / NANOS_PER_SECOND,
          report.indexes().toNanos() / NANOS_PER_SECOND,
          report.vacuum().toNanos() / NANOS_PER_SECOND);
    }
  }

  private static boolean isOption(String word) {
    return word.equals("--run") || word.equals("--yes");
  }

  private static boolean confirmed() {
    System.out.print("Replace the customers table? [y/N] ");
    try {
      String answer =
          new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8)).readLine();
      return "y".equals(answer);
    } catch (IOException readFailure) {
      throw new UncheckedIOException("could not read the confirmation", readFailure);
    }
  }

  private static void usage(String problem) {
    System.err.println(problem);
    System.err.println("usage: ./gradlew loadUsernames --args=\"<names> [--run] [--yes]\"");
    System.exit(EXIT_USAGE);
  }
}
