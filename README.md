# seatwave-filters

SeatWave's username Bloom filter and its visitor and seat counters, and a study of the username check on PostgreSQL 18.6. This is the project of Module 21, "Bloom filters", of the course System Design Fundamentals: a Bloom filter sized from an item count and a target false-positive rate, a username check that answers "free" from memory when the filter proves a name was never taken and asks the database only on "maybe", a counting filter for deletes, HyperLogLog for unique visitors and a count-min sketch for the most-viewed seats.

## Prerequisites

- JDK 25 (the Gradle toolchain finds it; built with Eclipse Temurin 25.0.2, where 25.0.5+8 is the current release).
- For Lab 2 against PostgreSQL, the study and the stack test only: Docker with Compose (run with Docker 24.0.6 and Compose v2.23.0) and `openssl`. `./gradlew check` needs no Docker.

## Dependencies (each checked on Maven Central, Docker Hub or the Gradle site on 12 November 2026; OSV listed no vulnerability)

| What | Coordinates | Version | License | Why the JDK alone is not enough |
|---|---|---|---|---|
| Gradle wrapper | `gradle-9.9.1-bin.zip`, both checksums compared | 9.9.1 | Apache-2.0 | one build tool version for every reader |
| HikariCP | `com.zaxxer:HikariCP` | 7.1.0 | Apache-2.0 | the JDK has no connection pool; a connection per check would measure the connection |
| PostgreSQL JDBC driver | `org.postgresql:postgresql` (runtime) | 42.7.14 | BSD-2-Clause | the JDK has the JDBC interfaces, not the driver |
| Logback | `ch.qos.logback:logback-classic` (runtime) | 1.6.6 | EPL-1.0 or LGPL-2.1 | HikariCP logs through SLF4J and needs a backend |
| JUnit | `org.junit:junit-bom` | 6.1.3 | EPL-2.0 | the test runner |
| AssertJ | `org.assertj:assertj-core` | 3.27.7 | Apache-2.0 | readable assertions |
| Spotless, google-java-format | `com.diffplug.spotless`, `com.google.googlejavaformat:google-java-format` | 8.11.0, 2.0.0 | Apache-2.0 | the formatter |
| JaCoCo | `org.jacoco` | 0.8.16 | EPL-2.0 | line coverage, floor 0.80 |
| PostgreSQL image | `postgres:18.6@sha256:e3d24f6906d0760bf4da0dae872415391ededefad23fd471c47dc53b0ddf3c42` | 18.6 | PostgreSQL License | the system of record for `customers` |

## Commands and their real output

Build and test (no Docker): `./gradlew clean check`

```

> Task :jacocoTestCoverageVerification
> Task :spotlessJava
> Task :spotlessJavaCheck
> Task :spotlessCheck
> Task :check
> Task :jacocoTestReport

BUILD SUCCESSFUL in 4s
9 actionable tasks: 9 executed
Consider enabling configuration cache to speed up this build: https://docs.gradle.org/9.9.1/userguide/configuration_cache_enabling.html
exit status 0
```

The programs, one task each (outputs in the module, Section 8 Step 9 and Section 11):

| Task | Program | What it prints |
|---|---|---|
| `./gradlew -q falsePositive` | `FalsePositiveCli` | the sizing table, measured rates, an overfilled filter |
| `./gradlew -q deleteDemo` | `DeleteDemoCli` | what clearing bits does; the counting filter |
| `./gradlew -q hmacCost` | `HmacCostCli` | nanoseconds per hash, the filter's against HMAC-SHA-256 |
| `./gradlew -q usernameCheck` | `UsernameCheckCli` | the sign-up mix with and without the filter; a stale snapshot |
| `./gradlew -q visitorCount` | `VisitorCountCli` | HyperLogLog per event, error against registers, merge |
| `./gradlew -q topSeats` | `TopSeatsCli` | the most-viewed seats from a count-min sketch |
| `./gradlew -q tenMillionNames` | `TenMillionNamesCli` | Lab 1 |
| `./gradlew -q readsAvoided` | `ReadsAvoidedCli` | Lab 2 in memory; `--args=--postgres` through `JdbcUsernameStore` |
| `./gradlew -q uniqueVisitorsLab` | `UniqueVisitorsLabCli` | Lab 3 |
| `./gradlew -q loadUsernames --args="<names> --run --yes"` | `LoadUsernamesCli` | replace `customers` with `fan_1` to `fan_N` (a dry run without `--run`) |
| `./gradlew -q usernameStudy` | `UsernameStudyCli` | the study on PostgreSQL |

