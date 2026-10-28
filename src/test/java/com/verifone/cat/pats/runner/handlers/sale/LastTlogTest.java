package com.verifone.cat.pats.runner.handlers.sale;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.Optional;

import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Element;

/** The STAN lookup in a TLog, as the Python's {@code find(".//trPaylines/trPayline/trpCardInfo")}. */
class LastTlogTest {

    private static Element parse(String xml) throws Exception {
        return DocumentBuilderFactory.newInstance().newDocumentBuilder()
                .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8))).getDocumentElement();
    }

    @Test
    void theStanIsTheFirstCardInfosBatchAndSequenceNumbersJoined() throws Exception {
        Element tlog = parse("<transSet><trans><trHeader/><trPaylines>"
                + "<trPayline><trpPaycode>CASH</trpPaycode></trPayline>"
                + "<trPayline><trpCardInfo><trpcBatchNr>12</trpcBatchNr><trpcSeqNr>345</trpcSeqNr></trpCardInfo>"
                + "</trPayline>"
                + "<trPayline><trpCardInfo><trpcBatchNr>99</trpcBatchNr><trpcSeqNr>999</trpcSeqNr></trpCardInfo>"
                + "</trPayline></trPaylines></trans></transSet>");

        assertEquals(Optional.of("12345"), LastTlog.stanOf(tlog));
    }

    @Test
    void aMissingHalfIsEmptyAndBothMissingIsNoStan() throws Exception {
        assertEquals(Optional.of("777"), LastTlog.stanOf(parse("<t><trPaylines><trPayline><trpCardInfo>"
                + "<trpcSeqNr>777</trpcSeqNr></trpCardInfo></trPayline></trPaylines></t>")));
        assertEquals(Optional.empty(), LastTlog.stanOf(parse("<t><trPaylines><trPayline><trpCardInfo/>"
                + "</trPayline></trPaylines></t>")));
    }

    @Test
    void aTlogWithNoCardInfoHasNoStan() throws Exception {
        assertEquals(Optional.empty(), LastTlog.stanOf(parse("<t><trPaylines><trPayline/></trPaylines></t>")));
        // Not under trPaylines/trPayline: the Python's path does not reach it.
        assertEquals(Optional.empty(), LastTlog.stanOf(parse("<t><trpCardInfo><trpcSeqNr>1</trpcSeqNr>"
                + "</trpCardInfo></t>")));
    }
}
