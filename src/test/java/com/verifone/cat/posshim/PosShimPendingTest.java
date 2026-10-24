package com.verifone.cat.posshim;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.verifone.cat.posshim.ShimReplies.PendingKeyResult;

/** {@link PosShimClient#pressWithData}: the shim's {@code POST /pos/keys/pending}, the Python's {@code press_with_data}. */
class PosShimPendingTest {

    private FakeShim shim;
    private PosShimClient client;

    @BeforeEach
    void setUp() {
        shim = new FakeShim();
        client = new PosShimClient(shim.host(), shim.port(), null, Duration.ofSeconds(2));
    }

    @AfterEach
    void tearDown() {
        shim.close();
    }

    @Test
    void pressWithDataPostsTheKeyAndItsDataToThePendingRoute() {
        shim.on("POST", "/pos/keys/pending", 200, "{\"key\":\"QTY\",\"data\":\"2\",\"processed\":true,\"elapsed_ms\":10}");

        PendingKeyResult reply = client.pressWithData("QTY", "2");

        assertEquals("QTY", reply.key());
        assertTrue(reply.processed());
        FakeShim.Seen sent = shim.seen.get(shim.seen.size() - 1);
        assertEquals("POST", sent.method());
        assertEquals("/pos/keys/pending", sent.pathAndQuery());
        assertTrue(sent.body().contains("\"key\":\"QTY\""), sent.body());
        assertTrue(sent.body().contains("\"data\":\"2\""), sent.body());
    }

    @Test
    void aKeyTheShimDidNotProcessReadsAsNotProcessed() {
        shim.on("POST", "/pos/keys/pending", 200, "{\"key\":\"0x3e\",\"processed\":false}");

        assertFalse(client.pressWithData("0x3e", "1000").processed());
    }
}
