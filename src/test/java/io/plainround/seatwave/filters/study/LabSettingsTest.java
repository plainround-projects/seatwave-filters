package io.plainround.seatwave.filters.study;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class LabSettingsTest {

  private static final String GOOD =
      """
      db.url=jdbc:postgresql://127.0.0.1:22100/seatwave
      db.user=seatwave_owner
      db.passwordFile=.secrets/owner.password
      db.poolSize=2
      db.connectionTimeoutMillis=5000
      db.socketTimeoutSeconds=300
      study.checks=100000
      study.probes=1000000
      """;

  @TempDir Path folder;

  @Test
  void readsTheProjectsOwnSettingsFile() {
    LabSettings settings = LabSettings.load(Path.of("config/lab.properties"));

    assertThat(settings.jdbcUrl()).isEqualTo("jdbc:postgresql://127.0.0.1:22100/seatwave");
    assertThat(settings.studyChecks()).isEqualTo(100_000);
  }

  @Test
  void reportsEveryProblemTogether() throws IOException {
    Path file =
        write(
            GOOD.replace("db.user=seatwave_owner\n", "")
                .replace("db.poolSize=2", "db.poolSize=0")
                .replace("study.checks=100000", "study.checks=many"));

    assertThatThrownBy(() -> LabSettings.load(file))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("db.user is missing")
        .hasMessageContaining("db.poolSize must be from 1 to 16, was 0")
        .hasMessageContaining("study.checks must be a whole number, was 'many'");
  }

  @Test
  void refusesADatabaseThatIsNotOnTheLoopbackAddress() throws IOException {
    Path file = write(GOOD.replace("127.0.0.1", "10.0.0.5"));

    assertThatThrownBy(() -> LabSettings.load(file))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must point at the lab database on 127.0.0.1");
  }

  @Test
  void namesThePasswordFileAndHowToMakeItWhenItIsMissing() throws IOException {
    LabSettings settings =
        LabSettings.load(write(GOOD.replace(".secrets/owner.password", folder + "/none.password")));

    assertThatThrownBy(settings::password)
        .isInstanceOf(UncheckedIOException.class)
        .hasMessageContaining("none.password")
        .hasMessageContaining("scripts/stack.sh up --run");
  }

  @Test
  void readsThePasswordWithoutItsLineEnd() throws IOException {
    Path passwordFile = folder.resolve("owner.password");
    Files.writeString(passwordFile, "throwaway\n", StandardCharsets.UTF_8);
    LabSettings settings =
        LabSettings.load(write(GOOD.replace(".secrets/owner.password", passwordFile.toString())));

    assertThat(settings.password()).isEqualTo("throwaway");
  }

  private Path write(String text) throws IOException {
    Path file = folder.resolve("lab.properties");
    Files.writeString(file, text, StandardCharsets.UTF_8);
    return file;
  }
}
