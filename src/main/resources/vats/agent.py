"""CAT's VATS agent: one physical pinpad, driven through Verifone's VATS SDK.

**This file runs under a different interpreter from the rest of CAT.** It is started
by `petrotest.devices.vats.client` with the staged Terminal Connector's embedded
Python 3.9 (`terminalConnector-V4/python-3.9.11-embed-amd64/python.exe`), because
the SDK it imports — `vats_runner/`, Verifone's VATS PC package 4.0.32-1, shipped
inside that zip — is written for it. So: Python 3.9 syntax, standard library only,
and nothing from `petrotest`.

What it replaces. PATS reaches the pinpad through its Terminal Connector: Java ->
py4j -> `patslib` -> this same SDK -> Verifone's KM4 relay -> the terminal. Every
`patslib` action then sleeps a fixed time (2 s per queue poll, 2 s after each
action, 1.2 s per PIN digit plus 7 s, 3 s after each card) and answers through a
file on disk. Here the SDK is called directly, in the calls `patslib` makes
(`patslib/helper/vatsapi.py`, `patslib/impl/collisCTterminalService.py`), with
none of those sleeps: every wait is one of the SDK's own waits on a terminal event.

Protocol. One JSON object per line on stdin, `{"id": n, "op": "...", ...}`; one
per line on stdout, `{"id": n, "ok": true, "result": {...}}` or `{"id": n, "ok":
false, "error": "..."}`. The SDK prints freely, so `sys.stdout` is pointed at
stderr before it is imported and replies go to the original stdout handle.

UNVERIFIED AGAINST HARDWARE except `hello` and `screen`, which ran against the lab
M400 on 2026-10-06 (HANDOFF.md §1.110). The card, key and button ops call the SDK
exactly as `patslib` does, but no sale has been driven through them yet.
"""

from __future__ import annotations

import argparse
import json
import re
import sys
import time

#: `pats_config.ini [Region_Path]`: the display region a model's payment app draws in.
REGION_PATH = {"M400_VOS2": "/0", "P400_VOS2": "/0", "M425_VOS3": "/6/1"}


def _parse_args(argv):
    parser = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    parser.add_argument("--sdk", required=True, help="the staged vats_runner/ directory")
    parser.add_argument("--km4-port", type=int, required=True, help="the KM4 relay's local port")
    return parser.parse_args(argv)


