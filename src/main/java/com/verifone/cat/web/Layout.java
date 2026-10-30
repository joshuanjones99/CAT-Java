package com.verifone.cat.web;

import static com.verifone.cat.web.Html.esc;

import java.util.Map;

import com.verifone.cat.shell.Shell;

/**
 * The shared page template, the Python CAT's {@code cat/web/layout.py} {@code frame}: a dark sidebar with the
 * navigation, the loading line, the toast feed, and a light workspace with the page heading and body. Every page is
 * rendered through {@link #page}.
 *
 * <p>The shell's live parts come from the {@link Shell} the app binds for the request ({@link #bind}): the Setup
 * link's attention marker (the setup status), the Run page's setup warning button before its controls, the loading
 * line ("Loading CAT — checking setup"), and the id of the newest toast, so a page that opens later does not replay
 * old ones. {@code /static/cat.js} polls {@code /_cat/shell} for new toasts and those lines, and that poll is the
 * page's heartbeat for the auto-close. A page rendered with no shell bound (a unit test) has none of these.
 *
 * <p>Brand assets: the sidebar and favicon use the Python CAT's own CAT monogram ({@code icons/cat-192.png},
 * {@code cat.ico}, the favicons) and its monochrome navigation glyphs, which {@code static/brand-assets.md} records
 * as original, user-approved CAT assets, not Verifone logos. The Python's wide {@code cat-commander-logo.png} is not
 * copied: it embeds a supplied "commander dots" artwork whose origin this port does not know, so the expanded
 * sidebar shows the monogram and the word CAT instead.
 *
 * <p>The sidebar collapses to an icon rail and remembers that choice; on narrow screens a menu button opens it as a
 * drawer. Both are done by {@code /static/cat.js}, and the page works without it: the links are always there.
 */
final class Layout {

    /** The navigation glyph images, by path; other links keep their letter. The Python's {@code NAV_IMAGES}. */
    static final Map<String, String> NAV_IMAGES = Map.of(
            "/run", "run-simple.webp",
            "/results", "results-simple.webp",
            "/authoring", "authoring-simple.webp",
            "/tools", "tools-simple.webp",
            "/setup", "setup-left.webp");

    private static final ThreadLocal<Shell> SHELL = new ThreadLocal<>();

    private Layout() {}

    /** Binds the shell every page rendered on this thread reads, until {@link #unbind}. */
    static void bind(Shell shell) {
        SHELL.set(shell);
    }

    static void unbind() {
        SHELL.remove();
    }

    /** The shell bound to this request, or null. */
    static Shell shell() {
        return SHELL.get();
    }

