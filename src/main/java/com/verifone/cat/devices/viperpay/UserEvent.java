package com.verifone.cat.devices.viperpay;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;

/**
 * One {@code ViperpayUserEvent}: what CAT sends to the simulator's key port for a key press, a card
 * or an entry. Encoded by hand as {@code ObjectOutputStream.writeObject} writes it, which is what
 * PATS's {@code ViperPayCommunicator} reads. Ported from the Python CAT's {@code encode_user_event}.
 *
 * <p>Field order follows the Java class (fields by name): {@code card}, {@code id}, {@code popID},
 * {@code softKey}, {@code track1..3}, and the boolean {@code cancelPressed} written first as a
 * primitive. Matching string values are one back-reference, as the JVM writes them.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The bytes equal the JDK's own for the fixtures.
 *
 * @param popId     the POP the event answers, as the display carried it
 * @param softKey   the soft key pressed, or "" (the Java default)
 * @param card      the card's label, or null
 * @param track1    null, or a track's data
 * @param track2    null, or a track's data
 * @param track3    null, or a track's data
 * @param eventId   the entry text (the {@code id} field), or "" (the Java default)
 * @param cancel    whether cancel was pressed
 */
public record UserEvent(
        String popId,
        String softKey,
        String card,
        String track1,
        String track2,
        String track3,
        String eventId,
        boolean cancel) {

    static final String CLASS_NAME =
            "com.verifone.isd.pcapps.simulators.payment.viperpay.comm.pres.ViperpayUserEvent";

    /** The object's string fields in the order the JVM writes them. */
    private static final String[] STRING_FIELDS = {"card", "id", "popID", "softKey", "track1", "track2", "track3"};

    private static final byte TC_NULL = 0x70;
    private static final byte TC_REFERENCE = 0x71;
    private static final byte TC_CLASSDESC = 0x72;
    private static final byte TC_OBJECT = 0x73;
    private static final byte TC_STRING = 0x74;
    private static final byte TC_ENDBLOCKDATA = 0x78;
    private static final byte SC_SERIALIZABLE = 0x02;
    private static final int BASE_HANDLE = 0x7E0000;

    public UserEvent {
        popId = popId == null ? "" : popId;
        softKey = softKey == null ? "" : softKey;
        eventId = eventId == null ? "" : eventId;
    }

    /** A key press: the soft key, answering the display of {@code popId}. */
    public static UserEvent key(String popId, String softKey) {
        return new UserEvent(popId, softKey, null, null, null, null, "", false);
    }

    /** A card read: the label, which the simulator looks up in its own card deck. */
    public static UserEvent card(String popId, String label) {
        return new UserEvent(popId, "", label, null, null, null, "", false);
    }

    /** An entry: the text as PATS's data string gives it, sent unchanged. */
    public static UserEvent entry(String popId, String text) {
        return new UserEvent(popId, "", null, null, null, null, text, false);
    }

    /** The bytes of one {@code ObjectOutputStream} stream holding this event. */
    public byte[] encode() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (DataOutputStream out = new DataOutputStream(bytes)) {
            out.write(JavaSerialReader.MAGIC);
            out.writeByte(TC_OBJECT);
            out.writeByte(TC_CLASSDESC);
            out.writeUTF(CLASS_NAME);
            out.writeLong(1L); // serialVersionUID
            out.writeByte(SC_SERIALIZABLE);
            out.writeShort(1 + STRING_FIELDS.length);
            out.writeByte('Z');
            out.writeUTF("cancelPressed");
            for (int i = 0; i < STRING_FIELDS.length; i++) {
                out.writeByte('L');
                out.writeUTF(STRING_FIELDS[i]);
                if (i == 0) {
                    out.writeByte(TC_STRING);
                    out.writeUTF("Ljava/lang/String;"); // handle base + 1
                } else {
                    out.writeByte(TC_REFERENCE);
                    out.writeInt(BASE_HANDLE + 1);
                }
            }
            out.writeByte(TC_ENDBLOCKDATA);
            out.writeByte(TC_NULL); // no superclass
            out.writeBoolean(cancel);
            // Field values. Equal strings are one object in Java, so the second is a back-reference.
            Map<String, Integer> handles = new HashMap<>();
            writeString(out, handles, card);
            writeString(out, handles, eventId);
            writeString(out, handles, popId);
            writeString(out, handles, softKey);
            writeString(out, handles, track1);
            writeString(out, handles, track2);
            writeString(out, handles, track3);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot encode a user event", e);
        }
        return bytes.toByteArray();
    }

    private static void writeString(DataOutputStream out, Map<String, Integer> handles, String value)
            throws IOException {
        if (value == null) {
            out.writeByte(TC_NULL);
        } else if (handles.containsKey(value)) {
            out.writeByte(TC_REFERENCE);
            out.writeInt(handles.get(value));
        } else {
            // After the class descriptor, its type string and the object itself.
            handles.put(value, BASE_HANDLE + 3 + handles.size());
            out.writeByte(TC_STRING);
            out.writeUTF(value);
        }
    }
}
