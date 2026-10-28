package com.verifone.cat.sims;

import java.util.List;

/**
 * The user32 calls the supervisor makes on a simulator's windows, so the window lookup and the
 * minimising are tested against a fake. The real bindings are {@link JnaWindowOps}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public interface WindowOps {

    /**
     * One top-level window.
     *
     * @param hwnd its handle
     * @param minimised whether it is iconic now
     */
    record TopWindow(long hwnd, boolean minimised) {
    }

    /**
     * The visible, unowned top-level windows of {@code pid}, minimised ones included, in
     * {@code EnumWindows} order: the Python CAT's {@code _top_level_windows}, which leaves the
     * minimised ones out (the caller filters on {@link TopWindow#minimised}). Empty when none, or
     * when Windows cannot be asked.
     */
    List<TopWindow> topLevelWindows(long pid);

    /** {@code ShowWindow(hwnd, SW_MINIMIZE)}. Best effort: a failure is ignored. */
    void minimise(long hwnd);
}
