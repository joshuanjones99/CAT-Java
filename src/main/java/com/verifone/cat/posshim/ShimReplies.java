package com.verifone.cat.posshim;

import java.util.List;
import java.util.Map;

/**
 * pos-shim's replies, typed.
 *
 * <p>Field names follow the shim's JSON through Jackson's snake_case strategy
 * ({@code uptimeS} is {@code uptime_s}). Fields the shim sends that are not here are
 * ignored, so a newer shim does not break this client. Boxed types mark the fields the
 * shim sends as {@code null} before a POS has pushed anything.
 */
public final class ShimReplies {

    private ShimReplies() {}

    /**
     * {@code GET /health}.
     *
     * <p>{@code warning} is set only when the shim has something to say about the state it reports (a POS that
     * accepts keys but has pushed no status, for one). It is null when there is none, and when the shim omits it.
     */
    public record Health(
            boolean ok,
            String build,
            long uptimeS,
            List<String> clients,
            List<String> drivableClients,
            List<String> readOnlyClients,
            boolean statePresent,
            Map<String, Long> lastChangeAgeS,
            List<String> drivableButSilent,
            String warning) {}

    /**
     * {@code GET /pos/screen}: everything the POS last pushed.
     *
     * <p>The POS pushes and cannot be polled, so {@code lastChangeAgeS} is how old this
     * picture is. An auto-hide overlay can stay here for minutes after the POS took it
     * off screen.
     */
    public record Screen(
            String client,
            boolean statePresent,
            Boolean idle,
            String idleBasis,
            boolean overlayPresent,
            Boolean transactionActive,
            Overlay overlay,
            List<String> overlayReview,
            List<ExpressButton> expressPanel,
            Map<String, Double> totals,
            Long lastChangeAgeS) {}

    public record Overlay(
            String title,
            String line1,
            String line2,
            String line3,
            boolean okOnly,
            boolean cancelOnly,
            boolean okAndCancel,
            boolean editBox,
            boolean autoHide) {}

    public record ExpressButton(String label, String returnValue, boolean disabled, boolean selected) {}

    /** {@code GET /pos/keys}: the key names the shim can build, checked against the POS's own token maker. */
    public record SupportedKeys(List<String> namedKeys, List<String> rejectedNames, List<String> notes) {}

    /**
     * {@code POST /pos/keys}.
     *
     * <p>{@code allProcessed} is true only when the POS called back for every key, so a
     * key that was delivered and ignored shows as {@code processed=false}.
     */
    public record KeyPressResult(String client, List<String> requested, List<KeyPress> pressed, boolean allProcessed) {}

    public record KeyPress(String key, boolean processed, long elapsedMs) {}

    /**
     * {@code POST /pos/control/cancel} and {@code POST /pos/control/ticket/void}.
     *
     * <p>{@code success} is the shim's own re-read of the screen after the action: true only
     * when the overlay (or the open ticket) is gone. It is boxed so a reply without the field
     * is seen as a contract break, not as a silent false.
     */
    public record ControlResult(String outcome, Boolean success) {}

    /** {@code POST /pos/keys/enter}: ENTER carrying a data payload. */
    public record PendingKeyResult(String key, boolean processed) {}

    /** {@code POST /pos/scan}: a barcode scan presented to the POS. */
    public record ScanResult(boolean processed) {}
}
