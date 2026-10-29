package com.verifone.cat.web;

/**
 * The styles and the small script the Run and Results run pages share: the board, the dialogs, the Validations
 * view's side-by-side table and diff colours, and the Log's following. Kept with the Run pages (the shell's
 * {@code cat.css} and {@code cat.js} belong to the layout) and written once into each page that draws a run.
 *
 * <p>The script does four things, each without a framework: the case filter (rows whose text holds the filter stay
 * visible, re-applied after each htmx swap of the table), closing a dialog, the Log's following (it stops when the
 * reader scrolls up, says how many lines arrived since, and resumes at the bottom or on Back to the newest), and Log
 * Copy (every line the filters keep, from the server, onto the clipboard).
 */
final class RunStyle {

    private RunStyle() {}

    /** The style element. Colours are the shell's tokens, so both themes follow. */
    static final String CSS = """
            <style>
            .run-board { display: grid; grid-template-columns: minmax(0, 1fr) 18rem; gap: 16px; align-items: start; }
            @media (max-width: 900px) { .run-board { grid-template-columns: 1fr; } }
            .run-board-cases, .run-side-card { border: 1px solid var(--border); border-radius: 8px; background: var(--surface); }
            .run-case-head { display: flex; gap: 8px; align-items: center; padding: 8px 12px; flex-wrap: wrap; }
            .run-case-head h2 { margin: 0; font-size: 16px; }
            .run-case-head .grow { flex: 1; }
            .run-case-table { width: 100%; border-collapse: collapse; font-size: 14px; }
            .run-case-table th, .run-case-table td { text-align: left; padding: 4px 12px; border-top: 1px solid var(--border); vertical-align: top; }
            .run-case-table td.num { text-align: right; white-space: nowrap; }
            .run-case-link { background: none; border: 0; padding: 0; color: var(--accent); cursor: pointer; text-align: left; font: inherit; }
            .run-case-detail { color: var(--muted); font-size: 12px; }
            .run-side { display: grid; gap: 12px; }
            .run-side-card h3 { margin: 0; padding: 8px 12px 4px; font-size: 15px; }
            .run-side-row { display: flex; gap: 8px; align-items: center; padding: 3px 12px; font-size: 14px; }
            .run-side-row .grow { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
            .run-side-row.click { cursor: pointer; }
            .run-side-row.click:hover { background: var(--accent-soft); }
            .run-side-empty { padding: 3px 12px 8px; color: var(--muted); font-size: 14px; }
            .run-current { display: grid; gap: 6px; width: 100%; text-align: left; padding: 10px 12px; border: 1px solid var(--border); border-radius: 8px; background: var(--surface); color: var(--text); cursor: pointer; font: inherit; }
            .run-current:hover { border-color: var(--accent); }
            .run-current .title { font-size: 17px; font-weight: 600; }
            .run-current .info { color: var(--muted); font-size: 13px; }
            .badge.run { color: var(--accent); }
            .badge.idle { color: var(--muted); }
            .badge.bad { color: var(--error); }
            .run-post { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) 16rem; gap: 16px; align-items: start; }
            @media (max-width: 900px) { .run-post { grid-template-columns: 1fr; } }
            .run-complete { font-family: ui-monospace, Consolas, monospace; font-size: 13px; white-space: pre; margin: 0; }
            .run-modal-back { position: fixed; inset: 0; background: rgba(0, 0, 0, 0.45); display: flex; align-items: flex-start; justify-content: center; padding: 4vh 16px; z-index: 50; overflow: auto; }
            .run-modal { background: var(--surface); color: var(--text); border-radius: 10px; border: 1px solid var(--border); width: min(64rem, 100%); max-height: 92vh; overflow: auto; padding: 16px; display: grid; gap: 10px; }
            .run-modal.wide { width: min(96rem, 100%); }
            .run-modal h2 { margin: 0; font-size: 18px; }
            .run-modal-foot { display: flex; justify-content: flex-end; gap: 8px; }
            .run-step-dialog { display: grid; grid-template-columns: 15rem minmax(0, 1fr); gap: 12px; }
            @media (max-width: 700px) { .run-step-dialog { grid-template-columns: 1fr; } }
            .run-step-tabs { display: grid; gap: 2px; align-content: start; }
            .run-step-tabs button { display: flex; justify-content: space-between; gap: 6px; text-align: left; background: none; border: 1px solid transparent; border-radius: 6px; padding: 4px 8px; color: var(--text); cursor: pointer; font: inherit; font-size: 14px; }
            .run-step-tabs button[aria-current="true"] { border-color: var(--accent); background: var(--accent-soft); }
            .run-rows { display: grid; gap: 2px; }
            .run-row { display: flex; gap: 8px; align-items: flex-start; font-size: 14px; padding: 2px 0; }
            .run-row .grow { flex: 1; min-width: 0; }
            .run-row .sub { color: var(--muted); font-size: 12px; white-space: pre-wrap; overflow-wrap: anywhere; }
            .run-pre { white-space: pre-wrap; overflow-wrap: anywhere; font-family: ui-monospace, Consolas, monospace; font-size: 13px; margin: 0; }
            .run-tabs { display: flex; gap: 4px; flex-wrap: wrap; border-bottom: 1px solid var(--border); }
            .run-tabs button, .run-tabs a { background: none; border: 0; border-bottom: 2px solid transparent; padding: 6px 10px; color: var(--text); cursor: pointer; font: inherit; text-decoration: none; }
            .run-tabs [aria-current="true"], .run-tabs [aria-current="page"] { border-bottom-color: var(--accent); font-weight: 600; }
            .run-vgroup { border: 1px solid var(--border); border-radius: 8px; padding: 8px 12px; display: grid; gap: 4px; }
            .run-vgroup h3 { margin: 0; font-size: 15px; }
            .run-vgroup details summary { cursor: pointer; display: flex; gap: 8px; align-items: center; list-style: none; }
            .run-vgroup details summary::-webkit-details-marker { display: none; }
            .run-vgroup .kind { font-weight: 600; min-width: 5rem; }
            .run-vgroup .vdetail { padding: 6px 0 6px 16px; display: grid; gap: 6px; }
            .diff .removed, .run-sbs tr.removed td, .run-sbs tr.changed td.l { color: var(--error); }
            .diff .added, .run-sbs tr.added td, .run-sbs tr.changed td.r { color: var(--ok); }
            .diff .hunk { color: var(--accent); }
            .diff .head { font-weight: 600; }
            .run-sbs { border-collapse: collapse; width: 100%; font-family: ui-monospace, Consolas, monospace; font-size: 12px; table-layout: fixed; }
            .run-sbs col.n { width: 3rem; }
            .run-sbs th { text-align: left; border-bottom: 1px solid var(--border); padding: 2px 6px; }
            .run-sbs td { white-space: pre-wrap; overflow-wrap: anywhere; padding: 0 6px; vertical-align: top; }
            .run-sbs td.n { color: var(--muted); text-align: right; }
            .run-sbs tr.changed, .run-sbs tr.removed, .run-sbs tr.added { background: var(--accent-soft); }
            .run-receipt { display: flex; gap: 16px; align-items: flex-start; flex-wrap: wrap; }
            .run-receipt pre { margin: 0; padding: 8px; background: #f2f2f2; color: #000; border-radius: 6px; font-size: 12px; }
            .run-receipt .diff { color: var(--error); font-family: ui-monospace, Consolas, monospace; font-size: 12px; white-space: pre; margin: 0; }
            .run-log-bar { display: flex; flex-wrap: wrap; gap: 10px; align-items: center; margin-bottom: 8px; }
            .run-log-bar fieldset { border: 0; padding: 0; margin: 0; display: flex; gap: 6px; flex-wrap: wrap; }
            .run-log .lv-WARNING { color: var(--warn); }
            .run-log .lv-ERROR, .run-log .lv-CRITICAL { color: var(--error); }
            .run-log .lv-DEBUG { color: var(--muted); }
            </style>
            """;

