package com.verifone.cat.desktop;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.BaseTSD.ULONG_PTRByReference;
import com.sun.jna.platform.win32.User32;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LPARAM;
import com.sun.jna.platform.win32.WinDef.LRESULT;
import com.sun.jna.platform.win32.WinDef.WPARAM;
import com.sun.jna.platform.win32.WinNT.HANDLE;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * {@link WindowIcon}'s window calls, through user32. jna-platform's {@code User32} has most of them; its
 * {@code SendMessageTimeout} returns the result in a 32-bit {@code DWORD} where {@code WM_GETICON}'s is a handle, and
 * it lacks {@code SetThreadDpiAwarenessContext}, so those two are declared here ({@link Lib}).
 *
 * <p>The icon sizes are the system's at the system DPI, the sizes the browser itself asks for: the Java launcher
 * declares no DPI awareness, so the loading thread takes system awareness for the call and then gives it back
 * (Windows 10 1607 and later; before that the sizes are 96 DPI's).
 *
 * <p>UNVERIFIED ON A CONTROLLER (Java port): checked only on a development PC (Windows 11, Edge, 100% scale).
 */
final class JnaWindowIcons implements WindowIcon.Windows {

    /** The window class of every Chromium browser's top-level windows. */
    static final String BROWSER_CLASS = "Chrome_WidgetWin_1";

    private static final int WM_GETICON = 0x7F;
    private static final int WM_SETICON = 0x80;
    private static final int GW_OWNER = 4;
    private static final int SMTO_ABORTIFHUNG = 2;
    private static final int TIMEOUT_MS = 200;
    private static final int IMAGE_ICON = 1;
    private static final int LR_LOADFROMFILE = 0x10;
    private static final int SM_CXICON = 11;
    private static final int SM_CXSMICON = 49;
    /** {@code DPI_AWARENESS_CONTEXT_SYSTEM_AWARE}. */
    private static final long SYSTEM_AWARE = -2;

    /** The two user32 calls jna-platform's {@code User32} does not have as needed. */
    interface Lib extends StdCallLibrary {
        Lib INSTANCE = Native.load("user32", Lib.class, W32APIOptions.DEFAULT_OPTIONS);

        LRESULT SendMessageTimeout(HWND hwnd, int message, WPARAM wParam, LPARAM lParam, int flags, int timeout,
                ULONG_PTRByReference result);

        Pointer SetThreadDpiAwarenessContext(Pointer context);
    }

    /** Loads {@code ico} at the system's big and small icon sizes. */
    WindowIcon.Icons load(Path ico) throws IOException {
        Pointer before = systemAware();
        try {
            int big = User32.INSTANCE.GetSystemMetrics(SM_CXICON);
            int small = User32.INSTANCE.GetSystemMetrics(SM_CXSMICON);
            return new WindowIcon.Icons(loadIcon(ico, big), loadIcon(ico, small));
        } finally {
            if (before != null) {
                Lib.INSTANCE.SetThreadDpiAwarenessContext(before);
            }
        }
    }

    @Override
    public List<WindowIcon.Window> browserWindows() {
        List<WindowIcon.Window> out = new ArrayList<>();
        char[] text = new char[512];
        User32.INSTANCE.EnumWindows((hwnd, data) -> {
            if (!User32.INSTANCE.IsWindowVisible(hwnd)
                    || User32.INSTANCE.GetWindow(hwnd, new DWORD(GW_OWNER)) != null
                    || !BROWSER_CLASS.equals(read(text, User32.INSTANCE.GetClassName(hwnd, text, text.length)))) {
                return true;
            }
            String title = read(text, User32.INSTANCE.GetWindowText(hwnd, text, text.length));
            out.add(new WindowIcon.Window(Pointer.nativeValue(hwnd.getPointer()), title));
            return true;
        }, null);
        return out;
    }

    @Override
    public long icon(long handle, boolean big) {
        ULONG_PTRByReference result = new ULONG_PTRByReference();
        LRESULT ok = Lib.INSTANCE.SendMessageTimeout(hwnd(handle), WM_GETICON, new WPARAM(big ? 1 : 0),
                new LPARAM(0), SMTO_ABORTIFHUNG, TIMEOUT_MS, result);
        return ok.longValue() == 0 ? 0 : result.getValue().longValue();
    }

    @Override
    public void setIcon(long handle, boolean big, long icon) {
        Lib.INSTANCE.SendMessageTimeout(hwnd(handle), WM_SETICON, new WPARAM(big ? 1 : 0), new LPARAM(icon),
                SMTO_ABORTIFHUNG, TIMEOUT_MS, new ULONG_PTRByReference());
    }

    private static long loadIcon(Path ico, int size) throws IOException {
        HANDLE icon = User32.INSTANCE.LoadImage(null, ico.toString(), IMAGE_ICON, size, size, LR_LOADFROMFILE);
        if (icon == null) {
            throw new IOException("could not load " + ico + " at " + size + " px (error "
                    + Native.getLastError() + ")");
        }
        return Pointer.nativeValue(icon.getPointer());
    }

    /** Takes system DPI awareness for this thread. @return the thread's awareness before, or null when unchanged */
    private static Pointer systemAware() {
        try {
            return Lib.INSTANCE.SetThreadDpiAwarenessContext(new Pointer(SYSTEM_AWARE));
        } catch (UnsatisfiedLinkError e) {
            return null;
        }
    }

    private static HWND hwnd(long handle) {
        return new HWND(new Pointer(handle));
    }

    private static String read(char[] text, int length) {
        return new String(text, 0, Math.max(0, Math.min(length, text.length)));
    }
}
