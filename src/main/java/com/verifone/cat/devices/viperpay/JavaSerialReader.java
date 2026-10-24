package com.verifone.cat.devices.viperpay;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Java Object Serialization, the subset Petro Simulator Suite's ViperPay socket uses: an
 * {@code ObjectOutputStream} stream read into plain Java values. Ported from the Python CAT's
 * {@code petrotest.devices.java_serial} reader.
 *
 * <p>Objects come back as {@link JavaObject} (class name and fields by name). {@code ArrayList} and
 * arrays come back as {@link List}, boxed primitives ({@code java.lang.Integer} and friends) as
 * their value, and enums as their constant name. A malformed or truncated stream is refused with
 * {@link IllegalArgumentException}, so a listener can drop one stream and keep listening.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). Checked against streams the real JDK wrote with the
 * suite's own jar (the fixtures under {@code src/test/resources/viperpay/}).
 */
final class JavaSerialReader {

    static final byte[] MAGIC = {(byte) 0xAC, (byte) 0xED, 0x00, 0x05};

    private static final int TC_NULL = 0x70;
    private static final int TC_REFERENCE = 0x71;
    private static final int TC_CLASSDESC = 0x72;
    private static final int TC_OBJECT = 0x73;
    private static final int TC_STRING = 0x74;
    private static final int TC_ARRAY = 0x75;
    private static final int TC_BLOCKDATA = 0x77;
    private static final int TC_ENDBLOCKDATA = 0x78;
    private static final int TC_BLOCKDATALONG = 0x7A;
    private static final int TC_LONGSTRING = 0x7C;
    private static final int TC_ENUM = 0x7E;

    private static final int SC_WRITE_METHOD = 0x01;
    private static final int BASE_HANDLE = 0x7E0000;
    private static final String BOX_PREFIX = "java.lang.";
    private static final Set<String> BOXES = Set.of(
            "Boolean", "Byte", "Character", "Double", "Float", "Integer", "Long", "Short");
    private static final String PRIMITIVE_KINDS = "BCDFIJSZ";

    /** A decoded object: its class name and its fields by name. */
    record JavaObject(String className, Map<String, Object> fields) {

        /** A field's value, or null when the stream did not carry it. */
        Object field(String name) {
            return fields.get(name);
        }
    }

    /** A class descriptor, as the stream describes a class. */
    private record ClassDesc(String name, int flags, List<Field> fields, Object superDesc) {}

    private record Field(char kind, String name) {}

    /** Marks a TC_ENDBLOCKDATA. */
    private static final Object END = new Object();

    /** Marks a block-data run, whose contents are skipped. */
    private static final Object BLOCK = new Object();

    private final ByteArrayInputStream raw;
    private final DataInputStream in;
    private final int length;
    private final List<Object> handles = new ArrayList<>();

    private JavaSerialReader(byte[] data) {
        this.raw = new ByteArrayInputStream(data);
        this.in = new DataInputStream(raw);
        this.length = data.length;
    }

    /** The first object in one {@code ObjectOutputStream} stream. */
    static Object loads(byte[] data) {
        if (data == null || data.length < MAGIC.length) {
            throw new IllegalArgumentException("not a Java serialization stream");
        }
        for (int i = 0; i < MAGIC.length; i++) {
            if (data[i] != MAGIC[i]) {
                throw new IllegalArgumentException("not a Java serialization stream");
            }
        }
        JavaSerialReader reader = new JavaSerialReader(data);
        try {
            reader.in.skipNBytes(MAGIC.length);
            Object value = reader.read();
            if (value == END || value == BLOCK) {
                throw new IllegalArgumentException("the stream starts with block data");
            }
            return value;
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("malformed Java serialization stream: " + e.getMessage(), e);
        }
    }

    private Object read() throws IOException {
        int tc = in.readUnsignedByte();
        return switch (tc) {
            case TC_NULL -> null;
            case TC_REFERENCE -> handle(in.readInt());
            case TC_STRING -> remember(readUtf(in.readUnsignedShort()));
            case TC_LONGSTRING -> remember(readUtf(longLength()));
            case TC_CLASSDESC -> classDesc();
            case TC_OBJECT -> object();
            case TC_ARRAY -> array();
            case TC_ENUM -> enumConstant();
            case TC_BLOCKDATA -> skipBlock(in.readUnsignedByte());
            case TC_BLOCKDATALONG -> skipBlock(in.readInt());
            case TC_ENDBLOCKDATA -> END;
            default -> throw new IllegalArgumentException(
                    "unsupported Java serialization tag 0x" + Integer.toHexString(tc)
                            + " at byte " + (length - raw.available() - 1));
        };
    }

    private Object remember(Object value) {
        handles.add(value);
        return value;
    }

    private Object handle(int reference) {
        int index = reference - BASE_HANDLE;
        if (index < 0 || index >= handles.size()) {
            throw new IllegalArgumentException(
                    "reference to unknown handle 0x" + Integer.toHexString(reference));
        }
        return handles.get(index);
    }

    private Object skipBlock(int count) throws IOException {
        if (count < 0) {
            throw new IllegalArgumentException("negative block length");
        }
        in.skipNBytes(count);
        return BLOCK;
    }

