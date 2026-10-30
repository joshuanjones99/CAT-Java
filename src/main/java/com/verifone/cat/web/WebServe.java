package com.verifone.cat.web;

import java.io.IOException;
import java.io.PrintStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.verifone.cat.posshim.PosShimClient;
import com.verifone.cat.results.ResultsLibrary;
import com.verifone.cat.rig.Rig;
import com.verifone.cat.rig.RigLoader;
import com.verifone.cat.runconfig.ConfigForm;
import com.verifone.cat.runservice.RunService;
import com.verifone.cat.shell.AutoClose;
import com.verifone.cat.shell.Shell;

import io.javalin.Javalin;

/**
 * Starting the web UI: the Python CAT's {@code cat.web.serve}, for {@code cat-java [web] ...}.
 *
 * <ul>
 *   <li>A second start on a PC where CAT is already serving opens the browser on the running one rather than
 *       starting another (the shortcut may be clicked twice): {@link WebApp#PING_PATH} answers {@code CAT}. A port
 *       held by another program is refused with exit 2.</li>
 *   <li>{@code --rig} names the rig (the Python's is always {@code lab}, which is the default here). As the Python's,
 *       the web UI serves whether or not the rig loads or sets {@code pos_shim: true}: the POS page then says it has
 *       no register, and the Config tab's run says why the rig will not load.</li>
 *   <li>On start, unless {@code --no-setup}: the setup check and its repairs, the saved-login sign-in, and the Java 17
 *       / simulator download once the Verifone login signs in; and runs are refused without the sign-ins.</li>
 *   <li>With the browser opened and {@code --stay} off, CAT closes itself once no page has been open for a while and
 *       nothing is running ({@link AutoClose}), and logs to {@link #LOG_NAME} under {@code ~/.cat/java} (CAT-Java's own
 *       place; the Python writes {@code catt-web.log} to the results folder), fresh each start.</li>
 *   <li>{@code --no-browser} opens no browser and never closes by itself.</li>
 * </ul>
 *
 * <p>Blocks until the server stops (Shut down CAT, or the auto-close) and returns the exit code.
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port): tests drive {@link #serve} with fakes for the browser and the port.
 */
public final class WebServe {

    /** The log, asked for only when used: the log file must be chosen before slf4j first starts. */
    private static Logger log() {
        return LoggerFactory.getLogger("cat.web");
    }

    /** The rig the Python's web UI always uses. */
    public static final String DEFAULT_RIG = "lab";

    /** The web log's name under {@code ~/.cat/java}. */
    public static final String LOG_NAME = "cat-java-web.log";

    /** slf4j-simple's log-file property. */
    static final String LOG_FILE_PROPERTY = "org.slf4j.simpleLogger.logFile";

    static final int DEFAULT_PORT = 8090;
    static final Duration SHIM_TIMEOUT = Duration.ofSeconds(5);

    static final String USAGE = """
            usage: cat-java [web] [--rig <name|file.yaml>] [--register <id>] [--port <n>] [--shim-port <n>]
                              [--no-browser] [--stay] [--no-setup]
              --rig         the rig (default lab); the UI serves even when it does not load
              --no-browser  open no browser, and never close by itself
              --stay        never close by itself
              --no-setup    no setup check on start and no sign-in gate (tests and diagnosis)
            """;

    /** The parsed command line. */
    static final class Options {
        String rig = DEFAULT_RIG;
        String register;
        int port = DEFAULT_PORT;
        int shimPort = PosShimClient.DEFAULT_SHIM_PORT;
        boolean browser = true;
        boolean stay;
        boolean setup = true;

        static Options parse(List<String> argv) {
            Options o = new Options();
            Deque<String> args = new ArrayDeque<>(argv);
            while (!args.isEmpty()) {
                String flag = args.pop();
                switch (flag) {
                    case "--rig" -> o.rig = value(flag, args);
                    case "--register" -> o.register = value(flag, args);
                    case "--port" -> o.port = port(flag, value(flag, args));
                    case "--shim-port" -> o.shimPort = port(flag, value(flag, args));
                    case "--no-browser" -> o.browser = false;
                    case "--stay" -> o.stay = true;
                    case "--no-setup" -> o.setup = false;
                    case "-h", "--help" -> throw new IllegalArgumentException("");
                    default -> throw new IllegalArgumentException("Unknown option " + flag + ".");
                }
            }
            return o;
        }

        private static String value(String flag, Deque<String> args) {
            if (args.isEmpty()) {
                throw new IllegalArgumentException(flag + " needs a value.");
            }
            return args.pop();
        }

        private static int port(String flag, String value) {
            try {
                int port = Integer.parseInt(value);
                if (port < 1 || port > 65535) {
                    throw new NumberFormatException();
                }
                return port;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(flag + " must be a port number, not '" + value + "'.");
            }
        }
    }

    /** What {@link #serve} reaches outside itself; tests fake each. */
    interface Host {
        /** Something answers on the port. */
        boolean portInUse(int port);

        /** What answers is a CAT (its ping says so). */
        boolean catServing(int port);

        void openBrowser(String url);

        /** The app for these options, not started. */
        Javalin app(Options options, Shell shell, Runnable exit);

        /** Called once the app is up: the setup check, when asked. */
        void started(Options options, Shell shell);

        /** Points the log at {@code ~/.cat/java/cat-java-web.log}; before anything logs. */
        void logToFile();
    }

    private WebServe() {}

