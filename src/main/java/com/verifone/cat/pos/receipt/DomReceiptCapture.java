package com.verifone.cat.pos.receipt;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads the on-screen receipt through the {@code ReceiptView_*} item-line families. Ported from
 * the Python {@code petrotest.pos.receipt.DomReceiptCapture} (DESIGN.md 5.3).
 *
 * <p>Each family is read with <b>one</b> bulk query on an {@code [id^="..."]} prefix, not by probing
 * index 0, 1, 2 until one is missing: a miss costs a full timeout (DESIGN.md 4.2 rule 2).
 *
 * <p>Header, total, MOP and footer are deliberately left empty. DESIGN.md 14 Q3 is open, and
 * inventing selectors for them would turn an open question into a silent wrong answer.
 *
 * <p>TODO(locator catalogue): the four {@link ItemFamilies} prefixes are the Python
 * {@code locators.RECEIPT_VIEW_ITEM_LINE_*_N} prefixes. They are supplied by the caller until the
 * catalogue lands in {@code com.verifone.cat.pos}.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The locators are proven by corpus references, but
 * this read strategy has not been run against a live Topaz.
 */
public final class DomReceiptCapture implements ReceiptSource {

    /** Trailing element-ID index, e.g. {@code ReceiptView_ItemLineDescription_12} gives 12. */
    private static final Pattern TRAILING_INDEX = Pattern.compile("_(\\d+)$");

    private final DomDriver driver;
    private final ItemFamilies families;
    private final boolean requireItems;

    /** The Python default: a receipt with no item lines at all is an error. */
    public DomReceiptCapture(DomDriver driver, ItemFamilies families) {
        this(driver, families, true);
    }

    public DomReceiptCapture(DomDriver driver, ItemFamilies families, boolean requireItems) {
        this.driver = driver;
        this.families = families;
        this.requireItems = requireItems;
    }

    /** The four item-line families, as ID prefixes. */
    public record ItemFamilies(String description, String amount, String quantity, String wholeLine) {}

    /** One element as the DOM reports it: its ID and its text content. */
    public record DomElement(String id, String text) {}

    /** The one query the capture makes. The browser implementation lives with the page objects. */
    public interface DomDriver {

        /** Every element whose ID starts with {@code idPrefix}, in one query. */
        List<DomElement> findFamily(String idPrefix);
    }

    @Override
    public Receipt capture() {
        Map<Integer, String> descriptions = readFamily(families.description());
        Map<Integer, String> amounts = readFamily(families.amount());
        Map<Integer, String> quantities = readFamily(families.quantity());
        Map<Integer, String> whole = readFamily(families.wholeLine());

        TreeSet<Integer> indices = new TreeSet<>();
        indices.addAll(descriptions.keySet());
        indices.addAll(amounts.keySet());
        indices.addAll(quantities.keySet());
        indices.addAll(whole.keySet());
        if (requireItems && indices.isEmpty()) {
            throw new ReceiptCaptureException(
                    "no ReceiptView_* item lines in the DOM. Either no sale is on screen, or "
                            + "the on-screen receipt is not rendered on this build (DESIGN.md 5.3).");
        }
        List<ReceiptLine> lines = new ArrayList<>();
        for (int i : indices) {
            lines.add(new ReceiptLine(
                    i,
                    descriptions.getOrDefault(i, ""),
                    amounts.getOrDefault(i, ""),
                    quantities.getOrDefault(i, ""),
                    whole.getOrDefault(i, "")));
        }
        return Receipt.ofLines(lines, Source.DOM);
    }

    /** All members of one indexed family, keyed by row index. */
    private Map<Integer, String> readFamily(String prefix) {
        Map<Integer, String> out = new LinkedHashMap<>();
        for (DomElement element : driver.findFamily(prefix)) {
            String id = element.id() == null ? "" : element.id();
            Matcher match = TRAILING_INDEX.matcher(id);
            if (!match.find()) {
                continue;
            }
            out.put(Integer.parseInt(match.group(1)), ReceiptText.normalise(
                    element.text() == null ? "" : element.text()));
        }
        return new TreeMap<>(out);
    }
}
