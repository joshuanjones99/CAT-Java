package com.verifone.cat.pos.fake;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.verifone.cat.pos.pinpad.PinpadDisplay;
import com.verifone.cat.pos.pinpad.PinpadDouble;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * The fake POS wired to the pinpad double, and the run-end banner. Ported from the Python
 * {@code plugin._wire_fake_pos_to_pinpad} and {@code pytest_terminal_summary}.
 *
 * <p>MOP taps go through the real HTTP routes, so the pinpad prompts are raised the way a run raises
 * them. The prompts are the corpus's own and the display is {@code LOOPBACK/}: a double.
 */
class FakePosWiringTest {

    private static final Duration WAIT = Duration.ofSeconds(5);
    private static final int PAYMENT_GATEWAY = 145397;
    private static final int CASH = 145394;
    private static final int CREDIT = 145395;

    private final HttpClient client = HttpClient.newHttpClient();
    private PinpadDouble pinpad;
    private FakePos pos;

    @BeforeEach
    void open() throws IOException {
        pinpad = new PinpadDouble("1", WAIT);
        pos = FakePos.open(FakePosPinpad.wire(pinpad));
    }

    @AfterEach
    void close() {
        pos.close();
        pinpad.reset();
    }

    @Test
    void cashShowsTheRoundUpPromptOnThePinpad() throws Exception {
        tap(PAYMENT_GATEWAY);
        tap(CASH);
        assertEquals("donate", pinpad.atPrompt("donate", WAIT));
        assertTrue(shows("LOOPBACK/DONATE"));
    }

    @Test
    void creditShowsSwipeCardAndTheRoundUpPromptFollowsTheCardInsert() throws Exception {
        tap(PAYMENT_GATEWAY);
        tap(CREDIT);
        assertEquals("SWIPE CARD", pinpad.atPrompt("SWIPE CARD", WAIT));
        String shown = pinpad.insertCard("TEST CARD", "donate", WAIT);
        assertTrue(shown.toLowerCase(java.util.Locale.ROOT).contains("donate"), shown);
    }

    @Test
    void answeringDonateOffersCashback() throws Exception {
        tap(PAYMENT_GATEWAY);
        tap(CASH);
        pinpad.atPrompt("donate", WAIT);
        pinpad.key("ENTER", "CASHBACK", WAIT);
        assertEquals("CASHBACK", pinpad.display().prompt());
    }

    @Test
    void theBannerSaysTheRunWasNotHardwareEvidence() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        FakePosBanner.print(new PrintStream(bytes, true, StandardCharsets.UTF_8));
        String text = bytes.toString(StandardCharsets.UTF_8);
        assertTrue(text.contains("FAKE POS"));
        assertTrue(text.contains("WARNING: FAKE POS WAS USED — THIS RUN IS EVIDENCE ABOUT TEST CODE,   █"));
        assertTrue(text.contains("NOT ABOUT HARDWARE. A test passing here does NOT mean a rig passes."));
    }

    private boolean shows(String text) {
        PinpadDisplay display = pinpad.display();
        return display.lines().toString().contains(text);
    }

    private void tap(int mop) throws IOException, InterruptedException {
        HttpResponse<String> response = client.send(
                HttpRequest.newBuilder(pos.url().resolve("/api/mop?mop=" + mop)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
    }
}
