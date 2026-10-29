package com.verifone.cat.pos.pages;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import com.microsoft.playwright.Page;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.driver.PosDriver;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The {@link Pos} methods that read the sale's markup, and its TLog routing, in headless Chromium. Ported
 * from the snapshot and TLog cases of the Python suite (test_pos_settle_due_fuel's sale checks, the newest-
 * TLog wait). Evidence about this repository only.
 */
class PosPagesTest {

    private static PagesBrowser browsers;

    @BeforeAll
    static void openBrowser() {
        browsers = PagesBrowser.open();
    }

    @AfterAll
    static void closeBrowser() {
        browsers.close();
    }

    @BeforeEach
    void needsChromium() {
        Assumptions.assumeTrue(browsers.available(), PagesBrowser.SKIP_REASON);
    }

    @Test
    void aFuelLineWithAVolumeIsAHoldingSale() {
        try (Page page = browsers.newPage()) {
            page.setContent(sale("PETR-UNLD1 CA #03", "27.788"));
            assertTrue(new Pos(PagesBrowser.driverOn(page)).holdsDispensedFuel());
        }
    }

    @Test
    void aPumpLineWithoutAVolumeIsNotTakenForAFill() {
        try (Page page = browsers.newPage()) {
            page.setContent(sale("PETR-UNLD1 CA #03", "1"));
            assertFalse(new Pos(PagesBrowser.driverOn(page)).holdsDispensedFuel());
        }
    }

    @Test
    void merchandiseIsNotAFillEvenWithAQuantity() {
        try (Page page = browsers.newPage()) {
            page.setContent(sale("COKE", "2"));
            assertFalse(new Pos(PagesBrowser.driverOn(page)).holdsDispensedFuel());
        }
    }

    @Test
    void aPayLineMeansAPartialTender() {
        try (Page page = browsers.newPage()) {
            page.setContent("<html><body><div id=\""
                    + Locators.ODSP_INFO_VIEW_AMOUNT_DESC_PAY_LINE_1.value()
                    + "\">FLEET CHECK 50.00</div></body></html>");
            Pos pos = new Pos(PagesBrowser.driverOn(page));

            assertTrue(pos.holdsTender());
        }
    }

    @Test
    void noPayLineMeansNoTender() {
        try (Page page = browsers.newPage()) {
            page.setContent("<html><body></body></html>");
            assertFalse(new Pos(PagesBrowser.driverOn(page)).holdsTender());
        }
    }

    @Test
    void tlogRefusesWhenNoSourceIsWired() {
        try (Page page = browsers.newPage()) {
            page.setContent("<html><body></body></html>");
            Pos pos = new Pos(PagesBrowser.driverOn(page));

            assertThrows(UnsupportedOperationException.class, pos::tlog);
        }
    }

    @Test
    void newestTlogReadsTheNewestWhenNothingIsAwaited() {
        try (Page page = browsers.newPage()) {
            page.setContent("<html><body></body></html>");
            RecordingSource source = new RecordingSource();
            Pos pos = new Pos(PagesBrowser.driverOn(page), null, source, () -> List.of("101", "100"));

            Object tlog = pos.newestTlog();

            assertEquals("tlog:null", tlog);
            assertNull(pos.newestRead());
        }
    }

    @Test
    void newestTlogWaitsForANewerTransactionAndReadsItsNumber() {
        try (Page page = browsers.newPage()) {
            page.setContent("<html><body></body></html>");
            RecordingSource source = new RecordingSource();
            Pos pos = new Pos(PagesBrowser.driverOn(page), null, source, () -> List.of("101", "100"));

            Object tlog = pos.newestTlog("100", Duration.ofSeconds(2), null);

            assertEquals("tlog:101", tlog);
            assertEquals("101", pos.newestRead());
        }
    }

    @Test
    void newestTlogFallsBackToTheNewestWhenNoNewerCame() {
        try (Page page = browsers.newPage()) {
            page.setContent("<html><body></body></html>");
            RecordingSource source = new RecordingSource();
            Pos pos = new Pos(PagesBrowser.driverOn(page), null, source, () -> List.of("100"));

            Object tlog = pos.newestTlog("100", Duration.ofMillis(300), null);

            assertEquals("tlog:null", tlog);
            assertNull(pos.newestRead());
        }
    }

    @Test
    void newestTlogStopsWaitingWhenTheAbortGivesAReasonAndReadsTheNewest() {
        // test_run_time_levers.py::test_a_tlog_wait_held_by_an_ok_only_message_ends_early: fetched == [None].
        try (Page page = browsers.newPage()) {
            page.setContent("<html><body></body></html>");
            RecordingSource source = new RecordingSource();
            Pos pos = new Pos(PagesBrowser.driverOn(page), null, source, () -> List.of("100"));

            long started = System.nanoTime();
            Object tlog = pos.newestTlog("100", Duration.ofSeconds(5), () -> "the POS has shown [OK] for 20s");

            assertTrue(Duration.ofNanos(System.nanoTime() - started).compareTo(Duration.ofSeconds(2)) < 0);
            assertEquals("tlog:null", tlog);
            assertEquals(java.util.Arrays.asList((String) null), source.asked);
        }
    }

    /** A TLog source that records what it was asked for. */
    private static final class RecordingSource implements Pos.TlogSource {
        final List<String> asked = new ArrayList<>();

        @Override
        public Object fetch(String transactionNumber) {
            asked.add(transactionNumber);
            return "tlog:" + transactionNumber;
        }
    }

    /** A sale whose first line is {@code description} with {@code quantity}, as the receipt view renders it. */
    private static String sale(String description, String quantity) {
        return "<html><body><div id=\"" + Locators.RECEIPT_VIEW_ITEM_LINE_DESCRIPTION_N.at(0).value()
                + "\">" + description + "</div><div id=\""
                + Locators.RECEIPT_VIEW_ITEM_LINE_QUANTITY_N.at(0).value() + "\">" + quantity
                + "</div></body></html>";
    }
}
