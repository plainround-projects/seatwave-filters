package io.plainround.seatwave.filters.signup;

import java.util.function.Consumer;

/**
 * The system of record for usernames: Module 13's {@code customers.customer_ref}, kept unique by
 * {@code one_customer_per_ref}. The filter is only a shortcut in front of it.
 *
 * <p>Two implementations: {@link JdbcUsernameStore} for PostgreSQL, and an in-memory store in
 * {@link UsernameCheckCli} that counts reads, so the demonstration runs with the JDK alone.
 */
public interface UsernameStore {

  /**
   * One read of the system of record.
   *
   * @return true if a customer already has {@code username} as its reference
   * @throws IllegalStateException if the store cannot answer; the caller must not guess
   */
  boolean isTaken(String username);

  /**
   * Passes every stored username to {@code sink}, for building a filter at start-up.
   *
   * @return the number of usernames passed
   * @throws IllegalStateException if the store cannot be read to the end
   */
  long forEachUsername(Consumer<String> sink);
}
