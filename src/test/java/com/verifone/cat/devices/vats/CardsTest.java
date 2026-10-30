package com.verifone.cat.devices.vats;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** PATS's card label rules, the image index and the MSR tracks: the Python's {@code test_vats_parts.py} card tests. */
class CardsTest {

    @TempDir
    Path dir;

    @Test
    void cardKindFollowsPatsLabelRules() {
        assertEquals(Cards.Kind.CONTACT, Cards.kindOf("EMV_CT_VISA"));
        assertEquals(Cards.Kind.CONTACT, Cards.kindOf("EMV CREDIT"));
        assertEquals(Cards.Kind.CONTACTLESS, Cards.kindOf("EMV_CTLS_UNIONPAY"));
        assertEquals(Cards.Kind.MAGSTRIPE, Cards.kindOf("MannatecFleet1"));
        assertEquals(Cards.Kind.MAGSTRIPE, Cards.kindOf("emv_ct_visa"), "PATS's contains(\"EMV\") is case-sensitive");
    }

    @Test
    void imagesResolveByNameFirstRootWinning() throws IOException {
        Path first = dir.resolve("petroapps");
        Path second = dir.resolve("btt").resolve("MasterCard");
        Files.createDirectories(first);
        Files.createDirectories(second);
        Files.writeString(first.resolve("MTIP_41_17A.xml"), "a");
        Files.writeString(second.resolve("MTIP_41_17A.xml"), "b");
        Files.writeString(second.resolve("MCD41 - v1.0.xml"), "c");
        CardImages images = new CardImages(List.of(first, dir.resolve("btt"), dir.resolve("missing")));
        assertEquals(first.resolve("MTIP_41_17A.xml"), images.resolve("mtip_41_17a.XML"));
        assertEquals(second.resolve("MCD41 - v1.0.xml"), images.resolve("MCD41 - v1.0.xml"));
        assertNull(images.resolve(" "));
        assertNull(images.resolve("nope.xml"));
        assertEquals(second.resolve("MCD41 - v1.0.xml"), images.resolve(second.resolve("MCD41 - v1.0.xml").toString()));
        assertEquals(first + "; " + dir.resolve("btt"), images.describe());
        assertEquals("no image folders found", new CardImages(List.of(dir.resolve("missing"))).describe());
    }

    @Test
    void msrTracksComeFromTheLabel() throws IOException {
        Path data = dir.resolve("MSRCardData.xml");
        Files.writeString(data, "<Cards><Card><label>A</label><track1>T1</track1><track2>T2</track2><track3/></Card>"
                + "</Cards>");
        assertEquals(new Cards.Tracks("T1", "T2", ""), Cards.msrTracks("A", data));
        assertNull(Cards.msrTracks("B", data));
        assertNull(Cards.msrTracks("A", dir.resolve("absent.xml")));
    }
}
