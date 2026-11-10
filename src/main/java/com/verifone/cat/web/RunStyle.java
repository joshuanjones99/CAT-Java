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
            .run-board { display: grid; grid-template-columns: minmax(0, 1fr) 290px; gap: 20px; align-items: start; }
            @media (max-width: 1100px) { .run-board { grid-template-columns: minmax(0, 1fr) 250px; } }
            @media (max-width: 850px) { .run-board { grid-template-columns: 1fr; } }
            .run-board-cases, .run-side-card { border: 1px solid var(--cat-line); border-radius: var(--cat-radius); background: var(--cat-surface); overflow: hidden; }
            .run-case-head { display: flex; gap: 12px; align-items: center; padding: 16px 18px; flex-wrap: wrap; }
            .run-case-head h2 { margin: 0; font-size: 18px; }
            .run-case-head .grow { flex: 1; }
            .run-case-table { width: 100%; border-collapse: collapse; font-size: 15px; }
            .run-case-table th { text-align: left; padding: 11px 14px; background: var(--cat-line-2); color: var(--cat-label); font-size: 13px; font-weight: 650; letter-spacing: 0.015em; }
            .run-case-table td { text-align: left; padding: 12px 14px; border-top: 1px solid var(--cat-line-2); vertical-align: top; color: var(--cat-ink-2); }
            .run-case-table tbody tr:hover td { background: var(--cat-hover); }
            .run-case-table td.num { text-align: right; white-space: nowrap; }
            .run-case-link { display: block; min-height: 0; background: none; border: 0; padding: 0; color: var(--cat-ink); cursor: pointer; text-align: left; font: inherit; font-weight: 600; }
            .run-case-link:hover { color: var(--cat-blue); text-decoration: underline; background: none; }
            .run-case-detail { color: var(--cat-muted); font-size: 13px; }
            .run-side { display: grid; gap: 16px; }
            .run-side-card h3 { margin: 0; padding: 14px 16px 6px; font-size: 16px; }
            .run-side-row { display: flex; gap: 8px; align-items: center; padding: 6px 16px; font-size: 14px; }
            .run-side-row:last-child { padding-bottom: 12px; }
            .run-side-row .grow { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
            .run-side-row.click { cursor: pointer; }
            .run-side-row.click:hover { background: var(--cat-hover); }
            .run-side-empty { padding: 4px 16px 14px; color: var(--cat-muted); font-size: 14px; }
            .run-current { display: grid; gap: 6px; width: 100%; min-height: 0; text-align: left; padding: 16px; border: 1px solid var(--cat-line); border-radius: var(--cat-radius); background: var(--cat-surface); color: var(--cat-ink); cursor: pointer; font: inherit; justify-content: stretch; }
            .run-current:hover { border-color: var(--cat-blue); background: var(--cat-hover); }
            .run-current .title { font: 600 23px/1.2 var(--cat-font-heading); }
            .run-current .info { color: var(--cat-muted); font-size: 12px; }
            .run-post { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) 290px; gap: 20px; align-items: start; }
            @media (max-width: 900px) { .run-post { grid-template-columns: 1fr; } }
            .run-complete { font-family: var(--cat-font-mono); font-size: 13px; line-height: 1.65; white-space: pre; margin: 0; overflow-x: auto; }
            .run-modal-back { position: fixed; inset: 0; background: var(--cat-backdrop); display: flex; align-items: flex-start; justify-content: center; padding: 6vh 16px; z-index: 80; overflow: auto; }
            .run-modal { background: var(--cat-surface); color: var(--cat-ink); border-radius: var(--cat-radius-dialog); border: 0; box-shadow: var(--cat-dialog-shadow); width: min(64rem, 100%); max-height: 88vh; overflow: auto; padding: 28px; display: grid; gap: 16px; }
            .run-modal.wide { width: min(96rem, 100%); }
            .run-modal h2 { margin: 0; padding-bottom: 16px; border-bottom: 1px solid var(--cat-line); font: 600 28px/1.2 var(--cat-font-heading); overflow-wrap: anywhere; }
            .run-modal-foot { display: flex; justify-content: flex-end; gap: 8px; padding-top: 16px; border-top: 1px solid var(--cat-line); }
            @media (max-width: 599px) { .run-modal { padding: 20px; } .run-modal h2 { font-size: 26px; } }
            .run-step-dialog { display: grid; grid-template-columns: 15.5rem minmax(0, 1fr); gap: 16px; }
            @media (max-width: 700px) { .run-step-dialog { grid-template-columns: 1fr; } }
            .run-step-tabs { display: grid; gap: 2px; align-content: start; padding-right: 12px; border-right: 1px solid var(--cat-line); }
            @media (max-width: 700px) { .run-step-tabs { padding: 0 0 12px; border-right: 0; border-bottom: 1px solid var(--cat-line); } }
            .run-step-tabs button { display: flex; justify-content: space-between; align-items: center; gap: 8px; min-height: 40px; text-align: left; background: none; border: 1px solid transparent; border-radius: 6px; padding: 4px 10px; color: var(--cat-ink); cursor: pointer; font: inherit; font-size: 14px; font-weight: 400; }
            .run-step-tabs button:hover { background: var(--cat-hover); }
            .run-step-tabs button[aria-current="true"] { border-color: transparent; background: var(--cat-blue-soft); color: var(--cat-blue); font-weight: 600; }
            .run-rows { display: grid; gap: 2px; }
            .run-row { display: flex; gap: 10px; align-items: flex-start; font-size: 14px; padding: 4px 0; }
            .run-row .grow { flex: 1; min-width: 0; }
            .run-row .sub { color: var(--cat-muted); font-size: 12px; white-space: pre-wrap; overflow-wrap: anywhere; }
            .run-pre { white-space: pre-wrap; overflow-wrap: anywhere; font-family: var(--cat-font-mono); font-size: 13px; line-height: 1.65; margin: 0; }
            .run-tabs { display: flex; gap: 4px; flex-wrap: wrap; border-bottom: 1px solid var(--cat-line); }
            .run-tabs button, .run-tabs a { min-height: 42px; background: none; border: 0; border-bottom: 3px solid transparent; border-radius: 3px 3px 0 0; padding: 0 14px; margin-bottom: -1px; color: var(--cat-muted); cursor: pointer; font: inherit; font-weight: 600; text-decoration: none; display: inline-flex; align-items: center; }
            .run-tabs button:hover, .run-tabs a:hover { color: var(--cat-ink); background: var(--cat-hover); }
            .run-tabs [aria-current="true"], .run-tabs [aria-current="page"] { border-bottom-color: var(--cat-blue); color: var(--cat-blue); }
            .run-vgroup { border: 1px solid var(--cat-line); border-radius: var(--cat-radius-control); padding: 12px 16px; display: grid; gap: 6px; background: var(--cat-surface); }
            .run-vgroup h3 { margin: 0; font-size: 16px; }
            .run-vgroup details summary { cursor: pointer; display: flex; gap: 8px; align-items: center; list-style: none; padding: 4px 0; }
            .run-vgroup details summary::-webkit-details-marker { display: none; }
            .run-vgroup .kind { font-weight: 600; min-width: 5rem; }
            .run-vgroup .vdetail { padding: 6px 0 6px 16px; display: grid; gap: 8px; }
            .diff .removed, .run-sbs tr.removed td, .run-sbs tr.changed td.l { color: var(--cat-bad); }
            .diff .added, .run-sbs tr.added td, .run-sbs tr.changed td.r { color: var(--cat-ok-text); }
            .diff .hunk { color: var(--cat-blue); }
            .diff .head { font-weight: 600; }
            .run-sbs { border-collapse: collapse; width: 100%; font-family: var(--cat-font-mono); font-size: 12px; line-height: 1.45; table-layout: fixed; }
            .run-sbs col.n { width: 3.2em; }
            .run-sbs th { text-align: left; font-weight: 600; color: var(--cat-label); background: var(--cat-line-2); border-bottom: 1px solid var(--cat-line); padding: 4px 8px; }
            .run-sbs td { white-space: pre-wrap; overflow-wrap: anywhere; padding: 0 8px; vertical-align: top; }
            .run-sbs td.n { color: var(--cat-muted); text-align: right; user-select: none; border-right: 1px solid var(--cat-line-2); }
            .run-sbs tr.changed, .run-sbs tr.removed, .run-sbs tr.added { background: var(--cat-blue-soft); }
            .run-receipt { display: flex; gap: 16px; align-items: flex-start; flex-wrap: wrap; }
            .run-receipt pre { margin: 0; padding: 12px 14px; background: var(--cat-receipt); color: var(--cat-receipt-ink); border: 1px solid var(--cat-line); border-radius: var(--cat-radius-control); font-size: 12px; }
            .run-receipt .diff { color: var(--cat-bad); font-family: var(--cat-font-mono); font-size: 12px; white-space: pre; margin: 0; }
            .run-log-bar { display: flex; flex-wrap: wrap; gap: 10px 12px; align-items: center; margin-bottom: 12px; }
            .run-log-bar fieldset { border: 0; padding: 4px; margin: 0; display: inline-flex; gap: 3px; flex-wrap: wrap; border-radius: 9px; background: var(--cat-line-2); }
            .run-log-bar fieldset label { display: inline-flex; align-items: center; gap: 6px; min-height: 30px; padding: 0 10px; border-radius: 6px; font-size: 14px; font-weight: 600; cursor: pointer; }
            .run-log-bar fieldset label:has(input:checked) { background: var(--cat-surface); }
            .run-log-bar fieldset label input { margin: 0; }
            .run-log-bar > label { display: inline-flex; align-items: center; gap: 8px; color: var(--cat-label); font-weight: 600; }
            .run-log .lv-WARNING { color: var(--cat-warn-text); }
            .run-log .lv-ERROR, .run-log .lv-CRITICAL { color: var(--cat-bad); }
            .run-log .lv-DEBUG { color: var(--cat-muted); }
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