    private ClassDesc classDesc() throws IOException {
        String name = readUtf(in.readUnsignedShort());
        in.skipNBytes(8); // serialVersionUID
        int slot = handles.size();
        handles.add(null);
        int flags = in.readUnsignedByte();
        int count = in.readUnsignedShort();
        List<Field> fields = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            char kind = (char) in.readUnsignedByte();
            String fieldName = readUtf(in.readUnsignedShort());
            if (kind == 'L' || kind == '[') {
                read(); // the field's type string
            }
            fields.add(new Field(kind, fieldName));
        }
        while (read() != END) {
            // the class annotation: nothing this reader needs
        }
        Object superDesc = read();
        ClassDesc desc = new ClassDesc(name, flags, fields, superDesc);
        handles.set(slot, desc);
        return desc;
    }

    private Object object() throws IOException {
        if (!(read() instanceof ClassDesc desc)) {
            throw new IllegalArgumentException("object without a class descriptor");
        }
        Map<String, Object> fields = new LinkedHashMap<>();
        JavaObject obj = new JavaObject(desc.name(), fields);
        int slot = handles.size();
        handles.add(obj);

        // Superclass data comes first, each class's fields in the order the stream lists them.
        List<ClassDesc> chain = new ArrayList<>();
        Object cursor = desc;
        while (cursor instanceof ClassDesc c) {
            chain.add(c);
            cursor = c.superDesc();
        }
        List<Object> extra = new ArrayList<>();
        for (int i = chain.size() - 1; i >= 0; i--) {
            ClassDesc klass = chain.get(i);
            for (Field field : klass.fields()) {
                fields.put(field.name(), isPrimitive(field.kind()) ? primitive(field.kind()) : read());
            }
            if ((klass.flags() & SC_WRITE_METHOD) != 0) {
                Object item;
                while ((item = read()) != END) {
                    if (item != BLOCK) {
                        extra.add(item);
                    }
                }
            }
        }
        if ("java.util.ArrayList".equals(desc.name())) {
            // ArrayList.writeObject writes the size as block data, then the elements.
            handles.set(slot, extra);
            return extra;
        }
        if (desc.name().startsWith(BOX_PREFIX) && BOXES.contains(desc.name().substring(BOX_PREFIX.length()))) {
            Object value = fields.get("value");
            handles.set(slot, value);
            return value;
        }
        return obj;
    }

    private List<Object> array() throws IOException {
        if (!(read() instanceof ClassDesc desc) || desc.name().length() < 2) {
            throw new IllegalArgumentException("array without a class descriptor");
        }
        List<Object> items = new ArrayList<>();
        handles.add(items);
        char element = desc.name().charAt(1);
        int size = in.readInt();
        // Every element takes at least one byte, so a longer count than the stream holds is malformed.
        if (size < 0 || size > raw.available()) {
            throw new IllegalArgumentException("array of " + size + " elements is longer than the stream");
        }
        for (int i = 0; i < size; i++) {
            items.add(isPrimitive(element) ? primitive(element) : read());
        }
        return items;
    }

    private Object enumConstant() throws IOException {
        read(); // the enum's class descriptor
        int slot = handles.size();
        handles.add(null);
        Object name = read();
        handles.set(slot, name);
        return name;
    }

    private Object primitive(char kind) throws IOException {
        return switch (kind) {
            case 'B' -> in.readByte();
            case 'C' -> (char) in.readUnsignedShort();
            case 'D' -> in.readDouble();
            case 'F' -> in.readFloat();
            case 'I' -> in.readInt();
            case 'J' -> in.readLong();
            case 'S' -> in.readShort();
            case 'Z' -> in.readBoolean();
            default -> throw new IllegalArgumentException("unknown primitive type code " + kind);
        };
    }

    private static boolean isPrimitive(char kind) {
        return PRIMITIVE_KINDS.indexOf(kind) >= 0;
    }

    private long longLength() throws IOException {
        long longLength = in.readLong();
        if (longLength < 0 || longLength > Integer.MAX_VALUE) {
            throw new IllegalArgumentException("string length " + longLength + " is out of range");
        }
        return longLength;
    }

    /** Reads {@code count} bytes of modified UTF-8, refusing a count the stream cannot hold. */
    private String readUtf(long count) throws IOException {
        if (count > raw.available()) {
            throw new IOException("string of " + count + " bytes is longer than the stream");
        }
        byte[] bytes = new byte[(int) count];
        in.readFully(bytes);
        return decodeModifiedUtf8(bytes);
    }

    /** Modified UTF-8, as {@code DataInput.readUTF} reads it: NUL is two bytes, supplementary characters two surrogates. */
    static String decodeModifiedUtf8(byte[] bytes) {
        StringBuilder text = new StringBuilder(bytes.length);
        int i = 0;
        while (i < bytes.length) {
            int b = bytes[i] & 0xFF;
            if (b < 0x80) {
                text.append((char) b);
                i += 1;
            } else if ((b & 0xE0) == 0xC0 && i + 1 < bytes.length) {
                text.append((char) (((b & 0x1F) << 6) | (bytes[i + 1] & 0x3F)));
                i += 2;
            } else if ((b & 0xF0) == 0xE0 && i + 2 < bytes.length) {
                text.append((char) (((b & 0x0F) << 12) | ((bytes[i + 1] & 0x3F) << 6) | (bytes[i + 2] & 0x3F)));
                i += 3;
            } else {
                throw new IllegalArgumentException("bad modified UTF-8 at byte " + i);
            }
        }
        return text.toString();
    }
}
