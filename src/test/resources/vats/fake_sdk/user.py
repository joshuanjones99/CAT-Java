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

class user:
    @staticmethod
    def set_terminal_specific_values(response_time=None):
        _call("set_terminal_specific_values", response_time, sys.argv[1:])
    @staticmethod
    def get_terminal_model_info():
        return _screen().get("model", "M400")
    @staticmethod
    def get_terminal_version_info():
        return "'ADK VERS':<ADK-x>,'V/OS2':<release-x>"
    @staticmethod
    def enter_keys(keys):
        _call("enter_keys", keys)
        screen = _screen()
        if "<CONFIRM>" in keys and screen.get("after_confirm"):
            # the screen each CONFIRM moves the "terminal" to, in turn
            screen.update(screen["after_confirm"].pop(0))
            with open(os.path.join(_DIR, "screen.json"), "w", encoding="utf-8") as f:
                json.dump(screen, f)
        return True
    _card_in = False
    @staticmethod
    def insert_smartcard():
        _call("insert_smartcard"); user._card_in = True; _advance("after_insert"); return True
    @staticmethod
    def remove_smartcard():
        _call("remove_smartcard")
        if user._card_in:
            # As the lab M400 on 2026-10-13: a removal clears the terminal's card-in only
            # after an insert this session; a bare one is dropped.
            screen = _screen()
            if screen.get("cardin") == 1:
                screen["cardin"] = 0
                with open(os.path.join(_DIR, "screen.json"), "w", encoding="utf-8") as f:
                    json.dump(screen, f)
        user._card_in = False
        _advance("after_remove")
        return True
    @staticmethod
    def get_smartcard_insertion_state():
        return user._card_in
    @staticmethod
    def get_keyboard_buffer():
        return _screen().get("pending_keys", "")
    @staticmethod
    def get_cardin_status(response_time=None):
        # the terminal's own reader: -1 (no answer) unless a test sets it, as on a
        # terminal that does not report a Python VATS PC
        _call("get_cardin_status")
        return _screen().get("cardin", -1)
    @staticmethod
    def swipe_magcard(t1, t2=None, t3=None):
        # As the real SDK: with t2 set it takes len() of all three, so None raises.
        if t2 is not None and (len(t1) > 79 or len(t2) > 40 or len(t3) > 107):
            return False
        _call("swipe_magcard", t1, t2, t3); return True
