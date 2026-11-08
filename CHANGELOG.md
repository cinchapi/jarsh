# Changelog

#### Version 2.0.0 (TBD)

* **jarsh runs on Gradle 8.14 and newer and builds the binary on its own.** The
  plugin no longer applies the `executable-jar` plugin or downloads One-JAR. The
  binary's jar holds the application's classes, its resources, and those of
  every runtime dependency directly. Where two jars hold a file with the same
  name, the binary keeps the one that comes first on the runtime classpath, and
  it joins the service files under `META-INF/services`.
  * The `executableJar` task is gone. A project that named its main class there
    now sets `application.mainClass` with the `application` plugin, or
    `jarsh.mainClass` without it. The `jarsh` task fails and says so when it has
    neither.

    ```groovy
    jarsh {
        mainClass = 'com.example.Main'
    }
    ```

* **An application that depends on signed jars runs from the binary.** The
  binary leaves out jar signature files, which would otherwise make Java reject
  it with `Invalid signature file digest`. A signed dependency runs unsigned.
* **A binary can require a minimum Java version.** With `javaVersion` set, the
  binary looks for a Java of at least that version in `JAVA_HOME`, on the
  `PATH`, with `java_home` on macOS, among SDKMAN's JDKs, and among the JDKs
  that Gradle provisioned. It passes over older ones. When it finds none, it
  prints what it found and how to install a suitable Java, and exits with status
  `127` without running the application.

  ```groovy
  jarsh {
      javaVersion = 17
  }
  ```

* **A project can start the binary with its own launcher script.** The launcher
  replaces the default stub, so it can run setup in the shell before Java
  starts. The binary keeps the launcher's `#!` line and opening comments. It
  then defines helpers that find a suitable Java (`jarsh_find_java`,
  `jarsh_exec_java`), read a file bundled in the binary (`jarsh_read`), and give
  the binary's path (`JARSH_BINARY`).

  ```groovy
  jarsh {
      launcher = file('src/main/sh/launcher.sh')
  }
  ```

* The default stub runs with `/bin/sh` instead of `bash`. It still passes every
  argument unchanged, keeps the caller's working directory, extracts nothing,
  exits with the application's status, and passes the words of `java_args` to
  `java`.
* Fixed a bug that kept a binary from starting when its path held a space.
* Fixed a bug that made the stub fail when `JAVA_HOME` had no `bin/java`. The
  binary now looks for Java in the other places above.
* The binary is a zip archive whose offsets count from the start of the file, so
  `unzip` and other zip tools read it without a warning.
