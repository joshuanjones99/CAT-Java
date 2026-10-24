package com.verifone.cat.pats.runner.handlers.sale;

import com.verifone.cat.devices.DeviceUnavailable;

/**
 * The scanner-data files a case's SCAN_ITEM and ADD_ITEM present: the XML PATS keeps under
 * {@code AutomationTests/scannerdata} in petroapps. Ported from the Python {@code case.store.shared(...)}.
 *
 * <p>The petroapps store has no Java reader yet (HANDOFF.md, the case store), so {@link #UNAVAILABLE} throws.
 */
@FunctionalInterface
public interface ScannerData {

    /** The scanner-data file's text, for example {@code scannerdata/<fileName>}, as the XML it is. */
    String xml(String fileName);

    /** The store that is not wired: every read throws {@link DeviceUnavailable}. */
    ScannerData UNAVAILABLE = fileName -> {
        throw new DeviceUnavailable("the scanner data", "reading scannerdata/" + fileName
                + ": no petroapps case store is wired to this run");
    };
}
