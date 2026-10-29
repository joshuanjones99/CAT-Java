package com.verifone.cat.authoring;

import java.io.IOException;
import java.io.PrintStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Supplier;

import com.verifone.cat.atlassian.AtlassianClient;
import com.verifone.cat.atlassian.AtlassianCredentials;
import com.verifone.cat.authoring.Check.Diagnostic;
import com.verifone.cat.authoring.Draft.DraftException;
import com.verifone.cat.authoring.Draft.DraftResult;

/**
 * {@code cat-java authoring [--session latest|new|<dir>] (ingest <KEY> | add --title <t> --file <f> [--source
 * drafted|written] | draft | show)}: the Authoring page's session, from a command line. Port of
 * {@code python -m cat.authoring} ({@code cat/authoring/__main__.py}), for the {@code /draft-scenario} skill and anyone
 * authoring from Claude Code: the same sessions the web page shows, under {@code $CAT_AUTHORING_DIR}, else
 * {@code ~/.cat/java/authoring}.
 *
 * <p>Exit 0 on success, 1 when a case fails the checker or nothing was drafted, 2 on a usage error or an unreadable
 * ticket.
 *
 * <p>UNVERIFIED AGAINST HARDWARE: a session is files; ingest reads Jira, draft runs the operator's Claude Code.
 */
public final class AuthoringCommand {

    public static final int EXIT_OK = 0;
    public static final int EXIT_FAILED = 1;
    public static final int EXIT_USAGE = 2;

    static final String USAGE = String.join("\n",
            "usage: cat-java authoring [--session latest|new|<dir>] ACTION",
            "",
            "  ingest <KEY>                       read a Jira ticket into the session's spec",
            "  add --title <t> --file <f.feature> [--source drafted|written]   add a .feature draft as a case",
            "  draft                              ask Claude to draft cases from the session's spec",
            "  show                               list the cases and their check results",
            "  --session  latest (default), new, or a session directory");

    private static final Duration JIRA_TIMEOUT = Duration.ofSeconds(20);

    private final Path root;
    private final Supplier<AtlassianClient> atlassian;
    private final Draft.Asker ask;

    /** The production command: the operator's session root, Jira from the credentials file, Claude Code. */
    public AuthoringCommand() {
        this(Session.authoringRoot(System.getenv(),
                Path.of(System.getProperty("user.home"), ".cat", "java", "authoring")),
                () -> AtlassianClient.forCredentialsFile(AtlassianCredentials.defaultPath(), JIRA_TIMEOUT), null);
    }

    /** A command over {@code root}, with Jira and the drafting model injected (tests pass fakes). */
    public AuthoringCommand(Path root, Supplier<AtlassianClient> atlassian, Draft.Asker ask) {
        this.root = root;
        this.atlassian = atlassian;
        this.ask = ask;
    }

    public int run(String[] argv) {
        return run(argv, System.out, System.err);
    }

