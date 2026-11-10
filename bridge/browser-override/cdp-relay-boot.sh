#!/bin/sh
# Pushed by the `cdp_relay` provision artifact (provision/artifacts.py) to
# /home/topaz/browser/chromium/cdp-relay-boot.sh. Called once at boot,
# backgrounded, from the single guarded line
# `provision.browser_override.install_relay_boot_hook` inserts into
# /etc/rc.d/rc.local — that insertion never touches rc.local's own vendor
# content beyond that one `[ -x ... ] && ... &` line (see that function's
# docstring). This script is the new file; nothing here is vendor-owned.
#
# Why this exists: BROWSER_STRATEGY.md §7 found that a rebooted register
# loses the CDP relay (`CdpRelay.java`) — it was only ever a hand/`enable()`-
# launched process with no boot persistence, confirmed live 2026-09-16 (SSH
# came back up after a reboot, the relay's port did not). Chromium itself
# comes back on its own via `apps`/`topaz.sh`; only the relay needed a hook.
#
# Mirrors `browser_override.start_cdp_relay`'s exact shell shape (same
# double-backgrounding, same `pgrep -f`-based idempotency check) rather than
# reinventing it — see that function's docstring for why a single level of
# `cmd &` hung on this register's shell/sshd combination during an SSH
# session. Whether that specific hang applies outside an SSH channel (this
# script runs from rc.local at boot, no SSH involved) is not confirmed either
# way, so the same proven shape is kept rather than assumed unnecessary.
#
# Silently does nothing if the relay source was never pushed (a register not
# opted into `browser_override: chromium` yet) or if a relay from the same
# source is already running (belt-and-braces — `enable()` also runs this, and
# both can race harmlessly since they share the `pgrep -f CdpRelay.java` check).
#
# Since 2026-10-14 (rig owner: the POS Chrome "should stay up forever in
# edge://inspect. Right now it rarely is visible") this is also the one way the
# relay is started — `provision.browser_override.start_cdp_relay` runs it too —
# and two things changed:
#
# * The JVM runs inside a loop that starts it again 2 s after it exits, for
#   whatever reason. `stop_cdp_relay`'s `pkill -f CdpRelay.java` still ends it:
#   the loop's own command line carries that name, so loop and JVM go together.
# * A relay running from an older `CdpRelay.java` than the one on disk is
#   replaced. Without that, a relay fix pushed by `provision` waited for the
#   register's next reboot to run. The source's md5 is kept beside the log.

RELAY_DIR=/home/topaz/browser/chromium
RELAY_SOURCE=CdpRelay.java
JAVA_BIN=/usr/java/j2se/bin/java
RELAY_PORT=19220
CDP_PORT=9220
LOG=/tmp/cdp-relay.log
PIDFILE=/tmp/cdp-relay.pid
STAMP=/tmp/cdp-relay.md5

[ -f "$RELAY_DIR/$RELAY_SOURCE" ] || exit 0
SOURCE_MD5=$(md5sum "$RELAY_DIR/$RELAY_SOURCE" | cut -d' ' -f1)
if pgrep -f "$RELAY_SOURCE" > /dev/null 2>&1; then
    [ "$(cat "$STAMP" 2>/dev/null)" = "$SOURCE_MD5" ] && exit 0
    pkill -f "$RELAY_SOURCE" 2>/dev/null
    sleep 1
    pkill -9 -f "$RELAY_SOURCE" 2>/dev/null
fi
echo "$SOURCE_MD5" > "$STAMP"

nohup su - topaz -c "cd $RELAY_DIR && while true; do $JAVA_BIN $RELAY_SOURCE $RELAY_PORT \
$CDP_PORT; echo \"cdp-relay exited \$?; starting it again\"; sleep 2; done \
< /dev/null >> $LOG 2>&1 & sleep 1; pgrep -f '$JAVA_BIN $RELAY_SOURCE' | head -1 > $PIDFILE" \
< /dev/null > /dev/null 2>&1 &
