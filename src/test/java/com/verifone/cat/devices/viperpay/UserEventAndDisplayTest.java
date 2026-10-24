package com.verifone.cat.devices.viperpay;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The byte codec, against streams the real JDK wrote with Petro Simulator Suite's own jar
 * ({@code src/test/resources/viperpay/Gen.java} beside them; the Python CAT's
 * {@code tests/fixtures/viperpay/}). The encoder must reproduce the JDK's bytes and the decoder must read
 * them. Evidence about the codec, not about the live simulator.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
class UserEventAndDisplayTest {

    static byte[] fixture(String name) {
        try (InputStream in = UserEventAndDisplayTest.class.getResourceAsStream("/viperpay/" + name)) {
            if (in == null) {
                throw new IllegalStateException("missing fixture " + name);
            }
            return in.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read fixture " + name, e);
        }
    }

    @Test
    void theKeyEventIsByteForByteWhatTheJdkWrites() {
        assertArrayEquals(fixture("user_event_key.bin"), UserEvent.key("1", "$0.51").encode());
    }

    @Test
    void theCardAndDecimalEventsAreByteForByteWhatTheJdkWrites() {
        assertArrayEquals(fixture("user_event_card.bin"), UserEvent.card("1", "EMV AMMEX CREDIT").encode());
        assertArrayEquals(fixture("user_event_decimal.bin"), UserEvent.entry("1", "123456").encode());
    }

    @Test
    void theJdkWrittenDisplayRequestDecodes() {
        Display display = Display.decode(fixture("display_request_get_any_key.bin"));
        assertEquals("1", display.popId());
        assertEquals("GET_ANY_KEY", display.commandType());
        assertEquals(List.of("Would you like to donate", "to CHARITY?"), display.lines());
        assertEquals(List.of("$0.51", "No"), display.keys());
    }

    @Test
    void theJdkWrittenCardAndEntryDisplaysDecode() {
        Display swipe = Display.decode(fixture("display_request_read_card.bin"));
        assertEquals("001", swipe.popId());
        assertEquals("READ_CARD", swipe.commandType());
        assertEquals(List.of("SWIPE CARD"), swipe.lines());

        Display entry = Display.decode(fixture("display_request_get_decimals.bin"));
        assertEquals("GET_DECIMALS", entry.commandType());
        assertEquals(1, entry.minDigits());
        assertEquals(19, entry.maxDigits());
    }

    @Test
    void theWelcomeDisplaysDecodeWithTheirEmptyLines() {
        Display welcome = Display.decode(fixture("display_request_welcome.bin"));
        assertEquals(List.of("WELCOME TO OUR STORE", "INSERT TAP OR SWIPE CARD"), welcome.lines());
        Display only = Display.decode(fixture("display_request_welcome_only.bin"));
        assertEquals(Arrays.asList("", "WELCOME TO OUR STORE", ""), only.lines());
    }

    @Test
    void aDisplayTextJoinsTheNonEmptyLinesWithSpaces() {
        Display display = new Display("1", "GET_ANY_KEY", Arrays.asList("A", null, "", "B"), List.of(), 0, 0);
        assertEquals("A B", display.text());
    }

    @Test
    void aMalformedStreamIsRefused() {
        byte[] malformed = fixture("display_request_read_card.bin").clone();
        malformed[4] = 0x71; // a back-reference before any object
        assertThrows(IllegalArgumentException.class, () -> Display.decode(malformed));
    }

    @Test
    void aTruncatedOrForeignStreamIsRefused() {
        byte[] whole = fixture("display_request_get_any_key.bin");
        assertThrows(IllegalArgumentException.class,
                () -> Display.decode(Arrays.copyOf(whole, whole.length / 2)));
        assertThrows(IllegalArgumentException.class, () -> Display.decode(new byte[] {1, 2, 3}));
        assertThrows(IllegalArgumentException.class,
                () -> Display.decode(fixture("user_event_key.bin"))); // a user event, not a display
    }

    @Test
    void theEventsEncodedFieldsDecodeBackToTheSameStrings() {
        byte[] stream = UserEvent.card("001", "VCMG_LOYALTY_CARD2").encode();
        Object decoded = JavaSerialReader.loads(stream);
        assertTrue(decoded instanceof JavaSerialReader.JavaObject, decoded.toString());
        JavaSerialReader.JavaObject event = (JavaSerialReader.JavaObject) decoded;
        assertEquals("VCMG_LOYALTY_CARD2", event.field("card"));
        assertEquals("001", event.field("popID"));
        assertEquals("", event.field("softKey"));
        assertEquals(false, event.field("cancelPressed"));
    }
}
