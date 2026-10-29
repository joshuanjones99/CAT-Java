package com.verifone.cat.coverage;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.zip.GZIPInputStream;

/**
 * A tar archive read as a stream, member by member, never seeked: the Python's {@code tarfile.open(mode="r|*")}
 * for the two layers of an overlay package ({@code classes.py}). A gzipped stream is recognised by its magic
 * bytes and inflated; anything else is read as a plain tar.
 *
 * <p>What it understands: ustar and old-style headers (with the ustar name prefix), GNU long names and long link
 * names ({@code L}, {@code K}), pax extended headers' {@code path} ({@code x}; global {@code g} headers are
 * skipped), octal and base-256 sizes. A member's data is readable from {@link #data()} until {@link #next()} is
 * called, which skips whatever was not read.
 *
 * <p>Deviation from the Python: {@code r|*} also opens bzip2 and xz streams. The overlay's packages and their
 * vpks are gzip (petroapps' build, {@code .tgz}); a bzip2 or xz layer reads as a corrupt tar here, which
 * {@link ClassFileExtractor} reports as an unreadable package, the same outcome as any other bad package.
 * No dependency is added for it.
 */
final class TarStream implements AutoCloseable {

    private static final int BLOCK = 512;

    /** One member's header. */
    record Entry(String name, long size, char type, String linkName) {

        /** A regular file: the Python's {@code TarInfo.isfile()} ({@code 0}, NUL, {@code 7}). */
        boolean isFile() {
            return type == '0' || type == '\0' || type == '7';
        }

        boolean isSymlink() {
            return type == '2';
        }
    }

    private final InputStream in;
    private long remaining;
    private long padding;
    private boolean ended;

    TarStream(InputStream raw) throws IOException {
        BufferedInputStream buffered = new BufferedInputStream(raw, 64 * 1024);
        buffered.mark(2);
        int first = buffered.read();
        int second = buffered.read();
        buffered.reset();
        this.in = first == 0x1f && second == 0x8b ? new BufferedInputStream(new GZIPInputStream(buffered, 64 * 1024),
                64 * 1024) : buffered;
    }

    /** The next member, or null at the end of the archive. */
    Entry next() throws IOException {
        if (ended) {
            return null;
        }
        skipRest();
        String longName = null;
        String longLink = null;
        String paxPath = null;
        while (true) {
            byte[] header = new byte[BLOCK];
            int got = readFully(header);
            if (got == 0) {
                ended = true;
                return null;
            }
            if (got < BLOCK) {
                throw new EOFException("truncated tar header");
            }
            if (isZero(header)) {
                ended = true;
                return null;
            }
            if (!checksumOk(header)) {
                throw new IOException("bad tar header checksum");
            }
            String name = field(header, 0, 100);
            long size = size(header, 124, 12);
            char type = (char) header[156];
            String link = field(header, 157, 100);
            if ("ustar".equals(field(header, 257, 5).strip())) {
                String prefix = field(header, 345, 155);
                if (!prefix.isEmpty()) {
                    name = prefix + "/" + name;
                }
            }
            remaining = size;
            padding = (BLOCK - size % BLOCK) % BLOCK;
            switch (type) {
                case 'L' -> longName = trimNul(new String(readBody(), StandardCharsets.UTF_8));
                case 'K' -> longLink = trimNul(new String(readBody(), StandardCharsets.UTF_8));
                case 'x' -> {
                    String path = paxPath(new String(readBody(), StandardCharsets.UTF_8));
                    if (path != null) {
                        paxPath = path;
                    }
                }
                case 'g' -> readBody();
                default -> {
                    String full = paxPath != null ? paxPath : longName != null ? longName : name;
                    String fullLink = longLink != null ? longLink : link;
                    // Directories and links carry no data, whatever their size field says.
                    if (type == '5' || type == '1' || type == '2') {
                        remaining = 0;
                        padding = 0;
                    }
                    return new Entry(full, remaining, type, fullLink);
                }
            }
        }
    }

