package io.plainround.seatwave.filters.study;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.util.Objects;

/** Opens the one connection pool a lab program shares for its whole run. */
public final class LabDatabase {

  private LabDatabase() {}

  /**
   * A pool on the lab database. The caller closes it once, when the program ends.
   *
   * @throws java.io.UncheckedIOException if the password file cannot be read
   */
  public static HikariDataSource openPool(LabSettings settings) {
    Objects.requireNonNull(settings, "settings");
    HikariConfig config = new HikariConfig();
    config.setJdbcUrl(settings.jdbcUrl());
    config.setUsername(settings.user());
    config.setPassword(settings.password());
    config.setMaximumPoolSize(settings.poolSize());
    // Every connection is opened at start-up, so no measured check pays for opening one.
    config.setMinimumIdle(settings.poolSize());
    config.setConnectionTimeout(settings.connectionTimeoutMillis());
    config.addDataSourceProperty(
        "socketTimeout", Integer.toString(settings.socketTimeoutSeconds()));
    config.setPoolName("seatwave-filters-lab");
    return new HikariDataSource(config);
  }
}
