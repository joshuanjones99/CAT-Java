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
      // The open drawer covers the menu button: a click on the dimmed page behind it closes it.
      if (body.classList.contains("drawer-open") && !(event.target.closest && event.target.closest(".sidebar"))) {
        body.classList.remove("drawer-open");
      }
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

  document.addEventListener("keydown", function (event) {
    if (event.key === "Escape" && body.classList.contains("drawer-open")) {
      body.classList.remove("drawer-open");
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

  // The colour the browser paints behind its window buttons in CAT's window with no title bar: the workspace's, read
  // from cat.css's token so the buttons sit on the page's own colour in either theme.
  function syncThemeColor() {
    var colour = window.getComputedStyle(document.documentElement).getPropertyValue("--cat-page").trim();
    if (!colour) {
      return;
    }
    var meta = document.querySelector('meta[name="theme-color"]');
    if (!meta) {
      meta = document.createElement("meta");
      meta.name = "theme-color";
      document.head.appendChild(meta);
    }
    meta.content = colour;
  }

  applyTheme(readAppearance());
  syncThemeColor();
  if (window.matchMedia) {
    var scheme = window.matchMedia("(prefers-color-scheme: dark)");
    if (scheme.addEventListener) {
      scheme.addEventListener("change", syncThemeColor);
    }
  }

  // The radio applies at once; the form's post saves it and sets the cookie for the next page.
  document.addEventListener("change", function (event) {
    var target = event.target;
    if (target && target.name === "appearance") {
      applyTheme(target.value);
      syncThemeColor();
    }
  });
})();

// Hide the title bar (Settings, Opening CAT; desktop.AppWindow): CAT's window loses its title bar once CAT is
// installed as an app in CAT's own browser profile. Edge offers that with beforeinstallprompt; this keeps the offer
// and shows the button, but only in CAT's own window (display-mode standalone), never in a browser tab, where it
// would install CAT into the operator's own browser profile. The button shows Edge's Install dialog; on Install, Edge
// reopens the window as the app, and CAT opens it that way from then on.
(function () {
  "use strict";
  var offer = null;

  function inCatsWindow() {
    return !!(window.matchMedia && window.matchMedia("(display-mode: standalone)").matches);
  }

  function say(text) {
    var line = document.getElementById("cat-install-result");
    if (line) {
      line.textContent = text;
    }
  }

  window.addEventListener("beforeinstallprompt", function (event) {
    event.preventDefault();
    if (!inCatsWindow()) {
      return;
    }
    offer = event;
    var button = document.getElementById("cat-install-window");
    if (button) {
      button.hidden = false;
    }
  });

  window.addEventListener("appinstalled", function () {
    var button = document.getElementById("cat-install-window");
    if (button) {
      button.hidden = true;
    }
    say("Installed: CAT's window has no title bar from now on.");
  });

  document.addEventListener("click", function (event) {
    var control = event.target.closest ? event.target.closest('[data-action="install-window"]') : null;
    if (!control || !offer) {
      return;
    }
    var shown = offer;
    offer = null;
    shown.prompt();
    shown.userChoice.then(function (choice) {
      if (choice.outcome !== "accepted") {
        say("Not installed. Open this page again to be offered it once more.");
        control.hidden = true;
      }
    });
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

// Waiting on CAT (Layout's cat-busy): something the operator did, a click, a change, typing in a filter or opening
// another CAT page, that has waited for more than a moment shows the bar along the top of the window and the busy
// cursor until the answer lands. A poll and a part that loads after its page (Html.deferred, which has its own
// spinner) do not show it. A part that fails to load says so in place of its spinner.
(function () {
  "use strict";
  var bar = document.getElementById("cat-busy");
  var body = document.body;
  if (!bar || !body) {
    return;
  }
  var DELAY_MS = 250;
  var pending = 0;
  var timer = null;

  function show() {
    timer = null;
    bar.hidden = false;
    body.classList.add("cat-waiting-on");
  }

  function begin() {
    pending++;
    if (pending === 1 && timer === null) {
      timer = window.setTimeout(show, DELAY_MS);
    }
  }

  function reset() {
    pending = 0;
    if (timer !== null) {
      window.clearTimeout(timer);
      timer = null;
    }
    bar.hidden = true;
    body.classList.remove("cat-waiting-on");
  }

  function end() {
    pending = Math.max(0, pending - 1);
    if (pending === 0) {
      reset();
    }
  }

  // A poll (hx-trigger "every") and a "load" trigger have no triggering event: only the operator's own do.
  function operatorAsked(detail) {
    var config = detail && detail.requestConfig;
    var event = config && config.triggeringEvent;
    return !!event && event.type !== "load";
  }

  document.addEventListener("htmx:beforeRequest", function (event) {
    var xhr = event.detail && event.detail.xhr;
    if (xhr && operatorAsked(event.detail)) {
      xhr.catBusy = true;
      begin();
    }
  });

  function finished(event) {
    var xhr = event.detail && event.detail.xhr;
    if (xhr && xhr.catBusy) {
      xhr.catBusy = false;
      end();
    }
  }

  ["htmx:afterRequest", "htmx:sendError", "htmx:sendAbort", "htmx:timeout"].forEach(function (name) {
    document.addEventListener(name, finished);
  });

  // A deferred part whose load failed: the words in place of the spinner, never a spinner that turns for ever.
  function deferredFailed(event) {
    var elt = event.detail && event.detail.elt;
    if (!elt || !elt.hasAttribute || !elt.hasAttribute("data-deferred")) {
      return;
    }
    var xhr = event.detail.xhr;
    var why = xhr && xhr.status ? "CAT answered " + xhr.status : "CAT did not answer";
    var waiting = elt.querySelector(".cat-waiting");
    if (waiting) {
      waiting.classList.add("failed");
      waiting.textContent = "This part did not load (" + why + "). Reload the page to try again.";
    }
    elt.setAttribute("aria-busy", "false");
  }

  document.addEventListener("htmx:responseError", deferredFailed);
  document.addEventListener("htmx:sendError", deferredFailed);

  // A page change that turns out to be a download leaves this page up: the bar goes after this long whatever happens.
  var NAVIGATION_CAP_MS = 20000;

  function navigating() {
    begin();
    window.setTimeout(end, NAVIGATION_CAP_MS);
  }

  // Opening another CAT page: the browser keeps this one up until the next one's answer arrives.
  document.addEventListener("click", function (event) {
    if (event.defaultPrevented || event.button !== 0 || event.metaKey || event.ctrlKey || event.shiftKey
        || event.altKey) {
      return;
    }
    var link = event.target.closest ? event.target.closest("a[href]") : null;
    if (!link || link.hasAttribute("download") || link.hasAttribute("hx-get") || link.hasAttribute("hx-post")
        || (link.target && link.target !== "_self") || link.origin !== window.location.origin) {
      return;
    }
    if (link.pathname === window.location.pathname && link.search === window.location.search && link.hash) {
      return; // a jump within this page
    }
    navigating();
  });

  // A plain form post (not htmx's, which prevents the browser's own) is a page change too.
  document.addEventListener("submit", function (event) {
    if (!event.defaultPrevented) {
      navigating();
    }
  });

  // Back to a page kept in the browser's cache: nothing is waiting on it.
  window.addEventListener("pageshow", function (event) {
    if (event.persisted) {
      reset();
    }
  });
})();
