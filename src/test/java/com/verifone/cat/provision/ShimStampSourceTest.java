package com.verifone.cat.provision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link ShimStamp#sourceStamp} on source trees written to disk, against the Python's
 * {@code petrotest.provision.shim_stamp.source_stamp}: the parity value was computed by the Python CAT's own function on
 * this exact fixture (2026-10-29), so a payload either CAT built carries one stamp. Evidence about this repository.
 */
class ShimStampSourceTest {

    @TempDir
    Path dir;

    static final String SHIM_JAVA = "bridge/pos-shim/src/main/java/com/verifone/petrotest/posshim";

    /** The Python's {@code source_stamp} of {@link #files()} (computed with the Python CAT's venv). */
    static final String PYTHON_STAMP = "54b7cf0c03f89c6787642fe41093edd9d91cc1617ea6f01c14189f0f4412b067";

    static Map<String, String> files() {
        Map<String, String> files = new LinkedHashMap<>();
        files.put("cat/" + SHIM_JAVA + "/Routes.java", "final class Routes {\n}\n");
        files.put("cat/" + SHIM_JAVA + "/Build.java", "final class Build {\n}\n");
        files.put("cat/" + SHIM_JAVA + "/a-b/Zed.java", "final class Zed {}\n");
        files.put("cat/" + SHIM_JAVA + "/a/Yak.java", "final class Yak {}\n");
        files.put("cat/" + SHIM_JAVA + "/apple.java", "x\r\ny\r");
        files.put("cat/bridge/pos-shim/petrotest-pos-shim.sh", "#!/bin/sh\nexec java\n");
        files.put("cat/tools/build_pos_shim.py", "TARGET_RELEASE = '17'\n");
        files.put("petroapps/" + ShimStamp.AGENT_SUBPATH + "/com/x/ObserverImpl.java", "class ObserverImpl {\n}\n");
        files.put("petroapps/" + ShimStamp.AGENT_SUBPATH + "/com/x/Base.java", "class Base {}\n");
        files.put("petroapps/" + ShimStamp.TOPAZERP_SUBPATH + "/" + ShimStamp.TOPAZERP_CLASSES.get(0),
                "class VsmsCommonImpl {\n}\n");
        return files;
    }

    static void write(Path root, Map<String, String> files, boolean crlf) throws IOException {
        for (Map.Entry<String, String> file : files.entrySet()) {
            Path path = ShimStamp.resolve(root, file.getKey());
            Files.createDirectories(path.getParent());
            String text = crlf ? file.getValue().replace("\r\n", "\n").replace("\n", "\r\n") : file.getValue();
            Files.write(path, text.getBytes(StandardCharsets.UTF_8));
        }
    }

    @Test
    void theStampIsThePythonsForTheSameSources() throws IOException {
        write(dir, files(), false);
        assertEquals(PYTHON_STAMP, ShimStamp.sourceStamp(dir.resolve("cat"), dir.resolve("petroapps")));
    }

    @Test
    void aCrlfCheckoutElsewhereIsTheSameBuild() throws IOException {
        Map<String, String> plain = new LinkedHashMap<>(files());
        plain.remove("cat/" + SHIM_JAVA + "/apple.java"); // its lone CR would change under the CRLF rewrite
        Path one = dir.resolve("one");
        Path two = dir.resolve("elsewhere").resolve("two");
        write(one, plain, false);
        write(two, plain, true);
        assertEquals(ShimStamp.sourceStamp(one.resolve("cat"), one.resolve("petroapps")),
                ShimStamp.sourceStamp(two.resolve("cat"), two.resolve("petroapps")));
    }

    @Test
    void aChangeOrARenameIsAnotherBuild() throws IOException {
        write(dir, files(), false);
        Path cat = dir.resolve("cat");
        Path petroapps = dir.resolve("petroapps");
        String before = ShimStamp.sourceStamp(cat, petroapps);
        Path routes = ShimStamp.resolve(cat, SHIM_JAVA + "/Routes.java");
        Files.move(routes, routes.resolveSibling("Routed.java"));
        assertNotEquals(before, ShimStamp.sourceStamp(cat, petroapps));
        Files.move(routes.resolveSibling("Routed.java"), routes);
        assertEquals(before, ShimStamp.sourceStamp(cat, petroapps));
        Files.writeString(ShimStamp.resolve(cat, "tools/build_pos_shim.py"), "TARGET_RELEASE = '21'\n");
        assertNotEquals(before, ShimStamp.sourceStamp(cat, petroapps));
    }

    @Test
    void aMissingInputIsNamed() throws IOException {
        write(dir, files(), false);
        Files.delete(ShimStamp.resolve(dir, "cat/bridge/pos-shim/petrotest-pos-shim.sh"));
        ShimStamp.StampInputMissing missing = assertThrows(ShimStamp.StampInputMissing.class,
                () -> ShimStamp.sourceStamp(dir.resolve("cat"), dir.resolve("petroapps")));
        assertTrue(missing.getMessage().startsWith("shim/petrotest-pos-shim.sh is not at"), missing.getMessage());

        ShimStamp.StampInputMissing noAgent = assertThrows(ShimStamp.StampInputMissing.class,
                () -> ShimStamp.sourceStamp(dir.resolve("cat"), dir.resolve("absent")));
        assertTrue(noAgent.getMessage().contains("no retained agent sources"), noAgent.getMessage());
    }

    @Test
    void thePayloadStampIsReadBack() throws IOException {
        assertEquals(Optional.empty(), ShimStamp.payloadStamp(dir));
        Files.writeString(dir.resolve(ShimStamp.STAMP_NAME), "ab12\n");
        assertEquals(Optional.of("ab12"), ShimStamp.payloadStamp(dir));
    }
}
