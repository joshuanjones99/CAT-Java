#!/bin/sh
#
# petrotest POS HTTP shim launcher — DESIGN.md §5.2, pushed by §6.1.
#
# Runs on the register (C18) next to the Topaz POS. The POS's automation observer
# dials this process over loopback RMI; petrotest reads the state it collects over
# HTTP. See bridge/pos-shim/README.md.
#
# Usage:
#   petrotest-pos-shim.sh start|stop|status|restart|foreground [-- shim args...]
#
# Deliberately POSIX sh with no systemd unit: the register's init is not ours to
# add units to, and `provision` pushes files over SFTP rather than installing
# packages. A pidfile plus `kill -0` is the whole of what is needed to make
# start idempotent, which is the property §6.1 asks of every push.

set -eu

HOME_DIR="${PETROTEST_SHIM_HOME:-/home/topaz/automation}"
JAVA_BIN="${PETROTEST_SHIM_JAVA:-/usr/java/j2se/bin/java}"
PIDFILE="${PETROTEST_SHIM_PIDFILE:-$HOME_DIR/petrotest-pos-shim.pid}"
LOGFILE="${PETROTEST_SHIM_LOG:-$HOME_DIR/petrotest-pos-shim.log}"

SHIM_JAR="$HOME_DIR/petrotest-pos-shim.jar"

# The Topaz classpath, not a curated subset. The shim's compile-time dependency
# is isdApps.topaz.pos.app-V1.jar, but the retained agent it drives reaches
# further at runtime (slf4j, log4j, the vsms2 fuel and sales entity classes), and
# enumerating that transitive set here would be a list that silently rots against
# every POS build. /home/topaz/lib is where the POS itself gets them — and where
# `provision` puts automation-target-pos-topaz.jar (artifacts.py's ArtifactFile
# for it targets _TOPAZ_LIB, never $HOME_DIR), so the agent jar's own path is
# derived from there rather than duplicated under automation/.
TOPAZ_LIB="${PETROTEST_SHIM_TOPAZ_LIB:-/home/topaz/lib}"
AGENT_JAR="$TOPAZ_LIB/automation-target-pos-topaz.jar"
CLASSPATH="$SHIM_JAR:$AGENT_JAR:$TOPAZ_LIB/*:$TOPAZ_LIB/partner/*"

# --add-opens java.rmi/...: the register runs Corretto 17 and the Topaz RMI stack
# reflects into java.rmi internals — the POS's own Tomcat command line carries the
# same two opens, so the shim needs them for the identical reason.
#
# -Dsun.rmi.registry.registryFilter: Java 17 refuses to deserialise non-JDK types
# through a registry unless they are allowlisted. The POS pushes com.verifone.*
# panel objects through this registry, so without this the observer connects and
# every update is rejected by the serial filter. Value matches the POS's own.
JAVA_OPTS="${PETROTEST_SHIM_JAVA_OPTS:-}"
JAVA_OPTS="$JAVA_OPTS --add-opens=java.rmi/sun.rmi.transport=ALL-UNNAMED"
JAVA_OPTS="$JAVA_OPTS --add-opens=java.rmi/java.rmi.server=ALL-UNNAMED"
JAVA_OPTS="$JAVA_OPTS -Dsun.rmi.registry.registryFilter=java.**;com.verifone.**;javax.**;org.**"
JAVA_OPTS="$JAVA_OPTS -Djdk.tls.ephemeralDHKeySize=2048"
JAVA_OPTS="$JAVA_OPTS -Djdk.tls.client.protocols=TLSv1.2"

MAIN_CLASS="com.verifone.petrotest.posshim.PosShim"

# A live pid is not enough: the pidfile survives a reboot, and the number in it
# can belong to another process by then. On 2026-10-13 a pidfile from 10-09 named
# a boot-time process after the register rebooted, `start` said "already running",
# and nothing listened on 8801 for a whole run. So the pid must also be this JVM.
PROC="${PETROTEST_SHIM_PROC:-/proc}"

