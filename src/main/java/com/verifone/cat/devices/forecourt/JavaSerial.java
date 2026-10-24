package com.verifone.cat.devices.forecourt;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Java Object Serialization, the subset Petro Simulator Suite's socket streams use. Ported from the Python
 * {@code petrotest.devices.java_serial}.
 *
 * <p>Reading: an object comes back as a {@code Map} keyed by field name, with {@code "__class__"} set to the
 * class name; arrays come back as {@code List}s, except {@code byte[]} for {@code [B}; {@code ArrayList} as a
 * {@code List}; the boxed primitives ({@code java.lang.Short} and friends) as their value; enums as their
 * constant name. Integral primitives read as {@code Integer} (or {@code Long} for {@code J}).
 *
 * <p>Writing: {@link JavaClass} describes one serializable class the way the JVM does, and {@link Obj} and
 * {@link Arr} hold the values. {@link #dumps} writes one stream. Type strings are shared by back-reference and
 * each class descriptor is written once, as the JVM does.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The bytes are checked against the JDK-written fixtures the Python
 * tests use.
 */
public final class JavaSerial {

    /** The stream header every {@code ObjectOutputStream} starts with. */
    public static final byte[] MAGIC = {(byte) 0xAC, (byte) 0xED, 0x00, 0x05};

    private static final int TC_NULL = 0x70;
    private static final int TC_REFERENCE = 0x71;
    private static final int TC_CLASSDESC = 0x72;
    private static final int TC_OBJECT = 0x73;
    private static final int TC_STRING = 0x74;
    private static final int TC_ARRAY = 0x75;
    private static final int TC_BLOCKDATA = 0x77;
    private static final int TC_ENDBLOCKDATA = 0x78;
    private static final int TC_LONGSTRING = 0x7C;
    private static final int TC_ENUM = 0x7E;
    private static final int SC_WRITE_METHOD = 0x01;
    private static final int SC_SERIALIZABLE = 0x02;
    private static final int BASE_HANDLE = 0x7E0000;
    private static final String PRIMITIVES = "BCDFIJSZ";
    private static final Set<String> BOXES = Set.of("java.lang.Boolean", "java.lang.Byte", "java.lang.Character",
            "java.lang.Double", "java.lang.Float", "java.lang.Integer", "java.lang.Long", "java.lang.Short");

    private static final Object END = new Object();
    private static final Object BLOCK = new Object();

    private JavaSerial() {
    }

    /** A stream this reader cannot take: truncated so far, malformed, or not a serialization stream. */
    public static final class StreamException extends IllegalArgumentException {
        private static final long serialVersionUID = 1L;

        public StreamException(String message) {
            super(message);
        }
    }

    /** One field of a class: its type code ({@code S}, {@code L}, {@code [}...), its name, and for references its type string. */
    public record Field(char code, String name, String typeSig) {
        /** A primitive field. */
        public static Field prim(char code, String name) {
            return new Field(code, name, null);
        }
    }

    /** A serializable class as {@code ObjectStreamClass} describes it. {@code flags} defaults to {@code SC_SERIALIZABLE}. */
    public record JavaClass(String name, long suid, List<Field> fields, JavaClass superClass, int flags) {
        public JavaClass {
            fields = List.copyOf(fields);
        }

        public JavaClass(String name, long suid, List<Field> fields, JavaClass superClass) {
            this(name, suid, fields, superClass, SC_SERIALIZABLE);
        }

        public JavaClass(String name, long suid) {
            this(name, suid, List.of(), null, SC_SERIALIZABLE);
        }
    }

    /** An instance: its class and its field values by name. Missing fields are Java defaults. */
    public record Obj(JavaClass cls, Map<String, Object> values) {
        public Obj {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        public Obj(JavaClass cls) {
            this(cls, Map.of());
        }
    }

    /** An array: {@code cls} is the array class (for example {@code [Ljava.lang.Short;}), {@code items} its elements. */
    public record Arr(JavaClass cls, List<?> items) {
        public Arr {
            items = Collections.unmodifiableList(new ArrayList<>(items));
        }
    }

    // -- reading ---------------------------------------------------------------

    /** The first object in one {@code ObjectOutputStream} stream. */
    public static Object loads(byte[] data) {
        if (data.length < MAGIC.length || !Arrays.equals(Arrays.copyOf(data, MAGIC.length), MAGIC)) {
            throw new StreamException("not a Java serialization stream");
        }
        Reader reader = new Reader(data);
        reader.pos = MAGIC.length;
        return reader.read();
    }

    /** A class descriptor as the reader holds it. */
    private static final class ClassDesc {
        final String name;
        final List<Field> fields = new ArrayList<>();
        int flags;
        ClassDesc superDesc;

        ClassDesc(String name) {
            this.name = name;
        }
    }

    private static final class Reader {
        private final byte[] data;
        private int pos;
        private final List<Object> handles = new ArrayList<>();

        Reader(byte[] data) {
            this.data = data;
        }

        byte[] take(int n) {
            if (n < 0 || (long) pos + n > data.length) {
                throw new StreamException("truncated Java serialization stream");
            }
            byte[] chunk = Arrays.copyOfRange(data, pos, pos + n);
            pos += n;
            return chunk;
        }

        int u8() {
            return take(1)[0] & 0xFF;
        }

        int u16() {
            return ByteBuffer.wrap(take(2)).order(ByteOrder.BIG_ENDIAN).getShort() & 0xFFFF;
        }

        int s32() {
            return ByteBuffer.wrap(take(4)).order(ByteOrder.BIG_ENDIAN).getInt();
        }

        long s64() {
            return ByteBuffer.wrap(take(8)).order(ByteOrder.BIG_ENDIAN).getLong();
        }

        String utf(long length) {
            if (length < 0 || length > Integer.MAX_VALUE) {
                throw new StreamException("string length out of range");
            }
            return new String(take((int) length), StandardCharsets.UTF_8);
        }

        Object primitive(char kind) {
            ByteBuffer buf = ByteBuffer.wrap(take(primitiveSize(kind))).order(ByteOrder.BIG_ENDIAN);
            return switch (kind) {
                case 'B' -> (int) buf.get();
                case 'C' -> buf.getShort() & 0xFFFF;
                case 'D' -> buf.getDouble();
                case 'F' -> buf.getFloat();
                case 'I' -> buf.getInt();
                case 'J' -> buf.getLong();
                case 'S' -> (int) buf.getShort();
                case 'Z' -> buf.get() != 0;
                default -> throw new StreamException("unknown primitive kind " + kind);
            };
        }

        Object read() {
            int tc = u8();
            switch (tc) {
                case TC_NULL:
                    return null;
                case TC_REFERENCE:
                    return handles.get(s32() - BASE_HANDLE);
                case TC_STRING:
                case TC_LONGSTRING: {
                    long length = tc == TC_STRING ? u16() : s64();
                    String text = utf(length);
                    handles.add(text);
                    return text;
                }
                case TC_CLASSDESC:
                    return classDesc();
                case TC_OBJECT:
                    return object();
                case TC_ARRAY:
                    return array();
                case TC_ENUM: {
                    read(); // the enum's class descriptor
                    int handle = handles.size();
                    handles.add(null);
                    Object name = read();
                    handles.set(handle, name);
                    return name;
                }
                case TC_BLOCKDATA:
                    take(u8());
                    return BLOCK;
                case TC_ENDBLOCKDATA:
                    return END;
                default:
                    throw new StreamException(String.format(
                            "unsupported Java serialization tag 0x%02x at byte %d", tc, pos - 1));
            }
        }

        private ClassDesc classDesc() {
            ClassDesc desc = new ClassDesc(utf(u16()));
            take(8); // serialVersionUID
            handles.add(desc);
            desc.flags = u8();
            int count = u16();
            for (int i = 0; i < count; i++) {
                char kind = (char) u8();
                String name = utf(u16());
                if (kind == 'L' || kind == '[') {
                    read(); // the field's type string
                }
                desc.fields.add(Field.prim(kind, name));
            }
            while (read() != END) {
                // class annotation: nothing to keep
            }
            desc.superDesc = (ClassDesc) read();
            return desc;
        }

        private Object array() {
            ClassDesc desc = (ClassDesc) read();
            int index = handles.size();
            List<Object> items = new ArrayList<>();
            handles.add(items);
            char element = desc.name.charAt(1);
            int length = s32();
            for (int i = 0; i < length; i++) {
                items.add(PRIMITIVES.indexOf(element) >= 0 ? primitive(element) : read());
            }
            if (element == 'B') {
                byte[] bytes = new byte[items.size()];
                for (int i = 0; i < bytes.length; i++) {
                    bytes[i] = (byte) (int) (Integer) items.get(i);
                }
                handles.set(index, bytes);
                return bytes;
            }
            return items;
        }

        private Object object() {
            ClassDesc desc = (ClassDesc) read();
            int handle = handles.size();
            Map<String, Object> obj = new LinkedHashMap<>();
            obj.put("__class__", desc.name);
            handles.add(obj);
            List<ClassDesc> chain = new ArrayList<>();
            for (ClassDesc d = desc; d != null; d = d.superDesc) {
                chain.add(d);
            }
            List<Object> extra = new ArrayList<>();
            for (int c = chain.size() - 1; c >= 0; c--) {
                ClassDesc klass = chain.get(c);
                for (Field field : klass.fields) {
                    obj.put(field.name(), PRIMITIVES.indexOf(field.code()) >= 0
                            ? primitive(field.code()) : read());
                }
                if ((klass.flags & SC_WRITE_METHOD) != 0) {
                    Object item;
                    while ((item = read()) != END) {
                        if (item != BLOCK) {
                            extra.add(item);
                        }
                    }
                }
            }
            if ("java.util.ArrayList".equals(desc.name)) {
                handles.set(handle, extra);
                return extra;
            }
            if (BOXES.contains(desc.name)) {
                Object value = obj.get("value");
                handles.set(handle, value);
                return value;
            }
            return obj;
        }

        private static int primitiveSize(char kind) {
            return switch (kind) {
                case 'B', 'Z' -> 1;
                case 'C', 'S' -> 2;
                case 'F', 'I' -> 4;
                case 'D', 'J' -> 8;
                default -> throw new StreamException("unknown primitive kind " + kind);
            };
        }
    }

    // -- writing ---------------------------------------------------------------

    /** One {@code ObjectOutputStream.writeObject(value)} stream, for an {@link Obj} or {@link Arr}. */
    public static byte[] dumps(Object value) {
        if (!(value instanceof Obj) && !(value instanceof Arr)) {
            throw new IllegalArgumentException("cannot serialize " + value + "; wrap it in Obj or Arr");
        }
        Writer writer = new Writer();
        writer.value(value);
        return writer.out.toByteArray();
    }

    private static final class Writer {
        private final ByteArrayOutputStream out = new ByteArrayOutputStream();
        private int nextHandle = BASE_HANDLE;
        private final Map<String, Integer> handles = new HashMap<>();

        Writer() {
            out.write(MAGIC, 0, MAGIC.length);
        }

        private void newHandle(String key) {
            if (key != null) {
                handles.put(key, nextHandle);
            }
            nextHandle++;
        }

        private boolean reference(String key) {
            Integer handle = handles.get(key);
            if (handle == null) {
                return false;
            }
            out.write(TC_REFERENCE);
            u32(handle);
            return true;
        }

        private void u16(int v) {
            out.write((v >>> 8) & 0xFF);
            out.write(v & 0xFF);
        }

        private void u32(int v) {
            for (int shift = 24; shift >= 0; shift -= 8) {
                out.write((v >>> shift) & 0xFF);
            }
        }

        private void utf(String text) {
            byte[] data = text.getBytes(StandardCharsets.UTF_8);
            u16(data.length);
            out.write(data, 0, data.length);
        }

        void typeString(String text) {
            String key = "s|" + text;
            if (!reference(key)) {
                out.write(TC_STRING);
                utf(text);
                newHandle(key);
            }
        }

        void classDesc(JavaClass cls) {
            if (cls == null) {
                out.write(TC_NULL);
                return;
            }
            String key = "c|" + cls.name();
            if (reference(key)) {
                return;
            }
            out.write(TC_CLASSDESC);
            utf(cls.name());
            byte[] suid = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putLong(cls.suid()).array();
            out.write(suid, 0, suid.length);
            out.write(cls.flags());
            u16(cls.fields().size());
            newHandle(key);
            for (Field field : cls.fields()) {
                out.write(field.code());
                utf(field.name());
                if (field.typeSig() != null) {
                    typeString(field.typeSig());
                }
            }
            out.write(TC_ENDBLOCKDATA);
            classDesc(cls.superClass());
        }

        void value(Object value) {
            if (value == null) {
                out.write(TC_NULL);
            } else if (value instanceof String text) {
                out.write(TC_STRING);
                utf(text);
                newHandle(null);
            } else if (value instanceof Arr arr) {
                out.write(TC_ARRAY);
                classDesc(arr.cls());
                newHandle(null);
                u32(arr.items().size());
                char element = arr.cls().name().charAt(1);
                for (Object item : arr.items()) {
                    if (PRIMITIVES.indexOf(element) >= 0) {
                        primitive(element, item);
                    } else {
                        value(item);
                    }
                }
            } else if (value instanceof Obj obj) {
                out.write(TC_OBJECT);
                classDesc(obj.cls());
                newHandle(null);
                List<JavaClass> chain = new ArrayList<>();
                for (JavaClass c = obj.cls(); c != null; c = c.superClass()) {
                    chain.add(c);
                }
                for (int c = chain.size() - 1; c >= 0; c--) {
                    for (Field field : chain.get(c).fields()) {
                        Object item = obj.values().get(field.name());
                        if (PRIMITIVES.indexOf(field.code()) >= 0) {
                            primitive(field.code(), item);
                        } else {
                            value(item);
                        }
                    }
                }
            } else {
                throw new IllegalArgumentException("cannot serialize " + value + "; wrap it in Obj or Arr");
            }
        }

        private void primitive(char kind, Object item) {
            if (kind == 'Z') {
                out.write(Boolean.TRUE.equals(item) ? 1 : 0);
                return;
            }
            if (kind == 'C' && item instanceof String text) {
                item = text.isEmpty() ? 0 : (int) text.charAt(0);
            }
            Number n = item instanceof Number num ? num : 0;
            switch (kind) {
                case 'B' -> out.write(n.intValue() & 0xFF);
                case 'C', 'S' -> u16(n.intValue() & 0xFFFF);
                case 'I' -> u32(n.intValue());
                case 'J' -> {
                    long v = n.longValue();
                    for (int shift = 56; shift >= 0; shift -= 8) {
                        out.write((int) ((v >>> shift) & 0xFF));
                    }
                }
                case 'D' -> {
                    byte[] d = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).putDouble(n.doubleValue()).array();
                    out.write(d, 0, d.length);
                }
                case 'F' -> u32(Float.floatToIntBits(n.floatValue()));
                default -> throw new IllegalArgumentException("unknown primitive kind " + kind);
            }
        }
    }

    // -- the classes CAT sends -------------------------------------------------

    /** {@code java.lang.Number}, the superclass of {@link #SHORT}. */
    public static final JavaClass NUMBER = new JavaClass("java.lang.Number", -8742448824652078965L);

    /** {@code java.lang.Short}, as the JDK serializes it (for {@code Short[]} fields). */
    public static final JavaClass SHORT = new JavaClass("java.lang.Short", 7515723908773894738L,
            List.of(Field.prim('S', "value")), NUMBER);

    /** {@code Short[]}. */
    public static final JavaClass SHORT_ARRAY = new JavaClass("[Ljava.lang.Short;", -6432008644767987895L);
}
