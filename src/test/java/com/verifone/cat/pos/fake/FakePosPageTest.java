package com.verifone.cat.pos.fake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.verifone.cat.pos.Locator;
import com.verifone.cat.pos.LocatorTypes;
import com.verifone.cat.pos.Locators;
import com.verifone.cat.pos.receipt.DomReceiptCapture;
import com.verifone.cat.pos.receipt.Receipt;
import com.verifone.cat.pos.receipt.ReceiptCaptureException;
import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The fake POS's page, over HTTP: that every element ID on it comes from {@link Locators} (or is
 * one of the double's own chrome), that the expected elements are there, and that the receipt reads
 * back through {@link DomReceiptCapture}. Ported from the element-presence and receipt cases of
 * {@code tests/unit/test_fake_pos.py}.
 *
 * <p>Evidence about this repository only. The page is the Python's, served by this port.
 */
class FakePosPageTest {

    private static final Pattern ID_ATTRIBUTE = Pattern.compile("id=\"([^\"]+)\"");
    private static final Pattern SCRIPT_ID_LOOKUP = Pattern.compile("getElementById\\('([^']+)'\\)");

    private final HttpClient client = HttpClient.newHttpClient();
    private FakePos pos;

    @BeforeEach
    void open() throws IOException {
        pos = FakePos.open(FakePos.Hooks.NONE);
    }

    @AfterEach
    void close() {
        pos.close();
    }

    @Test
    void everyElementIdOnThePageComesFromTheCatalogueOrIsTheDoublesOwnChrome() throws Exception {
        String html = page();
        Set<String> literals = new HashSet<>();
        List<Pattern> families = new ArrayList<>();
        catalogueSpellings(literals, families);

        Matcher ids = ID_ATTRIBUTE.matcher(html);
        int checked = 0;
        while (ids.find()) {
            String id = ids.group(1);
            assertTrue(allowed(id, literals, families),
                    "element id not from pos.Locators (and not chrome): " + id);
            checked++;
        }
        assertTrue(checked > 30, "the page should carry the catalogue elements, found " + checked);
    }

    @Test
    void theScriptLooksUpOnlyCatalogueOrChromeIds() throws Exception {
        String html = page();
        Set<String> literals = new HashSet<>();
        List<Pattern> families = new ArrayList<>();
        catalogueSpellings(literals, families);

        Matcher lookups = SCRIPT_ID_LOOKUP.matcher(html);
        while (lookups.find()) {
            String id = lookups.group(1);
            assertTrue(allowed(id, literals, families), "script looks up a hand-written id: " + id);
        }
    }

    @Test
    void theExpressPanelItemPanelAndKeypadsAreRenderedWithCatalogueIds() throws Exception {
        String html = page();
        for (int slot = 0; slot < 10; slot++) {
            assertTrue(html.contains(Locators.EXPRESS_PANEL_VIEW_BUTTON_ID_N.at(slot).value()), "slot " + slot);
        }
        assertTrue(html.contains(Locators.EXPRESS_PANEL_VIEW_KEYPAD_ICON.value()));
        for (int plu : FakePosPage.SUITE_ITEM_PLUS) {
            assertTrue(html.contains(Locators.ITEM_PANEL_N.at(plu).value()), "item " + plu);
        }
        for (int mop : FakePosPage.MOP_BUTTON_IDS) {
            assertTrue(html.contains(Locators.ITEM_PANEL_N.at(mop).value()), "mop " + mop);
        }
        for (int digit = 0; digit < 10; digit++) {
            assertTrue(html.contains(Locators.VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_N.at(digit).value()));
            assertTrue(html.contains(Locators.VIRTUAL_KEYBOARD_VIEW_OVERLAY_NUMERIC_KEYPAD_KEY_N.at(digit).value()));
        }
        assertTrue(html.contains(Locators.VIRTUAL_KEYBOARD_VIEW_OVERLAY_NUMERIC_KEYPAD_KEY_OK.value()));
        assertTrue(html.contains(Locators.VIRTUAL_KEYBOARD_VIEW_OVERLAY_NUMERIC_KEYPAD_KEY_CANCEL.value()));
        assertTrue(html.contains(Locators.INPUT_OVERLAY_VIEW_LABEL1.value()));
        assertTrue(html.contains("id=\"" + Locators.RECEIPT_VIEW_ITEM_LINES_CONTAINER.value() + "\""));
    }

