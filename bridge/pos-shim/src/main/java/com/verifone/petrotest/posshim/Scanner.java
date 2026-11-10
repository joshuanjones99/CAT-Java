package com.verifone.petrotest.posshim;

import java.util.LinkedHashMap;
import java.util.Map;

import com.verifone.isd.pcapps.automation.target.pos.topaz.app.rmi.LexTokenRMIServiceImpl;
import com.verifone.isd.pcapps.automation.target.pos.topaz.populator.LexTokenMaker;
import com.verifone.isd.topaz.sys.ent.LexToken;
import com.verifone.isd.topaz.sys.ent.ScannerLexDataEvent;

/**
 * Injecting a barcode scan into the POS — PATS' {@code SCAN_ITEM} (HANDOFF.md §3.2).
 *
 * <h2>Why this is not shaped like {@link Keys}</h2>
 *
 * A keyboard key is a name looked up in a table, and the table has a real gap:
 * digits have no case in {@code LexTokenMaker.createKBDToken}, so typing one
 * means an undiscovered Topaz scan code (see {@code Keys}' class doc and the
 * now-unsupported {@code /pos/control/cashier/sign-on}). A scan is different.
 * PATS' own {@code POSDevice.scanItem} builds a {@link ScannerLexDataEvent}
 * straight from the barcode string and symbology type that
 * {@code AutomationTests/scannerdata/*.xml} already carries as plain fields —
 * no name, no lookup — and {@code LexTokenMaker.createScannerToken} wraps it
 * as {@code LexToken(30, null, event)}. {@code LexTokenRMIServiceImpl.sendToken}
 * branches on {@code token.getSource() == 30} to route it to the POS's scanner
 * listener rather than its keyboard one, but it is the same {@code sendToken}
 * call {@link Keys} already uses. So this class needs no name validation and
 * no scan-code table: everything it sends is data, not a guess.
 */
final class Scanner {

    private final ShimRmiHost host;

    Scanner(ShimRmiHost host) {
        this.host = host;
    }

    /**
     * Presents one scan event to the POS, exactly as {@code POSDevice.scanItem}
     * does for a corpus {@code SCAN_ITEM} action.
     *
     * @param status        the scanner's own status code; the corpus's scannerdata
     *                      files carry {@code 0} for every case read so far
     * @param scanData      the decoded barcode value
     * @param decodeData    whether the scanner decoded the symbology (the corpus
     *                      files carry {@code true})
     * @param scanDataLabel the label shown for the scan; the corpus files repeat
     *                      {@code scanData} here
     * @param scanDataType  the symbology type code (e.g. {@code 104})
     */
    Map<String, Object> press(String client, int status, String scanData, boolean decodeData,
            String scanDataLabel, int scanDataType) {
        ScannerLexDataEvent event =
                new ScannerLexDataEvent(status, scanData, decodeData, scanDataLabel, scanDataType);
        LexToken token = LexTokenMaker.createScannerToken(event);
        LexTokenRMIServiceImpl service = host.tokenService(client);
        long started = System.currentTimeMillis();
        boolean processed = service.sendToken(token, client);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("client", client);
        result.put("scan_data", scanData);
        result.put("scan_data_type", scanDataType);
        result.put("processed", processed);
        result.put("elapsed_ms", System.currentTimeMillis() - started);
        return result;
    }
}
