// The POS page's live register screen (PosPage, PosScreenRoutes): edge://inspect's screencast, in CAT.
// Draws each frame the register sends and sends clicks, drags, the wheel and keys back, in CSS pixels of
// the register's page. Reconnects by itself when the register or CAT drops the socket.
(function () {
  'use strict';
  var canvas = document.getElementById('pos-screen');
  if (!canvas) return;
  var status = document.getElementById('pos-screen-status');
  var g = canvas.getContext('2d');
  var RETRY_MS = 3000;
  var KEEP_ALIVE_MS = 10000;
  var BUTTON_NAMES = ['left', 'middle', 'right'];

  var socket = null;
  var told = false;          // the server said why the socket ends; keep that on screen
  var keepAlive = null;
  var view = { w: 0, h: 0 }; // the register's viewport, CSS pixels, of the frame on the canvas
  var latest = null;
  var decoding = false;
  var lastPress = { at: 0, x: 0, y: 0, count: 0 };
  var pendingMove = null;

  function say(text, kind) {
    status.textContent = text;
    status.className = kind || '';
  }

  function connect() {
    told = false;
    var scheme = location.protocol === 'https:' ? 'wss://' : 'ws://';
    try {
      socket = new WebSocket(scheme + location.host + canvas.dataset.socket);
    } catch (e) {
      say('Could not open the screen socket: ' + e.message, 'warn');
      setTimeout(connect, RETRY_MS);
      return;
    }
    socket.onopen = function () {
      keepAlive = setInterval(function () { send({ t: 'ping' }); }, KEEP_ALIVE_MS);
    };
    socket.onmessage = function (event) {
      var m;
      try { m = JSON.parse(event.data); } catch (e) { return; }
      handle(m);
    };
    socket.onclose = function () {
      socket = null;
      clearInterval(keepAlive);
      if (!told) say('Disconnected from CAT. Reconnecting…', 'warn');
      setTimeout(connect, RETRY_MS);
    };
  }

  function handle(m) {
    if (m.type === 'frame') {
      latest = m;
      if (!decoding) drawNext();
    } else if (m.type === 'attached') {
      say('Live', 'ok'); // hidden: the picture speaks for itself
      canvas.title = [m.title, m.url].filter(Boolean).join(' — ');
    } else if (m.type === 'error' || m.type === 'closed') {
      told = true;
      say(m.text + ' Trying again in ' + (RETRY_MS / 1000) + ' s…', 'warn');
    }
  }

  // Only the newest frame is drawn: one still decoding when the next arrives is skipped.
  function drawNext() {
    var m = latest;
    latest = null;
    if (!m) return;
    decoding = true;
    var img = new Image();
    img.onload = function () {
      if (canvas.width !== img.naturalWidth || canvas.height !== img.naturalHeight) {
        canvas.width = img.naturalWidth;
        canvas.height = img.naturalHeight;
      }
      g.drawImage(img, 0, 0);
      view = { w: m.w, h: m.h };
      canvas.classList.add('live');
      decoding = false;
      drawNext();
    };
    img.onerror = function () { decoding = false; drawNext(); };
    img.src = 'data:image/jpeg;base64,' + m.data;
  }

  function send(message) {
    if (socket && socket.readyState === WebSocket.OPEN) socket.send(JSON.stringify(message));
  }

  // The canvas fills the page and the picture is contained in it (object-fit), so a point is mapped through the
  // picture's own box: inside is true when it falls on the picture rather than the margin beside it.
  function at(e) {
    var r = canvas.getBoundingClientRect();
    var scale = Math.min(r.width / canvas.width, r.height / canvas.height) || 1;
    var w = canvas.width * scale;
    var h = canvas.height * scale;
    var left = r.left + (r.width - w) / 2;
    var top = r.top + (r.height - h) / 2;
    var fx = (e.clientX - left) / w;
    var fy = (e.clientY - top) / h;
    return {
      x: Math.min(Math.max(fx, 0), 1) * view.w,
      y: Math.min(Math.max(fy, 0), 1) * view.h,
      inside: fx >= 0 && fx <= 1 && fy >= 0 && fy <= 1,
    };
  }

  // DevTools' modifier bits: Alt 1, Ctrl 2, Meta 4, Shift 8.
  function modifiers(e) {
    return (e.altKey ? 1 : 0) | (e.ctrlKey ? 2 : 0) | (e.metaKey ? 4 : 0) | (e.shiftKey ? 8 : 0);
  }

  function mouse(type, e, button, clickCount) {
    if (!view.w) return; // no frame yet: nothing to aim at
    var p = at(e);
    send({ t: 'mouse', type: type, x: p.x, y: p.y, button: button, buttons: e.buttons,
           clickCount: clickCount, modifiers: modifiers(e) });
  }

  var pressing = false; // a press that began on the picture: only its moves and release are sent

  canvas.addEventListener('pointerdown', function (e) {
    e.preventDefault();
    canvas.focus();
    if (!view.w || !at(e).inside) return;
    pressing = true;
    canvas.setPointerCapture(e.pointerId);
    var near = Math.abs(e.clientX - lastPress.x) < 5 && Math.abs(e.clientY - lastPress.y) < 5;
    var count = near && e.timeStamp - lastPress.at < 500 ? lastPress.count + 1 : 1;
    lastPress = { at: e.timeStamp, x: e.clientX, y: e.clientY, count: count };
    mouse('mousePressed', e, BUTTON_NAMES[e.button] || 'left', count);
  });

  canvas.addEventListener('pointerup', function (e) {
    e.preventDefault();
    if (!pressing) return;
    pressing = false;
    mouse('mouseReleased', e, BUTTON_NAMES[e.button] || 'left', lastPress.count);
  });

  // Moves go only while a press is held (a drag, a swipe), one per animation frame.
  canvas.addEventListener('pointermove', function (e) {
    if (!pressing || !e.buttons) return;
    var first = pendingMove === null;
    pendingMove = e;
    if (first) {
      requestAnimationFrame(function () {
        var move = pendingMove;
        pendingMove = null;
        if (move) mouse('mouseMoved', move, 'left', 0);
      });
    }
  });

  canvas.addEventListener('contextmenu', function (e) { e.preventDefault(); });

  canvas.addEventListener('wheel', function (e) {
    e.preventDefault();
    var p = at(e);
    if (!view.w || !p.inside) return;
    var scale = e.deltaMode === 1 ? 40 : e.deltaMode === 2 ? 400 : 1;
    send({ t: 'wheel', x: p.x, y: p.y, deltaX: e.deltaX * scale, deltaY: e.deltaY * scale, modifiers: modifiers(e) });
  }, { passive: false });

  canvas.addEventListener('keydown', function (e) {
    e.preventDefault();
    var mods = modifiers(e);
    var types = e.key.length === 1 && !e.ctrlKey && !e.metaKey;
    var text = types ? e.key : e.key === 'Enter' ? '\r' : '';
    send({ t: 'key', type: text ? 'keyDown' : 'rawKeyDown', key: e.key, code: e.code, text: text,
           keyCode: e.keyCode, modifiers: mods });
  });

  canvas.addEventListener('keyup', function (e) {
    e.preventDefault();
    send({ t: 'key', type: 'keyUp', key: e.key, code: e.code, keyCode: e.keyCode, modifiers: modifiers(e) });
  });

  connect();
})();