    @Test
    void theTotalButtonIsRenderedWithItsVisibleLabel() throws Exception {
        assertTrue(page().contains("<button onclick=\"handleTotal()\">Total</button>"));
    }

    @Test
    void anEmptyReceiptIsAnErrorNotAnEmptyReceipt() throws Exception {
        DomReceiptCapture capture = new DomReceiptCapture(this::domFamily, families());
        ReceiptCaptureException failure =
                assertThrows(ReceiptCaptureException.class, capture::capture);
        assertTrue(failure.getMessage().contains("no ReceiptView_* item lines"), failure.getMessage());
    }

    @Test
    void aSoldItemReadsBackThroughTheReceiptCapture() throws Exception {
        http("/api/select?item=9999");
        Receipt receipt = new DomReceiptCapture(this::domFamily, families()).capture();
        assertEquals(1, receipt.lines().size());
        assertTrue(receipt.lines().get(0).amount().contains("12.99"), receipt.lines().get(0).amount());
    }

    // -- helpers ---------------------------------------------------------------------

    private String page() throws IOException, InterruptedException {
        return http("/");
    }

    private String http(String path) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(pos.url().resolve(path)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode(), path);
        return response.body();
    }

    /** The one query the receipt capture makes, answered from the live page's own DOM. */
    private List<DomReceiptCapture.DomElement> domFamily(String prefix) {
        List<DomReceiptCapture.DomElement> out = new ArrayList<>();
        try {
            String html = http("/");
            Matcher element = Pattern.compile("<div id=\"([^\"]+)\">([^<]*)</div>").matcher(html);
            while (element.find()) {
                if (element.group(1).startsWith(prefix)) {
                    out.add(new DomReceiptCapture.DomElement(element.group(1), element.group(2)));
                }
            }
        } catch (IOException | InterruptedException failed) {
            throw new IllegalStateException(failed);
        }
        return out;
    }

    private static DomReceiptCapture.ItemFamilies families() {
        return new DomReceiptCapture.ItemFamilies(
                head(Locators.RECEIPT_VIEW_ITEM_LINE_DESCRIPTION_N),
                head(Locators.RECEIPT_VIEW_ITEM_LINE_AMOUNT_N),
                head(Locators.RECEIPT_VIEW_ITEM_LINE_QUANTITY_N),
                head(Locators.RECEIPT_VIEW_ITEM_LINE_N));
    }

    /** The literal prefix of an indexed family, up to its first placeholder. */
    private static String head(Locator family) {
        String value = family.value();
        return value.substring(0, value.indexOf('{'));
    }

    /** Every literal and template spelling of every ID-kind catalogue entry, by reflection. */
    private static void catalogueSpellings(Set<String> literals, List<Pattern> families)
            throws IllegalAccessException {
        for (Field field : Locators.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || !Locator.class.equals(field.getType())) {
                continue;
            }
            Locator locator = (Locator) field.get(null);
            if (locator.kind() != Locator.Kind.ID) {
                continue;
            }
            for (String spelling : locator.literals()) {
                if (spelling.indexOf('{') >= 0) {
                    families.add(LocatorTypes.templateRegex(spelling));
                } else {
                    literals.add(spelling);
                }
            }
        }
    }

    private static boolean allowed(String id, Set<String> literals, List<Pattern> families) {
        if (literals.contains(id) || FakePosPage.CHROME_IDS.contains(id)) {
            return true;
        }
        for (Pattern family : families) {
            if (family.matcher(id).matches()) {
                return true;
            }
        }
        return false;
    }
}
