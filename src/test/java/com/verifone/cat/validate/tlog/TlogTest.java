package com.verifone.cat.validate.tlog;

import static com.verifone.cat.validate.tlog.TlogFixtures.CARWASH;
import static com.verifone.cat.validate.tlog.TlogFixtures.LOYALTY;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Canonicalisation and structural comparison of TLogs (DESIGN.md §5.4).
 *
 * <p>Ported from the Python CAT's {@code tests/unit/test_validate_tlog.py}, comparison half. The fixtures
 * are genuine petroapps {@code -Tlog.xml} files.
 */
class TlogTest {

    private static TlogOptions rule(boolean caseInsensitive, boolean prune) {
        return new TlogOptions(TlogRule.IGNORE_TAGS, TlogRule.IGNORE_ATTRIBUTES, caseInsensitive, prune);
    }

    // ------------------------------------------------------------------
    // Canonicalisation
    // ------------------------------------------------------------------

    @Test
    void volatileValuesAreElidedInTheStoredForm() {
        String canonical = Tlog.canonicalise(TlogFixtures.file(CARWASH));
        String raw = TlogFixtures.text(CARWASH);
        // These are in TAG_NAMES / ATTRIBUTE_NAMES and must not reach the snapshot.
        assertTrue(raw.contains("2021-03-01T13:21:50-05:00") && !canonical.contains("2021-03-01T13:21:50-05:00"));
        assertTrue(raw.contains("<trSeq>9010317</trSeq>") && !canonical.contains("9010317"));
        assertTrue(raw.contains("<trUniqueSN>866</trUniqueSN>") && !canonical.contains("<trUniqueSN>866</trUniqueSN>"));
        assertTrue(canonical.contains("<date>" + Tlog.IGNORED + "</date>"));
        // ... and the values that matter are still there, verbatim.
        assertTrue(canonical.contains("<trTotWTax>20.00</trTotWTax>"));
        assertTrue(canonical.contains("<trlDesc>CARWASH</trlDesc>"));
    }

    @Test
    void anIgnoredContainerKeepsItsChildren() {
        // trlFuel is in TAG_NAMES and carries fuelVolume and basePrice: pruning it would delete the assertions.
        assertTrue(TlogRule.IGNORE_TAGS.contains("trlFuel"));
        String canonical = Tlog.canonicalise(TlogFixtures.file(CARWASH));
        assertTrue(canonical.contains("<fuelVolume uom=\"G\">4.548</fuelVolume>"));
        assertTrue(canonical.contains("<basePrice>2.499</basePrice>"));
        String pruned = Tlog.canonicalise(TlogFixtures.file(CARWASH), rule(true, true));
        assertFalse(pruned.contains("fuelVolume"));
    }

