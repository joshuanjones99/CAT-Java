# A stand-in for Verifone's VATS SDK, from the Python CAT's tests/unit/vats_fake_sdk.py.

import json, os, re, sys
_DIR = os.path.dirname(os.path.abspath(__file__))
def _screen():
    with open(os.path.join(_DIR, "screen.json"), encoding="utf-8") as f:
        return json.load(f)
def _call(name, *args):
    with open(os.path.join(_DIR, "calls.log"), "a", encoding="utf-8") as f:
        f.write(json.dumps([name, list(args)]) + "\n")
def _advance(key):
    # the screen an insert or a removal moves the "terminal" to, in turn
    screen = _screen()
    if screen.get(key):
        screen.update(screen[key].pop(0))
        with open(os.path.join(_DIR, "screen.json"), "w", encoding="utf-8") as f:
            json.dump(screen, f)

print("terminal.py: imported (the real SDK prints like this)")
class VatsConnectorCommunication:
    @staticmethod
    def catchup_event(flag):
        _call("catchup_event", flag); return True
    @staticmethod
    def wait_html_display_update(timeout, region):
        _call("wait_html_display_update", timeout, region)
        return _screen().get("redrawing", True)
