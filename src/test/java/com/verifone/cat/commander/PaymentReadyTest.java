package com.verifone.cat.commander;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import com.verifone.cat.commander.CommanderFixtures.FakeClient;
import com.verifone.cat.devices.DeviceUnreachable;

/** {@link PaymentReady}: the EPS's readiness from {@code vpaymentdiagnostics}, as the Python's {@code test_payment_ready.py}. */
class PaymentReadyTest {

    /** The lab Commander's reply on 2026-10-20 at 11:04, ready (trimmed to one secondary FEP). */
    static final String READY = """
            <?xml version="1.0" encoding="UTF-8" standalone="yes"?>
            <diag:diagnostics time="2026-10-20T11:04:22" xmlns:diag="urn:vfi-sapphire:diagnostics.2017-01-17">
                <paymentDiagnostics>
                    <deviceDetail deviceName="Payment Controller">
                        <status>true</status>
                    </deviceDetail>
                    <fepDetail fepName="buypass" isPrimary="true">
                        <connectionStatus>true</connectionStatus>
                        <safCount>0</safCount>
                    </fepDetail>
                    <fepDetail fepName="PROGRAM01" isPrimary="false">
                        <connectionStatus>true</connectionStatus>
                    </fepDetail>
                    <pinpadDetail popID="001">
                        <workstationName>C18 1</workstationName>
                        <connectionStatus>true</connectionStatus>
                    </pinpadDetail>
                </paymentDiagnostics>
            </diag:diagnostics>""";

    private static final Duration NOW = Duration.ofMillis(10);

    private static String with(String old, String replacement) {
        assertTrue(READY.contains(old), old);
        return READY.replaceFirst(java.util.regex.Pattern.quote(old), java.util.regex.Matcher.quoteReplacement(replacement));
    }

    @Test
    void theLabCommandersReadyReplyReadsReady() {
        PaymentReady.Readiness now = PaymentReady.parse(READY);
        assertTrue(now.ready());
        assertEquals("payment controller up, buypass connected, POP 001 connected", now.summary());
    }

    @Test
    void eachOfTheThreeHoldsItButASecondaryFepDoesNot() {
        String primary = "\"buypass\" isPrimary=\"true\">\n            <connectionStatus>true";
        String pinpad = "C18 1</workstationName>\n            <connectionStatus>true";
        String secondary = "\"PROGRAM01\" isPrimary=\"false\">\n            <connectionStatus>true";
        assertFalse(PaymentReady.parse(with("<status>true</status>", "<status>false</status>")).ready());
        assertFalse(PaymentReady.parse(with(primary, primary.replace(">true", ">false"))).ready());
        assertFalse(PaymentReady.parse(with(pinpad, pinpad.replace("true", "false"))).ready());
        assertTrue(PaymentReady.parse(with(secondary, secondary.replace(">true", ">false"))).ready());
        PaymentReady.Readiness noController = PaymentReady.parse(
                READY.replace("deviceName=\"Payment Controller\"", "deviceName=\"Other\""));
        assertFalse(noController.ready());
        assertTrue(noController.summary().startsWith("payment controller absent"));
    }

    @Test
    void theWaitSaysEachChangeAndEndsOnceReady() {
        String down = with("C18 1</workstationName>\n            <connectionStatus>true",
                "C18 1</workstationName>\n            <connectionStatus>false");
        Iterator<Object> replies = List.<Object>of(new DeviceUnreachable("commander", "refused"), down, down, READY)
                .iterator();
        Supplier<String> read = () -> {
            Object reply = replies.next();
            if (reply instanceof RuntimeException e) {
                throw e;
            }
            return (String) reply;
        };
        List<String> said = new ArrayList<>();

        PaymentReady.Outcome outcome = PaymentReady.waitReady(read, Duration.ofSeconds(5), NOW, said::add, Pauser.REAL);

        assertTrue(outcome.ready());
        assertNotNull(outcome.last());
        List<String> states = said.stream().map(line -> line.split(": ", 3)[2]).toList();
        assertEquals(List.of("no answer (DeviceUnreachable)",
                "payment controller up, buypass connected, POP 001 not connected",
                "payment controller up, buypass connected, POP 001 connected"), states);
    }

    @Test
    void aCommanderNeverReadyReturnsFalseWithWhatWasNot() {
        String down = with("<status>true</status>", "<status>false</status>");
        PaymentReady.Outcome outcome = PaymentReady.waitReady(() -> down, Duration.ofMillis(200), Duration.ofMillis(50),
                line -> {}, Pauser.REAL);
        assertFalse(outcome.ready());
        assertTrue(outcome.last().summary().startsWith("payment controller down"));
    }

    @Test
    void theCommandAsksTheViewThroughOneClientPerReadAndSaysTheVerdict() {
        List<Object> calls = new ArrayList<>();
        Say.Recorded say = new Say.Recorded();
        FakeClient client = new FakeClient(calls) {
            @Override
            public String sendCommand(String command) {
                super.sendCommand(command);
                return READY;
            }
        };

        assertTrue(PaymentReady.run(() -> client, Duration.ofSeconds(5), NOW, Pauser.REAL, say));
        assertEquals(List.of("send:" + PaymentReady.COMMAND, "closed"), calls);
        assertEquals("payment-ready: ready (payment controller up, buypass connected, POP 001 connected)",
                say.outLines().get(say.outLines().size() - 1));

        Say.Recorded never = new Say.Recorded();
        assertFalse(PaymentReady.run(() -> new FakeClient(new ArrayList<>()), Duration.ofMillis(100), NOW, Pauser.REAL,
                never));
        assertTrue(never.lastErr().startsWith("payment-ready: NOT ready after 0s: no answer"), never.lastErr());
    }
}
