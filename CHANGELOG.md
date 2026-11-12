# Changelog

All notable changes to this project are written here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [1.0.0] - 2026-11-12

The first build of Module 21's code seed (19 Java files), run for the first time with Gradle and
against PostgreSQL 18.6.

### Added

- The Gradle wrapper (9.9.1, both checksums compared), the version catalog, lockfiles, Spotless with
  google-java-format, JaCoCo with a line floor of 0.80, one `JavaExec` task per program, README,
  ARCHITECTURE, this changelog and the license.
- `compose.yaml` (PostgreSQL 18.6 on 127.0.0.1:22100, Module 13's pin) and `scripts/stack.sh`
  (`up`, `down`, `test`, `lab2`, `study`, `status`; a dry run unless `--run`).
- `study`: `LabSettings` (every problem reported together, loopback only, the password read from
  `.secrets/` when a program connects), `LabDatabase`, `UsernameLoader` and `LoadUsernamesCli` (fill
  `customers` with `fan_1` to `fan_N` in one transaction, then index and vacuum), and
  `UsernameStudyCli`, the reduced-scale study that replaces the module's estimates.
- `ReadsAvoidedCli --postgres`: Lab 2 through `JdbcUsernameStore`, with the database time of the
  reads with and without the filter.
- `HmacCostCli`: the cost of one HMAC-SHA-256 against the filter's hash, on the machine that runs it.
- Tests: `concurrentAddsFromEightThreadsLoseNoName` (it failed on every one of 10 runs against the
  unguarded update of `race-demo.jsh`), `BloomSizingTest`, `SeededHashTest`, `UsernameCheckTest`
  (format, counters, the rebuild order, a store failure during a rebuild, a store failure on a
  check), `CountingBloomFilterTest`, `CountMinSketchTest`, more `HyperLogLogTest` cases,
  `LabSettingsTest`, and the stack test `UsernameStoreStackTest` on PostgreSQL.

### Fixed

- `UsernameCheck.recordSignUp` read the installed filter before the filter being built. A rebuild
  that installed its new filter and cleared the one being built between those two reads left the
  name in the old filter only, while the new one, whose scan ran before the commit, would call the
  taken name free. It now reads the filter being built first. No test can force that interleaving
  without a hook in the class, so the fix rests on the argument in the comment beside it.
- `HyperLogLog.merge`'s Javadoc said the seed could not be checked; the code checks it.
- `scripts/stack.sh`, in the shape of Module 14's: `docker info --format` exits 0 when Docker 24.0.6's
  engine does not answer, so the check also requires the version it prints; `up` and `down` return
  on a failed `docker compose`; and the clean-up check fails when a listing fails, instead of
  reporting "nothing left" from empty output. Found when the engine stopped answering during the
  first stack run.
