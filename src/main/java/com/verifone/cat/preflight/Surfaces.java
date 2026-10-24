package com.verifone.cat.preflight;

import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

import com.verifone.cat.devices.DeviceUnavailable;

/**
 * The status and control surfaces a preflight check probes through. Ported from the Python
 * CAT's {@code petrotest.preflight.context} Protocols.
 *
 * <p>Status surfaces are read-only and safe mid-suite. Control surfaces are reachable only
 * from {@code repair()}: {@link RigContext#readOnly()} refuses them during verification.
 * Where the Python CAT has a device client this port lacks, {@link #unavailable} stands in:
 * every call raises {@link DeviceUnavailable}, which preflight records as ERROR, never FAIL
 * (CLAUDE.md hard rule 5).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class Surfaces {

    private Surfaces() {}

    /** The retained RMI agent's status surface, as the pos-shim reports it (DESIGN.md 5.2, 6.2). */
    public interface PosStatus {
        boolean printerPortOpen();

        boolean transactionActive();

        boolean idleScreenPresent();

        List<ParkedTransaction> parkedTransactions();

        boolean drawerOpen();

        /** True when the drawer is open because a human left it open, so no software repair exists. */
        boolean drawerPhysicallyBlocked();

        /** The signed-on cashier, empty when the register is at its login screen. */
        Optional<String> cashierSignedOn();

        String buildVersion();
    }

    /** Everything a POS repair may do. Bounded and idempotent (DESIGN.md 6.2). */
    public interface PosControl {
        boolean reopenPrinterPort();

        boolean resetSerialConnection();

        /** The cancel/escape ladder, shared with recovery rung 1 (DESIGN.md 6.3). */
        boolean cancelLadder();

        boolean voidOpenTicket();

        boolean recallAndVoid(String ticketId);

        boolean closeDrawer();

        boolean signOn(String cashier, String password);
    }

    /** The forecourt, read for all pumps at once (Petro Simulator Suite reports them together). */
    public interface PumpStatus {
        List<PumpState> pumps(List<Integer> numbers);
    }

    /** Whether the Commander's EPS is connected to its payment host. */
    public interface EpsProbe {
        /** False only for "looked, and the link is not there". A probe that cannot look throws. */
        boolean reachable(Duration timeout);

        /** What the probe looks for, for the FAIL message. */
        String describe();
    }

    /** Which config folder is live on the Commander. */
    public interface ConfigStatus {
        String activeConfigFolder();
    }

    /** Import from the petroapps configurations folder. */
    public interface ConfigControl {
        boolean importConfigFolder(String folder);
    }

    /** A parked ticket on the register's own list. */
    public record ParkedTransaction(String ticketId, String cashier, String total) {
        public ParkedTransaction {
            cashier = cashier == null ? "" : cashier;
            total = total == null ? "" : total;
        }

        public ParkedTransaction(String ticketId) {
            this(ticketId, "", "");
        }
    }

    /** Unsettled suspended tickets from the Commander's Day summary: a count and a total, no numbers. */
    public record UnsettledSuspends(int count, String amount) {
        public UnsettledSuspends {
            amount = amount == null ? "" : amount;
        }
    }

    /** A fuelling point as the forecourt reports it. */
    public record PumpState(int number, String state, boolean handleUp, boolean pendingPrepay) {

        public PumpState {
            state = state == null ? "" : state;
        }

        /** Idle or authorised with no handle up: testable. Mid-delivery is not. */
        public boolean idle() {
            String s = state.toLowerCase(java.util.Locale.ROOT);
            return (s.equals("idle") || s.equals("authorised") || s.equals("authorized")) && !handleUp;
        }
    }

    /**
     * A surface with no source on this rig or in this port. Every method raises
     * {@link DeviceUnavailable} naming {@code surface} and {@code reason}.
     */
    public static <T> T unavailable(Class<T> type, String surface, String reason) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, (proxy, method, args) -> {
            if (method.getDeclaringClass() == Object.class) {
                return objectMethod(proxy, method, args, type.getSimpleName());
            }
            throw new DeviceUnavailable(surface, reason);
        }));
    }

    private static Object objectMethod(Object proxy, java.lang.reflect.Method method, Object[] args, String type) {
        return switch (method.getName()) {
            case "equals" -> proxy == args[0];
            case "hashCode" -> System.identityHashCode(proxy);
            default -> type + " (no source on this port)";
        };
    }
}