# Whether the process at `$1` (a /proc directory) is the shim: its first argument a java
# binary, one argument exactly MAIN_CLASS. Read with GNU head's and grep's -z, each
# NUL-separated argument a record: Reg101 has no `tr`, so the `tr`-based check this
# replaced never matched there, `running` was always false, and every `restart` started
# a second JVM beside the live one (2026-10-16).
is_shim() {
    head -z -n 1 "$1/cmdline" 2>/dev/null | grep -qz 'java$' \
        && grep -qzx "$MAIN_CLASS" "$1/cmdline" 2>/dev/null
}

# The shim's JVM: the pidfile's pid when it is one, else any process that is the shim.
# A pidfile can go while its JVM runs on (Reg101, 2026-10-16: a shim up since 14 October
# with no pidfile; `restart` started a second JVM, and it died on "Address already in
# use" while the old one kept serving). A grep naming MAIN_CLASS is not a java binary.
shim_pid() {
    if [ -f "$PIDFILE" ]; then
        pid=$(cat "$PIDFILE" 2>/dev/null) || pid=""
        if [ -n "$pid" ] && kill -0 "$pid" 2>/dev/null && is_shim "$PROC/$pid"; then
            echo "$pid"
            return 0
        fi
    fi
    for dir in "$PROC"/[0-9]*; do
        if is_shim "$dir"; then
            echo "${dir##*/}"
            return 0
        fi
    done
    return 1
}

running() {
    shim_pid > /dev/null
}

require_files() {
    for f in "$SHIM_JAR" "$AGENT_JAR"; do
        if [ ! -f "$f" ]; then
            echo "petrotest-pos-shim: missing $f — has provision run?" >&2
            exit 1
        fi
    done
    if [ ! -x "$JAVA_BIN" ]; then
        echo "petrotest-pos-shim: no JVM at $JAVA_BIN" >&2
        exit 1
    fi
}

start() {
    if running; then
        echo "petrotest-pos-shim: already running (pid $(shim_pid))"
        return 0
    fi
    require_files
    # Truncate rather than append: the log is read back after a start to explain a
    # failed one, and scrolling past three earlier runs to find out why this one
    # died is how a wrong conclusion gets drawn.
    : > "$LOGFILE"
    # shellcheck disable=SC2086  # JAVA_OPTS and "$@" are intentionally split
    nohup "$JAVA_BIN" $JAVA_OPTS -cp "$CLASSPATH" "$MAIN_CLASS" "$@" \
        >> "$LOGFILE" 2>&1 &
    echo $! > "$PIDFILE"
    echo "petrotest-pos-shim: started (pid $(cat "$PIDFILE")), logging to $LOGFILE"
}

stop() {
    if ! running; then
        echo "petrotest-pos-shim: not running"
        rm -f "$PIDFILE"
        return 0
    fi
    pid=$(shim_pid)
    kill "$pid" 2>/dev/null || true
    # The shutdown hook stops the HTTP listener; give it a moment before SIGKILL
    # so an in-flight key injection is not orphaned mid-RMI-call.
    i=0
    while [ "$i" -lt 10 ] && kill -0 "$pid" 2>/dev/null; do
        sleep 1
        i=$((i + 1))
    done
    if kill -0 "$pid" 2>/dev/null; then
        echo "petrotest-pos-shim: did not exit in 10s, sending SIGKILL"
        kill -9 "$pid" 2>/dev/null || true
    fi
    rm -f "$PIDFILE"
    echo "petrotest-pos-shim: stopped"
}

status() {
    if running; then
        echo "petrotest-pos-shim: running (pid $(shim_pid))"
        return 0
    fi
    echo "petrotest-pos-shim: not running"
    return 3
}

case "${1:-}" in
    start)
        shift
        [ "${1:-}" = "--" ] && shift
        start "$@"
        ;;
    stop)
        stop
        ;;
    restart)
        shift
        [ "${1:-}" = "--" ] && shift
        stop
        start "$@"
        ;;
    status)
        status
        ;;
    foreground)
        # For debugging on the rig: same command line, no nohup, logs to the
        # terminal. Not what provision uses.
        shift
        [ "${1:-}" = "--" ] && shift
        require_files
        # shellcheck disable=SC2086
        exec "$JAVA_BIN" $JAVA_OPTS -cp "$CLASSPATH" "$MAIN_CLASS" "$@"
        ;;
    *)
        echo "usage: $0 start|stop|restart|status|foreground [-- shim args...]" >&2
        exit 2
        ;;
esac
