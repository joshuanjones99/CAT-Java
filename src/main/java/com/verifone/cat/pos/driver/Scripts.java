package com.verifone.cat.pos.driver;

import java.util.Map;

/**
 * The in-page scripts the driver evaluates. Ported verbatim from the Python
 * {@code petrotest.pos.driver} module-level {@code _*_DOC} strings: each is a
 * {@code (doc, arg) => ...} function that runs in one document (the page's own, or the nested
 * frame's {@code contentDocument}).
 *
 * <p>Scripts take element IDs and XPaths as arguments; none carries a selector of its own, so
 * the catalogue stays the only source of POS element IDs (DESIGN.md 4.2 rule 1).
 *
 * <p>UNVERIFIED AGAINST HARDWARE (Java port). Run in a local Chromium by the tests; not run
 * against a Topaz.
 */
final class Scripts {

    /** One check of one document: "busy", "dismissed", "nag", "nag without ok" or "clear". */
    static final String BLOCKING_OVERLAY_DOC = """
            (doc, [titleIds, buttonIds, busy, nags, mode]) => {
              const sel = (ids) => ids.map((id) => `[id="${id}"]`).join(",");
              const shown = (el) => el.offsetParent !== null;
              const text = (el) => (el.textContent || "").trim().toLowerCase();
              const onTop = (el) => {
                const r = el.getBoundingClientRect();
                const hit = doc.elementFromPoint(r.left + r.width / 2, r.top + r.height / 2);
                return !hit || el.contains(hit);
              };
              const titles = [...doc.querySelectorAll(sel(titleIds))].filter(shown);
              if (titles.some((t) => text(t).includes(busy))) return "busy";
              for (const title of titles.filter((t) => nags.some((nag) => text(t).includes(nag)))) {
                let ok = null;
                let panel = title.parentElement;
                for (; panel; panel = panel.parentElement) {
                  ok = [...panel.querySelectorAll(sel(buttonIds))].find((b) => shown(b) && text(b) === "ok");
                  if (ok) break;
                }
                if (!ok) return "nag without ok";
                if (onTop(ok)) {
                  if (mode === "look") return "nag";
                  ok.click();
                  return "dismissed";
                }
              }
              return "clear";
            }
            """;

    /** One look, no wait: presses the last visible match whose text starts with the prefix. */
    static final String TAP_LAST_VISIBLE_DOC = """
            (doc, [selector, prefix]) => {
              const norm = (el) => (el.textContent || "").replace(/\\s+/g, " ").trim();
              const hits = [...doc.querySelectorAll(selector)].filter((el) =>
                el.offsetParent !== null && !el.querySelector(selector) &&
                norm(el).toLowerCase().startsWith(prefix));
              const top = hits[hits.length - 1];
              if (!top) return null;
              top.click();
              return norm(top);
            }
            """;

    /** One document's rendered text, then every text field's value (password: length only). */
    static final String SCREEN_TEXT_DOC = """
            (doc) => {
              if (!doc.body) return "";
              const values = Array.from(doc.querySelectorAll("input, textarea"), (el) =>
                el.type === "password" ? "*".repeat(el.value.length) : el.value);
              return [doc.body.innerText, ...values].join("\\n");
            }
            """;

    /** Every element one locator matches in one document; {@code mark} "click" presses one. */
    static final String ELEMENTS_DOC = """
            (doc, [kind, selectors, mark]) => {
              let hits = [];
              if (kind === "id") {
                hits = [...doc.querySelectorAll("[id]")].filter((el) => selectors.includes(el.id));
              } else {
                for (const xp of selectors) {
                  const snap = doc.evaluate(xp, doc, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
                  for (let i = 0; i < snap.snapshotLength; i++) hits.push(snap.snapshotItem(i));
                }
              }
              const laid = (el) => el.getClientRects().length > 0;
              const states = hits.map((el) => ({
                visible: el.offsetParent !== null,
                laid: laid(el),
                text: (el.textContent || "").replace(/\\s+/g, " ").trim(),
                cls: el.getAttribute("class") || "",
              }));
              let shown = hits.filter((el) => el.offsetParent !== null);
              if (mark && !shown.length) shown = hits.filter(laid);
              if (mark && (shown.length || (mark === "click" && hits.length))) {
                const pick = shown.length ? (kind === "id" ? shown[shown.length - 1] : shown[0]) : hits[0];
                pick.click();
                states[hits.indexOf(pick)].pressed = true;
              }
              return states;
            }
            """;

    /** {@code [displayed, enabled]} of every element one locator matches in one document. */
    static final String CLICKABLE_DOC = """
            (doc, [kind, selectors]) => {
              let hits = [];
              if (kind === "id") {
                hits = [...doc.querySelectorAll("[id]")].filter((el) => selectors.includes(el.id));
              } else {
                for (const xp of selectors) {
                  const snap = doc.evaluate(xp, doc, null, XPathResult.ORDERED_NODE_SNAPSHOT_TYPE, null);
                  for (let i = 0; i < snap.snapshotLength; i++) hits.push(snap.snapshotItem(i));
                }
              }
              const view = doc.defaultView;
              const displayed = (el) => {
                const box = el.getBoundingClientRect();
                if (box.width <= 0 || box.height <= 0) return false;
                if (view.getComputedStyle(el).visibility !== "visible") return false;
                for (let node = el; node && node.nodeType === 1; node = node.parentElement) {
                  if (parseFloat(view.getComputedStyle(node).opacity) === 0) return false;
                }
                return true;
              };
              return hits.map((el) => [displayed(el), !el.matches(":disabled")]);
            }
            """;