    public int run(String[] argv, PrintStream out, PrintStream err) {
        List<String> args = new ArrayList<>(List.of(argv));
        if (!args.isEmpty() && "authoring".equals(args.get(0))) {
            args.remove(0);
        }
        String which = "latest";
        String command = null;
        String key = null;
        String title = null;
        String file = null;
        String source = "drafted";
        for (int i = 0; i < args.size(); i++) {
            String arg = args.get(i);
            switch (arg) {
                case "--session", "--title", "--file", "--source" -> {
                    if (i + 1 >= args.size()) {
                        err.println(arg + " needs a value");
                        err.println(USAGE);
                        return EXIT_USAGE;
                    }
                    String value = args.get(++i);
                    switch (arg) {
                        case "--session" -> which = value;
                        case "--title" -> title = value;
                        case "--file" -> file = value;
                        default -> source = value;
                    }
                }
                case "-h", "--help" -> {
                    out.println(USAGE);
                    return EXIT_OK;
                }
                default -> {
                    if (command == null && List.of("ingest", "add", "draft", "show").contains(arg)) {
                        command = arg;
                    } else if ("ingest".equals(command) && key == null && !arg.startsWith("--")) {
                        key = arg;
                    } else {
                        err.println("unknown argument: " + arg);
                        err.println(USAGE);
                        return EXIT_USAGE;
                    }
                }
            }
        }
        if (command == null) {
            err.println("an action is required: ingest, add, draft or show");
            err.println(USAGE);
            return EXIT_USAGE;
        }
        if (command.equals("ingest") && key == null) {
            err.println("ingest needs a ticket key");
            return EXIT_USAGE;
        }
        if (command.equals("add") && (title == null || file == null)) {
            err.println("add needs --title and --file");
            return EXIT_USAGE;
        }
        if (!List.of("drafted", "written").contains(source)) {
            err.println("--source: invalid choice: '" + source + "' (choose from 'drafted', 'written')");
            return EXIT_USAGE;
        }
        try {
            Session session = session(which);
            switch (command) {
                case "ingest" -> {
                    Ingest.Spec spec;
                    try {
                        spec = Ingest.intoSession(session, key.strip(), atlassian.get());
                    } catch (Ingest.IngestException e) {
                        err.println(e.getMessage());
                        return EXIT_USAGE;
                    }
                    out.println(session.specPath());
                    return spec == null ? EXIT_USAGE : EXIT_OK;
                }
                case "add" -> {
                    String text = Files.readString(Path.of(file), StandardCharsets.UTF_8);
                    Case c = session.insert(session.cases().size(), title, source, text);
                    CaseReview review = session.review(c);
                    for (Diagnostic d : review.analysis().diagnostics()) {
                        out.println(d.render(session.featurePath(c).toString()));
                    }
                    out.println("added " + c.id() + " as case " + review.number() + ": " + review.title());
                    return review.analysis().ok() ? EXIT_OK : EXIT_FAILED;
                }
                case "draft" -> {
                    DraftResult result;
                    try {
                        result = Actions.draftInto(session, out::println, ask);
                    } catch (DraftException e) {
                        err.println(e.getMessage());
                        return EXIT_USAGE;
                    }
                    out.println(result.cases.size() + " drafted (" + result.clean().size() + " clean), "
                            + result.notDrafted.size() + " not drafted, $"
                            + String.format(Locale.ROOT, "%.2f", result.costUsd));
                    return result.cases.isEmpty() ? EXIT_FAILED : EXIT_OK;
                }
                default -> {
                    return show(session, out);
                }
            }
        } catch (SessionException | IOException | UncheckedIOException e) {
            err.println(e.getMessage());
            return EXIT_USAGE;
        }
    }

    private Session session(String which) {
        if (which.equals("new")) {
            return Session.create(root);
        }
        if (which.equals("latest")) {
            Session latest = Session.latest(root);
            return latest != null ? latest : Session.create(root);
        }
        return Session.load(Path.of(which));
    }

    private static int show(Session session, PrintStream out) {
        out.println("session " + session.directory());
        String story = session.ticket() == null ? "—" : session.ticket();
        String spec = session.specTicket() == null ? "—" : session.specTicket();
        out.println("story " + story + " · spec " + spec + " · " + session.cases().size() + " case(s)");
        int failing = 0;
        for (Case c : session.cases()) {
            CaseReview review = session.review(c);
            String state = review.analysis().ok() ? "ok" : review.analysis().errors().size() + " error(s)";
            if (!review.analysis().ok()) {
                failing++;
            }
            out.println("  " + review.number() + ". " + review.title() + " [" + c.source() + "] — " + state);
            for (Diagnostic d : review.analysis().diagnostics()) {
                out.println("       " + d.render(""));
            }
        }
        return failing > 0 ? EXIT_FAILED : EXIT_OK;
    }
}
