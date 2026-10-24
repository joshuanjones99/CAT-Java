package com.verifone.cat.commander;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.provision.transport.CommandResult;
import com.verifone.cat.provision.transport.Transport;

/**
 * {@code commander-trust}: make the Commander's Java trust a simulator's TLS certificate. A port of
 * {@code petrotest.commander_trust}.
 *
 * <p>A FEP whose client trusts only the JVM's {@code cacerts} cannot reach a simulator with a self-signed
 * certificate until that certificate is in the {@code cacerts} of the Java PMC runs on. This reads the
 * certificate the simulator serves and imports it under one alias, only when the Commander does not hold
 * that same certificate there already. PMC reads {@code cacerts} when its JVM starts, so an import wants a
 * PMC restart; that restart ({@code petrotest.commander_links.restart_pmc}) is not ported, so
 * {@link #trustCli} reports it unavailable rather than claiming the certificate is in use.
 *
 * <p>Read on the lab Commander, 2026-10-20: PMC's trust store is the JDK's own
 * {@code /usr/java/j2se/lib/security/cacerts}, with its keytool beside it. {@code changeit} is the JDK's
 * default store password, not a credential of this rig.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port); the Python records no import on a Commander either.
 */
public final class CommanderTrust {

    public static final String KEYTOOL = "/usr/java/j2se/bin/keytool";
    public static final String CACERTS = "/usr/java/j2se/lib/security/cacerts";

    /** The JDK's default {@code cacerts} password (every JDK ships it), not a secret of this rig. */
    public static final String STOREPASS = "changeit";

    /** What the PMC restart after an import needs. */
    public static final String RESTART_UNAVAILABLE =
            "unavailable in CAT-Java: restarting PMC needs petrotest.commander_links.restart_pmc";

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(60);
    private static final Pattern SHELL_SAFE = Pattern.compile("[\\w@%+=:,./-]+");

    private CommanderTrust() {}

