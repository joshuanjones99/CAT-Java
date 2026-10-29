// The shell's two small behaviours: the sidebar's collapse to an icon rail (remembered),
// and the narrow-screen drawer. The links work without this script.
(function () {
  "use strict";
  var body = document.body;
  var RAIL_KEY = "cat.rail";

  function readRail() {
    try { return window.localStorage.getItem(RAIL_KEY); } catch (e) { return null; }
  }

  function writeRail(value) {
    try { window.localStorage.setItem(RAIL_KEY, value); } catch (e) { /* private window: not remembered */ }
  }

  function setRail(collapsed) {
    body.classList.toggle("rail", collapsed);
    var button = document.querySelector('[data-action="collapse"]');
    if (button) {
      button.setAttribute("aria-expanded", String(!collapsed));
    }
  }

  setRail(readRail() === "collapsed");

  document.addEventListener("click", function (event) {
    var control = event.target.closest ? event.target.closest("[data-action]") : null;
    if (!control) {
      return;
    }
    if (control.getAttribute("data-action") === "collapse") {
      var collapsed = !body.classList.contains("rail");
      setRail(collapsed);
      writeRail(collapsed ? "collapsed" : "open");
    } else if (control.getAttribute("data-action") === "drawer") {
      body.classList.toggle("drawer-open");
    }
  });
})();

// Appearance (docs/web-ui.md, Settings): the Settings page keeps the choice in the web settings
// file and in the cat_appearance cookie; this applies the cookie as data-theme on <html>, so
// cat.css follows it on every page. "auto" removes the attribute and the system's choice applies.
(function () {
  "use strict";

  function applyTheme(value) {
    if (value === "light" || value === "dark") {
      document.documentElement.setAttribute("data-theme", value);
    } else {
      document.documentElement.removeAttribute("data-theme");
    }
  }

  function readAppearance() {
    try {
      var match = document.cookie.match(/(?:^|; )cat_appearance=([^;]*)/);
      return match ? decodeURIComponent(match[1]) : null;
    } catch (e) {
      return null;
    }
  }

  applyTheme(readAppearance());

  // The radio applies at once; the form's post saves it and sets the cookie for the next page.
  document.addEventListener("change", function (event) {
    var target = event.target;
    if (target && target.name === "appearance") {
      applyTheme(target.value);
    }
  });
})();

// The shell (docs/web-ui.md; the Python's layout.frame): toasts on every page, the loading line and the Setup
// marker, from one poll of /_cat/shell every two seconds. The poll is also this page's heartbeat: CAT closes itself
// once no page has polled for a while (cat.web.autoclose), and a closing page says so at once.
(function () {
  "use strict";
  var body = document.body;
  if (!body || body.getAttribute("data-shell") !== "on") {
    return;
  }
  var page = Math.random().toString(36).slice(2) + Date.now().toString(36);
  var since = parseInt(body.getAttribute("data-since") || "0", 10) || 0;
  var KINDS = { info: "info", positive: "ok", warning: "warn", error: "error" };

  function toast(item) {
    var box = document.getElementById("cat-toasts");
    if (!box) {
      return;
    }
    var div = document.createElement("div");
    div.className = "toast " + (KINDS[item.kind] || "info");
    div.setAttribute("role", item.kind === "error" ? "alert" : "status");
    var text = document.createElement("span");
    text.className = "pre";
    text.textContent = item.message;
    div.appendChild(text);
    var close = document.createElement("button");
    close.type = "button";
    close.className = "icon-btn";
    close.setAttribute("aria-label", "Dismiss");
    close.textContent = "×";
    close.addEventListener("click", function () { div.remove(); });
    div.appendChild(close);
    box.appendChild(div);
    window.setTimeout(function () { div.remove(); }, Math.max(1000, (item.timeout || 5) * 1000));
  }

  function apply(state) {
    (state.toasts || []).forEach(function (item) {
      if (item.id > since) {
        toast(item);
      }
    });
    since = Math.max(since, state.last || 0);
    var loading = document.getElementById("cat-loading");
    var text = document.getElementById("cat-loading-text");
    if (loading && text) {
      text.textContent = state.loading || "";
      loading.hidden = !state.loading;
    }
    var marker = document.getElementById("cat-setup-marker");
    if (marker) {
      marker.hidden = !state.setup;
    }
  }

  function poll() {
    fetch("/_cat/shell?page=" + encodeURIComponent(page) + "&since=" + since, { cache: "no-store" })
      .then(function (reply) { return reply.ok ? reply.json() : null; })
      .then(function (state) { if (state) { apply(state); } })
      .catch(function () { /* CAT has stopped, or is restarting */ });
  }

  poll();
  window.setInterval(poll, 2000);
  window.addEventListener("pagehide", function () {
    try {
      navigator.sendBeacon("/_cat/page-closed?page=" + encodeURIComponent(page));
    } catch (e) { /* the page counts as closed once its polls stop */ }
  });
})();

// Small controls: Show on a masked field, and the confirmation dialogs (Shut down CAT).
(function () {
  "use strict";
  document.addEventListener("click", function (event) {
    var control = event.target.closest ? event.target.closest("[data-action]") : null;
    if (!control) {
      return;
    }
    var action = control.getAttribute("data-action");
    var target = document.getElementById(control.getAttribute("data-target") || "");
    if (!target) {
      return;
    }
    if (action === "reveal") {
      var shown = target.type === "text";
      target.type = shown ? "password" : "text";
      control.textContent = shown ? "Show" : "Hide";
    } else if (action === "open-dialog" && target.showModal) {
      target.showModal();
    } else if (action === "close-dialog" && target.close) {
      target.close();
    }
  });
})();
