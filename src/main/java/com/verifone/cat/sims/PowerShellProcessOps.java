package com.verifone.cat.sims;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * {@link ProcessOps} on Windows, as the Python CAT's {@code cat.sims.supervisor} does it: one
 * bounded {@code powershell} call per query (its {@code Get-CimInstance Win32_Process} and
 * {@code Get-NetTCPConnection}), and {@code taskkill} for a kill.
 *
 * <p>Stdout and stderr are read on daemon threads, so a stuck child cannot hang the caller past
 * its timeout. A child that outlives its timeout is destroyed and reported as an error.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port).
 */
public final class PowerShellProcessOps implements ProcessOps {

    private static final Duration IO_GRACE = Duration.ofSeconds(5);

    private final ExecutorService readers = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "sims-ps-reader");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public Snapshot commandLines(List<String> names, Duration timeout) {
        if (names.isEmpty()) {
            return Snapshot.ok("");
        }
        return powershell(cimCommand(names), timeout);
    }

    @Override
    public Snapshot listenerCommandLine(int port, Duration timeout) {
        String command = "$c = Get-NetTCPConnection -State Listen -LocalPort " + port
                + " -ErrorAction Stop | Select-Object -First 1; "
                + "(Get-CimInstance Win32_Process -Filter \"ProcessId=$($c.OwningProcess)\").CommandLine";
        Snapshot answer = powershell(command, timeout);
        return new Snapshot(answer.text().strip(), answer.error());
    }

    @Override
    public Snapshot listing(List<String> names, List<Integer> ports, Duration timeout) {
        return powershell(listingCommand(names, ports), timeout);
    }

    @Override
    public Snapshot stillAlive(List<Long> pids, Duration timeout) {
        String ids = pids.stream().map(String::valueOf).collect(Collectors.joining(","));
        return powershell("Wait-Process -Id " + ids + " -Timeout 10 -ErrorAction SilentlyContinue; "
                + "@(Get-Process -Id " + ids + " -ErrorAction SilentlyContinue).Id", timeout);
    }

    @Override
    public String taskkill(List<Long> pids, Duration timeout) {
        List<String> argv = new ArrayList<>(List.of("taskkill", "/T", "/F"));
        for (long pid : pids) {
            argv.add("/PID");
            argv.add(String.valueOf(pid));
        }
        try {
            Process process = new ProcessBuilder(argv).redirectErrorStream(true).start();
            process.getOutputStream().close();
            CompletableFuture<String> output = CompletableFuture.supplyAsync(
                    () -> read(process.getInputStream()), readers);
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return "taskkill timed out";
            }
            String said = output.get(IO_GRACE.toMillis(), TimeUnit.MILLISECONDS).strip();
            return process.exitValue() == 0 ? "" : said;
        } catch (ExecutionException | java.util.concurrent.TimeoutException e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        } catch (IOException e) {
            return e.getClass().getSimpleName() + ": " + e.getMessage();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "interrupted";
        }
    }

    static String cimCommand(List<String> names) {
        String clause = names.stream().map(name -> "Name='" + name + "'")
                .collect(Collectors.joining(" or "));
        return "Get-CimInstance Win32_Process -Filter \"" + clause
                + "\" | Select-Object -ExpandProperty CommandLine";
    }

    /** {@code Supervisor}'s single listing pass: listeners first, then processes, one line each. */
    static String listingCommand(List<String> names, List<Integer> ports) {
        List<String> parts = new ArrayList<>();
        if (!ports.isEmpty()) {
            String list = ports.stream().map(String::valueOf).collect(Collectors.joining(","));
            parts.add("Get-NetTCPConnection -State Listen -LocalPort " + list
                    + " -ErrorAction SilentlyContinue"
                    + " | ForEach-Object { \"L`t$($_.LocalPort)`t$($_.OwningProcess)\" }");
        }
        if (!names.isEmpty()) {
            String clause = names.stream().map(name -> "Name='" + name + "'")
                    .collect(Collectors.joining(" or "));
            parts.add("Get-CimInstance Win32_Process -Filter \"" + clause
                    + "\" | ForEach-Object { \"P`t$($_.ProcessId)`t"
                    + "$($_.CommandLine -replace '[\\r\\n]+', ' ')\" }");
        } else {
            parts.add("exit 0");
        }
        return String.join("; ", parts);
    }

    private Snapshot powershell(String command, Duration timeout) {
        Process process;
        try {
            process = new ProcessBuilder("powershell", "-NoProfile", "-NonInteractive",
                    "-EncodedCommand", encode(command)).start();
        } catch (IOException e) {
            return Snapshot.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
        try {
            process.getOutputStream().close();
            CompletableFuture<String> out = CompletableFuture.supplyAsync(
                    () -> read(process.getInputStream()), readers);
            CompletableFuture<String> err = CompletableFuture.supplyAsync(
                    () -> read(process.getErrorStream()), readers);
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
                return Snapshot.failed("timed out after " + timeout.toSeconds() + "s");
            }
            String stdout = out.get(IO_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            String stderr = err.get(IO_GRACE.toMillis(), TimeUnit.MILLISECONDS).strip();
            if (process.exitValue() != 0) {
                return new Snapshot(stdout, stderr.isEmpty() ? "exit " + process.exitValue() : stderr);
            }
            return Snapshot.ok(stdout);
        } catch (IOException e) {
            process.destroyForcibly();
            return Snapshot.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            return Snapshot.failed("interrupted");
        } catch (ExecutionException | java.util.concurrent.TimeoutException e) {
            process.destroyForcibly();
            return Snapshot.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    /**
     * The command as {@code -EncodedCommand} wants it: base64 of its UTF-16LE. Windows PowerShell
     * 5.1 re-parses a {@code -Command} argument and drops the double quotes that a WQL filter
     * ({@code -Filter "Name='java.exe'"}) needs, so the query fails as an invalid query. Progress
     * is silenced so a non-zero exit's stderr is the error, not progress records.
     */
    static String encode(String command) {
        String script = "$ProgressPreference = 'SilentlyContinue'; " + command;
        return Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE));
    }

    private static String read(InputStream stream) {
        try (stream) {
            return new String(stream.readAllBytes(), Charset.defaultCharset());
        } catch (IOException e) {
            return "";
        }
    }
}
