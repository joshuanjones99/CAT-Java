package com.verifone.cat.pos.fake;

import com.verifone.cat.pos.Locator;
import com.verifone.cat.pos.Locators;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The fake POS's one page, rendered from {@link Locators}. Ported from the Python
 * {@code _HTML_TEMPLATE}, {@code _serve_html} and {@code _generate_receipt_lines}.
 *
 * <p>Every POS element ID on the page is read from the generated catalogue (for example
 * {@code Locators.EXPRESS_PANEL_VIEW_BUTTON_ID_N.at(7)}), including the ones the page's JavaScript
 * looks up. The only IDs written here are the four in {@link #CHROME_IDS}: the double's own
 * overlays and status lines, which the real POS never renders.
 *
 * <p>The document is one page with no frames, as the Python's is. The corpus's frame hints on the
 * locators are not rendered.
 *
 * <p>The item prices in {@link #DOUBLE_ITEM_PRICES} are test data, not POS data. A migrated case
 * should fail on receipt content under the fake POS, because only a rig can prove the content.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). This is evidence about the repository.
 */
public final class FakePosPage {

    /**
     * Item PLUs the fake POS renders a button for. It serves a static DOM, so a test that picks any
     * other item finds no button. The Python's list, from the Charity-Donations-Indoor corpus.
     */
    public static final List<Integer> SUITE_ITEM_PLUS = List.of(
            9999, 9998, 9123, 7001, 7002, 9112, 8001, 8002, 32232211);

    /** The MOP buttons, as item ids. */
    public static final List<Integer> MOP_BUTTON_IDS = List.of(145397, 145395, 145394, 145398);

    /** Prices the fake POS prints. Test data only; see the class Javadoc. */
    public static final Map<Integer, String> DOUBLE_ITEM_PRICES = Map.ofEntries(
            Map.entry(9999, "12.99"),
            Map.entry(9998, "9.99"),
            Map.entry(9123, "4.25"),
            Map.entry(7001, "8.50"),
            Map.entry(7002, "6.50"),
            Map.entry(9112, "7.15"),
            Map.entry(8001, "7.50"),
            Map.entry(8002, "6.75"),
            Map.entry(32232211, "9.50"),
            Map.entry(145397, "5.99"),
            Map.entry(145395, "3.50"),
            Map.entry(145394, "0.00"),
            Map.entry(145398, "0.00"));

    /** The double's own chrome. The real POS renders none of these; they are not catalogue IDs. */
    public static final String STATE_ID = "PosDouble_State";
    public static final String SELECTED_ITEMS_ID = "PosDouble_SelectedItems";
    public static final String QUANTITY_OVERLAY_ID = "PosDouble_QuantityOverlay";
    public static final String VIRTUAL_KEYBOARD_ID = "PosDouble_VirtualKeyboard";

    /** Every ID in the chrome above. */
    public static final Set<String> CHROME_IDS =
            Set.of(STATE_ID, SELECTED_ITEMS_ID, QUANTITY_OVERLAY_ID, VIRTUAL_KEYBOARD_ID);

    private static final int CASH_MOP = 145394;
    private static final int CSR_SLOT = 7;
    /** Item ids whose MOPs never get a receipt line. CREDIT is not in this list, as in the Python. */
    private static final Set<Integer> NO_RECEIPT_LINE = Set.of(145397, 145394, 145398);

    private static final Set<Integer> MOP_ITEMS = Set.of(145397, 145394, 145395, 145398);

    private FakePosPage() {}

    /** The page as a client sees it at {@code snapshot}. */
    public static String render(FakePosState.Snapshot snapshot) {
        String csrId = Locators.EXPRESS_PANEL_VIEW_BUTTON_ID_N.at(CSR_SLOT).value();
        String receiptLines = receiptHtml(snapshot.selectedItems());
        return TEMPLATE
                .replace("@@EXPRESS_BUTTONS@@", expressButtons(snapshot))
                .replace("@@ITEM_BUTTONS@@", itemButtons())
                .replace("@@QTY_KEYPAD@@", qtyKeypad())
                .replace("@@VK_KEYPAD@@", virtualKeypad())
                .replace("@@RECEIPT_LINES@@", receiptLines)
                .replace("@@STATE_ID@@", STATE_ID)
                .replace("@@ITEMS_ID@@", SELECTED_ITEMS_ID)
                .replace("@@QTY_OVERLAY_ID@@", QUANTITY_OVERLAY_ID)
                .replace("@@VK_OVERLAY_ID@@", VIRTUAL_KEYBOARD_ID)
                .replace("@@QTY_LABEL_ID@@", Locators.INPUT_OVERLAY_VIEW_LABEL1.value())
                .replace("@@QTY_OK_ID@@", Locators.VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_OK.value())
                .replace("@@VK_OK_ID@@", Locators.VIRTUAL_KEYBOARD_VIEW_OVERLAY_NUMERIC_KEYPAD_KEY_OK.value())
                .replace("@@VK_CANCEL_ID@@",
                        Locators.VIRTUAL_KEYBOARD_VIEW_OVERLAY_NUMERIC_KEYPAD_KEY_CANCEL.value())
                .replace("@@CSR_ID@@", csrId)
                .replace("@@RECEIPT_CONTAINER_ID@@", Locators.RECEIPT_VIEW_ITEM_LINES_CONTAINER.value())
                .replace("@@CASH_MOP@@", Integer.toString(CASH_MOP));
    }

    /** The receipt item lines for the sale, as the page's receipt container holds them. */
    public static String receiptHtml(List<Integer> selectedItems) {
        StringBuilder html = new StringBuilder();
        int lineIndex = 0;
        for (int itemId : selectedItems) {
            String price = DOUBLE_ITEM_PRICES.getOrDefault(itemId, "0.00");
            if (NO_RECEIPT_LINE.contains(itemId)) {
                continue;
            }
            String descId = Locators.RECEIPT_VIEW_ITEM_LINE_DESCRIPTION_N.at(lineIndex).value();
            String amountId = Locators.RECEIPT_VIEW_ITEM_LINE_AMOUNT_N.at(lineIndex).value();
            html.append("<div id=\"").append(descId).append("\">Item ").append(itemId).append("</div>\n");
            html.append("<div id=\"").append(amountId).append("\">$").append(price).append("</div>\n");
            lineIndex++;
        }
        return html.toString();
    }

    /** The express panel: slots 0 to 9, slot 7 dynamic, and the keypad icon. */
    private static String expressButtons(FakePosState.Snapshot snapshot) {
        StringBuilder html = new StringBuilder();
        for (int slot = 0; slot < 10; slot++) {
            String id = Locators.EXPRESS_PANEL_VIEW_BUTTON_ID_N.at(slot).value();
            if (slot == CSR_SLOT) {
                String text = snapshot.transactionActive() ? "Error Correct" : "CSR Func";
                html.append("<button id=\"").append(id).append("\" data-dynamic=\"true\">")
                        .append(text).append("</button>\n");
            } else {
                html.append("<button id=\"").append(id).append("\">Slot ").append(slot)
                        .append("</button>\n");
            }
        }
        html.append("<button id=\"").append(Locators.EXPRESS_PANEL_VIEW_KEYPAD_ICON.value())
                .append("\" onclick=\"openVirtualKeyboard()\">Keypad</button>\n");
        return html.toString();
    }

    /** The item panel: the suite's PLUs, then the MOPs, which route to the MOP handler. */
    private static String itemButtons() {
        StringBuilder html = new StringBuilder();
        List<Integer> all = new ArrayList<>(SUITE_ITEM_PLUS);
        all.addAll(MOP_BUTTON_IDS);
        for (int itemId : all) {
            String id = Locators.ITEM_PANEL_N.at(itemId).value();
            String handler = MOP_ITEMS.contains(itemId)
                    ? "handleMopSelect(" + itemId + ")"
                    : "handleItemSelect(" + itemId + ")";
            html.append("<button id=\"").append(id).append("\" onclick=\"").append(handler)
                    .append("\">Item ").append(itemId).append("</button>\n");
        }
        return html.toString();
    }

    private static String qtyKeypad() {
        return digitButtons(Locators.VFI_KEYPAD_WIDGET_OVERLAY_NUMERIC_KEYPAD_KEY_N, "handleQtyDigit");
    }

    private static String virtualKeypad() {
        return digitButtons(
                Locators.VIRTUAL_KEYBOARD_VIEW_OVERLAY_NUMERIC_KEYPAD_KEY_N, "handleVkDigit");
    }

    private static String digitButtons(Locator family, String handler) {
        StringBuilder html = new StringBuilder();
        for (int digit = 0; digit < 10; digit++) {
            html.append("<button id=\"").append(family.at(digit).value()).append("\" onclick=\"")
                    .append(handler).append('(').append(digit).append(")\">").append(digit)
                    .append("</button>\n");
        }
        return html.toString();
    }

    /**
     * The page. Tokens are {@code @@NAME@@}, filled by {@link #render}. The script is the Python's,
     * with the two hand-written IDs it used replaced by tokens.
     */
    static final String TEMPLATE = """
            <!DOCTYPE html>
            <html>
            <head>
                <meta charset="UTF-8">
                <title>Fake POS</title>
                <style>
                    body { font-family: monospace; margin: 20px; background: #fff; }
                    button { padding: 10px; margin: 5px; font-size: 16px; cursor: pointer; }
                    .panel { border: 1px solid #ccc; padding: 10px; margin: 10px 0; }
                    .state { background: #f0f0f0; padding: 10px; margin: 10px 0; }
                </style>
            </head>
            <body>
                <h1>Fake POS</h1>
                <div class="state">
                    <p>State: <span id="@@STATE_ID@@">IDLE</span></p>
                    <p>Selected items: <span id="@@ITEMS_ID@@">none</span></p>
                </div>

                <div class="panel">
                    <h2>Express Panel</h2>
                    @@EXPRESS_BUTTONS@@
                </div>

                <div class="panel">
                    <h2>Item Panel</h2>
                    @@ITEM_BUTTONS@@
                    <button onclick="handleTotal()">Total</button>
                    <button onclick="handleQuantity()">Quantity</button>
                </div>

                <div class="panel" id="@@QTY_OVERLAY_ID@@" style="display:none;">
                    <p id="@@QTY_LABEL_ID@@">ENTER QTY</p>
                    @@QTY_KEYPAD@@
                    <button id="@@QTY_OK_ID@@" onclick="handleQtyOk()">OK</button>
                </div>

                <div class="panel" id="@@VK_OVERLAY_ID@@" style="display:none;">
                    <p>Numeric Keypad</p>
                    @@VK_KEYPAD@@
                    <button id="@@VK_OK_ID@@" onclick="handleVkOk()">OK</button>
                    <button id="@@VK_CANCEL_ID@@" onclick="handleVkCancel()">CANCEL</button>
                </div>

                <div class="panel" id="@@RECEIPT_CONTAINER_ID@@">
                    <h2>Receipt</h2>
                    @@RECEIPT_LINES@@
                </div>

                <script>
                    const stateId = '@@STATE_ID@@';
                    const itemsId = '@@ITEMS_ID@@';
                    const qtyOverlayId = '@@QTY_OVERLAY_ID@@';
                    const vkOverlayId = '@@VK_OVERLAY_ID@@';
                    const csrId = '@@CSR_ID@@';
                    const receiptContainerId = '@@RECEIPT_CONTAINER_ID@@';
                    let qtyBuffer = '';
                    let vkBuffer = '';

                    function openVirtualKeyboard() {
                        vkBuffer = '';
                        document.getElementById(vkOverlayId).style.display = 'block';
                    }

                    function handleVkDigit(digit) {
                        vkBuffer += digit;
                    }

                    // OK rings the keyed PLU up and leaves the pad open: the lab build leaves
                    // a second, empty copy of it up after OK (HANDOFF.md 1.29), which is
                    // why `Pos.key_plu` cancels it.
                    function handleVkOk() {
                        const plu = vkBuffer;
                        vkBuffer = '';
                        if (plu) {
                            handleItemSelect(Number(plu));
                        }
                    }

                    function handleVkCancel() {
                        vkBuffer = '';
                        document.getElementById(vkOverlayId).style.display = 'none';
                    }

                    // CASH asks for the tendered amount on the VFI keypad (HANDOFF.md 1.34).
                    function openAmountOverlay() {
                        qtyBuffer = '';
                        document.getElementById('@@QTY_LABEL_ID@@').textContent = 'Enter Amount';
                        document.getElementById(qtyOverlayId).style.display = 'block';
                    }

                    function handleQuantity() {
                        qtyBuffer = '';
                        document.getElementById(qtyOverlayId).style.display = 'block';
                    }

                    function handleQtyDigit(digit) {
                        qtyBuffer += digit;
                    }

                    function handleQtyOk() {
                        document.getElementById(qtyOverlayId).style.display = 'none';
                    }

                    function updateReceiptLines(receiptHtml) {
                        const container = document.getElementById(receiptContainerId);
                        if (container) {
                            container.innerHTML = '<h2>Receipt</h2>' + receiptHtml;
                        }
                    }

                    function updateCsrFunctionText() {
                        const csr = document.getElementById(csrId);
                        if (csr) {
                            const isDynamic = csr.getAttribute('data-dynamic');
                            if (isDynamic) {
                                fetch('/api/state').then(r => r.json()).then(state => {
                                    const text = state.is_transaction_active
                                        ? 'Error Correct' : 'CSR Func';
                                    csr.textContent = text;
                                });
                            }
                        }
                    }

                    function handleItemSelect(itemId) {
                        fetch('/api/select?item=' + itemId).then(r => r.json()).then(state => {
                            const itemsEl = document.getElementById(itemsId);
                            itemsEl.textContent = 'Item ' + itemId;
                            updateCsrFunctionText();
                            updateReceiptLines(state.receipt_html);
                        });
                    }

                    function handleMopSelect(mopId) {
                        if (mopId === @@CASH_MOP@@) {
                            openAmountOverlay();
                        }
                        fetch('/api/mop?mop=' + mopId).then(r => r.json()).then(state => {
                            const stateEl = document.getElementById(stateId);
                            stateEl.textContent = state.is_transaction_active ? 'TRANSACTION_ACTIVE' : 'IDLE';
                            updateCsrFunctionText();
                            updateReceiptLines(state.receipt_html);
                        });
                    }

                    function handleTotal() {
                        fetch('/api/total').then(r => r.json()).then(state => {
                            updateReceiptLines(state.receipt_html);
                        });
                    }

                    // Update state display on page load
                    window.addEventListener('load', () => {
                        fetch('/api/state').then(r => r.json()).then(state => {
                            const stateEl = document.getElementById(stateId);
                            stateEl.textContent = state.is_transaction_active ? 'TRANSACTION_ACTIVE' : 'IDLE';
                            updateReceiptLines(state.receipt_html);
                        });
                    });
                </script>
            </body>
            </html>
            """;
}
