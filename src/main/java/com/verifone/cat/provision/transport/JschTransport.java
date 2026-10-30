package com.verifone.cat.provision.transport;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Iterator;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.LockSupport;

import com.jcraft.jsch.ChannelExec;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpATTRS;
import com.jcraft.jsch.SftpException;
import com.verifone.cat.devices.DeviceError;
import com.verifone.cat.devices.DeviceUnreachable;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * SSH exec and SFTP over JSch. A port of {@code petrotest.provision.transport.ParamikoTransport}.
 *
 * <p><b>Host keys.</b> Python's {@code connect} sets {@code paramiko.AutoAddPolicy()}: an unknown host key
 * is accepted and not checked against any known_hosts file. The port does the same with
 * {@code StrictHostKeyChecking=no}, and reads and writes no known_hosts file. The reason is Python's:
 * these are lab rigs on a private network that are reimaged, so a pinned key would be a manual step per
 * reimage. The cost is that a box impersonating the lab Commander would be accepted. Nothing here is
 * reachable from outside the bench; a future rig-owner decision could pin keys instead.
 *
 * <p><b>Key only.</b> Python signs in with a key alone (no password is ever passed by the production
 * factory), so the port offers only {@code publickey}. With no key file configured, JSch has no identity
 * to offer and the login is refused, which is what {@link #connectFailure} reports.
 *
 * <p><b>SELinux.</b> Before the first SFTP operation on a transport, {@code setenforce 0} is run once,
 * as Python does, and never restored. The Python docstring says "once per connection"; the code runs it
 * once per transport instance and does not reset it on reconnect. This port keeps the code's behaviour.
 *
 * <p><b>Atomic write.</b> Python renames the temp file over the target with {@code posix_rename} and falls
 * back to remove-then-rename. JSch 0.2.20 has no posix-rename, so this port always takes the fallback:
 * there is a moment between the remove and the rename in which the target is absent. The temp file is
 * still written first, so a dropped connection cannot leave a truncated file under the real name.
 *
 * <p><b>Timeouts.</b> Each operation runs on a worker thread and is bounded by {@code timeout}. On expiry
 * the session is dropped, which is the only way to free a stuck channel, and the next operation reconnects.
 * Python's {@code call_with_timeout} leaves the connection up; the difference is deliberate and
 * documented here.
 *
 * <p><b>Reverse forwards</b> (the cloud tunnel's): {@link #requestReverseForward} is JSch's
 * {@code setPortForwardingR} with a {@link JschForwardDaemon}, the port of paramiko's
 * {@code request_port_forward(address, port, handler)}; {@link #keepalive} and {@link #isActive} are
 * {@code set_keepalive} and {@code is_active}. A forward lives as long as the session, so a timed-out
 * operation (which drops the session, above) ends the forwards too; the tunnel's serve loop then
 * reconnects.
 *
 * <p>Passwords are not supported: the Python production factory never passes one, and the Java rig
 * schema has no password field.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). The Python transport was verified against the lab Commander
 * and register on 2026-08-06; nothing here has been run against a box.
 */
public final class JschTransport implements Transport {

    private static final Logger log = LoggerFactory.getLogger(JschTransport.class);

    /** The command the Python transport runs once before SFTP. */
    static final String SELINUX_COMMAND = "setenforce 0";

    /** JSch's name for the SFTP "no such file" status (SSH_FX_NO_SUCH_FILE). */
    private static final int NO_SUCH_FILE = ChannelSftp.SSH_FX_NO_SUCH_FILE;

    private static final String TEMP_SUFFIX = ".petrotest-tmp";

    /** How often a running exec channel is polled for its close. A bounded park, not a busy spin. */
    private static final long EXEC_POLL_NANOS = 20_000_000L;

    /** Unanswered keepalives before JSch drops the session (see {@link #keepalive}). */
    static final int KEEPALIVE_COUNT_MAX = 3;

    private final SshTarget target;
    private final JSch jsch = new JSch();
    private Session session;
    /** The session forwards or a keepalive were set on; {@link #isActive} reports on it. */
    private Session pinned;
    private ChannelSftp sftp;
    private ExecutorService worker;
    private boolean selinuxChecked;

    public JschTransport(SshTarget target) {
        this.target = Objects.requireNonNull(target, "target");
    }

    @Override
    public String host() {
        return target.host();
    }

    /** The session options: the host-key policy and the publickey-only login. */
    static Properties sessionConfig() {
        Properties config = new Properties();
        config.put("StrictHostKeyChecking", "no");
        config.put("PreferredAuthentications", "publickey");
        return config;
    }

    /**
     * Opens the session, once. A connected session is reused. Failure is a {@link DeviceError}: a refused
     * login, or an unreachable box, as {@link #connectFailure} classifies it.
     */
    public synchronized void connect(Duration timeout) {
        if (session != null && session.isConnected()) {
            return;
        }
        int ms = millis(timeout);
        if (target.keyFilename() != null) {
            loadIdentity(jsch, target.keyFilename());
        }
        Session s = null;
        try {
            s = jsch.getSession(target.user(), target.host(), target.port());
            s.setConfig(sessionConfig());
            s.setTimeout(ms);
            s.connect(ms);
        } catch (JSchException e) {
            if (s != null) {
                s.disconnect();
            }
            throw connectFailure(target, e);
        }
        session = s;
    }

    private Session session(Duration timeout) {
        connect(timeout);
        return session;
    }

    @Override
    public CommandResult run(String command, Duration timeout) {
        Session s = session(timeout);
        return bounded(() -> exec(s, command, timeout), timeout, "run " + quote(command));
    }

    @Override
    public void put(String remotePath, byte[] content, int mode, Duration timeout) {
        ChannelSftp c = sftp(timeout);
        bounded(() -> {
            writeViaTemp(c, remotePath, content, mode);
            return null;
        }, timeout, "put " + remotePath);
    }

    @Override
    public Optional<byte[]> get(String remotePath, Duration timeout) {
        ChannelSftp c = sftp(timeout);
        return bounded(() -> readOrAbsent(c, remotePath), timeout, "get " + remotePath);
    }

    @Override
    public void mkdir(String remotePath, Duration timeout) {
        ChannelSftp c = sftp(timeout);
        bounded(() -> {
            mkdirP(c, remotePath);
            return null;
        }, timeout, "mkdir " + remotePath);
    }

    @Override
    public boolean exists(String remotePath, Duration timeout) {
        ChannelSftp c = sftp(timeout);
        return bounded(() -> statOrAbsent(c, remotePath).isPresent(), timeout, "stat " + remotePath);
    }

    /**
     * A remote forward ({@code Session.setPortForwardingR} with {@link JschForwardDaemon}), bounded by {@code timeout}:
     * the {@code tcpip-forward} request waits for sshd's answer, and a refusal is a {@link DeviceError}. Each accepted
     * connection runs on JSch's own per-channel threads, never the session's reader.
     */
    @Override
    public void requestReverseForward(String bindAddress, int port, ForwardHandler handler, Duration timeout) {
        Objects.requireNonNull(handler, "handler");
        Session s = session(timeout);
        pin(s);
        bounded(() -> {
            s.setPortForwardingR(bindAddress, port, JschForwardDaemon.class.getName(),
                    JschForwardDaemon.arguments(handler, port));
            return null;
        }, timeout, "remote forward " + bindAddress + ":" + port);
    }

    /**
     * JSch's server-alive probe: after {@code interval} of silence a keepalive is sent, and after
     * {@link #KEEPALIVE_COUNT_MAX} unanswered ones the session is dropped. Paramiko's {@code set_keepalive} only sends;
     * there a dead box is noticed when the socket fails. Either way {@link #isActive} turns false.
     */
    @Override
    public synchronized void keepalive(Duration interval) {
        if (session == null) {
            throw new DeviceError(label() + ": keepalive on a session that is not open");
        }
        pin(session);
        try {
            session.setServerAliveInterval(millis(interval));
            session.setServerAliveCountMax(KEEPALIVE_COUNT_MAX);
        } catch (JSchException e) {
            throw operationFailure(label(), "keepalive", e);
        }
    }

    /**
     * Whether the session is up. Once forwards or a keepalive are set, it answers for <em>that</em> session: the other
     * operations reconnect a dropped session silently, and a new session carries none of the old one's forwards, so
     * the tunnel must see the drop (the Python polls the paramiko transport it set the forwards on).
     */
    @Override
    public synchronized boolean isActive() {
        Session s = pinned != null ? pinned : session;
        return s != null && s == session && s.isConnected();
    }

    private synchronized void pin(Session s) {
        if (pinned == null) {
            pinned = s;
        }
    }

    /** The SFTP channel, opened after the once-per-transport SELinux step. */
    private ChannelSftp sftp(Duration timeout) {
        Session s = session(timeout);
        ensureSelinuxPermissive(timeout);
        if (sftp == null || sftp.isClosed()) {
            sftp = bounded(() -> {
                ChannelSftp c = (ChannelSftp) s.openChannel("sftp");
                c.connect(millis(timeout));
                return c;
            }, timeout, "open sftp");
        }
        return sftp;
    }

    /** {@code setenforce 0}, once per transport, before the first SFTP operation. Logged, never restored. */
    void ensureSelinuxPermissive(Duration timeout) {
        if (selinuxChecked) {
            return;
        }
        selinuxChecked = true;
        CommandResult result = run(SELINUX_COMMAND, timeout);
        if (result.ok()) {
            log.info("{}: setenforce 0 (was enforcing, or already permissive/absent)", host());
        } else {
            log.info("{}: setenforce 0 not applicable ({})", host(), result.stderr().strip());
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (sftp != null) {
                sftp.disconnect();
                sftp = null;
            }
            if (session != null) {
                session.disconnect();
                session = null;
            }
        }
        if (worker != null) {
            worker.shutdownNow();
            worker = null;
        }
    }

    // -- bounded execution ---------------------------------------------------------------------

    /**
     * Runs {@code op} on the worker and waits at most {@code timeout}. Expiry drops the session and reports
     * the box as unreachable. A failure inside {@code op} is mapped by {@link #operationFailure}.
     */
    <T> T bounded(Callable<T> op, Duration timeout, String what) {
        Future<T> future = worker().submit(op);
        try {
            return future.get(Math.max(1L, timeout.toMillis()), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            future.cancel(true);
            close();
            throw new DeviceUnreachable(label(), what + " timed out after " + timeout.toMillis() + " ms", e);
        } catch (InterruptedException e) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            throw new DeviceError(label() + ": " + what + " was interrupted", e);
        } catch (ExecutionException e) {
            throw operationFailure(label(), what, e.getCause());
        }
    }

    private ExecutorService worker() {
        if (worker == null) {
            worker = Executors.newSingleThreadExecutor(r -> {
                Thread t = new Thread(r, "ssh-" + target.name());
                t.setDaemon(true);
                return t;
            });
        }
        return worker;
    }

    private String label() {
        return "ssh " + target.name() + " (" + target.host() + ")";
    }

    // -- operations (run on the worker) --------------------------------------------------------

    private static CommandResult exec(Session s, String command, Duration timeout)
            throws JSchException, IOException, InterruptedException {
        ChannelExec channel = (ChannelExec) s.openChannel("exec");
        try {
            channel.setCommand(command);
            InputStream out = channel.getInputStream();
            InputStream err = channel.getErrStream();
            channel.connect(millis(timeout));
            // Stdout then stderr, as the Python does. A large stderr can stall this read; the outer timeout
            // bounds that stall.
            String stdout = new String(out.readAllBytes(), StandardCharsets.UTF_8);
            String stderr = new String(err.readAllBytes(), StandardCharsets.UTF_8);
            while (!channel.isClosed()) {
                if (Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("exec interrupted");
                }
                LockSupport.parkNanos(EXEC_POLL_NANOS);
            }
            return new CommandResult(channel.getExitStatus(), stdout, stderr);
        } finally {
            channel.disconnect();
        }
    }

    /** Writes a sibling temp file, then renames it over the target (see the class note on the fallback). */
    static void writeViaTemp(ChannelSftp c, String remotePath, byte[] content, int mode) throws SftpException {
        mkdirP(c, parentOf(remotePath));
        String tmp = remotePath + TEMP_SUFFIX;
        try {
            c.put(new ByteArrayInputStream(content), tmp);
            c.chmod(mode, tmp);
            // The fallback: plain SFTP rename refuses an existing target, so remove it first. Not being there
            // is the normal first-push case, so the removal's failure is ignored.
            removeQuietly(c, remotePath);
            c.rename(tmp, remotePath);
        } catch (SftpException | RuntimeException e) {
            removeQuietly(c, tmp);
            throw e;
        }
    }

    /** The file's bytes, or empty when the file does not exist. Other failures propagate. */
    static Optional<byte[]> readOrAbsent(ChannelSftp c, String remotePath) throws SftpException, IOException {
        InputStream in;
        try {
            in = c.get(remotePath);
        } catch (SftpException e) {
            if (isNoSuchFile(e)) {
                return Optional.empty();
            }
            throw e;
        }
        try (InputStream stream = in) {
            return Optional.of(stream.readAllBytes());
        }
    }

    /** The stat of {@code remotePath}, or empty when it does not exist. */
    static Optional<SftpATTRS> statOrAbsent(ChannelSftp c, String remotePath) throws SftpException {
        try {
            return Optional.of(c.stat(remotePath));
        } catch (SftpException e) {
            if (isNoSuchFile(e)) {
                return Optional.empty();
            }
            throw e;
        }
    }

    /**
     * {@code mkdir -p}. Walks up to the first directory that stats, then creates the missing ones shallowest
     * first, as Python's {@code _mkdir_p} does. Any stat failure counts as missing.
     */
    static void mkdirP(ChannelSftp c, String directory) throws SftpException {
        Deque<String> missing = new ArrayDeque<>();
        String probe = directory;
        while (!probe.isEmpty() && !probe.equals("/")) {
            try {
                c.stat(probe);
                break;
            } catch (SftpException e) {
                missing.add(probe);
                probe = parentOf(probe);
            }
        }
        for (Iterator<String> it = missing.descendingIterator(); it.hasNext();) {
            c.mkdir(it.next());
        }
    }

    private static void removeQuietly(ChannelSftp c, String remotePath) {
        try {
            c.rm(remotePath);
        } catch (SftpException ignored) {
            // Absent, or not removable: the caller's next step reports whatever is actually wrong.
        }
    }

    // -- pure helpers, the testable seam -------------------------------------------------------

    /**
     * A JSch timeout in milliseconds. Refuses zero and negatives: JSch reads a zero timeout as "wait forever",
     * which would break the bounded-wait rule (DESIGN.md §6.3).
     */
    static int millis(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("ssh operations need a positive timeout, got " + timeout);
        }
        return (int) Math.min(Integer.MAX_VALUE, Math.max(1L, timeout.toMillis()));
    }

    /** A remote path's parent: {@code /a/b/c.jar} gives {@code /a/b}, {@code /x} gives {@code /}, {@code c} gives {@code ""}. */
    static String parentOf(String path) {
        String trimmed = path;
        while (trimmed.length() > 1 && trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        int slash = trimmed.lastIndexOf('/');
        if (slash < 0) {
            return "";
        }
        return slash == 0 ? "/" : trimmed.substring(0, slash);
    }

    /** True when JSch reports the server refused the login. Matched on JSch's own wording. */
    static boolean isAuthRefusal(Throwable e) {
        if (!(e instanceof JSchException)) {
            return false;
        }
        String message = e.getMessage();
        return message != null && (message.contains("Auth fail") || message.contains("Auth cancel"));
    }

    static boolean isNoSuchFile(SftpException e) {
        String message = e.getMessage();
        return e.id == NO_SUCH_FILE || (message != null && message.contains("No such file"));
    }

    /**
     * Classifies a failure to open the session. A refused login is a {@link DeviceError}, not a dead box: a
     * restart does not help it, and the message says what to check. Everything else is unreachable.
     *
     * <p>JSch does not expose which methods the server offers, so Python's separate "offers only
     * keyboard-interactive" wording is not available here; the refused-login text covers that case too.
     */
    static DeviceError connectFailure(SshTarget target, JSchException e) {
        String label = "ssh " + target.name() + " (" + target.host() + ")";
        if (isAuthRefusal(e)) {
            String key = target.keyFilename() == null
                    ? "no key (the rig's commander.ssh_key_path_env is unset)"
                    : "the key (" + target.keyFilename() + ")";
            return new DeviceError(label + " refused " + key + ": it is not authorized there, or the no-pw patch "
                    + "is not applied. Compare `ssh -v -i <key> " + target.user() + "@" + target.host()
                    + " true` from a controller that works", e);
        }
        return new DeviceUnreachable(label, "connect failed: " + e.getMessage(), e);
    }

    /**
     * Classifies a failure inside an operation. A dropped session is unreachable; an SFTP status the server
     * returned is a {@link DeviceError}, since the box answered.
     */
    static DeviceError operationFailure(String label, String what, Throwable cause) {
        if (cause instanceof IOException || cause instanceof JSchException) {
            return new DeviceUnreachable(label, what + " failed: " + cause.getMessage(), cause);
        }
        return new DeviceError(label + ": " + what + " failed: " + cause.getMessage(), cause);
    }

    /**
     * Loads the key file into JSch. JSch 0.2.20 reads OpenSSH/PEM keys and PuTTY {@code .ppk} v2 keys itself
     * (checked 2026-10-29, the webshell slice), so no conversion is needed.
     */
    static void loadIdentity(JSch jsch, String keyFilename) {
        try {
            jsch.addIdentity(keyFilename);
        } catch (JSchException e) {
            throw new DeviceError("ssh key " + keyFilename + " cannot be loaded: " + e.getMessage()
                    + " (CAT reads OpenSSH/PEM keys and PuTTY .ppk v2 keys)", e);
        }
    }

    private static String quote(String command) {
        return "'" + command + "'";
    }
}