    /** The index-th visible match (ids only) gets mousedown, mouseup and click. */
    static final String DISPATCH_DOC = """
            (doc, [kind, selectors, index]) => {
              if (kind !== "id") return false;
              const shown = [...doc.querySelectorAll("[id]")]
                .filter((el) => selectors.includes(el.id) && el.getClientRects().length > 0);
              const el = shown[index];
              if (!el) return false;
              for (const type of ["mousedown", "mouseup", "click"]) {
                el.dispatchEvent(
                  new MouseEvent(type, { bubbles: true, cancelable: true, view: doc.defaultView }));
              }
              return true;
            }
            """;

    /** {@code [id, text]} of every element matching one prefix selector in one document. */
    static final String FAMILY_TEXTS_DOC = """
            (doc, selector) => [...doc.querySelectorAll(selector)]
              .map((el) => [el.id, (el.textContent || "").replace(/\\s+/g, " ").trim()])
            """;

    /** The text of every visible element matching one selector, in one document. */
    static final String VISIBLE_TEXTS_DOC = """
            (doc, selector) => [...doc.querySelectorAll(selector)]
              .filter((el) => el.offsetParent !== null)
              .map((el) => (el.textContent || "").replace(/\\s+/g, " ").trim())
            """;

    /**
     * The documents one look covers: the page's own, then the nested frame's, reached through its
     * {@code contentDocument} (same origin on the register). {@code docsOf} returns
     * {@code [found, reached, loading, docs]}.
     */
    static final String DOCS_JS = """
              const docsOf = (frameName) => {
                const docs = [document];
                let found = false, reached = true, loading = false;
                if (frameName) {
                  const owner = [...document.querySelectorAll("iframe")]
                    .find((f) => (f.name || f.id) === frameName);
                  found = !!owner;
                  const inner = owner ? owner.contentDocument : null;
                  if (owner && !inner) reached = false;
                  else if (inner && inner.readyState !== "complete") loading = true;
                  else if (inner) docs.push(inner);
                }
                return [found, reached, loading, docs];
              };
            """;

    /** Waits in the browser for the screen to change from {@code before} and then hold still. */
    static final String SETTLE_SCRIPT = """
            ([before, quietMs, capMs, moved, frameName, intervalMs]) => new Promise((resolve) => {
            """ + DOCS_JS + """
              const read = (""" + SCREEN_TEXT_DOC.strip() + """
              );
              const text = () => docsOf(frameName)[3].map(read).join("\\n");
              const start = performance.now();
              let last = before, since = start, changed = moved;
              const tick = () => {
                const now = performance.now();
                const seen = text();
                if (seen !== last) { last = seen; since = now; changed = true; }
                if ((changed && now - since >= quietMs) || now - start >= capMs) {
                  resolve([changed, seen]);
                  return;
                }
                setTimeout(tick, intervalMs);
              };
              tick();
            })
            """;

    private static final Map<String, String> MULTI = Map.of(
            BLOCKING_OVERLAY_DOC, multi(BLOCKING_OVERLAY_DOC),
            TAP_LAST_VISIBLE_DOC, multi(TAP_LAST_VISIBLE_DOC),
            SCREEN_TEXT_DOC, multi(SCREEN_TEXT_DOC),
            ELEMENTS_DOC, multi(ELEMENTS_DOC),
            CLICKABLE_DOC, multi(CLICKABLE_DOC),
            DISPATCH_DOC, multi(DISPATCH_DOC),
            FAMILY_TEXTS_DOC, multi(FAMILY_TEXTS_DOC),
            VISIBLE_TEXTS_DOC, multi(VISIBLE_TEXTS_DOC));

    private Scripts() {}

    /**
     * {@code script} run over every document one look covers, in one evaluate. Takes
     * {@code [arg, frameName, reverse, stop]} and evaluates to {@code [found, reached, loading, results]}.
     */
    static String multi(String script) {
        return "([arg, frameName, reverse, stop]) => {\n"
                + DOCS_JS
                + "  const fn = (" + script.strip() + ");\n"
                + "  const [found, reached, loading, docs] = docsOf(frameName);\n"
                + "  if (reverse) docs.reverse();\n"
                + "  const results = [];\n"
                + "  for (const doc of docs) {\n"
                + "    const r = fn(doc, arg);\n"
                + "    results.push(r);\n"
                + "    if (stop === \"truthy\" && r) break;\n"
                + "    if (stop === \"pressed\" && Array.isArray(r) && r.some((s) => s && s.pressed)) break;\n"
                + "  }\n"
                + "  return [found, reached, loading, results];\n"
                + "}";
    }

    /** The pre-built {@link #multi} of one of the per-document scripts above. */
    static String multiOf(String script) {
        String built = MULTI.get(script);
        return built != null ? built : multi(script);
    }

    /** {@code script} bound to the evaluating frame's own document: {@code (arg) => fn(document, arg)}. */
    static String perDoc(String script) {
        return "(arg) => (" + script.strip() + ")(document, arg)";
    }
}