class Agent:
    """The SDK's module-level singletons, behind one object per process."""

    def __init__(self, sdk: str, km4_port: int) -> None:
        # The SDK reads its options from sys.argv at import time: `VatsSocket()`
        # takes `--km4_port`, and `terminal._is_connected_mode()` goes quiet when it
        # sees -v/-t/-l. So the argv it parses is exactly this, and nothing of ours.
        sys.argv = [sys.argv[0], "--km4_port", str(km4_port)]
        sys.path.insert(0, sdk)
        import adkcom
        import terminal
        import user
        import vats_html

        self.adk = adkcom.AdkCom
        self.km4 = terminal.VatsConnectorCommunication
        self.user = user.user
        self.gui = vats_html.vats_json
        self.region = "/0"
        self.model = ""

    # -- ops ---------------------------------------------------------------

    def hello(self, response_ms: int = 20000) -> dict:
        """Catch up on the relay's events, identify the terminal, set its keymap."""
        self.km4.catchup_event(False)
        self.user.set_terminal_specific_values(response_ms)
        self.model = (self.user.get_terminal_model_info() or "").strip()
        version = self.user.get_terminal_version_info() or ""
        os_type = "VOS3" if "V/OS3" in version else "VOS2"
        self.region = REGION_PATH.get(f"{self.model}_{os_type}", "/0")
        return {"model": self.model, "version": version, "os": os_type, "region": self.region}

    def screen(self) -> dict:
        """What the payment app's region shows, split the way `patslib` splits it."""
        self.km4.catchup_event(False)
        return {"lines": self._lines(self.gui.gui_get_html(self.region))}

    def wait(self, pattern: str, timeout_ms: int, hold_ms: int = 0, gone: bool = False) -> dict:
        """Wait until any display region matches `pattern` (or, `gone`, none does).

        `pattern` is a regular expression; the client escapes plain prompts.
        """
        if not gone:
            status = bool(
                self.gui.gui_any_wait_region_contains(
                    pattern, response_time=timeout_ms, hold_time=hold_ms
                )
            )
            return {"status": status, **self.screen()}
        deadline = time.monotonic() + timeout_ms / 1000.0
        while True:
            self.km4.catchup_event(False)
            shown = self.gui.gui_does_region_contain(pattern, region_path=self.region)
            remaining = int((deadline - time.monotonic()) * 1000)
            if not shown or remaining <= 0:
                return {"status": not shown, **self.screen()}
            # Blocks on the terminal's next display event, at most `remaining`.
            self.km4.wait_html_display_update(min(remaining, 1000), self.region)

    def update(self, timeout_ms: int) -> dict:
        """Block until the payment app's region redraws, at most `timeout_ms`."""
        status = bool(self.km4.wait_html_display_update(timeout_ms, self.region))
        return {"status": status, **self.screen()}

    def shows(self, patterns: list) -> dict:
        """Which of `patterns` some display region shows now, each True or False.

        `wait` matches in any region (`gui_any_wait_region_contains`) but reports only
        the payment app's lines, so a caller waiting on several outcomes asks this which
        one it got. The M400's 6.9.1 apps draw in more than one region: "CHIP NOT READ"
        comes from evpay, "Remove Card" from mapp (`ats_event.dat`, 2026-10-13).
        """
        self.km4.catchup_event(False)
        return {"shown": [bool(self.gui.gui_does_any_region_contain(p)) for p in patterns]}

    def settle(self, busy: str, quiet_ms: int, timeout_ms: int, watch: str = "") -> dict:
        """Wait until no region shows `busy` and the payment app's region has not
        redrawn for `quiet_ms`; `status` False if `timeout_ms` ran out first. `seen` is
        whether `watch` was on screen at any pass: a decline shows for 1-2 s only.

        Each pass blocks on the terminal's next display event, at most `quiet_ms`: a pass
        that sees no redraw, with nothing busy on screen, is the terminal at rest.
        """
        deadline = time.monotonic() + timeout_ms / 1000.0
        seen = False
        while True:
            self.km4.catchup_event(False)
            seen = seen or bool(watch and self.gui.gui_does_any_region_contain(watch))
            remaining = int((deadline - time.monotonic()) * 1000)
            if remaining <= 0:
                return {"status": False, "seen": seen, **self.screen()}
            redrew = self.km4.wait_html_display_update(min(quiet_ms, remaining), self.region)
            if not redrew and not self.gui.gui_does_any_region_contain(busy):
                seen = seen or bool(watch and self.gui.gui_does_any_region_contain(watch))
                return {"status": True, "seen": seen, **self.screen()}

    def press(self, label: str, timeout_ms: int) -> dict:
        """`vatsapi.performKeyPress`: wait for the soft key, then press it."""
        pattern = re.escape(label)
        if not self.gui.gui_any_wait_region_contains(pattern, response_time=timeout_ms):
            return {"status": False, "reason": f"{label!r} never shown", **self.screen()}
        action = self.gui.gui_press_button(pattern)
        return {"status": action is not None, "action": str(action), **self.screen()}

    def keys(self, keys: str) -> dict:
        """`user.enter_keys`: digits as themselves, function keys as `<CONFIRM>` etc."""
        return {"status": bool(self.user.enter_keys(keys))}

    def insert(self) -> dict:
        return {"status": bool(self.user.insert_smartcard())}

    def remove(self) -> dict:
        return {"status": bool(self.user.remove_smartcard())}

    def card_state(self) -> dict:
        """What this PC told the terminal: inserted or not. Not the terminal's reader."""
        return {"state": self.user.get_smartcard_insertion_state()}

    def cardin(self, response_ms: int = 3000) -> dict:
        """`user.get_cardin_status`: the terminal's own card-in state (its handler asks
        `IFD_Is_ICC_Present`). 1 a card is in, 0 none, -1 no answer or not supported:
        the SDK asks first whether the terminal reports a `PYTHON_VATS_PC`."""
        return {"present": int(self.user.get_cardin_status(response_ms))}

    def keybuf(self) -> dict:
        """`user.get_keyboard_buffer`: keys sent and not yet taken by the terminal, which
        pulls them from the PC side (`vats_hdlr`'s `HandlePCEventInfo`)."""
        return {"keys": str(self.user.get_keyboard_buffer() or "")}

    def swipe(self, track1: str = "", track2: str = "", track3: str = "") -> dict:
        """`user.swipe_magcard` with three strings, as PATS's `collisCTterminalService` calls it.

        Never `None` for an empty track: the SDK reads `track2 is None` as "track1 is one
        `t1|t2|t3` string", and with track2 set it takes `len()` of all three, so a
        `None` track3 raised `TypeError` (VCMG_LOYALTY_CARD2, run `5608174bd4d4`).
        """
        self.user.set_terminal_specific_values()
        return {"status": bool(self.user.swipe_magcard(track1 or "", track2 or "", track3 or ""))}

    def comm(self, interface: str) -> dict:
        """`vatsapi.getPinpadCommConfigs`, with its arguments in the right order."""
        enum = getattr(self.adk.COM_INTERFACE, interface.upper(), None)
        if enum is None:
            raise ValueError(f"unknown interface {interface!r}")
        return {
            "config": {
                k: str(v) for k, v in dict(self.adk.get_network_interface_info(enum)).items()
            }
        }

    # -- parsing -----------------------------------------------------------

    @staticmethod
    def _lines(html_text: str) -> list:
        """`vatshelper.__getVos2ScreenData`/`__getVos3ScreenData`, as plain strings."""
        if not html_text:
            return []
        data = json.loads(html_text)
        value = data.get("value") or {}
        if "body_header" in value:
            return [s for s in str(value["body_header"]).split("<br>") if s.strip()]
        text = value.get("textline")
        if text is None:
            text = value.get("text")
        if text is None:
            return [
                m.strip() for m in re.findall(r">([^<]+)<", str(data.get("text", ""))) if m.strip()
            ]
        if isinstance(text, list):
            return [str(t.get("name", t)) if isinstance(t, dict) else str(t) for t in text]
        return [line for line in str(text).splitlines() if line.strip()]


def main(argv=None) -> int:
    args = _parse_args(argv)
    replies = sys.stdout
    sys.stdout = sys.stderr  # the SDK prints; only replies reach the real stdout
    agent = Agent(args.sdk, args.km4_port)
    for raw in sys.stdin:
        if not raw.strip():
            continue
        request = json.loads(raw)
        op = request.pop("op")
        rid = request.pop("id", None)
        if op == "bye":
            replies.write(json.dumps({"id": rid, "ok": True, "result": {}}) + "\n")
            replies.flush()
            return 0
        try:
            handler = getattr(agent, op) if not op.startswith("_") else None
            if handler is None:
                raise ValueError(f"unknown op {op!r}")
            reply = {"id": rid, "ok": True, "result": handler(**request)}
        except Exception as exc:  # every failure is a reply; the agent stays up
            reply = {"id": rid, "ok": False, "error": f"{type(exc).__name__}: {exc}"}
        replies.write(json.dumps(reply, default=str) + "\n")
        replies.flush()
    return 0


if __name__ == "__main__":
    sys.exit(main())
