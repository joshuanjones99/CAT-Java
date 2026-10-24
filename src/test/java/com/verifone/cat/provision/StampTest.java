package com.verifone.cat.provision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class StampTest {

    /** The Python CAT's {@code Stamp.for_manifest(...).to_bytes()} for the register table, same inputs. */
    static final String PYTHON_REGISTER_STAMP = """
            {
              "files": {
                "/etc/opt/chrome_for_testing/policies/managed/petrotest-no-infobars.json": "18ffe076c9f1707eb524ac31396b978e1b6f7190f4c4dbf855b064fd54ee9ea4",
                "/home/common/filebeat-8.2.2-linux-x86_64/filebeat.yml": "7951ddd512e4b1d050b151532e5e9d24410910d861c6c4368f36a726496dcafe",
                "/home/common/lib/jacocoagent.jar": "e8421e306d70e342767e0e9504ffcb2c4a8f481bc7e820d2d291461747ccd221",
                "/home/topaz/automation/petrotest-pos-shim.jar": "1bb1a02bccd5646e2dcb90d18d4b9ce8da578ad9bfe3d073af0c4466d65503de",
                "/home/topaz/automation/petrotest-pos-shim.sh": "d534c52d75bbe6e3401649865c18f05458d2aed0eaf2cb8e4ae7277d45844a04",
                "/home/topaz/browser/chromium/CdpRelay.java": "a5f721f2afe56c06ba59c17d76a1f42ec89489170e24e64158a9aa0405f8a176",
                "/home/topaz/browser/chromium/cdp-relay-boot.sh": "cfc8a9d51b8541da9e5f8f18a8ed433efe432dea8e2ec62a301c64c23b2bf79b",
                "/home/topaz/browser/chromium/chrome-linux64-152.0.7977.42.tar.gz": "5127bfb37283d50201cf0219cb22adb4353cea38cc2000c8bcbc055944cae140",
                "/home/topaz/browser/chromium/lib/libcups.so.2": "732ab8674d7a19f30395a4ce9b9b8eb1d672db918509807bd8cfa2cdf12ea9ac",
                "/home/topaz/browser/chromium/lib/libxkbcommon.so.0": "64758623825d2c9df95a02ad63c0a6dc85f64382b7314e0d8b6c9ce15d1f2420",
                "/home/topaz/config/private_system.prop": "6eb60c733d84933e1656bda5b055b0e1ee23137f30e95ede11a2f4617b5c64f5",
                "/home/topaz/config/tokenRMI.prop": "1b37a1539747cd275588a7cfe27d41ec305e191941fd7b37b1d467a8e422324c",
                "/home/topaz/lib/automation-target-pos-topaz.jar": "aa704371c72c721a79d4b13d0f790dc5a7f902580c10c2da30a4de1878c7f4a2",
                "/home/topaz/lib/topazerp-connection-enabler.jar": "00b91b1da63b20fde30c15ba7d91fa32eef6adecd4d94fa44a819cadafc36723",
                "/usr/local/bin/chromium": "307e92cec0f22b58d1142a4a24e2bfd6bbdef014e6dd0a77c624cffab71570fb"
              },
              "format": 1,
              "manifest_digest": "eaab9d95170574d64481e8042c8b45c72e40f81ec4f8efb63530b0708b868748",
              "provisioned_at": "2026-10-24T11:40:05+00:00",
              "rig": "testrig",
              "role": "register",
              "tool_version": "t"
            }
            """;

    private static Manifest registerManifest() {
        return Manifest.build(TargetRole.REGISTER, ManifestTest.contentOfSource(), Artifacts.ARTIFACTS);
    }

    @Test
    void theStampFileIsThePythonCatalogueByteForByte() {
        Stamp stamp = Stamp.forManifest(registerManifest(), "testrig", "t",
                Instant.parse("2026-10-24T11:40:05Z"));

        assertEquals(PYTHON_REGISTER_STAMP, new String(stamp.toBytes(), StandardCharsets.UTF_8));
    }

    @Test
    void aStampReadsBackAndMatchesItsManifest() {
        Manifest manifest = registerManifest();
        Stamp written = Stamp.forManifest(manifest, "testrig", "t", Instant.parse("2026-10-24T11:40:05Z"));

        Stamp read = Stamp.parse(written.toBytes());

        assertNotNull(read);
        assertTrue(read.matches(manifest));
        assertTrue(read.drift(manifest).isEmpty());
        assertEquals("testrig", read.rig());
    }

    @Test
    void aPythonWrittenStampIsReadAsTheSameStamp() {
        Stamp read = Stamp.parse(PYTHON_REGISTER_STAMP.getBytes(StandardCharsets.UTF_8));

        assertNotNull(read);
        assertTrue(read.matches(registerManifest()));
        assertEquals(15, read.files().size());
    }

    @Test
    void driftNamesWhatChangedAndWhatWentAway() {
        Manifest manifest = registerManifest();
        Stamp recorded = Stamp.forManifest(manifest, "testrig", "t", Instant.parse("2026-10-24T11:40:05Z"));
        Map<String, String> files = new HashMap<>(recorded.files());
        files.put("/home/topaz/config/tokenRMI.prop", "0".repeat(64));
        files.remove("/usr/local/bin/chromium");
        files.put("/home/topaz/old-file", "1".repeat(64));
        Stamp edited = new Stamp(recorded.manifestDigest(), recorded.role(), "testrig", "t", "x", files);

        assertEquals(List.of("/home/topaz/config/tokenRMI.prop", "/home/topaz/old-file", "/usr/local/bin/chromium"),
                edited.drift(manifest));
    }

    @Test
    void aDifferentDigestDoesNotMatch() {
        Stamp stamp = new Stamp("0".repeat(64), TargetRole.REGISTER, "testrig", "t", "x", Map.of());

        assertFalse(stamp.matches(registerManifest()));
        assertNull(Stamp.parse("{\"format\": 1, \"role\": \"martian\", \"manifest_digest\": \"x\", \"files\": {}}"
                .getBytes(StandardCharsets.UTF_8)));
    }
}
