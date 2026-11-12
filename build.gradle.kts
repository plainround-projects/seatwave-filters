plugins {
    java
    jacoco
    alias(libs.plugins.spotless)
}

group = "io.plainround"
version = "1.0.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    // Lab 2 and the study read the database once per check; the JDK has no connection pool, and a
    // new connection per check would measure the connection, not the lookup.
    implementation(libs.hikari)
    // The JDK has the JDBC interfaces, not a driver that speaks PostgreSQL's protocol.
    runtimeOnly(libs.postgresql)
    // HikariCP logs through SLF4J and needs a backend.
    runtimeOnly(libs.logback.classic)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.assertj.core)
    testRuntimeOnly(libs.junit.platform.launcher)
}

// Transitive versions are written to gradle.lockfile, so two builds can never resolve different jars.
dependencyLocking {
    lockAllConfigurations()
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 25
    // Every warning is an error: a warning that is allowed to stay is a defect nobody owns.
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Werror"))
}

spotless {
    java {
        googleJavaFormat(libs.versions.googleJavaFormat.get())
        removeUnusedImports()
        endWithNewline()
    }
}

jacoco {
    toolVersion = libs.versions.jacoco.get()
}

// ./gradlew check needs no Docker. The tests tagged "stack" need the lab database;
// scripts/stack.sh test starts it, runs them, and stops it.
tasks.test {
    useJUnitPlatform {
        excludeTags("stack")
    }
    // The million-name tests hold a 1.2 MB filter and a million strings at a time.
    maxHeapSize = "1g"
    testLogging {
        events("passed", "failed", "skipped")
    }
    finalizedBy(tasks.jacocoTestReport)
}

tasks.register<Test>("stackTest") {
    group = "verification"
    description = "Runs the tests against the lab database; scripts/stack.sh test starts and stops it."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    useJUnitPlatform {
        includeTags("stack")
    }
    // The database is outside Gradle's view, so these tests are never up to date.
    outputs.upToDateWhen { false }
    testLogging {
        events("passed", "failed", "skipped")
    }
}

// The programs (*Cli) are run by hand and their output is printed in the README and the module.
// Code that only runs against PostgreSQL is measured by the stack run, not by check.
val programs = listOf("**/*Cli*")
val databaseOnly = programs + listOf("**/JdbcUsernameStore*", "**/study/UsernameLoader*", "**/study/LabDatabase*")

tasks.jacocoTestReport {
    reports {
        xml.required = true
    }
}

tasks.jacocoTestCoverageVerification {
    dependsOn(tasks.test)
    classDirectories.setFrom(sourceSets.main.get().output.asFileTree.matching { exclude(databaseOnly) })
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

tasks.check {
    dependsOn(tasks.jacocoTestCoverageVerification)
}

tasks.register<JacocoReport>("stackCoverageReport") {
    group = "verification"
    description = "Writes the line coverage of the unit and stack test runs together."
    dependsOn(tasks.test, tasks.named("stackTest"))
    executionData(fileTree(layout.buildDirectory.dir("jacoco")).include("*.exec"))
    sourceDirectories.setFrom(sourceSets.main.get().allSource.srcDirs)
    classDirectories.setFrom(sourceSets.main.get().output.asFileTree.matching { exclude(programs) })
    reports {
        xml.required = true
    }
}

tasks.register<JacocoCoverageVerification>("stackCoverage") {
    group = "verification"
    description = "Checks line coverage of all main code but the programs over both test runs."
    dependsOn(tasks.named("stackCoverageReport"))
    executionData(fileTree(layout.buildDirectory.dir("jacoco")).include("*.exec"))
    classDirectories.setFrom(sourceSets.main.get().output.asFileTree.matching { exclude(programs) })
    violationRules {
        rule {
            limit {
                counter = "LINE"
                minimum = "0.80".toBigDecimal()
            }
        }
    }
}

// One task per program, so a reader runs one command per example. The working folder is the
// project, where config/ and .secrets/ are.
fun registerProgram(taskName: String, mainClassName: String, text: String) =
    tasks.register<JavaExec>(taskName) {
        group = "seatwave"
        description = text
        classpath = sourceSets.main.get().runtimeClasspath
        mainClass = mainClassName
        workingDir = projectDir
    }

val root = "io.plainround.seatwave.filters"
registerProgram("falsePositive", "$root.bloom.FalsePositiveCli", "Sizing table, measured rates, an overfilled filter")
registerProgram("deleteDemo", "$root.bloom.DeleteDemoCli", "What clearing bits does; the counting filter")
registerProgram("hmacCost", "$root.bloom.HmacCostCli", "Nanoseconds per hash: seeded FNV with a finalizer against HMAC-SHA-256")
registerProgram("usernameCheck", "$root.signup.UsernameCheckCli", "The sign-up mix with and without the filter; a stale snapshot")
registerProgram("visitorCount", "$root.sketch.VisitorCountCli", "HyperLogLog: unique visitors, error against registers, merge")
registerProgram("topSeats", "$root.sketch.TopSeatsCli", "Count-min sketch: the most-viewed seats")
registerProgram("tenMillionNames", "$root.labs.TenMillionNamesCli", "Lab 1: a filter for 10,000,000 names at 1 percent")
registerProgram("readsAvoided", "$root.labs.ReadsAvoidedCli", "Lab 2: database reads avoided (--postgres: through JdbcUsernameStore)")
registerProgram("uniqueVisitorsLab", "$root.labs.UniqueVisitorsLabCli", "Lab 3: HyperLogLog against an exact set")
registerProgram("loadUsernames", "$root.study.LoadUsernamesCli", "Load fan_1 to fan_N into customers (dry run unless --run)")
registerProgram("usernameStudy", "$root.study.UsernameStudyCli", "The reduced-scale study on PostgreSQL")
