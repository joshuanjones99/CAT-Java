#!/bin/sh
# Canonical source — BROWSER_STRATEGY.md §5.6. Staged into `payload/pos/chromium/`
# (gitignored build output, see `provision/artifacts.py`'s `DEFAULT_PAYLOAD_ROOT`
# docstring) by hand today, the same "no build tool yet" honesty as `checklist.py`'s
# stubs; `bridge/pos-shim/petrotest-pos-shim.sh` is the precedent for a launcher
# script living here and being copied rather than authored directly under
# `payload/`, where `.gitignore` would silently swallow it.
#
# Pushed by `provision`'s `chromium_override` artifact to /usr/local/bin/chromium —
# what topaz.sh's `CHROMIUM=$(which chromium)` fallback branch resolves to once
# browser_override.enable() has chmod -x'd Vewd's runbrowser (BROWSER_STRATEGY.md §5).
#
# Never modifies, deletes or redistributes Vewd's files — only reads its private
# NSS/NSPR libs (read-only, LD_LIBRARY_PATH) and reuses them, per §5's guiding
# principle. See BROWSER_STRATEGY.md §5.0 result for why each of these exact
# libraries is needed and where each one came from.
set -e

BROWSER_DIR=/home/topaz/browser/chromium
TARBALL="$BROWSER_DIR/chrome-linux64-152.0.7977.42.tar.gz"
CHROME_BIN="$BROWSER_DIR/chrome-linux64/chrome"
LIB_DIR="$BROWSER_DIR/lib"
VEWD_LIB_DIR=/usr/browser/vewd/lib

# Idempotent self-extract: `provision` pushes the tarball as one hash-guarded
# blob (artifacts.py's own note on why — thousands of files is the wrong shape
# for the per-file manifest model), so unpacking happens here, on first launch,
# not in `provision` itself.
if [ ! -x "$CHROME_BIN" ]; then
    tar -xzf "$TARBALL" -C "$BROWSER_DIR"
    # Confirmed on rigs/lab.yaml's topaz101, 2026-08-25: extracting as the
    # non-root `topaz` user (topaz.sh refuses to run as anyone else) landed
    # every file but did NOT restore chrome's own executable bit — the archive
    # member's mode says 0755, but this box's tar silently drops it under some
    # combination of `su -`'s environment and this filesystem, not reproduced
    # or root-caused further. Restoring it by hand is cheap and unconditional
    # (a no-op chmod costs nothing if a future tar behaves correctly).
    chmod +x "$CHROME_BIN"
fi

# libnspr4/libnss3/libnssutil3/libsmime3: reused from Vewd's own private copy,
# never pushed — genuinely present there already (§5.0 result). libxkbcommon.so.0
# and libcups.so.2: pushed alongside this wrapper, since the register has neither.
export LD_LIBRARY_PATH="$LIB_DIR:$VEWD_LIB_DIR${LD_LIBRARY_PATH:+:$LD_LIBRARY_PATH}"

# --ozone-platform=x11: §5.0 result's finding — a stable, visibly-rendering
# launch against this register's real X display (DISPLAY=:0, the same one Vewd
# renders to) needed this; topaz.sh's own CHROMIUM_OPTS does not pass it.
#
# --remote-debugging-port=9220: confirmed 2026-08-25, reading topaz.sh directly —
# its `DEV_UNIT=yes` branch adds `--remote-debugging-port` to VEWD_OPTS only,
# never to CHROMIUM_OPTS, so the vendor's own dormant Chromium fallback branch
# never opens a CDP port on its own. Hardcoded to 9220 to match this register's
# `rigs/lab.yaml` entry (`cdp_port: 9220`, itself non-default because 9222 was
# found closed on this rig) — this wrapper is pushed per-register, not shared,
# so matching one rig file's port here is the same kind of deliberate,
# non-guessed choice as everything else in this file.
#
# --remote-debugging-address=0.0.0.0: kept even though it is a confirmed NO-OP on
# this exact Chrome build (152.0.7977.42) — the string is not even present in the
# binary's own symbol/rodata data, meaning this flag was removed upstream, not
# merely defaulted differently. DevTools binds 127.0.0.1 unconditionally regardless
# of this flag; a controller off this box cannot reach it through the wrapper alone
# (BROWSER_STRATEGY.md §5.6/§7 — an out-of-band relay is today's only working
# exposure mechanism, not anything this script does). Left in place, harmless,
# in case a future Chrome build restores the flag or a different build honors it;
# do not read its presence here as evidence off-box CDP access works.
#
# --remote-allow-origins=*: second-line insurance against BROWSER_STRATEGY.md
# §7's "chrome://inspect lists targets but Inspect shows nothing" finding.
# `CdpRelay.java`'s Host-header rewrite addresses the confirmed cause (a
# Host-header check on the WebSocket upgrade); recent Chromium separately
# added an `Origin`-header allowlist for DevTools WebSocket connections
# (rejects with "Rejected an incoming WebSocket connection" unless the
# request's Origin is empty or explicitly allowed). NOT independently
# confirmed to fire on this exact build/client combination — the
# DevTools front-end typically sends no Origin at all for this flow — but
# the flag is a no-op if the check never triggers, so it costs nothing to
# carry. Never a security concern here: §5's own premise is an unauthenticated
# CDP port on a rig-local, opt-in toggle, not a hardened exposure.
#
# --disable-features=WebUIOmniboxPopup,WebUIOmniboxAimPopup: confirmed on
# topaz101 2026-09-15 — this build pre-creates two hidden WebUI omnibox popups
# that show up as `browser_ui` targets in /json/list and chrome://inspect. A
# kiosk app window has no omnibox to use them, so they are noise; both feature
# names were read out of the binary, and with this flag /json/list lists only
# the POS page.
#
# The "Chrome for Testing" infobar (which steals 56px of the 1080px fullscreen
# window and crops the bottom of the POS) is NOT a command-line matter: no
# switch hides it, only the managed policy CommandLineFlagSecurityWarningsEnabled
# = false. `chromium_override` pushes that as petrotest-no-infobars.json into
# /etc/opt/chrome_for_testing/policies/managed/ (provision/artifacts.py).
#
# One browser, one page: any running instance is stopped first. A second launch on
# this profile hands its URL to the running Chrome as a new window and exits at once,
# so the PID topaz.sh writes to its pid file is already gone and the keep-running
# check relaunches it about a minute later, for ever. On topaz101 (2026-10-09, base
# 55.02) that had happened 18 times since a POS restart: 16 POS pages, all but the
# newest black, each reconnecting and taking the POS session from the others
# ("Received a new Open Session"). Vewd never stacked: topaz.sh clears its profile and
# starts it afresh each time, which this does for Chromium.
if pkill -f "^$CHROME_BIN " 2>/dev/null; then
    for _ in 1 2 3 4 5 6 7 8 9 10; do
        pgrep -f "^$CHROME_BIN " >/dev/null 2>&1 || break
        sleep 1
    done
    pkill -9 -f "^$CHROME_BIN " 2>/dev/null || true
fi
exec "$CHROME_BIN" --ozone-platform=x11 --remote-debugging-port=9220 \
    --remote-debugging-address=0.0.0.0 --remote-allow-origins=* \
    --disable-features=WebUIOmniboxPopup,WebUIOmniboxAimPopup "$@"
