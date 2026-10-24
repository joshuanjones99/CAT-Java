package com.verifone.cat.preflight;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * A POS that holds its state in fields, for both the status and the control surface. Ported from
 * the Python test's {@code FakePos}. Evidence about this repository only.
 */
final class FakePos implements Surfaces.PosStatus, Surfaces.PosControl {

    boolean printerOpen = true;
    boolean reopenOk = true;
    boolean transaction;
    boolean idleScreen = true;
    final List<Surfaces.ParkedTransaction> parked = new ArrayList<>();
    boolean drawer;
    boolean drawerBlocked;
    String cashier = "PATS";
    String build = "057.00";
    boolean cancelOk = true;
    final List<String> actions = new ArrayList<>();

    @Override
    public boolean printerPortOpen() {
        return printerOpen;
    }

    @Override
    public boolean transactionActive() {
        return transaction;
    }

    @Override
    public boolean idleScreenPresent() {
        return idleScreen;
    }

    @Override
    public List<Surfaces.ParkedTransaction> parkedTransactions() {
        return List.copyOf(parked);
    }

    @Override
    public boolean drawerOpen() {
        return drawer;
    }

    @Override
    public boolean drawerPhysicallyBlocked() {
        return drawerBlocked;
    }

    @Override
    public Optional<String> cashierSignedOn() {
        return Optional.ofNullable(cashier);
    }

    @Override
    public String buildVersion() {
        return build;
    }

    @Override
    public boolean reopenPrinterPort() {
        actions.add("reopen_printer_port");
        printerOpen = reopenOk;
        return reopenOk;
    }

    @Override
    public boolean resetSerialConnection() {
        actions.add("reset_serial_connection");
        printerOpen = true;
        return true;
    }

    @Override
    public boolean cancelLadder() {
        actions.add("cancel_ladder");
        if (cancelOk) {
            transaction = false;
            idleScreen = true;
        }
        return cancelOk;
    }

    @Override
    public boolean voidOpenTicket() {
        actions.add("void_open_ticket");
        transaction = false;
        idleScreen = true;
        return true;
    }

    @Override
    public boolean recallAndVoid(String ticketId) {
        actions.add("recall_and_void:" + ticketId);
        parked.removeIf(t -> t.ticketId().equals(ticketId));
        return true;
    }

    @Override
    public boolean closeDrawer() {
        actions.add("close_drawer");
        drawer = false;
        return true;
    }

    @Override
    public boolean signOn(String cashier, String password) {
        actions.add("sign_on:" + cashier);
        this.cashier = cashier;
        return true;
    }
}
