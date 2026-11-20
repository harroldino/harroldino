/* Command Centre home screen logic.
   On the Fire Stick, window.Android is the native bridge (Bridge.java). In a desktop browser a small
   stand-in bridge is used so the screen can be previewed; it is clearly fake and never ships data anywhere.
   Kept to older JavaScript (no ?. or ??) so it runs on older Fire OS WebViews. */
(function () {
  "use strict";

  // ---------------------------------------------------------------- bridge
  var NATIVE = !!window.Android;
  var B = window.Android || makePreviewBridge();

  var pending = {}, seq = 0;
  window.__httpCb = function (id, ok, body) {
    var p = pending[id]; if (!p) return;
    delete pending[id];
    if (ok) p.res(body); else p.rej(new Error(body));
  };
  function getJSON(url) {
    if (!NATIVE) return previewGet(url);
    return new Promise(function (res, rej) {
      var id = "r" + (++seq);
      pending[id] = { res: res, rej: rej };
      B.httpGet(url, id);
      setTimeout(function () { if (pending[id]) { delete pending[id]; rej(new Error("timeout")); } }, 15000);
    }).then(function (t) { return JSON.parse(t); });
  }

  // ---------------------------------------------------------------- storage
  var store = {
    get: function (k, d) { try { var v = localStorage.getItem("cc." + k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } },
    set: function (k, v) { try { localStorage.setItem("cc." + k, JSON.stringify(v)); } catch (e) {} }
  };
  var LEAGUES = [
    { id: "soccer/aus.1", name: "A-League Men" },
    { id: "soccer/aus.w.1", name: "A-League Women" },
    { id: "australian-football/afl", name: "AFL" },
    { id: "rugby-league/3", name: "NRL" },
    { id: "soccer/eng.1", name: "Premier League" },
    { id: "basketball/nba", name: "NBA" }
  ];
  var settings = Object.assign({
    name: "Harry",
    hour24: false,
    city: { name: "Sydney", lat: -33.8688, lon: 151.2093 },
    iptv: null,
    pinned: null,
    leagues: ["soccer/aus.1", "australian-football/afl", "rugby-league/3"]
  }, store.get("settings", {}));
  function save() { store.set("settings", settings); }
  var recents = store.get("recents", []);

  // ---------------------------------------------------------------- apps
  // Apps we look for to build the default row, in this order.
  var WANTED = [
    /netflix/i, /^youtube$|^youtube \(|youtube for/i, /^stan\b/i, /iview/i, /\bsbs\b/i,
    /7plus/i, /9now/i, /10 ?play/i, /spotify/i, /disney/i
  ];
  var IPTV_HINT = /tivimate|smarters|ott navigator|sparkle|iptv|xciptv|purple|televizo|perfect player/i;

  var apps = [], byPkg = {}, icons = {};
  function loadApps() {
    try { apps = JSON.parse(B.getApps()) || []; } catch (e) { apps = []; }
    apps.sort(function (a, b) { return a.label.localeCompare(b.label); });
    byPkg = {};
    apps.forEach(function (a) { byPkg[a.pkg] = a; });
    if (!settings.iptv || !byPkg[settings.iptv]) {
      var guess = apps.filter(function (a) { return IPTV_HINT.test(a.label); })[0];
      settings.iptv = guess ? guess.pkg : (byPkg[settings.iptv] ? settings.iptv : null);
    }
    if (!settings.pinned) settings.pinned = defaultPins();
    settings.pinned = settings.pinned.filter(function (p) { return byPkg[p]; });
    save();
  }
  function defaultPins() {
    var out = [];
    WANTED.forEach(function (re) {
      var hit = apps.filter(function (a) { return re.test(a.label) && out.indexOf(a.pkg) < 0 && a.pkg !== settings.iptv; })[0];
      if (hit && out.length < 8) out.push(hit.pkg);
    });
    return out;
  }
  function icon(pkg) {
    if (!(pkg in icons)) { try { icons[pkg] = B.getIcon(pkg) || ""; } catch (e) { icons[pkg] = ""; } }
    return icons[pkg];
  }
  function iconHTML(pkg, label) {
    var src = icon(pkg);
    return src ? '<img alt="" src="' + src + '">' : esc((label || "?").replace(/[^A-Za-z0-9+]/g, "").slice(0, 2) || "?");
  }
  function open(pkg) {
    if (!pkg) return;
    if (B.launch(pkg)) {
      recents = [{ pkg: pkg, t: Date.now() }].concat(recents.filter(function (r) { return r.pkg !== pkg; })).slice(0, 6);
      store.set("recents", recents);
      setTimeout(renderRecents, 400);
    } else {
      toast("Couldn't open that app");
    }
  }

  // ---------------------------------------------------------------- dom helpers
  function $(id) { return document.getElementById(id); }
  function esc(s) { return String(s).replace(/[&<>"']/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]; }); }
  var toastTimer;
  function toast(msg) {
    var t = $("toast"); t.textContent = msg; t.classList.add("show");
    clearTimeout(toastTimer); toastTimer = setTimeout(function () { t.classList.remove("show"); }, 2200);
  }

  var SUN = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><circle cx="12" cy="12" r="4.2"/><path d="M12 2v2.5M12 19.5V22M2 12h2.5M19.5 12H22M4.9 4.9l1.8 1.8M17.3 17.3l1.8 1.8M4.9 19.1l1.8-1.8M17.3 6.7l1.8-1.8"/></svg>';
  var CLOUD = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><path d="M7 18h10a4 4 0 0 0 .6-7.95A6 6 0 0 0 6.1 11.2 3.5 3.5 0 0 0 7 18z"/></svg>';
  var RAIN = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><path d="M7 15h10a4 4 0 0 0 .6-7.95A6 6 0 0 0 6.1 8.2 3.5 3.5 0 0 0 7 15z"/><path d="M9 18l-1 2.5M13 18l-1 2.5M17 18l-1 2.5"/></svg>';
  var GEAR = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6"><circle cx="12" cy="12" r="3.2"/><path d="M12 2.5v2.6M12 18.9v2.6M4.6 4.6l1.8 1.8M17.6 17.6l1.8 1.8M2.5 12h2.6M18.9 12h2.6M4.6 19.4l1.8-1.8M17.6 6.4l1.8-1.8"/></svg>';
  var DOTS = '<span class="dots"><i></i><i></i><i></i></span>';

  // ---------------------------------------------------------------- clock
  var DAYS = ["Sunday", "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday"];
  var MON = ["January", "February", "March", "April", "May", "June", "July", "August", "September", "October", "November", "December"];
  function pad(n) { return (n < 10 ? "0" : "") + n; }
  function tick() {
    var d = new Date(), H = d.getHours(), m = pad(d.getMinutes());
    $("time").innerHTML = settings.hour24
      ? pad(H) + '<span class="colon">:</span>' + m
      : (H % 12 || 12) + '<span class="colon">:</span>' + m + '<span class="ampm">' + (H < 12 ? "AM" : "PM") + "</span>";
    $("date").textContent = DAYS[d.getDay()] + " " + d.getDate() + " " + MON[d.getMonth()];
    $("greet").innerHTML = settings.name ? 'Welcome back, <span class="nm">' + esc(settings.name) + "</span>" : "Welcome back";
    $("day").style.width = ((H * 60 + d.getMinutes()) / 1440 * 100).toFixed(1) + "%";
  }
  function timeStr(d) {
    var H = d.getHours(), m = pad(d.getMinutes());
    return settings.hour24 ? pad(H) + ":" + m : (H % 12 || 12) + ":" + m + (H < 12 ? "am" : "pm");
  }

  // ---------------------------------------------------------------- weather (Open-Meteo, no key needed)
  function wxText(c) {
    if (c === 0) return "Clear"; if (c === 1) return "Mostly clear"; if (c === 2) return "Partly cloudy"; if (c === 3) return "Cloudy";
    if (c === 45 || c === 48) return "Fog"; if (c >= 51 && c <= 57) return "Drizzle"; if (c >= 61 && c <= 67) return "Rain";
    if (c >= 71 && c <= 77) return "Snow"; if (c >= 80 && c <= 82) return "Showers"; if (c >= 95) return "Storms"; return "";
  }
  function wxIcon(c) { return c <= 1 ? SUN : ((c >= 51 && c <= 67) || (c >= 80 && c <= 82) || c >= 95) ? RAIN : CLOUD; }
  var wxDays = [];
  function loadWeather() {
    var c = settings.city;
    var url = "https://api.open-meteo.com/v1/forecast?latitude=" + c.lat + "&longitude=" + c.lon +
      "&current=temperature_2m,weather_code&daily=temperature_2m_max,temperature_2m_min,weather_code&timezone=auto&forecast_days=4";
    getJSON(url).then(function (j) {
      var code = j.current.weather_code, t = Math.round(j.current.temperature_2m);
      $("wx-icon").innerHTML = wxIcon(code);
      $("wx-t").textContent = t + "°";
      $("wx-l1").textContent = c.name + " · " + wxText(code);
      $("wx-l2").textContent = "Low " + Math.round(j.daily.temperature_2m_min[0]) + "° · Tomorrow " + Math.round(j.daily.temperature_2m_max[1]) + "°";
      wxDays = j.daily.time.slice(1).map(function (iso, i) {
        var dt = new Date(iso + "T12:00:00");
        return DAYS[dt.getDay()].slice(0, 3) + " " + Math.round(j.daily.temperature_2m_max[i + 1]) + "° " + wxText(j.daily.weather_code[i + 1]).toLowerCase();
      });
    }).catch(function () {
      $("wx-icon").innerHTML = CLOUD; $("wx-t").textContent = "--°";
      $("wx-l1").textContent = c.name; $("wx-l2").textContent = "Weather unavailable";
    });
  }

  // ---------------------------------------------------------------- scores (ESPN public scoreboards)
  var scoreTimer;
  function loadScores() {
    clearTimeout(scoreTimer);
    var leagues = LEAGUES.filter(function (l) { return settings.leagues.indexOf(l.id) >= 0; });
    $("scores-em").textContent = leagues.map(function (l) { return l.name; }).join(" · ");
    if (!leagues.length) { $("scores").innerHTML = '<div class="empty">No leagues picked. Press Menu ☰ to choose some in Settings.</div>'; return; }
    Promise.all(leagues.map(function (l) {
      return getJSON("https://site.api.espn.com/apis/site/v2/sports/" + l.id + "/scoreboard")
        .then(function (j) { return (j.events || []).map(function (e) { return toMatch(e, l.name); }).filter(Boolean); })
        .catch(function () { return null; });
    })).then(function (lists) {
      var failed = lists.every(function (x) { return x === null; });
      var all = [].concat.apply([], lists.filter(Boolean));
      var rank = { "in": 0, pre: 1, post: 2 };
      all.sort(function (a, b) {
        if (rank[a.state] !== rank[b.state]) return rank[a.state] - rank[b.state];
        return a.state === "post" ? b.date - a.date : a.date - b.date;
      });
      var top = all.slice(0, 4);
      if (failed) $("scores").innerHTML = '<div class="empty">Scores unavailable right now.</div>';
      else if (!top.length) $("scores").innerHTML = '<div class="empty">No matches this week for your leagues.</div>';
      else $("scores").innerHTML = top.map(function (s) {
        return '<button class="score f" data-nav data-watch="1"><span class="comp">' + esc(s.comp) + '</span><span class="st' + (s.state === "in" ? " live" : "") + '">' + esc(s.status) +
          '</span><span class="teams">' + esc(s.teams) + '</span><span class="res">' + esc(s.res) + "</span></button>";
      }).join("");
      var anyLive = top.some(function (s) { return s.state === "in"; });
      scoreTimer = setTimeout(loadScores, anyLive ? 60000 : 15 * 60000);
    });
  }
  function toMatch(e, comp) {
    try {
      var c = e.competitions[0], st = e.status.type;
      var home = c.competitors.filter(function (x) { return x.homeAway === "home"; })[0] || c.competitors[0];
      var away = c.competitors.filter(function (x) { return x.homeAway === "away"; })[0] || c.competitors[1];
      var nm = function (t) { return t.team.shortDisplayName || t.team.abbreviation || t.team.displayName; };
      var date = new Date(e.date);
      var status, res;
      if (st.state === "in") { status = "LIVE " + (e.status.displayClock && e.status.displayClock !== "0'" ? e.status.displayClock : (st.shortDetail || "")); res = home.score + "–" + away.score; }
      else if (st.state === "post") { status = st.shortDetail || "FT"; res = home.score + "–" + away.score; }
      else { status = DAYS[date.getDay()].slice(0, 3).toUpperCase() + " " + timeStr(date).toUpperCase(); res = "—"; }
      return { comp: comp, state: st.state, status: status.trim(), teams: nm(home) + " v " + nm(away), res: res, date: +date };
    } catch (err) { return null; }
  }

  // ---------------------------------------------------------------- system line
  function loadSystem() {
    var s = {}; try { s = JSON.parse(B.getSystem()); } catch (e) {}
    var net;
    if (!s.online) net = 'Network <b class="off">offline</b>';
    else if (s.type === "wifi") net = "Wi-Fi <b>" + (s.mbps > 0 ? s.mbps + " Mbps" : "connected") + "</b>";
    else if (s.type === "ethernet") net = "Ethernet <b>connected</b>";
    else net = "Network <b>connected</b>";
    $("sys-net").innerHTML = net;
    $("sys-disk").innerHTML = s.freeGB != null ? "<b>" + s.freeGB.toFixed(1) + " GB</b> free of " + Math.round(s.totalGB) + " GB" : "";
  }

  // ---------------------------------------------------------------- render
  function renderApps() {
    var html = "";
    var iptv = settings.iptv && byPkg[settings.iptv];
    html += tileHTML({ cls: iptv ? "" : "ghost", attr: 'data-iptv="1"', inner: iptv ? iconHTML(iptv.pkg, iptv.label) : "TV", name: "My TV" });
    settings.pinned.slice(0, 8).forEach(function (p, i) {
      var a = byPkg[p];
      html += tileHTML({ attr: 'data-pkg="' + esc(p) + '" data-pin="' + i + '"', inner: iconHTML(p, a.label), name: a.label });
    });
    html += tileHTML({ cls: "ghost", attr: 'data-all="1"', inner: DOTS, name: "All apps" });
    html += tileHTML({ cls: "ghost", attr: 'data-settings="1"', inner: GEAR, name: "Settings" });
    $("apps").innerHTML = html;

  }
  function tileHTML(o) {
    return '<button class="app ' + (o.cls || "") + '" data-nav ' + o.attr + '><span class="tile">' + o.inner + '</span><span class="name">' + esc(o.name) + "</span></button>";
  }
  function ago(t) {
    var m = Math.round((Date.now() - t) / 60000);
    if (m < 1) return "just now"; if (m < 60) return m + " min ago";
    var h = Math.round(m / 60); if (h < 24) return h + " h ago";
    var d = Math.round(h / 24); return d === 1 ? "yesterday" : d + " days ago";
  }
  // ---------------------------------------------------------------- continue watching
  var watch = [];
  function whenLabel(t) {
    var d = new Date(t), now = new Date();
    var today = new Date(now.getFullYear(), now.getMonth(), now.getDate()).getTime();
    var H = d.getHours();
    if (t >= today) return H < 4 ? "Last night" : (H >= 18 ? "Tonight " : "Today ") + timeStr(d);
    if (t >= today - 864e5) return H >= 17 ? "Last night" : "Yesterday";
    if (t >= today - 6 * 864e5) return DAYS[d.getDay()];
    return d.getDate() + " " + MON[d.getMonth()].slice(0, 3);
  }
  function leftLabel(w) {
    if (!(w.dur > 0) || !(w.pos > 0)) return "";
    var left = Math.round((w.dur - w.pos) / 60000);
    return left <= 1 ? "Nearly finished" : left + " min left";
  }
  function loadWatch() {
    try { watch = JSON.parse(B.getWatch()) || []; } catch (e) { watch = []; }
    watch = watch.filter(function (w) { return byPkg[w.pkg]; });
  }
  function artHTML(w, cls) {
    var src = ""; try { src = w.art ? B.getArt(w.art) : ""; } catch (e) {}
    return src ? '<img alt="" src="' + src + '">' : (cls === "hero" ? '<span class="feed"></span>' : iconHTML(w.pkg, byPkg[w.pkg].label));
  }
  function renderRecents() {
    loadWatch();
    var hero = $("hero"), w = watch[0];
    hero.classList.toggle("empty", !w);
    if (w) {
      var app = byPkg[w.pkg];
      hero.dataset.watchId = w.id;
      delete hero.dataset.empty;
      $("hero-bg").innerHTML = artHTML(w, "hero");
      $("hero-badge").innerHTML = (icon(w.pkg) ? '<img alt="" src="' + icon(w.pkg) + '">' : '<span class="mini"></span>') + esc(app.label);
      $("hero-title").textContent = w.title;
      $("hero-sub").textContent = w.sub || "";
      var pct = w.dur > 0 && w.pos > 0 ? Math.min(100, w.pos / w.dur * 100) : 0;
      $("hero-prog-wrap").hidden = !pct;
      $("hero-prog").style.width = pct.toFixed(1) + "%";
      $("hero-when").textContent = [whenLabel(w.t), leftLabel(w)].filter(Boolean).join(" · ");
      $("watch-em").textContent = "OK to resume";
    } else {
      delete hero.dataset.watchId;
      var access = true; try { access = B.watchAccess(); } catch (e) {}
      hero.dataset.empty = access ? "start" : "access";
      $("hero-bg").innerHTML = '<span class="feed"></span>';
      $("hero-badge").innerHTML = "";
      $("hero-title").textContent = access ? "Nothing watched yet" : "Turn on Continue watching";
      $("hero-sub").textContent = access ? "Start a show on Netflix, Stan, YouTube or My TV and it'll be here."
        : "Lets Command Centre see what's playing, so it can take you back to it.";
      $("hero-prog-wrap").hidden = true;
      $("hero-when").textContent = "";
      $("watch-em").textContent = "";
    }

    var more = watch.slice(1, 4);
    if (more.length) {
      $("earlier-h").textContent = "Earlier";
      $("recents").innerHTML = more.map(function (x) {
        return '<button class="rec f" data-nav data-watch-id="' + esc(x.id) + '"><span class="ic' + (x.art ? " art" : "") + '">' + artHTML(x, "row") +
          '</span><span class="txt"><b>' + esc(x.title) + "</b><span>" + esc(byPkg[x.pkg].label + " · " + whenLabel(x.t)) + "</span></span></button>";
      }).join("");
      return;
    }
    // Nothing else watched yet: fall back to recently opened apps.
    $("earlier-h").textContent = "Recently opened";
    var list = recents.filter(function (r) { return byPkg[r.pkg]; }).slice(0, 3);
    $("recents").innerHTML = list.length ? list.map(function (r) {
      var a = byPkg[r.pkg];
      return '<button class="rec f" data-nav data-pkg="' + esc(r.pkg) + '"><span class="ic">' + iconHTML(r.pkg, a.label) + "</span><b>" + esc(a.label) + '</b><span class="ago">' + ago(r.t) + "</span></button>";
    }).join("") : '<div class="empty">Apps you open from here will show up in this list.</div>';
  }
  function renderAll() {
    $("all-grid").innerHTML = apps.map(function (a) {
      return tileHTML({ cls: settings.pinned.indexOf(a.pkg) >= 0 ? "pinned" : "", attr: 'data-pkg="' + esc(a.pkg) + '"', inner: iconHTML(a.pkg, a.label), name: a.label });
    }).join("") || '<div class="empty">No apps found.</div>';
  }
  function renderPicker() {
    $("picker-grid").innerHTML = apps.map(function (a) {
      return tileHTML({ cls: a.pkg === settings.iptv ? "pinned" : "", attr: 'data-choose="' + esc(a.pkg) + '"', inner: iconHTML(a.pkg, a.label), name: a.label });
    }).join("");
  }
  function renderSettings() {
    document.querySelectorAll('[data-opt="hour24"] button').forEach(function (b) { b.setAttribute("aria-pressed", String(b.dataset.val === String(settings.hour24))); });
    $("city").value = settings.city.name;
    $("name").value = settings.name || "";
    var ip = settings.iptv && byPkg[settings.iptv];
    $("pick-iptv").textContent = ip ? ip.label + " · change" : "Choose app";
    $("leagues").innerHTML = LEAGUES.map(function (l) {
      return '<button data-nav data-league="' + l.id + '" aria-pressed="' + (settings.leagues.indexOf(l.id) >= 0) + '">' + esc(l.name) + "</button>";
    }).join("");
    var on = false; try { on = B.homeServiceOn(); } catch (e) {}
    $("home-state").textContent = on ? "On" : "Off";
    $("home-state").className = "state" + (on ? " on" : "");
    $("home-setup").textContent = on ? "Accessibility settings" : "Set up";
    $("home-hint").hidden = on;
    try { $("version").textContent = "Version " + B.version(); } catch (e) {}
  }

  // ---------------------------------------------------------------- layers
  var LAYERS = ["actions", "picker", "settings", "all"];
  var returnFocus = {};
  function topLayer() { for (var i = 0; i < LAYERS.length; i++) { var el = $(LAYERS[i]); if (!el.hidden) return el; } return null; }
  function openLayer(id) {
    returnFocus[id] = document.activeElement;
    if (id === "all") renderAll();
    if (id === "picker") renderPicker();
    if (id === "settings") renderSettings();
    $(id).hidden = false;
    var first = id === "picker" ? $(id).querySelector(".pinned") : null;
    focusEl(first || navList()[0]);
  }
  function closeLayer(id) {
    $(id).hidden = true;
    var back = returnFocus[id];
    focusEl(back && document.body.contains(back) && !back.closest("[hidden]") ? back : navList()[0]);
  }
  function closeAllLayers() { LAYERS.forEach(function (id) { $(id).hidden = true; }); }

  // ---------------------------------------------------------------- spatial navigation for the remote
  function navList() {
    var scope = topLayer() || $("home");
    return Array.prototype.filter.call(scope.querySelectorAll("[data-nav]"), function (e) { return e.getClientRects().length; });
  }
  function focusEl(el) {
    if (!el) return;
    try { el.focus({ preventScroll: true }); } catch (e) { el.focus(); }
    if (el.closest(".grid")) { try { el.scrollIntoView({ block: "nearest" }); } catch (e) {} }
  }
  function firstApp() { return $("apps").querySelector(".app"); }
  function move(dir) {
    var list = navList(); if (!list.length) return;
    var cur = list.indexOf(document.activeElement) >= 0 ? document.activeElement : null;
    if (!cur) return focusEl(topLayer() ? list[0] : firstApp());
    var r = cur.getBoundingClientRect(), ax = r.left + r.width / 2, ay = r.top + r.height / 2;
    var best = null, bs = Infinity;
    list.forEach(function (el) {
      if (el === cur) return;
      var b = el.getBoundingClientRect(), dx = b.left + b.width / 2 - ax, dy = b.top + b.height / 2 - ay, p, s;
      if (dir === "right") { if (dx <= 2) return; p = dx; s = Math.abs(dy); }
      if (dir === "left") { if (dx >= -2) return; p = -dx; s = Math.abs(dy); }
      if (dir === "down") { if (dy <= 2) return; p = dy; s = Math.abs(dx); }
      if (dir === "up") { if (dy >= -2) return; p = -dy; s = Math.abs(dx); }
      var sc = p + s * 2.2; if (sc < bs) { bs = sc; best = el; }
    });
    if (best) focusEl(best);
  }

  // ---------------------------------------------------------------- actions
  var actionPin = -1;
  function activate(el) {
    if (!el) return;
    var d = el.dataset;
    if (d.watchId) {
      var w = watch.filter(function (x) { return x.id === d.watchId; })[0];
      if (w && B.resumeWatch(w.id)) toast("Back to " + w.title); else toast("Couldn't open that. Is the app still installed?");
      return;
    }
    if (el.id === "hero" && el.dataset.empty === "access") {
      var ok = false; try { ok = B.openWatchAccess(); } catch (e) {}
      if (!ok) toast("Run the install command on your PC again to switch this on");
      return;
    }
    if (d.iptv || d.watch || el.id === "hero") {
      if (settings.iptv && byPkg[settings.iptv]) open(settings.iptv); else openLayer("picker");
      return;
    }
    if (d.all) return openLayer("all");
    if (d.settings) return openLayer("settings");
    if (d.choose) {
      settings.iptv = d.choose; settings.pinned = settings.pinned.filter(function (p) { return p !== d.choose; }); save();
      closeLayer("picker"); renderApps(); renderSettings(); toast("My TV now opens " + byPkg[d.choose].label);
      return;
    }
    if (d.pkg) { closeAllLayers(); return open(d.pkg); }
    if (el.id === "wx") return toast(wxDays.length ? wxDays.join("   ·   ") : "Weather is still loading");
    if (d.act) return doAction(d.act);
    if (d.val && el.parentNode.dataset.opt === "hour24") { settings.hour24 = d.val === "true"; save(); tick(); renderSettings(); return; }
    if (d.league) {
      var i = settings.leagues.indexOf(d.league);
      if (i >= 0) settings.leagues.splice(i, 1); else settings.leagues.push(d.league);
      save(); el.setAttribute("aria-pressed", String(i < 0)); loadScores(); return;
    }
    if (el.id === "city" || el.id === "name") return; // typing happens in the on-screen keyboard
    if (el.id === "name-save") { settings.name = $("name").value.trim().slice(0, 24); save(); tick(); toast(settings.name ? "Hi " + settings.name : "Name cleared"); return; }
    if (el.id === "city-save") return saveCity();
    if (el.id === "pick-iptv") return openLayer("picker");
    if (el.id === "home-setup") return B.openAccessibility();
    if (el.id === "fire-settings") return B.openSettings();
    if (el.id === "amazon-home") return B.openAmazonHome(5);
    if (el.id === "reset-apps") { settings.pinned = defaultPins(); save(); renderApps(); toast("App row reset"); }
  }
  function saveCity() {
    var q = $("city").value.trim(); if (!q) return;
    toast("Looking up " + q + "…");
    getJSON("https://geocoding-api.open-meteo.com/v1/search?count=1&name=" + encodeURIComponent(q)).then(function (j) {
      var r = j.results && j.results[0];
      if (!r) return toast("Couldn't find " + q + ". Try a bigger nearby city.");
      settings.city = { name: r.name, lat: r.latitude, lon: r.longitude }; save();
      $("city").value = r.name; loadWeather(); toast("Weather set to " + r.name + (r.admin1 ? ", " + r.admin1 : ""));
    }).catch(function () { toast("Couldn't look that up. Check the internet connection."); });
  }
  function doAction(act) {
    var i = actionPin, p = settings.pinned;
    if (act === "left" && i > 0) { p.splice(i - 1, 0, p.splice(i, 1)[0]); i--; }
    if (act === "right" && i < p.length - 1) { p.splice(i + 1, 0, p.splice(i, 1)[0]); i++; }
    if (act === "unpin") { var a = byPkg[p[i]]; p.splice(i, 1); toast((a ? a.label : "App") + " removed. Add it back from All apps."); i = -1; }
    save(); renderApps(); closeLayer("actions");
    var tiles = $("apps").querySelectorAll("[data-pin]");
    focusEl(i >= 0 ? tiles[i] : firstApp());
  }
  // Menu (☰) button: options for whatever is focused.
  function menu() {
    var el = document.activeElement, d = el && el.dataset ? el.dataset : {};
    var layer = topLayer();
    if (layer && layer.id === "all" && d.pkg) {
      var i = settings.pinned.indexOf(d.pkg), name = byPkg[d.pkg].label;
      if (i >= 0) { settings.pinned.splice(i, 1); toast(name + " removed from the row"); }
      else if (settings.pinned.length >= 8) { toast("The row is full. Remove an app first."); return; }
      else { settings.pinned.push(d.pkg); toast(name + " added to the row"); }
      save(); renderApps(); el.classList.toggle("pinned", i < 0); return;
    }
    if (!layer && d.watchId) {
      var gone = watch.filter(function (x) { return x.id === d.watchId; })[0];
      try { B.removeWatch(d.watchId); } catch (e) {}
      renderRecents(); focusEl($("hero"));
      toast((gone ? gone.title : "Show") + " removed from Continue watching");
      return;
    }
    if (!layer && d.pin != null) {
      actionPin = +d.pin; $("actions-title").textContent = byPkg[settings.pinned[actionPin]].label;
      return openLayer("actions");
    }
    if (!layer) return openLayer("settings");
    if (layer.id === "settings") return closeLayer("settings");
  }
  function back() {
    var layer = topLayer();
    if (layer) return closeLayer(layer.id);
    focusEl(firstApp());
  }

  // ---------------------------------------------------------------- input
  document.addEventListener("keydown", function (e) {
    var k = e.key, code = e.keyCode;
    var inInput = document.activeElement && document.activeElement.tagName === "INPUT";
    if (inInput && (k === "ArrowLeft" || k === "ArrowRight")) return;
    if (inInput && k === "Enter") { e.preventDefault(); if (document.activeElement.id === "name") activate($("name-save")); else saveCity(); return; }
    var dir = { ArrowUp: "up", ArrowDown: "down", ArrowLeft: "left", ArrowRight: "right" }[k] ||
      { 19: "up", 20: "down", 21: "left", 22: "right" }[code];
    if (dir) { e.preventDefault(); move(dir); return; }
    if (k === "Enter" || code === 13 || code === 23) { e.preventDefault(); activate(document.activeElement); return; }
    if (k === "Escape" || (k === "Backspace" && !inInput)) { e.preventDefault(); back(); return; }
    if (!NATIVE && (k === "m" || k === "ContextMenu")) { e.preventDefault(); menu(); }
  });
  document.addEventListener("click", function (e) {
    var b = e.target.closest && e.target.closest("[data-nav]");
    if (b && b.tagName === "BUTTON") activate(b);
  });

  window.onBack = back;
  window.onMenu = menu;
  window.onHome = function () { closeAllLayers(); focusEl(firstApp()); };
  window.onResume = function () {
    loadSystem(); renderRecents();
    if (!$("settings").hidden) renderSettings();
    if (!document.activeElement || document.activeElement === document.body) focusEl(firstApp());
  };
  var watchTimer;
  window.onWatchChanged = function () { clearTimeout(watchTimer); watchTimer = setTimeout(renderRecents, 500); };
  window.onAppsChanged = function () { icons = {}; loadApps(); renderApps(); renderRecents(); if (!$("all").hidden) renderAll(); };

  // ---------------------------------------------------------------- start
  loadApps(); renderApps(); renderRecents();
  tick(); setInterval(tick, 1000);
  setInterval(renderRecents, 60000);
  loadWeather(); setInterval(loadWeather, 20 * 60000);
  loadScores();
  loadSystem(); setInterval(loadSystem, 60000);
  setTimeout(function () { focusEl(firstApp()); }, 50);

  // ---------------------------------------------------------------- browser preview only
  function makePreviewBridge() {
    var list = ["Netflix", "YouTube", "Stan", "ABC iview", "SBS On Demand", "7plus", "9Now", "Spotify", "TiviMate", "Prime Video", "Downloader", "Disney+", "Plex", "Silk Browser", "Kayo Sports", "10 Play"];
    return {
      getApps: function () { return JSON.stringify(list.map(function (l) { return { pkg: "preview." + l.toLowerCase().replace(/\W/g, ""), label: l }; })); },
      getIcon: function () { return ""; },
      launch: function (p) { toast("Preview: would open " + p.replace("preview.", "")); return true; },
      openStore: function () {}, openAccessibility: function () { toast("Preview: opens Accessibility settings"); },
      openSettings: function () { toast("Preview: opens Fire TV settings"); }, openAmazonHome: function () { toast("Preview: opens Amazon home"); },
      homeServiceOn: function () { return false; },
      getSystem: function () { return JSON.stringify({ online: true, type: "wifi", mbps: 86, freeGB: 2.9, totalGB: 8 }); },
      httpGet: function () {}, version: function () { return "preview"; },
      getWatch: function () {
        var y = new Date(); y.setDate(y.getDate() - 1); y.setHours(21, 40, 0, 0);
        return JSON.stringify([
          { id: "n|The Bear", pkg: "preview.netflix", title: "The Bear", sub: "S3:E4 · Violet", t: +y, dur: 1800000, pos: 1020000 },
          { id: "s|Bump", pkg: "preview.stan", title: "Bump", sub: "Season 5, Episode 2", t: +y - 864e5 * 2 },
          { id: "t|Sports 1", pkg: "preview.tivimate", title: "Sports 1", sub: "A-League Men: Sydney FC v Melbourne Victory", t: Date.now() - 3 * 36e5 },
          { id: "y|Video", pkg: "preview.youtube", title: "Every Goal from Round 4", sub: "A-Leagues", t: Date.now() - 864e5 * 4 }
        ]);
      },
      getArt: function () { return ""; }, watchAccess: function () { return true; },
      resumeWatch: function (id) { toast("Preview: would resume " + id.split("|")[1]); return true; },
      openWatchAccess: function () { return false; }, removeWatch: function () {}
    };
  }
  function previewGet(url) {
    if (url.indexOf("open-meteo.com/v1/forecast") >= 0) return Promise.resolve({ current: { temperature_2m: 24, weather_code: 0 }, daily: { time: ["2026-11-19", "2026-11-20", "2026-11-21", "2026-11-22"], temperature_2m_max: [25, 27, 29, 22], temperature_2m_min: [18, 19, 20, 17], weather_code: [0, 1, 2, 61] } });
    if (url.indexOf("geocoding") >= 0) return Promise.resolve({ results: [{ name: "Melbourne", admin1: "Victoria", latitude: -37.81, longitude: 144.96 }] });
    if (url.indexOf("aus.1/") >= 0) return Promise.resolve({ events: [
      { date: new Date().toISOString(), status: { displayClock: "63'", type: { state: "in", shortDetail: "63'" } }, competitions: [{ competitors: [{ homeAway: "home", score: "1", team: { shortDisplayName: "Sydney FC" } }, { homeAway: "away", score: "0", team: { shortDisplayName: "Melb Victory" } }] }] }] });
    if (url.indexOf("rugby-league") >= 0) return Promise.resolve({ events: [
      { date: new Date(Date.now() - 864e5).toISOString(), status: { type: { state: "post", shortDetail: "FT" } }, competitions: [{ competitors: [{ homeAway: "home", score: "22", team: { shortDisplayName: "Roosters" } }, { homeAway: "away", score: "18", team: { shortDisplayName: "Broncos" } }] }] }] });
    if (url.indexOf("afl") >= 0) return Promise.resolve({ events: [] });
    return Promise.reject(new Error("preview"));
  }
})();
