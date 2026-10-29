package com.verifone.cat.pats.runner.handlers;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.verifone.cat.pats.runner.handlers.devices.DcrDevice;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/**
 * The run's DCR receipt seam ({@code StandardHandlers.dcrReceipts}) keeps the DCR's receipt as printed: the Python's
 * {@code receipt_since} string, which VALIDATE_RECEIPT compares as it is. PETROCORE-118399 (CAT-Java run
 * 5285e78aa370) failed when it was parsed into item lines first. Fake-tested only.
 */
class DcrReceiptWiringTest {

    @Test
    void theDcrReceiptReachesTheValidationAsPrinted() {
        String printed = "     WELCOME TO     \n\nGALLONS:      16.005\n   CREDIT     $13.14\n\n  AGAIN AND AGAIN   ";
        DcrDevice dcr = new DcrDevice() {
            @Override
            public void insertCard(String card) {
            }

            @Override
            public void waitForPrompt(String text, Duration timeout) {
            }

            @Override
            public void keyPress(String key) {
            }

            @Override
            public void press(String functionKey) {
            }

            @Override
            public String receiptSince(int mark, Duration timeout) {
                return printed;
            }
        };

        assertEquals(printed, StandardHandlers.dcrReceipts(dcr).receiptSince(0, 1.0).text());
    }
}