For example, `./gradlew -q falsePositive`:

```
$ ./gradlew -q falsePositive
== sizing: 1,000,000,000 usernames
target     bits/item  k   memory MB   theory at n
0.1             4.79  3        599.1  0.10071
0.01            9.59  7       1198.1  0.01004
0.001          14.38  10      1797.2  0.00100
1.0E-4         19.17  13      2396.3  0.00010
== sizing: 10,000,000 usernames (Lab 1)
0.01            9.59  7         12.0  0.01004
== measured: 1,000,000 inserted, 1,000,000 never inserted
design 1,000,000 at 0.01 (m=9,585,088, k=7, 1.20 MB) | inserted 1,000,000 | fill 0.518 | false negatives 0 | false positives 9,998 of 1,000,000 = 0.01000 | theory 0.01004 | 91 ns/add | 12.3 M lookups/s
design 1,000,000 at 0.001 (m=14,377,600, k=10, 1.80 MB) | inserted 1,000,000 | fill 0.501 | false negatives 0 | false positives 999 of 1,000,000 = 0.00100 | theory 0.00100 | 96 ns/add | 12.4 M lookups/s
== overfilled: sized for 1,000,000 at 0.01, filled past it
design 1,000,000 at 0.01 (m=9,585,088, k=7, 1.20 MB) | inserted 1,000,000 | fill 0.518 | false negatives 0 | false positives 9,998 of 1,000,000 = 0.01000 | theory 0.01004 | 55 ns/add | 13.9 M lookups/s
design 1,000,000 at 0.01 (m=9,585,088, k=7, 1.20 MB) | inserted 1,500,000 | fill 0.666 | false negatives 0 | false positives 57,938 of 1,000,000 = 0.05794 | theory 0.05788 | 63 ns/add | 12.7 M lookups/s
design 1,000,000 at 0.01 (m=9,585,088, k=7, 1.20 MB) | inserted 2,000,000 | fill 0.768 | false negatives 0 | false positives 157,551 of 1,000,000 = 0.15755 | theory 0.15745 | 56 ns/add | 12.6 M lookups/s
design 1,000,000 at 0.01 (m=9,585,088, k=7, 1.20 MB) | inserted 3,000,000 | fill 0.888 | false negatives 0 | false positives 437,005 of 1,000,000 = 0.43701 | theory 0.43604 | 56 ns/add | 13.4 M lookups/s
exit status 0
```

## The Compose path

`scripts/stack.sh <up|down|test|lab2|study|status> [--run] [--yes]` starts PostgreSQL 18.6 on 127.0.0.1:22100 under the Compose project name `seatwave-filters`, with throwaway passwords generated into the ignored `.secrets/` folder, and always brings it down with `down -v`. Without `--run` it is a dry run:

```
$ scripts/stack.sh study
plan: study the Compose project seatwave-filters
  replace customers with fan_1 to fan_50000000, then ./gradlew usernameStudy
dry run: nothing changed. Add --run to do it.
exit status 0
```

`scripts/stack.sh test --run --yes` runs the stack test (`UsernameStoreStackTest`) between `up` and `down`:

```
UsernameStoreStackTest > findsALoadedNameAndNotANameNobodyHas() PASSED
UsernameStoreStackTest > aStoreWhoseDatabaseIsGoneFailsLoudlyAndTheOldFilterStays() PASSED
UsernameStoreStackTest > scansEveryNameOnceInTheIndexsOrder() PASSED
UsernameStoreStackTest > aFilterRebuiltFromTheTableNeverCallsALoadedNameFree() PASSED
UsernameStoreStackTest > theUniqueIndexRefusesADuplicateWhateverTheFilterSaid() PASSED
UsernameStoreStackTest > theFormatRuleRefusesANameWithASpaceInTheDatabaseToo() PASSED
BUILD SUCCESSFUL in 1s
nothing of seatwave-filters is left
```

