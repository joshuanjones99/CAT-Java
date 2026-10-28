package com.verifone.cat.sims;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link Capk}: the Python's {@code tests/unit/test_cat_sims_capk.py} TestRedate, TestRecut and
 * TestBuild, ported over the same synthetic class bytes. Evidence about this port's bytes only;
 * not that the Commander accepts the keys. The real jar is never read here.
 */
class CapkTest {

    /** The shape of the real key file (the Python test's {@code _KEYS}). */
    private static final String KEYS =
            "CA_KEYS,011712312024,01,01,A000000025,C9,B362,03,8E8DFF443D78CD91DE88821D70C98F0638E51E49"
            + "12312020,01,01,A000000277,02,E0FF,010001,BAF3652A9F5D0FF45EECECA55B4597B1F53BE283"
            + "12312030,01,01,A000000333,0B,CF9F,03,BD331F9996A490B33C13441066A09AD3FEB5F66C";
    private static final String PACKET =
            "<FBData>CA_KEYS,011712312024,01,01,A000000025,C9,B362,03,8E8D</FBData>";
    private static final String CUT = "\t<TTCutTime>1330</TTCutTime>\r";

    @TempDir
    Path tmp;

    /** The Python test's {@code _class}: a class-file magic, the strings joined by junk, a tail. */
    private static byte[] klass(String... strings) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0xca, (byte) 0xfe, (byte) 0xba, (byte) 0xbe});
        out.writeBytes(String.join("\u0000junk\u0001", strings).getBytes(StandardCharsets.ISO_8859_1));
        out.writeBytes("\u0000tail".getBytes(StandardCharsets.ISO_8859_1));
        return out.toByteArray();
    }

    private static String latin1(byte[] data) {
        return new String(data, StandardCharsets.ISO_8859_1);
    }

    /** A jar shaped as the Python test's {@code _jar}, plus a STORED entry to prove the method is kept. */
    private static Path jar(Path path, byte[] classBytes) throws IOException {
        try (ZipOutputStream out = new ZipOutputStream(Files.newOutputStream(path))) {
            out.putNextEntry(new ZipEntry("META-INF/MANIFEST.MF"));
            out.write("Main-Class: ArbitraryHostSim\n".getBytes(StandardCharsets.UTF_8));
            out.putNextEntry(new ZipEntry(Capk.CLASS));
            out.write(classBytes);
            out.putNextEntry(new ZipEntry("Other.class"));
            out.write("untouched".getBytes(StandardCharsets.UTF_8));
            byte[] stored = "stored bytes".getBytes(StandardCharsets.UTF_8);
            ZipEntry entry = new ZipEntry("res/Stored.bin");
            entry.setMethod(ZipEntry.STORED);
            CRC32 crc = new CRC32();
            crc.update(stored);
            entry.setSize(stored.length);
            entry.setCompressedSize(stored.length);
            entry.setCrc(crc.getValue());
            out.putNextEntry(entry);
            out.write(stored);
            out.closeEntry();
        }
        return path;
    }

    private static byte[] read(ZipFile zip, String name) throws IOException {
        try (InputStream in = zip.getInputStream(zip.getEntry(name))) {
            return in.readAllBytes();
        }
    }

    // --- TestRedate ---

    @Test
    void expiriesAreMonthEndsThreeMonthsApart() {
        assertEquals(List.of("03312029", "06302029", "09302029", "12312029", "03312030"),
                List.of(Capk.expiry(0), Capk.expiry(1), Capk.expiry(2), Capk.expiry(3), Capk.expiry(4)));
    }

    @Test
    void theEighteenthKeyExpiresInJune2033() {
        // March 2029 plus 17 * 3 months; the real key file has 18 keys.
        assertEquals("06302033", Capk.expiry(17));
    }

    @Test
    void onlyTheDatesChangeAndEachKeyFileCountsFromItsFirstKey() {
        byte[] data = klass(PACKET, KEYS, CUT);
        String expected = latin1(data).replace("12312024", "03312029")
                .replace("12312020", "06302029").replace("12312030", "09302029");
        assertEquals(expected, latin1(Capk.redate(data)));
        assertArrayEquals(expected.getBytes(StandardCharsets.ISO_8859_1), Capk.redate(data));
    }

    @Test
    void aClassWithNoKeyFileIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Capk.redate(klass("nothing here")));
        assertTrue(e.getMessage().contains("CA_KEYS"), e.getMessage());
    }

    @Test
    void aKeyFileInAnotherLayoutIsRefused() {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Capk.redate(klass("CA_KEYS,2024-12-31;A000000025")));
        assertTrue(e.getMessage().contains("expected layout"), e.getMessage());
    }

    @Test
    void highBytesSurviveUnchanged() {
        byte[] data = klass(PACKET, KEYS, CUT);
        data[1] = (byte) 0xfe;
        assertEquals((byte) 0xfe, Capk.redate(data)[1]);
        assertEquals(data.length, Capk.redate(data).length);
    }

    // --- TestRecut ---

    @Test
    void theCutTimeMovesAndNothingElseDoes() {
        byte[] data = klass(PACKET, KEYS, CUT);
        assertEquals(latin1(data).replace(">1330<", ">" + Capk.CUT_TIME + "<"), latin1(Capk.recut(data)));
    }

    @Test
    void aClassWithoutOneCutTimeIsRefused() {
        IllegalArgumentException none = assertThrows(IllegalArgumentException.class,
                () -> Capk.recut(klass(KEYS)));
        assertTrue(none.getMessage().contains("found 0"), none.getMessage());
        IllegalArgumentException two = assertThrows(IllegalArgumentException.class,
                () -> Capk.recut(klass(CUT, CUT)));
        assertTrue(two.getMessage().contains("found 2"), two.getMessage());
    }

    // --- TestBuild ---

    @Test
    void writesTheTargetOnceAndLeavesEveryOtherEntryAlone() throws IOException {
        Path source = jar(tmp.resolve("src.jar"), klass(PACKET, KEYS, CUT));
        Path target = tmp.resolve("cat.jar");

        assertTrue(Capk.build(source, target));
        long written = Files.getLastModifiedTime(target).toMillis();
        byte[] first = Files.readAllBytes(target);
        assertFalse(Capk.build(source, target), "an up-to-date jar is not rewritten");
        assertArrayEquals(first, Files.readAllBytes(target));
        assertEquals(written, Files.getLastModifiedTime(target).toMillis());

        try (ZipFile a = new ZipFile(source.toFile()); ZipFile b = new ZipFile(target.toFile())) {
            List<String> namesA = new ArrayList<>();
            List<String> namesB = new ArrayList<>();
            Collections.list(a.entries()).forEach(e -> namesA.add(e.getName()));
            Collections.list(b.entries()).forEach(e -> namesB.add(e.getName()));
            assertEquals(namesA, namesB);
            assertEquals("META-INF/MANIFEST.MF", namesB.get(0), "the manifest stays first");
            for (String name : namesA) {
                assertEquals(a.getEntry(name).getMethod(), b.getEntry(name).getMethod(), name);
                assertEquals(a.getEntry(name).getTime(), b.getEntry(name).getTime(), name);
                if (!name.equals(Capk.CLASS)) {
                    assertArrayEquals(read(a, name), read(b, name), name);
                }
            }
            assertEquals(ZipEntry.STORED, b.getEntry("res/Stored.bin").getMethod());
            assertArrayEquals(Capk.recut(Capk.redate(read(a, Capk.CLASS))), read(b, Capk.CLASS));
        }
        assertFalse(Files.exists(tmp.resolve("cat.jar.tmp")));
    }

    @Test
    void aStaleTargetIsReplaced() throws IOException {
        Path source = jar(tmp.resolve("src.jar"), klass(PACKET, KEYS, CUT));
        Path target = tmp.resolve("cat.jar");
        Files.writeString(target, "stale");
        assertTrue(Capk.build(source, target));
        assertArrayEquals(Capk.patched(source), Files.readAllBytes(target));
    }

    @Test
    void aJarWithoutTheClassIsRefused() throws IOException {
        Path source = tmp.resolve("src.jar");
        Files.write(source, Zips.zip("Other.class", "x"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> Capk.build(source, tmp.resolve("cat.jar")));
        assertTrue(e.getMessage().contains(Capk.CLASS), e.getMessage());
        assertFalse(Files.exists(tmp.resolve("cat.jar")));
    }

    // --- SimContext.jarFor (the Python's SimContext.jar_for) ---

    @Test
    void theJarIsTheFirstNonOptionAfterDashJarUnderTheRowsCwd() {
        SimContext ctx = SimContext.of(tmp, "");
        SimEntry buypass = SimEntry.javaw("BUYPASS Host").cwd("BUYPASS")
                .argv("-jar", "ArbitraryHostSim_v5.00-cat.jar").build();
        assertEquals(tmp.resolve("BUYPASS").resolve("ArbitraryHostSim_v5.00-cat.jar"),
                ctx.jarFor(buypass).orElseThrow());
        SimEntry petro = SimEntry.javaw("Petro").argv("-jar", "-agentlib:jdwp=x", "{cwd}/p.jar").build();
        assertEquals(tmp.resolve("p.jar"), ctx.jarFor(petro).orElseThrow());
        assertTrue(ctx.jarFor(SimEntry.javaw("None").argv("-cp", "x").build()).isEmpty());
        assertTrue(ctx.jarFor(SimEntry.javaw("Trailing").argv("x", "-jar").build()).isEmpty());
    }

    @Test
    void theRegistryRowRunsCatsJarBuiltFromPatss() {
        SimEntry entry = Registry.byName("BUYPASS Host").orElseThrow();
        assertTrue(entry.adoptOnlyMatch());
        assertEquals("ArbitraryHostSim_v5.00.jar", entry.capkFrom());
        assertEquals(tmp.resolve("BUYPASS").resolve("ArbitraryHostSim_v5.00-cat.jar"),
                SimContext.of(tmp, "").jarFor(entry).orElseThrow());
    }
}
