package com.verifone.cat.core;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/** SHA-256 as lower-case hex, the form every manifest, stamp and checksum in CAT uses. */
public final class Hashes {

    private static final int BLOCK = 1 << 16;

    private Hashes() {}

    public static String sha256Hex(byte[] bytes) {
        return HexFormat.of().formatHex(sha256().digest(bytes));
    }

    /** The hash of a file's content, streamed, so a build artefact of any size hashes in constant memory. */
    public static String sha256Hex(Path file) throws IOException {
        MessageDigest digest = sha256();
        try (InputStream in = Files.newInputStream(file)) {
            byte[] block = new byte[BLOCK];
            for (int read = in.read(block); read != -1; read = in.read(block)) {
                digest.update(block, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static MessageDigest sha256() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is required of every Java runtime", e);
        }
    }
}
