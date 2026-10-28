package com.verifone.cat.sims;

import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.platform.win32.WinDef.HWND;
import com.sun.jna.platform.win32.WinDef.LRESULT;
import com.sun.jna.platform.win32.WinUser.MSG;
import com.sun.jna.platform.win32.WinUser.WNDENUMPROC;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

/**
 * The user32 entry points {@link JnaWindowOps} and {@link JnaAccessBridge} call. Declared here rather
 * than taken from jna-platform's {@code User32}, which lacks {@code IsIconic} and
 * {@code MsgWaitForMultipleObjects}. {@link W32APIOptions#DEFAULT_OPTIONS} maps
 * {@code PeekMessage}/{@code DispatchMessage} to their wide-character entry points.
 *
 * <p>Loaded on first use only ({@link Holder}), so nothing in a test that never reaches the real
 * bindings loads a native library.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
interface User32Lib extends StdCallLibrary {

    /** {@code GetWindow}'s {@code GW_OWNER}: a dialog or popup has an owner; a frame has none. */
    int GW_OWNER = 4;
    /** {@code ShowWindow}'s {@code SW_MINIMIZE}. */
    int SW_MINIMIZE = 6;
    /** {@code PeekMessage}'s {@code PM_REMOVE}. */
    int PM_REMOVE = 1;
    /** {@code WM_QUIT}. */
    int WM_QUIT = 0x0012;
    /** {@code MsgWaitForMultipleObjects}'s {@code QS_ALLINPUT}. */
    int QS_ALLINPUT = 0x04FF;

    /** The library, loaded on first use. */
    static User32Lib get() {
        return Holder.INSTANCE;
    }

    boolean EnumWindows(WNDENUMPROC callback, Pointer data);

    int GetWindowThreadProcessId(HWND hwnd, IntByReference pid);

    boolean IsWindowVisible(HWND hwnd);

    boolean IsIconic(HWND hwnd);

    HWND GetWindow(HWND hwnd, int command);

    boolean ShowWindow(HWND hwnd, int command);

    int MsgWaitForMultipleObjects(int count, Pointer handles, boolean waitAll, int milliseconds, int wakeMask);

    boolean PeekMessage(MSG msg, HWND hwnd, int filterMin, int filterMax, int remove);

    boolean TranslateMessage(MSG msg);

    LRESULT DispatchMessage(MSG msg);

    /** Lazy holder: the class is initialised, and user32 bound, on the first {@link #get}. */
    final class Holder {
        static final User32Lib INSTANCE = Native.load("user32", User32Lib.class, W32APIOptions.DEFAULT_OPTIONS);

        private Holder() {
        }
    }

    /** Pumps the calling thread's messages for up to {@code millis}: pyjab's {@code setup_msg_pump}, one turn. */
    static void pump(int millis) {
        User32Lib user32 = get();
        user32.MsgWaitForMultipleObjects(0, null, false, Math.max(0, millis), QS_ALLINPUT);
        MSG msg = new MSG();
        while (user32.PeekMessage(msg, null, 0, 0, PM_REMOVE)) {
            if (msg.message == WM_QUIT) {
                return;
            }
            user32.TranslateMessage(msg);
            user32.DispatchMessage(msg);
        }
    }

    /** A handle as JNA's {@link HWND}. */
    static HWND hwnd(long handle) {
        return new HWND(new Pointer(handle));
    }

    /** JNA's {@link HWND} as a handle; 0 for null. */
    static long handle(HWND hwnd) {
        return hwnd == null ? 0 : Pointer.nativeValue(hwnd.getPointer());
    }
}
