package com.verifone.cat.sims;

import java.util.ArrayList;
import java.util.List;

import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinUser.WNDENUMPROC;
import com.sun.jna.ptr.IntByReference;

/**
 * {@link WindowOps} on user32 through JNA: the Python CAT's {@code _top_level_windows} and
 * {@code _minimise} ({@code cat.sims.supervisor}), which use ctypes. A thin binding with no logic of
 * its own beyond the filter; no test calls it.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The Python's window lookup ran against the real
 * simulators on 2026-09-10 (by {@code MainWindowHandle}) and 2026-10-20 (Petro's minimising); this
 * binding has not run against any window.
 */
public final class JnaWindowOps implements WindowOps {

    @Override
    public List<TopWindow> topLevelWindows(long pid) {
        User32Lib user32 = User32Lib.get();
        List<TopWindow> found = new ArrayList<>();
        WNDENUMPROC visit = (HWND hwnd, com.sun.jna.Pointer data) -> {
            IntByReference owner = new IntByReference();
            user32.GetWindowThreadProcessId(hwnd, owner);
            if (Integer.toUnsignedLong(owner.getValue()) == pid
                    && user32.IsWindowVisible(hwnd)
                    && User32Lib.handle(user32.GetWindow(hwnd, User32Lib.GW_OWNER)) == 0) {
                found.add(new TopWindow(User32Lib.handle(hwnd), user32.IsIconic(hwnd)));
            }
            return true;
        };
        user32.EnumWindows(visit, null);
        return found;
    }

    @Override
    public void minimise(long hwnd) {
        try {
            User32Lib.get().ShowWindow(User32Lib.hwnd(hwnd), User32Lib.SW_MINIMIZE);
        } catch (RuntimeException | LinkageError ignored) {
            // Cosmetic, as in the Python: a window left up is never a row.
        }
    }
}