    @Test
    void canonicalisationIsIdempotent() {
        String once = Tlog.canonicalise(TlogFixtures.file(LOYALTY));
        assertEquals(once, Tlog.canonicalise(once));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void anIgnoredTagCanonicalisesTheSameEmptyOrNot(boolean prune) {
        String full = "<trans><date>x</date><trlFuel>y<basePrice>2.499</basePrice></trlFuel></trans>";
        String empty = "<trans><date/><trlFuel><basePrice>2.499</basePrice></trlFuel></trans>";
        TlogOptions options = rule(true, prune);
        String once = Tlog.canonicalise(full, options);
        assertEquals(once, Tlog.canonicalise(empty, options));
        assertEquals(once, Tlog.canonicalise(once, options));
        assertTrue(Tlog.compare(full, empty, options).ok());
    }

    @Test
    void attributeOrderDoesNotAffectTheCanonicalForm() {
        assertEquals(Tlog.canonicalise("<trans a=\"1\" b=\"2\"><x/></trans>"),
                Tlog.canonicalise("<trans b=\"2\" a=\"1\"><x/></trans>"));
    }

    @Test
    void aTruncatedTlogFailsLoudly() {
        TlogParseException e = assertThrows(TlogParseException.class,
                () -> Tlog.canonicalise("<trans><trHeader>"));
        assertTrue(e.getMessage().contains("could not parse"), e.getMessage());
    }

    @Test
    void canonicalFormIsIndentedAndEndsWithANewline() {
        assertEquals("<trans>\n  <trValue>\n    <trTotWTax>20.00</trTotWTax>\n  </trValue>\n</trans>\n",
                Tlog.canonicalise("<trans><trValue><trTotWTax>20.00</trTotWTax></trValue></trans>"));
    }

    // ------------------------------------------------------------------
    // Structural comparison
    // ------------------------------------------------------------------

    @Test
    void aTlogMatchesItself() {
        Tlog.Diff diff = Tlog.compare(TlogFixtures.file(CARWASH), TlogFixtures.file(CARWASH));
        assertTrue(diff.ok());
        assertTrue(diff.compared() > 90, "the comparison must actually be looking at the tree");
        assertTrue(diff.describe().contains("TLogs match"), diff.describe());
    }

    @Test
    void volatileFieldsDoNotProduceDifferences() {
        String raw = TlogFixtures.text(CARWASH);
        String rerun = raw.replace("2021-03-01T13:21:50-05:00", "2026-07-28T09:02:11-04:00")
                .replace("<trSeq>9010317</trSeq>", "<trSeq>9014412</trSeq>")
                .replace("<trUniqueSN>866</trUniqueSN>", "<trUniqueSN>1204</trUniqueSN>")
                .replace("<duration>52</duration>", "<duration>61</duration>")
                .replace("<trlCwCode>18832</trlCwCode>", "<trlCwCode>40197</trlCwCode>")
                .replace("seq=\"13\"", "seq=\"19\"");
        assertFalse(rerun.equals(raw));
        assertTrue(Tlog.compare(TlogFixtures.file(CARWASH), rerun).ok());
    }

    @Test
    void aChangedValueNamesItsPath() {
        String changed = TlogFixtures.text(CARWASH).replace("<trTotWTax>20.00</trTotWTax>",
                "<trTotWTax>25.00</trTotWTax>");
        Tlog.Diff diff = Tlog.compare(TlogFixtures.file(CARWASH), changed);
        assertFalse(diff.ok());
        assertEquals(List.of("/trans/trValue/trTotWTax"), paths(diff));
        Tlog.Difference only = diff.differences().get(0);
        assertEquals(Tlog.Kind.CHANGED, only.kind());
        assertEquals("20.00", only.expected());
        assertEquals("25.00", only.actual());
        assertTrue(diff.describe().contains("/trans/trValue/trTotWTax: expected '20.00', got '25.00'"),
                diff.describe());
    }

    @Test
    void aChangedAttributeNamesTheAttribute() {
        String changed = TlogFixtures.text(CARWASH).replace("<trlDept number=\"275\"", "<trlDept number=\"999\"");
        assertEquals(List.of("/trans/trLines/trLine[1]/trlDept@number"),
                paths(Tlog.compare(TlogFixtures.file(CARWASH), changed)));
    }

    @Test
    void anIgnoredAttributeChangeIsNotADifference() {
        String raw = TlogFixtures.text(CARWASH);
        assertTrue(TlogRule.IGNORE_ATTRIBUTES.contains("sysid"));
        assertTrue(Tlog.compare(TlogFixtures.file(CARWASH), raw.replace("sysid=\"4\">UNLD", "sysid=\"9\">UNLD")).ok());
        // ... but the sibling attribute that is NOT on the list still fails.
        Tlog.Diff diff = Tlog.compare(TlogFixtures.file(CARWASH),
                raw.replace("NAXMLFuelGradeID=\"1\"", "NAXMLFuelGradeID=\"3\""));
        assertFalse(diff.ok());
        assertTrue(diff.differences().get(0).path().endsWith("fuelProd@NAXMLFuelGradeID"));
    }

    @Test
    void aNewlyAddedElementIsOnePreciseDifference() {
        String changed = TlogFixtures.text(CARWASH).replace("<trTotTax>0.00</trTotTax>",
                "<trTotTax>0.00</trTotTax>\n    <trSurcharge>0.35</trSurcharge>");
        Tlog.Diff diff = Tlog.compare(TlogFixtures.file(CARWASH), changed);
        assertEquals(List.of("/trans/trValue/trSurcharge"), paths(diff));
        assertEquals(Tlog.Kind.UNEXPECTED, diff.differences().get(0).kind());
    }

    @Test
    void aRemovedElementIsOnePreciseDifference() {
        String changed = TlogFixtures.text(CARWASH).replace("<trTotTax>0.00</trTotTax>", "");
        Tlog.Diff diff = Tlog.compare(TlogFixtures.file(CARWASH), changed);
        assertEquals(List.of("/trans/trValue/trTotTax"), paths(diff));
        assertEquals(Tlog.Kind.MISSING, diff.differences().get(0).kind());
    }

    @Test
    void reorderingUnlikeSiblingsIsNotADifference() {
        String a = "<trans><trValue><trTotNoTax>1.00</trTotNoTax><trTotTax>0.00</trTotTax></trValue></trans>";
        String b = "<trans><trValue><trTotTax>0.00</trTotTax><trTotNoTax>1.00</trTotNoTax></trValue></trans>";
        assertTrue(Tlog.compare(a, b).ok());
    }

    @Test
    void reorderingLikeSiblingsIsAPreciseDiffNotAWholeFileMismatch() {
        String a = "<trans><trLines><trLine><d>A</d></trLine><trLine><d>B</d></trLine></trLines></trans>";
        String b = "<trans><trLines><trLine><d>B</d></trLine><trLine><d>A</d></trLine></trLines></trans>";
        Tlog.Diff diff = Tlog.compare(a, b);
        assertFalse(diff.ok());
        assertEquals(List.of("/trans/trLines/trLine[1]/d", "/trans/trLines/trLine[2]/d"), paths(diff));
    }

    @Test
    void caseInsensitiveValuesArePatsParityAndSwitchable() {
        String a = "<trans><trlDesc>CARWASH</trlDesc></trans>";
        String b = "<trans><trlDesc>CarWash</trlDesc></trans>";
        assertTrue(Tlog.compare(a, b, rule(true, false)).ok(), "equalsIgnoreCase, the first check in PATS");
        assertFalse(Tlog.compare(a, b, rule(false, false)).ok());
    }

    @Test
    void aWholeExtraLineDoesNotCascade() {
        // The property that makes a TLog failure cheap to read.
        String raw = TlogFixtures.text(LOYALTY);
        int first = raw.indexOf("<trLine ");
        int end = raw.indexOf("</trLine>", first) + "</trLine>".length();
        String changed = raw.substring(0, end) + raw.substring(first, end) + raw.substring(end);
        Tlog.Diff diff = Tlog.compare(TlogFixtures.file(LOYALTY), changed);
        assertFalse(diff.ok());
        for (String path : paths(diff)) {
            assertTrue(path.contains("/trLines/"), diff.describe());
        }
    }

    @Test
    void diffOutputIsDeterministic() {
        String changed = TlogFixtures.text(CARWASH).replace("<trlQty>1</trlQty>", "<trlQty>2</trlQty>");
        assertEquals(Tlog.compare(TlogFixtures.file(CARWASH), changed).describe(),
                Tlog.compare(TlogFixtures.file(CARWASH), changed).describe());
    }

    @Test
    void describeTruncatesAHugeDiff() {
        StringBuilder a = new StringBuilder("<trans>");
        StringBuilder b = new StringBuilder("<trans>");
        for (int i = 0; i < 60; i++) {
            a.append("<n").append(i).append(">").append(i).append("</n").append(i).append(">");
            b.append("<n").append(i).append(">").append(i + 1).append("</n").append(i).append(">");
        }
        String described = Tlog.compare(a + "</trans>", b + "</trans>").describe(5);
        assertTrue(described.contains("and 55 more"), described);
    }

    private static List<String> paths(Tlog.Diff diff) {
        return diff.differences().stream().map(Tlog.Difference::path).toList();
    }
}
