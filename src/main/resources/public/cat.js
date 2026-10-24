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
