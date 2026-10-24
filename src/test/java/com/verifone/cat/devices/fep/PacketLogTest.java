package com.verifone.cat.devices.fep;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Duration;
import java.util.Optional;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.verifone.cat.preflight.Bounded.TimeoutExpired;

/**
 * {@link PacketLog}: reading the Buypass host simulator's {@code packetFile.txt}. A port of the Python CAT's
 * {@code tests/unit/test_buypass_host.py}; the lines are the shape BUYPASS Host wrote on the lab controller
 * (2026-08-13 for TABLE, 2026-09-24 for EMV2KEY), trimmed. Evidence about the reader, not the rig.
 */
class PacketLogTest {

    private static final String NS = "xmlns=\"com/fiserv/Merchant/gmfV12.04\"";

    @TempDir
    Path dir;

    static String request(String fileType) {
        return "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?><GMF " + NS + "><AdminRequest>"
                + "<CommonGrp><TxnType>FileDownload</TxnType><STAN>001857</STAN></CommonGrp>"
                + "<FileDLGrp><FileType>" + fileType + "</FileType><FunCode>U</FunCode></FileDLGrp>"
                + "</AdminRequest></GMF>\n";
    }

    static String response(String fileType, String code) {
        return "<GMF " + NS + "><AdminResponse><CommonGrp><TxnType>FileDownload</TxnType>"
                + "<STAN>001857</STAN></CommonGrp><RespGrp><RespCode>" + code + "</RespCode></RespGrp>"
                + "<FileDLGrp><FileType>" + fileType + "</FileType><FunCode>U</FunCode></FileDLGrp>"
                + "</AdminResponse></GMF>\n";
    }

    private PacketLog log(String... lines) throws IOException {
        Path path = dir.resolve("packetFile.txt");
        Files.writeString(path, String.join("", lines), StandardCharsets.UTF_8);
        return new PacketLog(path);
    }

    private static void append(PacketLog log, String... lines) throws IOException {
        Files.writeString(log.path(), String.join("", lines), StandardCharsets.UTF_8, StandardOpenOption.APPEND);
    }

    @Test
    void aKeyDownloadSentAfterTheMarkIsSeenWithItsCode() throws IOException {
        PacketLog log = log();
        long mark = log.mark();
        append(log, request(PacketLog.EMV_KEYS), response(PacketLog.EMV_KEYS, "703"));
        assertEquals(Optional.of("703"), log.answered(PacketLog.EMV_KEYS, mark));
    }

    @Test
    void oneSentBeforeTheMarkDoesNotCount() throws IOException {
        PacketLog log = log(request(PacketLog.EMV_KEYS), response(PacketLog.EMV_KEYS, "703"));
        assertEquals(Optional.empty(), log.answered(PacketLog.EMV_KEYS, log.mark()));
    }

    @Test
    void aRequestAloneIsNotAnAnswer() throws IOException {
        PacketLog log = log();
        long mark = log.mark();
        append(log, request(PacketLog.TABLE));
        assertEquals(Optional.empty(), log.answered(PacketLog.TABLE, mark));
    }

    @Test
    void theTableLoadAndTheKeyDownloadAreToldApart() throws IOException {
        PacketLog log = log();
        long mark = log.mark();
        append(log, request(PacketLog.TABLE), response(PacketLog.TABLE, "000"));
        assertEquals(Optional.of("000"), log.answered(PacketLog.TABLE, mark));
        assertEquals(Optional.empty(), log.answered(PacketLog.EMV_KEYS, mark));
    }

    @Test
    void aFileCutShortSinceTheMarkIsReadFromTheStart() throws IOException {
        PacketLog log = log("x".repeat(5000));
        long mark = log.mark();
        Files.writeString(log.path(), response(PacketLog.TABLE, "000"), StandardCharsets.UTF_8);
        assertEquals(Optional.of("000"), log.answered(PacketLog.TABLE, mark));
    }

    @Test
    void noAnswerTimesOutNamingTheFileType() throws IOException {
        PacketLog log = log();
        TimeoutExpired e = assertThrows(TimeoutExpired.class,
                () -> log.waitFor(PacketLog.EMV_KEYS, log.mark(), Duration.ofMillis(300), Duration.ofMillis(50)));
        assertTrue(e.getMessage().contains("EMV2KEY"), e.getMessage());
    }

    @Test
    void anAnswerAlreadyThereIsReturnedAtOnce() throws IOException {
        PacketLog log = log();
        long mark = log.mark();
        append(log, response(PacketLog.TABLE, "000"));
        assertEquals("000", log.waitFor(PacketLog.TABLE, mark, Duration.ofSeconds(5)));
    }

    @Test
    void aMissingFileIsNoAnswerRatherThanAnError() {
        PacketLog log = new PacketLog(dir.resolve("absent.txt"));
        assertEquals(0L, log.mark());
        assertEquals(Optional.empty(), log.answered(PacketLog.TABLE, 0));
    }

    @Test
    void anEarlierResponseOfAnotherTypeDoesNotLendItsCode() throws IOException {
        PacketLog log = log();
        long mark = log.mark();
        String echo = "<GMF " + NS + "><AdminResponse><CommonGrp><TxnType>EchoTest</TxnType></CommonGrp>"
                + "<RespGrp><RespCode>999</RespCode></RespGrp></AdminResponse></GMF>\n";
        append(log, echo, request(PacketLog.EMV_KEYS), response(PacketLog.EMV_KEYS, "703"));
        assertEquals(Optional.of("703"), log.answered(PacketLog.EMV_KEYS, mark));
    }

    @Test
    void theDefaultFileIsBuypassHostsUnderTheToolsRoot() {
        Path path = new PacketLog().path();
        assertEquals("packetFile.txt", path.getFileName().toString());
        assertEquals("BUYPASS", path.getParent().getFileName().toString());
    }
}
