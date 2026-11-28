# jarsh

jarsh is a Gradle plugin that builds a Java application into a binary: one
executable file that carries the application and all of its dependencies, so a
user runs it like any other command.

The binary is a shell script followed by a jar. The script, called the launcher,
finds a suitable `java` and runs the jar that follows it. Because the binary is
also a jar, `java -jar`, `unzip`, and other zip tools read it as one.

## Build a binary

Apply the plugin and name the application's main class. The `application`
plugin's `mainClass` is enough:

```groovy
plugins {
    id 'application'
    id 'com.cinchapi.jarsh' version '2.0.0'
}

application {
    mainClass = 'com.example.Main'
}
```

Then run the `jarsh` task:

```bash
./gradlew jarsh
```

The task writes the binary to `build/libs/<project name>.sh`. Copy it anywhere
and run it:

```bash
build/libs/example.sh --verbose input.txt
```

jarsh needs Gradle 8.14 or newer, and its tests pass on Gradle 8.14 and 9.8. The
plugin applies the `java` plugin itself. It builds the binary from the project's
jar and its runtime classpath, so the binary holds what the `application`
plugin's start scripts would put on the classpath.

## How the binary runs

By default the binary starts with the default stub, a launcher that does nothing
but run the application. The stub behaves like the application itself:

- It passes every argument to the application unchanged.
- It runs the application in the caller's working directory.
- It extracts nothing and writes no file.
- It exits with the application's exit status.

The stub runs the first `java` it finds, as the next section describes. The
words of the `java_args` environment variable go to `java` as JVM options, for
example `java_args=-Xmx2g example.sh`.

## Require a minimum Java version

When the application needs a newer Java than some users have, set `javaVersion`
to the minimum feature version:

```groovy
jarsh {
    javaVersion = 17
}
```

The binary then looks for a Java of at least that version, in this order, and
runs the first one it finds:

1. `$JAVA_HOME/bin/java`
2. `java` on the `PATH`
3. The Java that `/usr/libexec/java_home -v 17+` names, on macOS
4. The JDKs that SDKMAN installed, under `$SDKMAN_DIR/candidates/java` or
   `~/.sdkman/candidates/java`
5. The JDKs that Gradle provisioned, under `$GRADLE_USER_HOME/jdks` or
   `~/.gradle/jdks`

The binary passes over any Java older than the minimum. When it finds none, it
does not run the application. It prints each Java it passed over, says how to
install a suitable one, and exits with status `127`. Without `javaVersion`, the
binary looks in the same places and runs the first Java it finds, whatever its
version.

## Use your own launcher

Some applications need to run setup in the shell before Java starts. For them, a
project supplies its own launcher script in place of the default stub:

```groovy
jarsh {
    launcher = file('src/main/sh/launcher.sh')
}
```

The binary keeps the launcher's `#!` line, so the launcher can use any POSIX
shell, such as sh, dash, bash, or ksh. A launcher without a `#!` line runs with
`/bin/sh`. jarsh builds the binary's script in this order:

1. The launcher's `#!` line and the comment lines that follow it, unchanged.
2. A blank line, then the jarsh helpers below.
3. The rest of the launcher.
4. An `exit`, so the shell never reads the jar that follows.

The rest of the launcher can use these helpers. They work in any POSIX shell,
including under `set -euo pipefail`:

- `JARSH_BINARY` is the absolute path of the binary. Pass it to `java -jar` or
  `java -cp`.
- `JARSH_JAVA_VERSION` is the configured `javaVersion`, or empty.
- `jarsh_find_java` sets `JARSH_JAVA` to the `java` that the default stub would
  run. When none qualifies, it prints why and exits with status `127`.
- `jarsh_exec_java ARGS` runs `jarsh_find_java`, then replaces the shell with
  that `java`, given the words of `java_args` and then `ARGS`.
- `jarsh_read PATH` writes the file at `PATH` in the binary's jar to stdout,
  such as a resource from `src/main/resources`. It needs `unzip`, and it fails
  when the jar has no such file.

Other helper names start with `_jarsh_` and are internal.

For example, this launcher runs Gradle with an init script that the application
ships as a resource, then runs the application:

```bash
#!/usr/bin/env bash
# Usage: example [OPTIONS] [PATH ...]
set -euo pipefail
init=$(mktemp)
jarsh_read com/example/init.gradle > "$init"
./gradlew -q -I "$init" prepare
rm -f "$init"
jarsh_exec_java -cp "$JARSH_BINARY" com.example.Main "$@"
```

With a launcher of its own, a project need not set a main class, because the
launcher names it.

## Settings

The `jarsh` block takes these settings:

- `mainClass` is the class that the default stub runs. It defaults to the
  `application` plugin's `mainClass` when the project applies that plugin.
- `javaVersion` is the minimum Java feature version that the binary accepts. It
  has no default.
- `launcher` is the script that the binary starts with. It defaults to the
  default stub.
- `name` is the binary's file name in `build/libs`. It defaults to the project's
  name followed by `.sh`.

The `jarsh` task fails when it has neither a main class nor a launcher.

## What the binary's jar holds

The jar merges the project's jar and its runtime classpath, and it runs the
application the way that classpath does:

- When two jars hold a file with the same name, the jar keeps the file from the
  one that comes first on the classpath, as a class loader would.
- The jar joins the service files under `META-INF/services`, so a
  `ServiceLoader` finds every provider.
- The jar leaves out jar signature files and jar indexes. A signed dependency
  runs unsigned, and Java does not reject the merged jar for signatures that no
  longer match.
- The manifest is the project jar's manifest without `Class-Path`, with
  `Main-Class` set to `mainClass`. It declares `Multi-Release: true` when any
  dependency does.

## Build jarsh

`./gradlew build` compiles the plugin and runs its unit and functional tests.
The functional tests build sample applications with Gradle TestKit and run the
binaries. They need `unzip` on the `PATH`. Gradle's daemon runs on a JDK 17,
which `gradle/gradle-daemon-jvm.properties` selects, while the plugin itself
targets Java 8.

`./gradlew publishPlugins` publishes the plugin to the Gradle Plugin Portal. It
needs the portal's publishing key and secret in `~/.gradle/gradle.properties`.
