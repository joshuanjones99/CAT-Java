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
