package com.verifone.cat.pos.inspect;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Base64;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import com.microsoft.playwright.options.BoundingBox;

/**
 * The screencast against a real Chromium's DevTools endpoint on loopback ({@link LocalDevTools}): the first frame
 * arrives at once, a frame follows a change, and a click and keys land on the page. Skipped without Playwright's
 * Chromium. Evidence about this repository only, not about the register's browser.
 */
class PosScreencastBrowserTest {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    @Test
    void theScreenShowsAtOnceAndTakesClicksAndKeys() throws Exception {
        try (LocalDevTools chromium = LocalDevTools.openOrNull()) {
            Assumptions.assumeTrue(chromium != null,
                    "Chromium is not installed for Playwright; skipping. Install it with: " + LocalDevTools.INSTALL_COMMAND);
            BlockingQueue<PosScreencast.Frame> frames = new LinkedBlockingQueue<>();
            BlockingQueue<String> closed = new LinkedBlockingQueue<>();
            PosScreencast.Viewer viewer = new PosScreencast.Viewer() {
                @Override
                public void frame(PosScreencast.Frame frame) {
                    frames.add(frame);
                }

                @Override
                public void closed(String why) {
                    closed.add(why);
                }
            };

            try (PosScreencast cast = PosScreencast.start(chromium.cdpUrl(), TIMEOUT, viewer)) {
                assertEquals("screen test", cast.target().title());
                PosScreencast.Frame first = frames.poll();
                assertNotNull(first, "the first frame is there when start returns");
                byte[] jpeg = Base64.getDecoder().decode(first.jpegBase64());
                assertEquals((byte) 0xFF, jpeg[0]);
                assertEquals((byte) 0xD8, jpeg[1]);
                assertTrue(first.width() > 0 && first.height() > 0, first.toString());

                BoundingBox button = chromium.page().locator("#press").boundingBox();
                click(cast, button.x + button.width / 2, button.y + button.height / 2);
                chromium.page().waitForFunction("() => document.querySelector('#press').dataset.n === '1'");

                BoundingBox box = chromium.page().locator("#typed").boundingBox();
                click(cast, box.x + 10, box.y + box.height / 2);
                cast.key("keyDown", "a", "KeyA", "a", 65, 0);
                cast.key("keyUp", "a", "KeyA", "", 65, 0);
                cast.key("keyDown", "Enter", "Enter", "\r", 13, 0);
                cast.key("keyUp", "Enter", "Enter", "", 13, 0);
                chromium.page().waitForFunction("() => document.querySelector('#typed').value === 'a'");

                frames.clear();
                chromium.page().evaluate("() => { document.body.style.background = 'rgb(200, 30, 30)'; }");
                assertNotNull(frames.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS), "a change sends a frame");

                cast.mouse("notAMouseEvent", 0, 0, "left", 1, 1, 0); // ignored, not sent
                assertTrue(cast.isOpen());
            }
            assertTrue(closed.isEmpty(), "a screencast CAT closes does not report itself closed: " + closed);
        }
    }

    @Test
    void thePageGoingAwayClosesTheScreencastOnce() throws Exception {
        try (LocalDevTools chromium = LocalDevTools.openOrNull()) {
            Assumptions.assumeTrue(chromium != null,
                    "Chromium is not installed for Playwright; skipping. Install it with: " + LocalDevTools.INSTALL_COMMAND);
            BlockingQueue<String> closed = new LinkedBlockingQueue<>();
            PosScreencast.Viewer viewer = new PosScreencast.Viewer() {
                @Override
                public void frame(PosScreencast.Frame frame) {
                }

                @Override
                public void closed(String why) {
                    closed.add(why);
                }
            };
            try (PosScreencast cast = PosScreencast.start(chromium.cdpUrl(), TIMEOUT, viewer)) {
                chromium.page().close();

                assertNotNull(closed.poll(TIMEOUT.toSeconds(), TimeUnit.SECONDS), "the viewer is told");
                assertEquals(null, closed.poll(500, TimeUnit.MILLISECONDS), "and told once");
            }
        }
    }

    private static void click(PosScreencast cast, double x, double y) {
        cast.mouse("mousePressed", x, y, "left", 1, 1, 0);
        cast.mouse("mouseReleased", x, y, "left", 0, 1, 0);
    }
}
