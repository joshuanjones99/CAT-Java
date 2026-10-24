package com.verifone.cat.devices.forecourt;

import static com.verifone.cat.devices.forecourt.Fakes.fixture;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

/** The forecourt's bytes: every request is byte for byte what the JDK writes, and the decoder reads the JDK's streams. */
class JavaSerialTest {

    @Test
    void everyRequestIsByteForByteWhatTheJdkWrites() {
        assertArrayEquals(fixture("fuel_login.bin"), ForecourtWire.encodeLogin(true));
        assertArrayEquals(fixture("dcr_login.bin"), ForecourtWire.encodeLogin(false));
        assertArrayEquals(fixture("dcr_ack.bin"), ForecourtWire.DCR_ACK);
        assertArrayEquals(fixture("fuel_handle_up.bin"), ForecourtWire.encodeHandleUp(1, new BigDecimal("24.15"), 1));
        assertArrayEquals(fixture("fuel_handle_down.bin"), ForecourtWire.encodeHandleDown(5, new BigDecimal("14.50")));
        assertArrayEquals(fixture("dcr_key_l3.bin"), ForecourtWire.encodeDcrKey(1, "L3"));
        // GenDcrInput.java: DCR INSERT_OR_SLIDE_CARD and KEY_PRESS.
        assertArrayEquals(fixture("dcr_card.bin"), ForecourtWire.encodeDcrCard(1, "VCMG_LOYALTY_CARD2"));
        assertArrayEquals(fixture("dcr_keyboard.bin"), ForecourtWire.encodeDcrKeyboard(1, "12345"));
    }

    @Test
    void statusInfoWriterMatchesTheJdk() {
        assertArrayEquals(fixture("fuel_status_info.bin"),
                Fakes.status(1, 1, "IDLEOFF", "ACK", 5, 2, "BUSYON", "NCK"));
    }

    @Test
    void theJdkWrittenStatusAndDisplayDecode() {
        List<?> states = (List<?>) JavaSerial.loads(fixture("fuel_status_info.bin"));
        assertEquals(2, states.size());
        Map<?, ?> first = (Map<?, ?>) states.get(0);
        Map<?, ?> second = (Map<?, ?>) states.get(1);
        assertEquals(1, ((Number) first.get("fuelingPosition")).intValue());
        assertEquals("IDLEOFF", first.get("deviceStatus"));
        assertEquals(5, ((Number) second.get("fuelingPosition")).intValue());
        assertEquals("BUSYON", second.get("deviceStatus"));

        List<?> displays = (List<?>) JavaSerial.loads(fixture("dcr_display.bin"));
        assertEquals(1, displays.size());
        Map<?, ?> display = (Map<?, ?>) displays.get(0);
        assertEquals(1, ((Number) display.get("position")).intValue());
        assertEquals(List.of("", "WELCOME", "PAY INSIDE"), display.get("promptLines"));
        Map<?, ?> device = (Map<?, ?>) display.get("dcrDeviceParameters");
        assertEquals(Boolean.TRUE, device.get("emvEnabled"));
    }

    @Test
    void theL3KeyIsColumnLRow2AsPatsMapsIt() {
        Map<?, ?> key = (Map<?, ?>) JavaSerial.loads(ForecourtWire.encodeDcrKey(1, "l3"));
        Map<?, ?> info = (Map<?, ?>) key.get("dcrKeyDataInfo");
        assertEquals((int) 'L', ((Number) info.get("column")).intValue());
        assertEquals((int) '2', ((Number) info.get("row")).intValue());
        assertEquals(List.of(1), key.get("position"));
    }

    @Test
    void aBackReferenceBeforeAnyObjectIsRefused() {
        byte[] malformed = Fakes.status(1, 0, "BUSYON", "ACK");
        malformed[4] = 0x71;
        assertThrows(IndexOutOfBoundsException.class, () -> JavaSerial.loads(malformed));
    }

    @Test
    void aNonStreamIsRefused() {
        assertThrows(IllegalArgumentException.class, () -> JavaSerial.loads(new byte[] {1, 2, 3, 4}));
        assertFalse(Fakes.complete(new byte[] {(byte) 0xAC, (byte) 0xED, 0x00, 0x05}));
        assertTrue(Fakes.complete(fixture("dcr_ack.bin")));
    }
}