`scripts/stack.sh up --run --yes`, then `scripts/stack.sh lab2 --run --yes` and `STUDY_NAMES=50000000 scripts/stack.sh study --run --yes`, then `scripts/stack.sh down --run --yes` produce the outputs of the module's Section 11 (Lab 2) and Section 8 (Step 10). The study's run of 12 November 2026, on an Apple M2 Max laptop with PostgreSQL in Docker Desktop's virtual machine (4 CPUs, 2 GB, 384 MB of shared buffers), every database read crossing that virtual machine's boundary:

```
names: 50,000,000 (fan_1 to fan_50000000)
one_customer_per_ref: 1,577,140,224 bytes, 1577.1 MB; 191,571 leaf pages, 950 internal; levels 3 above the leaves; leaves 89.99 percent full
per name: 31.54 bytes of index, 261.0 names per leaf; table customers 3011.8 MB
shared_buffers: 384MB, 0.255 of the index
warm-up: 100,000 random lookups in 29.3 s
no filter checks 100,000 | database reads 100,000 | false positives 0 | live rate 0.0000 | 30.0 s in all, 0.300 ms a check
no filter database read p50 0.286 ms, p99 0.498 ms | answered from filter p50 none (no filter) | index pages per read: 3.275 from the cache, 0.755 from outside it
rebuild, design 1.2 n: 50,000,000 names scanned and added in 22.2 s, 443 ms per million names | m=575,103,552, k=7, 71.9 MB
rebuild, design n: 50,000,000 names scanned and added in 22.0 s, 441 ms per million names | m=479,252,928, k=7, 59.9 MB
warm-up: 100,000 random lookups in 28.5 s
filter    checks 100,000 | database reads 10,298 | false positives 368 | live rate 0.0041 | 3.2 s in all, 0.032 ms a check
filter    database read p50 0.292 ms, p99 0.476 ms | answered from filter p50 0.0004 ms | index pages per read: 3.755 from the cache, 0.863 from outside it
probe, design 1.2 n: 1,000,000 never-added names, measured rate 0.00397 | theory 0.00409 at 50,000,000 names | target 0.01 at 60,000,000 | fill 0.4559
probe, design n: 1,000,000 never-added names, measured rate 0.00982 | theory 0.01004 at 50,000,000 names | target 0.01 at 50,000,000 | fill 0.5183
scaled to 1,000,000,000 names (estimated): index 31,543 MB at 31.54 bytes a name | at 5,000 checks a second with 90 percent free: database reads 5,000 without the filter, 518 with it | index pages from outside the cache, at this cache's share of the index: 3,775 and 391 a second
check: index pages from outside the cache per database read, filter phase 0.863 against 0.755 without it
scaled to 1,000,000,000 names (estimated): rebuild 443 s, about 7.4 minutes, at this run's rate
```

## Project structure

```
src/main/java/io/plainround/seatwave/filters/
  bloom/   SeededHash, BloomSizing, BloomFilter, CountingBloomFilter, FalsePositiveCli, DeleteDemoCli, HmacCostCli
  signup/  UsernameStore, JdbcUsernameStore, UsernameCheck, UsernameCheckCli
  sketch/  HyperLogLog, CountMinSketch, VisitorCountCli, TopSeatsCli
  labs/    TenMillionNamesCli, ReadsAvoidedCli, UniqueVisitorsLabCli
  study/   LabSettings, LabDatabase, UsernameLoader, LoadUsernamesCli, UsernameStudyCli
src/test/java/...   nine test classes; UsernameStoreStackTest is tagged stack and runs only through scripts/stack.sh test
config/lab.properties, compose.yaml, db/init/10-roles.sh, scripts/stack.sh
```

ARCHITECTURE.md has the packages, the dependency rule, a data-flow diagram and the invariants.

## What is simplified

- `HyperLogLog` keeps one byte per register (production packs 6 bits) and has no bias tables.
- `CountMinSketch` is not thread-safe; one per thread, or a lock around it.
- The filter's seed is a constant in the programs; production reads a secret, random seed per deployment from the secret store.
- `HmacCostCli` is a timing program with warm-up rounds, not a JMH benchmark.
- The study runs at 50,000,000 names, a twentieth of the module's billion, and scales its numbers with the arithmetic it prints; the laptop's virtual machine is not the reference machine.
- There is no HTTP endpoint: `GET /v1/usernames/{username}/availability` is named for later modules; `UsernameCheck` is what it would call.

## No CI on purpose

There is no `.github/` folder and no workflow. Every command above was run locally, and its output is printed here as it came.
