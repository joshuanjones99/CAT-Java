package com.verifone.cat.web;

import static com.verifone.cat.web.Html.esc;

/**
 * The shared page template: a dark sidebar with the navigation, and a light workspace
 * with the page heading and body. Every page is rendered through {@link #page}.
 *
 * <p>The sidebar collapses to an icon rail and remembers that choice; on narrow screens a
 * menu button opens it as a drawer. Both are done by {@code /static/cat.js}, and the page
 * works without it: the links are always there.
 */
final class Layout {

    private Layout() {}

    /**
     * @param active  the section the sidebar marks as current
     * @param heading the page's heading; also the first part of its title
     * @param body    the page's HTML, already escaped by the page that built it
     */
    static String page(Section active, String heading, String body) {
        StringBuilder nav = new StringBuilder();
        for (Section s : Section.values()) {
            boolean current = s == active;
            nav.append("<li><a href=\"").append(esc(s.path())).append('"')
                    .append(current ? " class=\"active\" aria-current=\"page\"" : "")
                    .append("><span class=\"glyph\" aria-hidden=\"true\">")
                    .append(esc(s.label().substring(0, 1)))
                    .append("</span><span class=\"label\">").append(esc(s.label()))
                    .append("</span></a></li>\n");
        }
        return """
                <!doctype html>
                <html lang="en">
                <head>
                  <meta charset="utf-8">
                  <meta name="viewport" content="width=device-width, initial-scale=1">
                  <meta name="color-scheme" content="light dark">
                  <title>%1$s · CAT-Java</title>
                  <link rel="stylesheet" href="/static/cat.css">
                  <script src="/static/htmx.min.js"></script>
                  <script src="/static/cat.js" defer></script>
                </head>
                <body class="app">
                  <a class="skip" href="#main">Skip to content</a>
                  <aside class="sidebar">
                    <div class="brand">
                      <a class="wordmark" href="/run" aria-label="CAT home">CAT</a>
                      <button type="button" class="icon-btn collapse" data-action="collapse"
                              aria-expanded="true" aria-controls="nav" aria-label="Collapse navigation">&#8249;</button>
                    </div>
                    <nav id="nav" aria-label="Main">
                      <ul>
                %2$s                  </ul>
                    </nav>
                  </aside>
                  <div class="content">
                    <header class="topbar">
                      <button type="button" class="icon-btn menu" data-action="drawer"
                              aria-controls="nav" aria-label="Open navigation">&#9776;</button>
                      <h1>%1$s</h1>
                    </header>
                    <main id="main">
                %3$s
                    </main>
                  </div>
                </body>
                </html>
                """.formatted(esc(heading), nav, body);
    }
}