    /** The PEM certificate {@code host:port} presents, unverified: it is self-signed. */
    public static String servedCertificate(String host, int port, Duration timeout) throws IOException {
        TrustManager[] anyCertificate = {new X509TrustManager() {
            @Override
            public void checkClientTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public void checkServerTrusted(X509Certificate[] chain, String authType) {}

            @Override
            public X509Certificate[] getAcceptedIssuers() {
                return new X509Certificate[0];
            }
        }};
        try {
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, anyCertificate, null);
            int millis = (int) Math.max(1, Math.min(Integer.MAX_VALUE, timeout.toMillis()));
            try (Socket plain = new Socket()) {
                plain.connect(new InetSocketAddress(host, port), millis);
                plain.setSoTimeout(millis);
                try (SSLSocket tls = (SSLSocket) context.getSocketFactory().createSocket(plain, host, port, true)) {
                    tls.startHandshake();
                    Certificate[] chain = tls.getSession().getPeerCertificates();
                    return derToPem(chain[0].getEncoded());
                }
            }
        } catch (GeneralSecurityException e) {
            throw new IOException("TLS handshake with " + host + ":" + port + " failed: " + e.getMessage(), e);
        }
    }

    /** DER bytes as PEM, the way Python's {@code ssl.DER_cert_to_PEM_cert} writes them. */
    static String derToPem(byte[] der) {
        String body = Base64.getEncoder().encodeToString(der);
        StringBuilder pem = new StringBuilder("-----BEGIN CERTIFICATE-----\n");
        for (int i = 0; i < body.length(); i += 64) {
            pem.append(body, i, Math.min(body.length(), i + 64)).append('\n');
        }
        return pem.append("-----END CERTIFICATE-----\n").toString();
    }

    /** SHA-256 of the certificate's DER bytes, upper-case hex pairs joined with colons, as keytool prints it. */
    public static String pemFingerprint(String pem) {
        StringBuilder body = new StringBuilder();
        for (String line : pem.split("\\R")) {
            if (!line.startsWith("-----")) {
                body.append(line.strip());
            }
        }
        byte[] der = Base64.getDecoder().decode(body.toString());
        byte[] digest;
        try {
            digest = MessageDigest.getInstance("SHA-256").digest(der);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("no SHA-256 in this JDK", e);
        }
        List<String> pairs = new ArrayList<>();
        for (byte b : digest) {
            pairs.add(String.format("%02X", b & 0xff));
        }
        return String.join(":", pairs);
    }

    /** {@code shlex.quote}: a word as the shell reads it back unchanged. */
    static String shellQuote(String word) {
        if (word.isEmpty()) {
            return "''";
        }
        if (SHELL_SAFE.matcher(word).matches()) {
            return word;
        }
        return "'" + word.replace("'", "'\"'\"'") + "'";
    }

    static String keytool(String... args) {
        List<String> parts = new ArrayList<>();
        parts.add(KEYTOOL);
        parts.addAll(List.of(args));
        parts.addAll(List.of("-keystore", CACERTS, "-storepass", STOREPASS));
        List<String> quoted = new ArrayList<>();
        for (String part : parts) {
            quoted.add(shellQuote(part));
        }
        return String.join(" ", quoted);
    }

    /** {@link #trustCertificate(Transport, String, String, Duration, Say)} with a 60 s budget. */
    public static boolean trustCertificate(Transport transport, String pem, String alias, Say say) {
        return trustCertificate(transport, pem, alias, DEFAULT_TIMEOUT, say);
    }

    /**
     * Imports {@code pem} into PMC's {@code cacerts} as {@code alias}; {@code false} when it is there already.
     * An alias holding another certificate (a simulator that regenerated its key) is replaced.
     *
     * @throws IllegalStateException keytool refused, or the import does not read back
     */
    public static boolean trustCertificate(Transport transport, String pem, String alias, Duration timeout, Say say) {
        String want = pemFingerprint(pem);
        CommandResult listed = transport.run(keytool("-list", "-v", "-alias", alias), timeout);
        if (listed.stdout().contains(want)) {
            say.out("commander-trust: " + alias + " already trusted (" + want.substring(0, 23) + "...)");
            return false;
        }
        String staged = "/tmp/" + alias + ".pem";
        transport.put(staged, pem.getBytes(StandardCharsets.US_ASCII), 0644, timeout);
        if (listed.ok()) {
            transport.run(keytool("-delete", "-alias", alias), timeout);
        }
        CommandResult imported = transport.run(keytool("-importcert", "-noprompt", "-alias", alias, "-file", staged),
                timeout);
        CommandResult check = transport.run(keytool("-list", "-v", "-alias", alias), timeout);
        if (!imported.ok() || !check.stdout().contains(want)) {
            String why = (imported.stderr().isEmpty() ? imported.stdout() : imported.stderr()).strip();
            throw new IllegalStateException("keytool did not import " + alias + ": " + why);
        }
        say.out("commander-trust: " + alias + " imported into " + CACERTS + " (" + want.substring(0, 23) + "...)");
        return true;
    }

    /**
     * {@code commander-trust --endpoint host:port --alias <alias>}: the certificate the endpoint serves into
     * the Commander's {@code cacerts}. When it changed and {@code restartPmc} is asked, the restart is
     * unavailable here, so the result is {@code false} with that reason: the certificate is imported but PMC
     * has not read it.
     */
    public static boolean trustCli(CommanderAccess access, String endpoint, String alias, boolean restartPmc, Say say) {
        int colon = endpoint.lastIndexOf(':');
        String host = colon < 0 ? "" : endpoint.substring(0, colon);
        String port = colon < 0 ? "" : endpoint.substring(colon + 1);
        if (host.isEmpty() || port.isEmpty() || !port.chars().allMatch(Character::isDigit)) {
            say.err("commander-trust: '" + endpoint + "' is not host:port");
            return false;
        }
        String pem;
        try {
            pem = servedCertificate(host, Integer.parseInt(port), Duration.ofSeconds(10));
        } catch (IOException | NumberFormatException e) {
            say.err("commander-trust: nothing serves TLS on " + endpoint + ": " + e.getMessage());
            return false;
        }
        Transport transport;
        try {
            transport = access.connectedTransport();
        } catch (DeviceError e) {
            say.err("commander-trust: " + e.getMessage());
            return false;
        }
        try {
            boolean changed = trustCertificate(transport, pem, alias, say);
            if (changed && restartPmc) {
                say.err("commander-trust: " + alias + " imported, but PMC was not restarted to read it: "
                        + RESTART_UNAVAILABLE);
                return false;
            }
            return true;
        } catch (DeviceError | IllegalStateException e) {
            say.err("commander-trust: " + e.getMessage());
            return false;
        } finally {
            transport.close();
        }
    }
}
