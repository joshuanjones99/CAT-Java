package com.verifone.cat.recorder;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.Writer;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.PlaywrightException;
import com.verifone.cat.pos.Locators;

/**
 * The recorder against a real Chromium on a local page: Playwright's own Chromium is launched with a CDP port on
 * loopback, the recorder attaches to it from another thread exactly as it attaches to a register, and the test clicks a
 * catalogue button and an id-less "Total" button. Port of the Python's {@code test_recorder_e2e.py} (its fake-POS half).
 *
 * <p>Skipped (not failed) when Playwright's Chromium is not installed. A local page is not a register: evidence about
 * this repository's listener, binding and file only.
 */
class CaptureBrowserTest {

    @TempDir
    Path tmp;

    @Test
    void tapsOnALocalPageAreWrittenMappedAndTheFooterCountsThem() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = probe.getLocalPort();
        }
        String item = Locators.ITEM_PANEL_N.at(9999).literals().get(0);
        String page = "<html><body><button id=\"" + item + "\"><span>9999</span></button>"
                + "<button>Total</button></body></html>";
        try (Playwright playwright = Playwright.create()) {
            BrowserContext context;
            try {
                context = playwright.chromium().launchPersistentContext(tmp.resolve("profile"),
                        new BrowserType.LaunchPersistentContextOptions().setHeadless(true)
                                .setArgs(List.of("--remote-debugging-port=" + port,
                                        "--remote-debugging-address=127.0.0.1")));
            } catch (PlaywrightException e) {
                assumeTrue(false, "Playwright's Chromium is not installed: " + e.getMessage().lines().findFirst()
                        .orElse(""));
                return;
            }
            Page tab = context.pages().isEmpty() ? context.newPage() : context.pages().get(0);
            tab.setContent(page);

            Path out = tmp.resolve("rec.ndjson");
            AtomicBoolean stop = new AtomicBoolean();
            CountDownLatch watching = new CountDownLatch(1);
            AtomicInteger taps = new AtomicInteger(-1);
            AtomicReference<Throwable> failure = new AtomicReference<>();
            String cdp = "http://127.0.0.1:" + port;
            Thread recorder = new Thread(() -> {
                try (Writer stream = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
                    taps.set(Capture.record(cdp, stream, new Capture.Source("cdp", null, null), stop::get,
                            () -> false, 30_000, line -> {
                                if (line.startsWith("recording: watching")) {
                                    watching.countDown();
                                }
                            }));
                } catch (Throwable e) {
                    failure.set(e);
                    watching.countDown();
                }
            }, "recorder-under-test");
            recorder.start();
            assertTrue(watching.await(60, TimeUnit.SECONDS), "the recorder never started watching");
            assertEquals(null, failure.get());

            tab.locator(Locators.ITEM_PANEL_N.at(9999).selector()).click();
            tab.getByText("Total").click();
            stop.set(true);
            recorder.join(60_000);
            assertFalse(recorder.isAlive(), "the recorder did not stop");
            assertEquals(null, failure.get());
            context.close();

            Recording recording = Recording.read(out);
            assertEquals(2, taps.get());
            assertEquals("cdp", recording.sourceKind());
            assertNotNull(recording.footer());
            assertEquals("stop requested", recording.footer().get("reason"));
            Map<String, Object> first = Recording.map(recording.taps().get(0).get("map"));
            assertEquals("ITEM_PANEL_N", first.get("locator"));
            assertEquals(List.of("9999"), first.get("args"));
            Map<String, Object> second = Recording.map(recording.taps().get(1).get("map"));
            assertEquals("text", second.get("via"));
            assertEquals(List.of("pos.item_panel.select(9999)", "pos.item_panel.total()"),
                    Collapse.collapse(recording).stream().map(Collapse.Step::code).toList());
            assertFalse(Files.readString(out).contains("127.0.0.1"), "no address is written");
        }
    }
}