    /** The page script: the filter, the dialogs and the Log's following and Copy. */
    static final String SCRIPT = """
            <script>
            (function () {
              "use strict";
              var catRun = window.catRun = window.catRun || {};
              catRun.filterText = "";
              catRun.filter = function (text) {
                catRun.filterText = (text || "").toLowerCase();
                catRun.applyFilter();
              };
              catRun.applyFilter = function () {
                var rows = document.querySelectorAll("#run-cases tbody tr");
                rows.forEach(function (row) {
                  var show = !catRun.filterText || row.textContent.toLowerCase().indexOf(catRun.filterText) >= 0;
                  row.hidden = !show;
                });
              };
              catRun.close = function (button) {
                var back = button.closest(".run-modal-back");
                if (back) { back.remove(); }
              };
              var catLog = window.catLog = window.catLog || {};
              catLog.following = true;
              catLog.frozenAt = 0;
              catLog.pre = function () { return document.getElementById("log-pre"); };
              catLog.atBottom = function (pre) { return pre.scrollTop + pre.clientHeight >= pre.scrollHeight - 40; };
              catLog.toBottom = function () { var pre = catLog.pre(); if (pre) { pre.scrollTop = pre.scrollHeight; } };
              catLog.scrolled = function () {
                var pre = catLog.pre();
                if (!pre) { return; }
                if (catLog.atBottom(pre)) {
                  if (!catLog.following) { catLog.resume(); }
                } else if (catLog.following) {
                  catLog.following = false;
                  var body = document.getElementById("log-body");
                  catLog.frozenAt = body ? parseInt(body.getAttribute("data-count") || "0", 10) : 0;
                  var newest = document.getElementById("log-newest");
                  if (newest) { newest.hidden = false; }
                  var count = document.getElementById("log-count");
                  if (count) { count.setAttribute("data-since", String(catLog.frozenAt)); htmx.trigger(count, "refresh"); }
                }
              };
              catLog.resume = function () {
                catLog.following = true;
                var newest = document.getElementById("log-newest");
                if (newest) { newest.hidden = true; }
                var count = document.getElementById("log-count");
                if (count) { count.textContent = ""; }
                var body = document.getElementById("log-body");
                if (body) { htmx.trigger(body, "refresh"); }
              };
              catLog.copy = function (url) {
                var form = document.getElementById("log-filter");
                var query = form ? new URLSearchParams(new FormData(form)).toString() : "";
                fetch(url + (query ? "?" + query : "")).then(function (r) { return r.text(); }).then(function (text) {
                  var note = document.getElementById("log-copied");
                  if (!text) { if (note) { note.textContent = "Nothing to copy."; } return; }
                  var lines = text.split("\\n").length;
                  navigator.clipboard.writeText(text).then(function () {
                    if (note) { note.textContent = "Copied " + lines + " line(s)."; }
                  }, function () {
                    if (note) { note.textContent = "The browser would not copy; open the text instead."; }
                  });
                });
              };
              document.addEventListener("htmx:configRequest", function (event) {
                var elt = event.detail.elt;
                if (elt && elt.id === "log-count") {
                  event.detail.parameters.since = elt.getAttribute("data-since") || String(catLog.frozenAt);
                }
              });
              document.addEventListener("htmx:afterSwap", function () {
                catRun.applyFilter();
                var filter = document.getElementById("run-filter");
                if (filter && filter.value) { catRun.filterText = filter.value.toLowerCase(); catRun.applyFilter(); }
                var pre = catLog.pre();
                if (pre && !pre.getAttribute("data-watched")) {
                  pre.setAttribute("data-watched", "1");
                  pre.addEventListener("scroll", catLog.scrolled);
                }
                if (catLog.following) { catLog.toBottom(); }
              });
              document.addEventListener("DOMContentLoaded", function () {
                var pre = catLog.pre();
                if (pre) { pre.setAttribute("data-watched", "1"); pre.addEventListener("scroll", catLog.scrolled); catLog.toBottom(); }
              });
            })();
            </script>
            """;

    /** Both, for a page body. */
    static String assets() {
        return CSS + SCRIPT;
    }
}