    /** {@code cat-java [web] ...}: parse, serve, block until stopped; the exit code. */
    public static int run(String[] argv) {
        Options options;
        try {
            options = Options.parse(List.of(argv));
        } catch (IllegalArgumentException e) {
            if (!e.getMessage().isEmpty()) {
                System.err.println(e.getMessage());
            }
            System.err.print(USAGE);
            return 2;
        }
        return serve(options, real(), System.out);
    }

    static int serve(Options options, Host host, PrintStream out) {
        String url = "http://" + WebApp.BIND_HOST + ":" + options.port + "/";
        if (host.portInUse(options.port)) {
            if (!host.catServing(options.port)) {
                out.println("Port " + options.port + " is in use by another program; start CAT with --port N.");
                return 2;
            }
            out.println("CAT is already running at " + url);
            if (options.browser) {
                host.openBrowser(url);
            }
            return 0;
        }
        boolean watch = options.browser && !options.stay;
        if (watch) {
            host.logToFile();
        }
        Shell shell = new Shell();
        CountDownLatch stopped = new CountDownLatch(1);
        Javalin[] holder = new Javalin[1];
        Runnable exit = () -> {
            Thread thread = new Thread(() -> {
                if (holder[0] != null) {
                    holder[0].stop();
                }
                stopped.countDown();
            }, "cat-web-exit");
            thread.start();
        };
        Javalin app = host.app(options, shell, exit);
        holder[0] = app;
        app.events(events -> events.serverStopped(stopped::countDown));
        app.start(WebApp.BIND_HOST, options.port);
        out.println("CAT-Java on " + url + " (rig " + options.rig + ")");
        host.started(options, shell);
        AutoClose closer = null;
        if (watch) {
            closer = AutoClose.watch(shell, System::nanoTime, line -> log().info(line), () -> {
                shell.shutdown();
                exit.run();
            });
        }
        if (options.browser) {
            host.openBrowser(url);
        }
        try {
            stopped.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            if (closer != null) {
                closer.close();
            }
        }
        return 0;
    }

    /** The real host: a TCP connect, the ping over HTTP, the Windows browser, the real services. */
    static Host real() {
        return new Host() {
            @Override
            public boolean portInUse(int port) {
                try (Socket socket = new Socket()) {
                    socket.connect(new InetSocketAddress(WebApp.BIND_HOST, port), 500);
                    return true;
                } catch (IOException e) {
                    return false;
                }
            }

            @Override
            public boolean catServing(int port) {
                return pingSaysCat(port);
            }

            @Override
            public void openBrowser(String url) {
                try {
                    new ProcessBuilder("rundll32", "url.dll,FileProtocolHandler", url).start();
                } catch (IOException e) {
                    log().warn("could not open the browser on {}: {}", url, e.getMessage());
                }
            }

            @Override
            public Javalin app(Options options, Shell shell, Runnable exit) {
                Rig rig = null;
                Rig.Register register = null;
                try {
                    rig = RigLoader.load(options.rig, RigLoader.rigsDir(System.getenv()));
                    register = rig.register(options.register);
                } catch (RuntimeException e) {
                    log().warn("rig '{}' will not load: {}", options.rig, e.getMessage());
                }
                String registerHost = register == null ? "localhost" : register.host();
                // No register (no rig, or no pos_shim): the POS page reaches nothing, and says so.
                PosShimClient shim = new PosShimClient(registerHost, register == null ? 1 : options.shimPort, null,
                        SHIM_TIMEOUT);
                String title = rig == null || register == null
                        ? "CAT-Java: no register (rig '" + options.rig + "' did not load)"
                        : "CAT-Java: " + rig.name() + " / " + register.id()
                                + (rig.posShim() ? "" : " (no pos_shim on this rig)");
                WebApp.Parts parts = WebApp.parts(shim, title, ResultsLibrary.fromEnvironment(System.getenv()),
                        ConfigForm.standard().online(), RunService.standard(options.rig, System.getenv()), null);
                parts.rig = options.rig;
                parts.shell = shell;
                parts.exit = exit;
                setup = SetupService.standard(shell, options.setup);
                parts.setup = setup;
                parts.sims = SimulatorsService.standard(options.rig, shell, () -> false);
                return WebApp.build(parts);
            }

            private SetupService setup;

            @Override
            public void started(Options options, Shell shell) {
                // The Python's CatService.start: check setup (repairs, saved sign-ins, the tools download) on start.
                if (options.setup && setup != null) {
                    setup.check(false);
                }
            }

            @Override
            public void logToFile() {
                Path dir = Path.of(System.getProperty("user.home"), ".cat", "java");
                try {
                    Files.createDirectories(dir);
                    Path file = dir.resolve(LOG_NAME);
                    Files.writeString(file, "", StandardCharsets.UTF_8);
                    System.setProperty(LOG_FILE_PROPERTY, file.toString());
                } catch (IOException e) {
                    // No log file: the lines go to the console, as before.
                }
            }
        };
    }

    /** Whether the program on {@code port} is a CAT, by its ping (not just a listener). */
    static boolean pingSaysCat(int port) {
        try {
            HttpResponse<String> reply = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()
                    .send(HttpRequest.newBuilder(URI.create("http://" + WebApp.BIND_HOST + ":" + port
                            + WebApp.PING_PATH)).timeout(Duration.ofSeconds(2)).GET().build(),
                            HttpResponse.BodyHandlers.ofString());
            return reply.statusCode() == 200 && WebApp.PING_REPLY.equals(reply.body().strip());
        } catch (IOException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }
}
