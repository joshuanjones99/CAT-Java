package com.verifone.petrotest.posshim;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.verifone.isd.pcapps.automation.target.pos.topaz.ent.TokenInfoHolder;
import com.verifone.isd.topaz.sys.tokenRMI.gui.ExpressPanelInfo;
import com.verifone.isd.topaz.sys.tokenRMI.gui.FuelPanelInfo;
import com.verifone.isd.topaz.sys.tokenRMI.gui.ODSPPanelInfo;
import com.verifone.isd.topaz.sys.tokenRMI.gui.OverlayMsgInfo;
import com.verifone.isd.topaz.sys.tokenRMI.gui.OverlayReviewMsgInfo;
import com.verifone.isd.topaz.sys.tokenRMI.gui.ReceiptPanelInfo;
import com.verifone.isd.topaz.sys.tokenRMI.gui.SimpleCalculatorInfo;

/**
 * Turns one {@link TokenInfoHolder} into the JSON the shim serves.
 *
 * <h2>What this surface is, and what it is not</h2>
 *
 * The retained agent (§5.2) is a <em>push</em> surface, not a request/response
 * one: {@code ObserverImpl} inside the Topaz GUI JVM observes panel widgets and
 * calls {@code IPOSStatusMonitor.update*Event(...)} on a remote object this shim
 * exports, which files the payload into a {@code TokenInfoHolder}. So everything
 * readable here is <strong>the last screen state the POS pushed</strong> —
 * overlays, soft-key labels, ticket totals, pump state.
 *
 * <p>That has a consequence worth stating plainly, because the route table this
 * shim replaces guessed otherwise: several things `preflight` wants are simply
 * not on this surface. There is no printer port status, no cash drawer position,
 * no suspended-ticket list and no build version in any panel object the observer
 * sends. Those routes answer 501 (see {@link Routes}) rather than inventing a
 * value, because §6.2 draws the distinction that matters — a check that reports
 * ERROR is a probe that could not run, and a check that reports PASS on a
 * fabricated {@code false} is a lie that costs somebody a debugging session.
 *
 * <h2>Absent state versus quiet state</h2>
 *
 * Every field starts null and is only written when the POS pushes. So "no
 * overlay object" means either "the POS says there is no dialog" or "the POS has
 * never told us anything". Those are different claims and this class refuses to
 * collapse them: {@link #statePresent()} is false until at least one field has
 * been observed, and the routes answer 503 in that window instead of reporting a
 * confident idle screen. Getting this wrong is the failure mode where preflight
 * goes green against a POS that was never listening.
 */
final class Snapshot {

    private final TokenInfoHolder holder;

    Snapshot(TokenInfoHolder holder) {
        this.holder = holder;
    }

    /**
     * Whether the POS has pushed anything at all for this client.
     *
     * <p>Deliberately checks the panel objects rather than the Lex event fields:
     * a keystroke this shim itself injected sets {@code kbdLexDataEvent}, so
     * including those would let the shim mistake its own echo for evidence that
     * the observer is alive.
     */
    boolean statePresent() {
        return holder.getOverlayMsgInfo() != null
                || holder.getOverlayReviewMsgInfo() != null
                || holder.getExpressPanelInfo() != null
                || holder.getReceiptPanelInfo() != null
                || holder.getOdspPanelInfo() != null
                || holder.getFuelPanelInfo() != null
                || holder.getFuelDetailViewInfo() != null
                || holder.getSimpleCalculatorInfo() != null;
    }

    /** True when the POS is showing a modal overlay that carries text. */
    boolean overlayPresent() {
        OverlayMsgInfo overlay = holder.getOverlayMsgInfo();
        if (overlay == null) {
            return false;
        }
        // The POS pushes an OverlayMsgInfo with every field blanked when a
        // dialog is dismissed rather than pushing null, so presence of the
        // object is not presence of a dialog — presence of text is.
        return hasText(overlay.getTitle())
                || hasText(overlay.getLine1())
                || hasText(overlay.getLine2())
                || hasText(overlay.getLine3());
    }

    /**
     * Whether a ticket is open, as far as the ODSP totals panel shows.
     *
     * <p>Note this is read off the totals panel and not off the receipt panel.
     * {@link ReceiptPanelInfo}'s constructor assigns {@code setItemLine(itemLine)}
     * — its own null field — instead of the {@code item} parameter it was given,
     * so an item line pushed through that constructor arrives null and a
     * line-count test would read every ticket as empty. That is an upstream bug
     * in petroapps, not something this shim can fix from outside; the totals
     * panel carries the same information without depending on it.
     */
    boolean transactionActive() {
        ODSPPanelInfo odsp = holder.getOdspPanelInfo();
        if (odsp == null) {
            return false;
        }
        return odsp.getSubTotal() != 0.0d
                || odsp.getTotalDue() != 0.0d
                || odsp.getBalDue() != 0.0d
                || odsp.getTotalPaid() != 0.0d;
    }

    /**
     * The §6.3 testability predicate: is the POS ready to take a new transaction?
     *
     * <p>Reported together with {@link #idleBasis()} rather than as a bare
     * boolean, because "idle" here is a conclusion drawn from two facts and the
     * caller deserves to see which one moved. A rung that reports "not idle"
     * without saying whether it was blocked by an overlay or by an open ticket
     * sends whoever reads the log to the wrong place.
     */
    boolean idle() {
        return !overlayPresent() && !transactionActive();
    }

