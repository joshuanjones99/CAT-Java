package com.verifone.cat.desktop;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The icon keeper's look, against a fake window list: no native call is made. */
class WindowIconTest {

    private static final WindowIcon.Icons OURS = new WindowIcon.Icons(0x1001, 0x1002);
    private static final long BLURRED_BIG = 0x2001;
    private static final long BLURRED_SMALL = 0x2002;

    /** Windows with icons that change only when the keeper sets them; it records each set. */
    private static class FakeWindows implements WindowIcon.Windows {
        final List<WindowIcon.Window> windows = new ArrayList<>();
        final Map<String, Long> icons = new HashMap<>();
        final List<String> sets = new ArrayList<>();

        void add(long handle, String title, long big, long small) {
            windows.add(new WindowIcon.Window(handle, title));
            icons.put(handle + "/big", big);
            icons.put(handle + "/small", small);
        }

        @Override
        public List<WindowIcon.Window> browserWindows() {
            return windows;
        }

        @Override
        public long icon(long handle, boolean big) {
            return icons.getOrDefault(handle + (big ? "/big" : "/small"), 0L);
        }

        @Override
        public void setIcon(long handle, boolean big, long icon) {
            icons.put(handle + (big ? "/big" : "/small"), icon);
            sets.add(handle + (big ? " big" : " small"));
        }
    }

    @Test
    void aCatWindowWithTheBrowsersBlurredIconsGetsCatsOwn() {
        FakeWindows windows = new FakeWindows();
        windows.add(7, "Run · CAT-Java", BLURRED_BIG, BLURRED_SMALL);

        assertEquals(1, WindowIcon.keep(windows, OURS));

        assertEquals(List.of("7 big", "7 small"), windows.sets);
        assertEquals(OURS.big(), windows.icon(7, true));
        assertEquals(OURS.small(), windows.icon(7, false));
    }

    @Test
    void iconsAlreadySetAreLeftAloneAndOnlyTheOneTheBrowserPutBackIsSetAgain() {
        FakeWindows windows = new FakeWindows();
        windows.add(7, "Run · CAT-Java", OURS.big(), OURS.small());
        WindowIcon.keep(windows, OURS);
        assertEquals(List.of(), windows.sets);

        windows.icons.put("7/big", BLURRED_BIG);
        WindowIcon.keep(windows, OURS);

        assertEquals(List.of("7 big"), windows.sets);
    }

    @Test
    void otherBrowserWindowsAreNotCats() {
        FakeWindows windows = new FakeWindows();
        windows.add(1, "Run · CAT-Java - Work - Microsoft​ Edge", BLURRED_BIG, BLURRED_SMALL);
        windows.add(2, "Run · CAT - Google Chrome", BLURRED_BIG, BLURRED_SMALL);
        windows.add(3, "AdBlock is now installed! and 1 more page - Profile 1 - Microsoft​ Edge", 0, 0);
        windows.add(4, "127.0.0.1:8090/run", 0, 0);

        assertEquals(0, WindowIcon.keep(windows, OURS));
        assertEquals(List.of(), windows.sets);
    }

    @Test
    void theSuffixIsWhatTellsCatsWindow() {
        assertTrue(WindowIcon.isCatWindow("Setup · CAT-Java"));
        assertFalse(WindowIcon.isCatWindow("Setup · CAT"));
        assertFalse(WindowIcon.isCatWindow("Setup · CAT-Java - Microsoft Edge"));
    }

    @Test
    void aFailedLookOnlyWaitsForTheNext() {
        int[] looks = {0};
        WindowIcon.Windows failing = new FakeWindows() {
            @Override
            public List<WindowIcon.Window> browserWindows() {
                if (++looks[0] == 2) {
                    Thread.currentThread().interrupt();
                }
                throw new IllegalStateException("EnumWindows failed");
            }
        };

        WindowIcon.run(failing, OURS);

        assertEquals(2, looks[0]);
        assertTrue(Thread.interrupted(), "the keeper ends when its thread is interrupted");
    }
}
