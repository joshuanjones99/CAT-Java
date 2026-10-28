package com.verifone.cat.recovery;

/**
 * The register's screen as recovery's rungs read and press it, over CDP. The Python's DOM half of
 * {@code petrotest/recovery/plugin.py} ({@code _live_soft_cancel}, {@code _live_void}, {@code _testable}) and of
 * {@code petrotest/devices/context.py} ({@code live_pos_idle}, {@code live_at_login}, {@code live_signed_on}).
 *
 * <p>Each method is one look or one pass: {@link LivePosScreen} opens a CDP session for it and closes it after, as
 * the Python's {@code with connect_pos(...)} does per call.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public interface PosScreen {

    /**
     * {@code live_pos_idle}: the idle sales screen (the express panel's CSR key), or an SCO lane's welcome screen.
     * Preflight's {@code pos_idle} and the ladder's testability probe both read it.
     */
    boolean idle();

    /** {@code live_at_login}: the login screen or its ID/password prompt. */
    boolean atLogin();

    /**
     * {@code live_signed_on}: false at the login screen, true on the sales screen; throws when the screen is neither,
     * because that is not an answer.
     */
    boolean signedOn();

    /**
     * Rung 1's DOM half: an OK-only message pressed away, an SCO overlay cancelled, the CSR menus closed.
     */
    void softCancel();

    /**
     * Rung 2's DOM half: on a register with an open sale, a review list closed, a message's Cancel pressed, then the
     * sale cleared ({@code Pos.clear_open_sale}: paid when it holds a fill or a tender, else voided). Returns what was
     * done ("idle", "settled", "voided"). Throws when it could not be done, and the rung falls back to the shim.
     */
    String voidOpenSale();
}
