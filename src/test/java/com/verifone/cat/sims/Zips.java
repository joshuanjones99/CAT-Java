package com.verifone.cat.sims;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Zips built in memory, shaped as the Python CAT's tests record the real simulator and Java 17 zips. */
public final class Zips {

    private Zips() {
    }

    /** A zip of {@code name, bytes} pairs, in order. */
    public static byte[] zip(Object... namesAndBytes) {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        for (int i = 0; i < namesAndBytes.length; i += 2) {
            Object value = namesAndBytes[i + 1];
            entries.put((String) namesAndBytes[i],
                    value instanceof String text ? text.getBytes(StandardCharsets.UTF_8) : (byte[]) value);
        }
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(buffer)) {
            for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
                out.putNextEntry(new ZipEntry(entry.getKey()));
                out.write(entry.getValue());
                out.closeEntry();
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return buffer.toByteArray();
    }

    /** A jar carrying one JAXB API, as each Java set's {@code jaxb-ri/mod} does. */
    public static byte[] jaxbApi(String namespace) {
        return zip(namespace + "/xml/bind/JAXBException.class", new byte[0]);
    }

    /** A simulator jar whose one class references {@code <namespace>/xml/bind}. */
    public static byte[] suiteJar(String namespace) {
        return zip("com/verifone/Analyser.class", namespace + "/xml/bind/X");
    }

    /** Stages a Java set's {@code jaxb-ri/mod} under {@code tools/<target>} with {@code namespace}'s API. */
    public static void stageJava(Path tools, String target, String namespace) throws IOException {
        Path mod = Files.createDirectories(tools.resolve(target).resolve("jaxb-ri").resolve("mod"));
        Files.write(mod.resolve("api.jar"), jaxbApi(namespace));
    }
}