    /** The current member's data, bounded to its size. Valid until the next {@link #next()}. */
    InputStream data() {
        return new InputStream() {
            @Override
            public int read() throws IOException {
                if (remaining <= 0) {
                    return -1;
                }
                int b = in.read();
                if (b < 0) {
                    throw new EOFException("truncated tar member");
                }
                remaining--;
                return b;
            }

            @Override
            public int read(byte[] buffer, int offset, int length) throws IOException {
                if (remaining <= 0) {
                    return -1;
                }
                int n = in.read(buffer, offset, (int) Math.min(length, remaining));
                if (n < 0) {
                    throw new EOFException("truncated tar member");
                }
                remaining -= n;
                return n;
            }
        };
    }

    @Override
    public void close() throws IOException {
        in.close();
    }

    private byte[] readBody() throws IOException {
        if (remaining > Integer.MAX_VALUE - 8) {
            throw new IOException("tar extension header too large");
        }
        byte[] body = data().readAllBytes();
        skipRest();
        return body;
    }

    private void skipRest() throws IOException {
        skip(remaining + padding);
        remaining = 0;
        padding = 0;
    }

    private void skip(long count) throws IOException {
        long left = count;
        byte[] scratch = new byte[8192];
        while (left > 0) {
            int n = in.read(scratch, 0, (int) Math.min(scratch.length, left));
            if (n < 0) {
                throw new EOFException("truncated tar member");
            }
            left -= n;
        }
    }

    private int readFully(byte[] buffer) throws IOException {
        int at = 0;
        while (at < buffer.length) {
            int n = in.read(buffer, at, buffer.length - at);
            if (n < 0) {
                break;
            }
            at += n;
        }
        return at;
    }

    private static boolean isZero(byte[] block) {
        for (byte b : block) {
            if (b != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean checksumOk(byte[] header) {
        long stored;
        try {
            stored = octal(header, 148, 8);
        } catch (IOException e) {
            return false;
        }
        long unsigned = 0;
        long signed = 0;
        for (int i = 0; i < BLOCK; i++) {
            int b = i >= 148 && i < 156 ? ' ' : header[i];
            unsigned += b & 0xff;
            signed += i >= 148 && i < 156 ? ' ' : header[i];
        }
        return stored == unsigned || stored == signed;
    }

    private static String field(byte[] header, int offset, int length) {
        int end = offset;
        while (end < offset + length && header[end] != 0) {
            end++;
        }
        return new String(header, offset, end - offset, StandardCharsets.UTF_8);
    }

    private static long size(byte[] header, int offset, int length) throws IOException {
        if ((header[offset] & 0x80) != 0) {
            // GNU base-256: the remaining bytes, big-endian.
            long value = header[offset] & 0x7f;
            for (int i = offset + 1; i < offset + length; i++) {
                value = (value << 8) | (header[i] & 0xff);
            }
            return value;
        }
        return octal(header, offset, length);
    }

    private static long octal(byte[] header, int offset, int length) throws IOException {
        String text = field(header, offset, length).strip();
        if (text.isEmpty()) {
            return 0;
        }
        try {
            return Long.parseLong(text, 8);
        } catch (NumberFormatException e) {
            throw new IOException("bad tar number field: " + text, e);
        }
    }

    /** {@code path=} from a pax header's {@code <length> <key>=<value>\n} records. */
    private static String paxPath(String records) {
        String found = null;
        for (String line : records.split("\n")) {
            int space = line.indexOf(' ');
            if (space < 0) {
                continue;
            }
            String record = line.substring(space + 1);
            if (record.startsWith("path=")) {
                found = record.substring("path=".length());
            }
        }
        return found;
    }

    private static String trimNul(String text) {
        int nul = text.indexOf('\0');
        return nul < 0 ? text : text.substring(0, nul);
    }
}
