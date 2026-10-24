package com.verifone.cat.provision;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The content-hash guard (DESIGN.md 6.1). The digests below are the Python CAT's own, computed over the same table
 * with the reader {@code "content-of-" + source}: the Java manifest must agree, or each tool would re-push what the
 * other provisioned.
 */
class ManifestTest {

    static final String COMMANDER_DIGEST = "0cd27369afb38578c4e47c4cd7ca196725f7eddac33277ef98600793a8ef9263";
    static final String REGISTER_DIGEST = "eaab9d95170574d64481e8042c8b45c72e40f81ec4f8efb63530b0708b868748";

    /** The Python CAT's {@code canonical_json()} for the commander role, byte for byte. */
    static final String COMMANDER_CANONICAL = "{\"entries\":[{\"artifact\":\"filebeat_config\",\"mode\":\"0644\","
            + "\"remote_path\":\"/home/common/filebeat-8.2.2-linux-x86_64/filebeat.yml\","
            + "\"sha256\":\"64d17a6e3fca3ef851d268422f8654310e25a77beae124f9193c4e7ad205de95\",\"size\":42},"
            + "{\"artifact\":\"jacoco\",\"mode\":\"0644\",\"remote_path\":\"/home/common/lib/jacocoagent.jar\","
            + "\"sha256\":\"e8421e306d70e342767e0e9504ffcb2c4a8f481bc7e820d2d291461747ccd221\",\"size\":33},"
            + "{\"artifact\":\"coresvcs_private_props\",\"mode\":\"0644\","
            + "\"remote_path\":\"/home/coresvcs/config/private_system.prop\","
            + "\"sha256\":\"b33c14af84d090f40fe64a77d7c6a64c20d7efe0661a56e72c5a1e8391988cbd\",\"size\":41}],"
            + "\"format\":1,\"role\":\"commander\"}";

    @TempDir
    Path dir;

    static Manifest.ContentReader contentOfSource() {
        return file -> ("content-of-" + file.source()).getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void digestsAgreeWithThePythonCatalogue() {
        assertEquals(COMMANDER_DIGEST,
                Manifest.build(TargetRole.COMMANDER, contentOfSource(), Artifacts.ARTIFACTS).digest());
        assertEquals(REGISTER_DIGEST,
                Manifest.build(TargetRole.REGISTER, contentOfSource(), Artifacts.ARTIFACTS).digest());
    }

    @Test
    void canonicalJsonIsThePythonCatalogueByteForByte() {
        assertEquals(COMMANDER_CANONICAL,
                Manifest.build(TargetRole.COMMANDER, contentOfSource(), Artifacts.ARTIFACTS).canonicalJson());
    }

    @Test
    void eachRoleHasItsPythonEntryCount() {
        assertEquals(3, Manifest.build(TargetRole.COMMANDER, contentOfSource(), Artifacts.ARTIFACTS).entries().size());
        assertEquals(15, Manifest.build(TargetRole.REGISTER, contentOfSource(), Artifacts.ARTIFACTS).entries().size());
    }

    @Test
    void digestIsIndependentOfTheOrderArtifactsAreGiven() {
        List<Artifact> reversed = new ArrayList<>(Artifacts.ARTIFACTS);
        java.util.Collections.reverse(reversed);
        assertEquals(Manifest.build(TargetRole.REGISTER, contentOfSource(), Artifacts.ARTIFACTS).digest(),
                Manifest.build(TargetRole.REGISTER, contentOfSource(), reversed).digest());
    }

    @Test
    void digestCoversDestinationAndModeNotJustContent() {
        String baseline = Manifest.build(TargetRole.REGISTER, contentOfSource(), Artifacts.ARTIFACTS).digest();

        List<Artifact> moved = new ArrayList<>();
        for (Artifact a : Artifacts.ARTIFACTS) {
            List<ArtifactFile> files = a.files().stream()
                    .map(f -> new ArtifactFile(f.source(), "/elsewhere/" + RemotePaths.name(f.remotePath()), f.mode()))
                    .toList();
            moved.add(new Artifact(a.name(), a.roles(), files, a.replaces(), a.replacedGate()));
        }
        assertNotEquals(baseline, Manifest.build(TargetRole.REGISTER, contentOfSource(), moved).digest());

        List<Artifact> rechmoded = new ArrayList<>();
        for (Artifact a : Artifacts.ARTIFACTS) {
            List<ArtifactFile> files = a.files().stream()
                    .map(f -> new ArtifactFile(f.source(), f.remotePath(), 0600)).toList();
            rechmoded.add(new Artifact(a.name(), a.roles(), files, a.replaces(), a.replacedGate()));
        }
        assertNotEquals(baseline, Manifest.build(TargetRole.REGISTER, contentOfSource(), rechmoded).digest());
    }

    @Test
    void aMissingPayloadFileFailsBeforeAnythingIsPushed() {
        assertThrows(Manifest.MissingPayloadException.class,
                () -> Manifest.build(TargetRole.COMMANDER, Manifest.payloadReader(dir), Artifacts.ARTIFACTS));
    }

    @Test
    void aCrlfShellScriptFailsTheManifestNamingTheFile() {
        Manifest.ContentReader crlf = file -> file.source().equals("pos/petrotest-pos-shim.sh")
                ? "#!/bin/sh\r\nexit 0\r\n".getBytes(StandardCharsets.UTF_8)
                : contentOfSource().read(file);
        Manifest.CrlfScriptException e = assertThrows(Manifest.CrlfScriptException.class,
                () -> Manifest.build(TargetRole.REGISTER, crlf, Artifacts.ARTIFACTS));
        assertTrue(e.getMessage().contains("pos/petrotest-pos-shim.sh"), e.getMessage());
    }

    @Test
    void aPayloadReadFromDiskHashesItsBytes() throws Exception {
        Files.createDirectories(dir.resolve("props"));
        Files.write(dir.resolve("props/private_system.prop"), "live=1\n".getBytes(StandardCharsets.UTF_8));
        Manifest.ContentReader read = Manifest.payloadReader(dir);
        ArtifactFile file = new ArtifactFile("props/private_system.prop", "/home/topaz/config/private_system.prop");
        assertEquals(Manifest.sha256Hex("live=1\n".getBytes(StandardCharsets.UTF_8)),
                Manifest.sha256Hex(read.read(file)));
    }

    @Test
    void sortingIsByPathComponentsNotCharacters() {
        // PurePosixPath orders "/a/b/c" before "/a/b-c"; a string sort would put "b-c" first.
        assertTrue(RemotePaths.compare("/a/b/c", "/a/b-c") < 0);
        assertEquals(0, RemotePaths.compare("/a/b", "/a/b/"));
    }

    @Test
    void shellQuotingMatchesShlex() {
        assertEquals("/home/topaz/config/x.prop", RemotePaths.shellQuote("/home/topaz/config/x.prop"));
        assertEquals("'it'\"'\"'s here'", RemotePaths.shellQuote("it's here"));
        assertEquals("''", RemotePaths.shellQuote(""));
    }

    @Test
    void aCorruptOrForeignStampIsNoStamp() {
        assertNull(Stamp.parse((byte[]) null));
        assertNull(Stamp.parse(new byte[0]));
        assertNull(Stamp.parse("{".getBytes(StandardCharsets.UTF_8)));
        assertNull(Stamp.parse("null".getBytes(StandardCharsets.UTF_8)));
        assertNull(Stamp.parse("{\"format\": 999}".getBytes(StandardCharsets.UTF_8)));
        assertNull(Stamp.parse(Optional.empty()));
    }
}
