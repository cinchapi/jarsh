#!/usr/bin/env bash
# Adapted from https://coderwall.com/p/ssuaxa
# This script is embedded in the generated fatjar to give it the ability to call
# itself when invoked from the command line

MYSELF=`which "$0" 2>/dev/null`
[ $? -gt 0 -a -f "$0" ] && MYSELF="./$0"
java=java
if test -n "$JAVA_HOME"; then
    java="$JAVA_HOME/bin/java"
fi
exec "$java" $java_args -jar $MYSELF "$@"
exit 1
