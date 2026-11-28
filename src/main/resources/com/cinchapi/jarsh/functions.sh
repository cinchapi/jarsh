
# jarsh defines these variables and functions for the launcher script around
# them. They work in any POSIX shell, including under set -euo pipefail. Names
# that start with _jarsh_ are internal.

# The absolute path of this binary. The binary is also a jar, so java can run it
# with -jar or take it as a classpath with -cp.
JARSH_BINARY=$0
if [ ! -f "$JARSH_BINARY" ]; then
    JARSH_BINARY=$(command -v "$0" 2>/dev/null || :)
fi
case $JARSH_BINARY in
    /*) ;;
    *) JARSH_BINARY=$PWD/$JARSH_BINARY ;;
esac

# The minimum Java feature version the application needs, such as 17, or empty
# when any version will do.
JARSH_JAVA_VERSION=@JAVA_VERSION@

# The java that jarsh_find_java chose.
JARSH_JAVA=

# Set _jarsh_major to the feature version of the java at $1, or to empty when it
# cannot tell. The release file of the java's home answers without starting a
# JVM, so the java itself answers only when that file is missing.
_jarsh_java_major() {
    _jarsh_version=
    _jarsh_release=${1%/bin/java}/release
    if [ -f "$_jarsh_release" ]; then
        while IFS= read -r _jarsh_line || [ -n "$_jarsh_line" ]; do
            case $_jarsh_line in
                JAVA_VERSION=*) _jarsh_version=${_jarsh_line#JAVA_VERSION=} ;;
                *) ;;
            esac
        done < "$_jarsh_release"
    fi
    if [ -z "$_jarsh_version" ]; then
        _jarsh_version=$("$1" -version 2>&1 || :)
        case $_jarsh_version in
            *' version "'*) _jarsh_version=${_jarsh_version#*' version '} ;;
            *) _jarsh_version= ;;
        esac
    fi
    _jarsh_version=${_jarsh_version#\"}
    _jarsh_version=${_jarsh_version%%\"*}
    # Java 8 and earlier report their feature version after "1.".
    case $_jarsh_version in
        1.*) _jarsh_version=${_jarsh_version#1.} ;;
        *) ;;
    esac
    _jarsh_major=${_jarsh_version%%[!0-9]*}
}

# Set JARSH_JAVA to $1 when $1 is a java that meets JARSH_JAVA_VERSION. When it
# is too old, record it under the source that $2 names, for the message that
# jarsh_find_java prints when no java qualifies.
_jarsh_accept() {
    if [ -f "$1" ] && [ -x "$1" ]; then
        if [ -z "$JARSH_JAVA_VERSION" ]; then
            JARSH_JAVA=$1
        else
            _jarsh_java_major "$1"
            if [ -n "$_jarsh_major" ] \
                    && [ "$_jarsh_major" -ge "$JARSH_JAVA_VERSION" ]; then
                JARSH_JAVA=$1
            else
                _jarsh_rejected="$_jarsh_rejected
  $2: $1 is Java ${_jarsh_major:-of an unknown version}"
            fi
        fi
    fi
}

# Print that no java qualifies, which ones it passed over, and how to install a
# suitable one, then exit with status 127.
_jarsh_missing() {
    if [ -n "$JARSH_JAVA_VERSION" ]; then
        _jarsh_need="Java $JARSH_JAVA_VERSION or newer"
    else
        _jarsh_need=Java
    fi
    printf '%s\n' \
        "${JARSH_BINARY##*/} needs $_jarsh_need and could not find it." \
        "It looked in JAVA_HOME, on the PATH, with java_home on macOS, in" \
        "SDKMAN, and among the JDKs that Gradle installed." >&2
    if [ -n "$_jarsh_rejected" ]; then
        printf 'These did not qualify:%s\n' "$_jarsh_rejected" >&2
    fi
    printf '%s\n' \
        "To install $_jarsh_need, run one of these, or download it from" \
        "https://adoptium.net:" \
        "  brew install --cask temurin" \
        "  sdk install java <version>-tem  (sdk list java shows the versions)" \
        "Then set JAVA_HOME to it, or put its bin folder on the PATH." >&2
    exit 127
}

# Set JARSH_JAVA to the first java that meets JARSH_JAVA_VERSION. Look in
# JAVA_HOME, then on the PATH, then with java_home on macOS, then among SDKMAN's
# JDKs, then among the JDKs that Gradle provisioned. When none qualifies, print
# why and exit with status 127.
jarsh_find_java() {
    JARSH_JAVA=
    _jarsh_rejected=
    if [ -n "${JAVA_HOME:-}" ]; then
        _jarsh_accept "$JAVA_HOME/bin/java" JAVA_HOME
    fi
    if [ -z "$JARSH_JAVA" ]; then
        _jarsh_accept "$(command -v java 2>/dev/null || :)" PATH
    fi
    if [ -z "$JARSH_JAVA" ] && [ -x /usr/libexec/java_home ]; then
        # java_home names its default Java when no Java matches the version, so
        # _jarsh_accept checks the version of the Java it names.
        if [ -n "$JARSH_JAVA_VERSION" ]; then
            _jarsh_home=$(/usr/libexec/java_home -v "$JARSH_JAVA_VERSION+" \
                2>/dev/null || :)
        else
            _jarsh_home=$(/usr/libexec/java_home 2>/dev/null || :)
        fi
        if [ -n "$_jarsh_home" ]; then
            _jarsh_accept "$_jarsh_home/bin/java" java_home
        fi
    fi
    for _jarsh_home in "${SDKMAN_DIR:-${HOME:-}/.sdkman}"/candidates/java/*; do
        # SDKMAN's current is a link to one of the other candidates.
        if [ -z "$JARSH_JAVA" ] && [ "${_jarsh_home##*/}" != current ]; then
            _jarsh_accept "$_jarsh_home/bin/java" SDKMAN
        fi
    done
    _jarsh_jdks=${GRADLE_USER_HOME:-${HOME:-}/.gradle}/jdks
    for _jarsh_candidate in "$_jarsh_jdks"/*/bin/java \
            "$_jarsh_jdks"/*/*/bin/java \
            "$_jarsh_jdks"/*/Contents/Home/bin/java \
            "$_jarsh_jdks"/*/*/Contents/Home/bin/java; do
        if [ -z "$JARSH_JAVA" ]; then
            _jarsh_accept "$_jarsh_candidate" Gradle
        fi
    done
    if [ -z "$JARSH_JAVA" ]; then
        _jarsh_missing
    fi
}

# Replace this process with the java that jarsh_find_java chooses. The java gets
# the words of the java_args environment variable, then the arguments of this
# function.
jarsh_exec_java() {
    jarsh_find_java
    exec "$JARSH_JAVA" ${java_args:-} "$@"
}

# Write the file at path $1 in this binary's jar, such as a resource of the
# application, to stdout. Return a nonzero status when the jar has no such file
# or unzip is not on the PATH.
jarsh_read() {
    if command -v unzip >/dev/null 2>&1; then
        unzip -p "$JARSH_BINARY" "$1"
    else
        printf '%s\n' \
            "${JARSH_BINARY##*/} needs unzip to read $1 from itself," \
            "and unzip is not on the PATH." >&2
        return 127
    fi
}