    /**
     * @param active  the section the sidebar marks as current
     * @param heading the page's heading; also the first part of its title
     * @param body    the page's HTML, already escaped by the page that built it
     */
    static String page(Section active, String heading, String body) {
        Shell shell = SHELL.get();
        String setupStatus = shell == null ? "" : shell.setupStatus();
        StringBuilder top = new StringBuilder();
        StringBuilder bottom = new StringBuilder();
        for (Section s : Section.values()) {
            boolean lower = s == Section.SETUP || s == Section.SETTINGS;
            navLink(lower ? bottom : top, s, s == active, setupStatus);
        }
        String warning = active == Section.RUN && !setupStatus.isEmpty()
                ? "<a class=\"setup-warning\" href=\"/setup\" role=\"button\">&#9888; " + esc(setupStatus) + "</a>"
                : "";
        String loading = shell == null ? "" : shell.loadingLine();
        long since = shell == null ? 0 : shell.lastToast();
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <meta name="color-scheme" content="light dark">
                  <title>%1$s · CAT-Java</title>
                  <link rel="icon" href="/static/icons/cat.ico" sizes="any">
                  <link rel="icon" type="image/png" sizes="32x32" href="/static/icons/favicon-32.png">
                  <link rel="stylesheet" href="/static/cat.css">
                  <script src="/static/htmx.min.js"></script>
                  <script src="/static/cat.js" defer></script>
                </head>
                <body class="app" data-shell="%6$s" data-since="%7$d">
                  <a class="skip" href="#main">Skip to content</a>
                  <aside class="sidebar">
                    <div class="brand">
                      <a class="wordmark" href="/run" aria-label="CAT home"><img src="/static/icons/cat-192.png"
                         class="brand-icon" alt="" aria-hidden="true" width="28" height="28"><span>CAT</span></a>
                      <button type="button" class="icon-btn collapse" data-action="collapse"
                              aria-expanded="true" aria-controls="nav" aria-label="Collapse navigation">&#8249;</button>
                    </div>
                    <nav id="nav" aria-label="Main">
                      <ul>
                %2$s                  </ul>
                      <ul class="nav-bottom">
                %3$s                  </ul>
                    </nav>
                  </aside>
                  <div class="content">
                    <div id="cat-loading" class="cat-note"%8$s role="status"><span class="cat-loader"></span><span
                         id="cat-loading-text">%9$s</span></div>
                    <header class="topbar">
                      <button type="button" class="icon-btn menu" data-action="drawer"
                              aria-controls="nav" aria-label="Open navigation">&#9776;</button>
                      <h1>%1$s</h1>
                      %4$s
                    </header>
                    <main id="main">
                %5$s
                    </main>
                  </div>
                  <div id="cat-toasts" class="toasts" aria-live="polite"></div>
                </body>
                </html>
                """.formatted(esc(heading), top, bottom, warning, body, shell == null ? "off" : "on", since,
                loading.isEmpty() ? " hidden" : "", esc(loading));
    }

    private static void navLink(StringBuilder nav, Section s, boolean current, String setupStatus) {
        nav.append("<li><a href=\"").append(esc(s.path())).append('"')
                .append(current ? " class=\"active\" aria-current=\"page\"" : "")
                .append(" aria-label=\"").append(esc(s.label())).append("\" title=\"").append(esc(s.label()))
                .append("\"><span class=\"glyph\" aria-hidden=\"true\">");
        String image = NAV_IMAGES.get(s.path());
        if (image != null) {
            nav.append("<img src=\"/static/icons/navigation/").append(esc(image))
                    .append("\" class=\"nav-image\" width=\"21\" height=\"21\" alt=\"\">");
        } else {
            nav.append(esc(s.label().substring(0, 1)));
        }
        nav.append("</span><span class=\"label\">").append(esc(s.label())).append("</span>");
        if (s == Section.SETUP) {
            nav.append("<img id=\"cat-setup-marker\" class=\"setup-marker\" src=\"/static/icons/navigation/setup-warning.webp\""
                    + " alt=\"Setup needs attention\" width=\"16\" height=\"16\"")
                    .append(setupStatus.isEmpty() ? " hidden" : "").append('>');
        }
        nav.append("</a></li>\n");
    }

    /**
     * Shut down CAT, with its confirmation: the Python's {@code shutdown_button} and {@code _shutdown_dialog}. The
     * note says what shutting down stops; the dialog's Shut down posts {@code /_cat/shutdown}.
     */
    static String shutdownButton() {
        Shell shell = SHELL.get();
        String note = shell == null ? "Every open CAT page stops working until CAT is started again."
                : shell.shutdownNote();
        return """
                <button type="button" class="danger" data-action="open-dialog" data-target="cat-shutdown">Shut down CAT</button>
                <dialog id="cat-shutdown" class="cat-dialog" aria-labelledby="cat-shutdown-title">
                  <h2 id="cat-shutdown-title">Shut down CAT?</h2>
                  <p class="muted">%s</p>
                  <div class="sim-actions">
                    <button type="button" class="ghost" data-action="close-dialog" data-target="cat-shutdown">Cancel</button>
                    <button type="button" class="danger" hx-post="/_cat/shutdown" hx-swap="none"
                            data-action="close-dialog" data-target="cat-shutdown">Shut down</button>
                  </div>
                </dialog>
                """.formatted(esc(note));
    }
}
