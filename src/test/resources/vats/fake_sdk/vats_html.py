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

class vats_json:
    @staticmethod
    def gui_get_html(region="/0"):
        return json.dumps({"value": {"textline": _screen().get("lines", [])}})
    @staticmethod
    def _text():
        s = _screen(); return "\n".join(s.get("lines", []) + s.get("buttons", []))
    @staticmethod
    def gui_any_wait_region_contains(pattern, response_time=None, hold_time=None):
        _call("gui_any_wait_region_contains", pattern, response_time)
        return re.search(pattern, vats_json._text(), re.DOTALL | re.MULTILINE) is not None
    @staticmethod
    def gui_does_any_region_contain(pattern):
        return re.search(pattern, vats_json._text(), re.DOTALL | re.MULTILINE) is not None
    @staticmethod
    def gui_does_region_contain(pattern, region_path="/0"):
        return re.search(pattern, vats_json._text(), re.DOTALL | re.MULTILINE) is not None
    @staticmethod
    def gui_press_button(pattern):
        _call("gui_press_button", pattern)
        for b in _screen().get("buttons", []):
            if re.search(pattern, b):
                return [("action", "return " + b)]
        return None
