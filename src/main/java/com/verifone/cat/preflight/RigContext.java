package com.verifone.cat.preflight;

import java.util.Map;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * The {@code rig} every check is handed. Ported from {@code petrotest.preflight.context.RigContext}.
 *
 * <p>Holds the rig's name, its expectations and the surfaces the checks probe through. The
 * optional readers are the Python CAT's DOM and Commander readers, which this port does not
 * have yet: when one is null the check falls back to the status surface, exactly as the
 * Python does when its callable is None.
 *
 * <p>{@link #readOnly()} is the mechanical form of DESIGN.md 6.2's "verify() never mutates":
 * on a read-only context the control surfaces raise {@link MutationInVerifyError} when touched.
 * Unlike the Python view, which delegates live to its inner rig, this one is a snapshot taken
 * at the moment of the call. The runner does not change the rig during verification, so the
 * difference is not observable from a check.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class RigContext {

    private final String name;
    private final Surfaces.PosStatus posStatus;
    private final Surfaces.PosControl posControl;
    private final Surfaces.EpsProbe eps;
    private final Surfaces.ConfigStatus configStatus;
    private final Surfaces.ConfigControl configControl;
    private final Expectations expectations;
    private final boolean readOnly;

    private BooleanSupplier screenIdle;
    private Surfaces.PumpStatus pumpStatus;
    private BooleanSupplier signedOn;
    private Supplier<Map<String, Boolean>> printerPorts;
    private Map<String, Boolean> cashDrawers;
    private Supplier<Surfaces.UnsettledSuspends> parkedTickets;

    public RigContext(String name, Surfaces.PosStatus posStatus, Surfaces.PosControl posControl,
            Surfaces.EpsProbe eps, Surfaces.ConfigStatus configStatus, Surfaces.ConfigControl configControl,
            Expectations expectations) {
        this(name, posStatus, posControl, eps, configStatus, configControl, expectations, false);
    }

    private RigContext(String name, Surfaces.PosStatus posStatus, Surfaces.PosControl posControl,
            Surfaces.EpsProbe eps, Surfaces.ConfigStatus configStatus, Surfaces.ConfigControl configControl,
            Expectations expectations, boolean readOnly) {
        this.name = name;
        this.posStatus = posStatus;
        this.posControl = posControl;
        this.eps = eps;
        this.configStatus = configStatus;
        this.configControl = configControl;
        this.expectations = expectations == null ? new Expectations() : expectations;
        this.readOnly = readOnly;
    }

    /** A snapshot of this rig that refuses control-surface access and writes. */
    public RigContext readOnly() {
        RigContext frozen = new RigContext(name, posStatus, posControl, eps, configStatus, configControl,
                expectations, true);
        frozen.screenIdle = screenIdle;
        frozen.pumpStatus = pumpStatus;
        frozen.signedOn = signedOn;
        frozen.printerPorts = printerPorts;
        frozen.cashDrawers = cashDrawers;
        frozen.parkedTickets = parkedTickets;
        return frozen;
    }

    public boolean isReadOnly() {
        return readOnly;
    }

    public String name() {
        return name;
    }

    public Surfaces.PosStatus posStatus() {
        return posStatus;
    }

    /** Refused on a read-only context: a verify() that asks for this has mutated the rig's intent. */
    public Surfaces.PosControl posControl() {
        guardControl("pos_control");
        return posControl;
    }

    public Surfaces.EpsProbe eps() {
        return eps;
    }

    public Surfaces.ConfigStatus configStatus() {
        return configStatus;
    }

    /** Refused on a read-only context, like {@link #posControl()}. */
    public Surfaces.ConfigControl configControl() {
        guardControl("config_control");
        return configControl;
    }

    public Expectations expectations() {
        return expectations;
    }

    /** The register's idle screen read from its DOM; null when this port has no DOM reader. */
    public BooleanSupplier screenIdle() {
        return screenIdle;
    }

    /** Where {@code pumps_idle} reads the pumps; null means the rig names no forecourt source. */
    public Surfaces.PumpStatus pumpStatus() {
        return pumpStatus;
    }

    /** Whether a cashier is signed on, from the register's DOM; null when not read. */
    public BooleanSupplier signedOn() {
        return signedOn;
    }

    /** Each register's printer port as this controller lists it; null when not read. */
    public Supplier<Map<String, Boolean>> printerPorts() {
        return printerPorts;
    }

    /** Each register's {@code cash_drawer} from the rig file: FALSE declares none attached, null that the rig does not say. */
    public Map<String, Boolean> cashDrawers() {
        return cashDrawers;
    }

    /** The Commander's unsettled suspended tickets; null when not read. */
    public Supplier<Surfaces.UnsettledSuspends> parkedTickets() {
        return parkedTickets;
    }

    public void setScreenIdle(BooleanSupplier screenIdle) {
        guardWrite("screen_idle");
        this.screenIdle = screenIdle;
    }

    public void setPumpStatus(Surfaces.PumpStatus pumpStatus) {
        guardWrite("pump_status");
        this.pumpStatus = pumpStatus;
    }

    public void setSignedOn(BooleanSupplier signedOn) {
        guardWrite("signed_on");
        this.signedOn = signedOn;
    }

    public void setPrinterPorts(Supplier<Map<String, Boolean>> printerPorts) {
        guardWrite("printer_ports");
        this.printerPorts = printerPorts;
    }

    public void setCashDrawers(Map<String, Boolean> cashDrawers) {
        guardWrite("cash_drawers");
        this.cashDrawers = cashDrawers;
    }

    public void setParkedTickets(Supplier<Surfaces.UnsettledSuspends> parkedTickets) {
        guardWrite("parked_tickets");
        this.parkedTickets = parkedTickets;
    }

    private void guardControl(String attribute) {
        if (readOnly) {
            throw new MutationInVerifyError(attribute);
        }
    }

    private void guardWrite(String attribute) {
        if (readOnly) {
            throw new MutationInVerifyError("set " + attribute);
        }
    }
}
