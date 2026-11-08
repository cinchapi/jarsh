# Project instructions

Instructions specific to this repo. The shared instructions in `AGENTS.md` apply
first. Rules here add to them and win on conflict.

## Build and quality gates

- `./gradlew build` must pass before delivery. It compiles the plugin, runs
  `validatePlugins`, the unit tests, and the functional tests, and takes under a
  minute, so it is the local gate here.
- `./gradlew functionalTest --tests '*Name*'` runs one functional test. The
  functional tests need `unzip` on the `PATH`.
- The repo has no Java formatter. Format by hand to `JAVA.md`, and run
  `.agents/tools/reflow --changed -i` on each file you edit.
- Run `sh -n`, `dash -n`, and `bash -n` on each shell script you edit under
  `src/main/resources`, since the binary runs it with whatever shell the
  launcher names.

## Repo layout

- `src/main/java/com/cinchapi/jarsh/`: the plugin. `JarshPlugin` wires the
  `jarsh` extension and task; `JarshTask` writes the binary; `MergedJar` merges
  the classpath; `ZipOffsets` makes the archive readable by zip tools.
- `src/main/resources/com/cinchapi/jarsh/`: `stub.sh`, the default launcher, and
  `functions.sh`, the helpers that every binary defines.
- `src/test/java/`: unit tests for the merged jar and the zip offsets.
- `src/functionalTest/java/`: TestKit tests that build sample applications and
  run their binaries.

## Facts that differ by repo

- The plugin's classes target Java 8, because Gradle 8 runs on Java 8. The tests
  compile for the daemon's JDK and may use Java 17 features.
- `gradle/gradle-daemon-jvm.properties` pins Gradle's daemon to JDK 17, which
  the plugin-publish plugin needs.
- Tests use JUnit 4 with no base class.
- The integration branch is `master`.

## Where to look

- `README.md` states the binary's contract and the launcher helpers. Update it
  with any change to either.
