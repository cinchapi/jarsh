#!/bin/sh
# This binary is a Java application in one file: this launcher script, then a
# jar that holds the application and its dependencies. It runs that jar with a
# suitable java in the caller's working directory, passes on every argument, and
# exits with the application's status.
jarsh_exec_java -jar "$JARSH_BINARY" "$@"