    String idleBasis() {
        if (overlayPresent()) {
            return "overlay-present";
        }
        if (transactionActive()) {
            return "transaction-active";
        }
        return "no-overlay-no-open-ticket";
    }

    /** The full snapshot, as served by {@code GET /pos/screen}. */
    Map<String, Object> toMap() {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("client", holder.getClientHost());
        out.put("state_present", statePresent());
        out.put("idle", idle());
        out.put("idle_basis", idleBasis());
        out.put("overlay_present", overlayPresent());
        out.put("transaction_active", transactionActive());
        out.put("overlay", overlay());
        out.put("overlay_review", overlayReview());
        out.put("express_panel", expressPanel());
        out.put("receipt", receipt());
        out.put("totals", totals());
        out.put("fuel_panel", fuelPanel());
        out.put("calculator", calculator());
        out.put("token_processing_done", holder.isTokenProcessingDone());
        out.put("period_close_done", holder.isPeriodCloseDone());
        return out;
    }

    Map<String, Object> overlay() {
        OverlayMsgInfo overlay = holder.getOverlayMsgInfo();
        if (overlay == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", overlay.getTitle());
        out.put("line1", overlay.getLine1());
        out.put("line2", overlay.getLine2());
        out.put("line3", overlay.getLine3());
        // Which buttons the dialog offers is what decides whether the cancel
        // ladder can clear it at all: a CANCEL-only prompt needs a different
        // key than an OK-only acknowledgement (§6.3 rung 1).
        out.put("ok_only", overlay.isOkonly());
        out.put("cancel_only", overlay.isCancelonly());
        out.put("ok_and_cancel", overlay.isOkandcancel());
        out.put("edit_box", overlay.isEditBox());
        out.put("auto_hide", overlay.isAutoHide());
        out.put("has_text", overlayPresent());
        return out;
    }

    List<String> overlayReview() {
        OverlayReviewMsgInfo review = holder.getOverlayReviewMsgInfo();
        if (review == null || review.getTabData() == null) {
            return null;
        }
        List<String> lines = new ArrayList<>();
        for (String line : review.getTabData()) {
            lines.add(line);
        }
        return lines;
    }

    /**
     * The soft-key buttons currently on screen.
     *
     * <p>This is the most useful thing on the whole surface for a test author:
     * the labels are what an operator would press, so a suite can assert on the
     * screen it reached without scraping pixels or guessing at CDP frames.
     */
    List<Map<String, Object>> expressPanel() {
        ExpressPanelInfo[] buttons = holder.getExpressPanelInfo();
        if (buttons == null) {
            return null;
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (ExpressPanelInfo button : buttons) {
            if (button == null) {
                continue;
            }
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("label", button.getButtonLabel());
            entry.put("return_value", button.getReturnValue());
            entry.put("disabled", button.isDisabled());
            entry.put("selected", button.isSelected());
            entry.put("button_type", button.getButtonType());
            entry.put("image", button.getImageFile());
            out.add(entry);
        }
        return out;
    }

    Map<String, Object> receipt() {
        ReceiptPanelInfo receipt = holder.getReceiptPanelInfo();
        if (receipt == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("action", receipt.getAction());
        // Always expected to be null on the wire — see transactionActive() for
        // the upstream constructor bug. Reported anyway, so that the day it is
        // fixed upstream the field starts carrying data without a shim change.
        Object itemLine = receipt.getItemLine();
        out.put("item_line", itemLine == null ? null : String.valueOf(itemLine));
        out.put("item_line_available", itemLine != null);
        return out;
    }

    Map<String, Object> totals() {
        ODSPPanelInfo odsp = holder.getOdspPanelInfo();
        if (odsp == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("sub_total", odsp.getSubTotal());
        out.put("tax", odsp.getTax());
        out.put("total_due", odsp.getTotalDue());
        out.put("rounded_cash_due", odsp.getRoundedCashDue());
        out.put("bal_paid", odsp.getBalPaid());
        out.put("bal_due", odsp.getBalDue());
        out.put("change", odsp.getChange());
        out.put("total_paid", odsp.getTotalPaid());
        out.put("total_fs", odsp.getTotalFS());
        out.put("disc_before_tax", odsp.getDiscBeforeTax());
        out.put("disc_after_tax", odsp.getDiscAfterTax());
        out.put("tip", optionalGetter(odsp, "getTip"));
        return out;
    }

    /**
     * Calls a no-arg getter that only some Topaz builds have, by reflection, so
     * the shim compiles against all of them. `ODSPPanelInfo.getTip()` is the
     * case: the register build of 2026-07-01 that topaz101 runs has no such
     * method, and a direct call stopped the shim compiling at all. Null when
     * the build lacks the method or the call throws.
     */
    private static Object optionalGetter(Object target, String name) {
        try {
            return target.getClass().getMethod(name).invoke(target);
        } catch (ReflectiveOperationException e) {
            return null;
        }
    }

    Map<String, Object> fuelPanel() {
        FuelPanelInfo fuel = holder.getFuelPanelInfo();
        if (fuel == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fuel_position", fuel.getFuelPosition());
        out.put("pump_state", fuel.getPumpState());
        out.put("help_requested", fuel.isHelpReq());
        out.put("paper_low", fuel.isPaperLow());
        return out;
    }

    Map<String, Object> calculator() {
        SimpleCalculatorInfo calc = holder.getSimpleCalculatorInfo();
        if (calc == null) {
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("title", calc.getTitleString());
        out.put("message", calc.getMsgString());
        out.put("display", calc.getUpdateDisplay());
        return out;
    }

    private static boolean hasText(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
