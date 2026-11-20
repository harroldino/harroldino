/* My TV browse screen (design 05: streaming rows).
   On the Fire Stick, window.Android is the native bridge (Bridge.java). In a desktop browser a stand-in bridge with
   made-up channels and posters is used so the screen can be previewed; it never contacts a provider.
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
  function call(method, url, timeout) {
    return new Promise(function (res, rej) {
      var id = "r" + (++seq);
      pending[id] = { res: res, rej: rej };
      B[method](url, id);
      setTimeout(function () { if (pending[id]) { delete pending[id]; rej(new Error("The server took too long to answer")); } }, timeout || 45000);
    });
  }
  function getJSON(url) {
    return call("httpGet", url).then(function (t) {
      try { return JSON.parse(t); } catch (e) { throw new Error("The server sent something that isn't IPTV data"); }
    });
  }

  function $(id) { return document.getElementById(id); }
  function esc(s) { return String(s == null ? "" : s).replace(/[&<>"']/g, function (c) { return { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]; }); }
  // Single quotes inside, so it can also sit in a style="..." attribute.
  function cssUrl(u) { return u ? "url('" + String(u).replace(/'/g, "%27").replace(/["\\\n<>]/g, "") + "')" : "none"; }
  var store = {
    get: function (k, d) { try { var v = localStorage.getItem("mytv." + k); return v == null ? d : JSON.parse(v); } catch (e) { return d; } },
    set: function (k, v) { try { localStorage.setItem("mytv." + k, JSON.stringify(v)); } catch (e) {} }
  };

  // ---------------------------------------------------------------- provider
  var login = null;        // {server, username, password, exp} or {m3u}
  var cats = {};           // kind -> [{id, name}]
  var catItems = {};       // "kind:id" -> [item]
  var m3uChannels = null;  // M3U logins: every channel, grouped into cats.live
  var myList = store.get("mylist", []);

  // The provider's categories are usually prefixed by country. Australian ones go first.
  var HOME_RE = /(^|[^a-z])(au|aus|australia)([^a-z]|$)/i;
  function homeFirst(list) {
    var a = [], b = [];
    list.forEach(function (c) { (HOME_RE.test(c.name) ? a : b).push(c); });
    return a.concat(b);
  }

  function base() { return login.server; }
  function cred() { return encodeURIComponent(login.username) + "/" + encodeURIComponent(login.password); }
  function api(action, extra) {
    var u = base() + "/player_api.php?username=" + encodeURIComponent(login.username) + "&password=" + encodeURIComponent(login.password);
    if (action) u += "&action=" + action;
    if (extra) u += "&" + extra;
    return getJSON(u);
  }

  function liveItem(s, catName) {
    return { kind: "live", id: s.stream_id, name: s.name || "Channel", img: s.stream_icon || "", sub: catName || "",
      url: base() + "/live/" + cred() + "/" + s.stream_id + ".ts" };
  }
  function movieItem(s) {
    return { kind: "movie", id: s.stream_id, name: s.name || "Movie", img: s.stream_icon || "", rating: s.rating_5based || s.rating || "",
      key: "mv:" + s.stream_id, url: base() + "/movie/" + cred() + "/" + s.stream_id + "." + (s.container_extension || "mp4") };
  }
  function seriesItem(s) {
    return { kind: "series", id: s.series_id, name: s.name || "Series", img: s.cover || "", plot: s.plot || "",
      rating: s.rating_5based || s.rating || "", year: (s.releaseDate || s.release_date || "").slice(0, 4) };
  }

  var ACTION = { live: "get_live_streams", movies: "get_vod_streams", series: "get_series" };
  var CAT_ACTION = { live: "get_live_categories", movies: "get_vod_categories", series: "get_series_categories" };
  function toItem(kind, s, catName) { return kind === "live" ? liveItem(s, catName) : kind === "movies" ? movieItem(s) : seriesItem(s); }

  function loadCats(kind) {
    if (cats[kind]) return Promise.resolve(cats[kind]);
    if (login.m3u) return Promise.resolve(cats[kind] = []);
    return api(CAT_ACTION[kind]).then(function (list) {
      cats[kind] = homeFirst((list || []).map(function (c) { return { id: c.category_id, name: c.category_name || "Other" }; }));
      return cats[kind];
    });
  }
  function loadCat(kind, cat) {
    var k = kind + ":" + cat.id;
    if (catItems[k]) return Promise.resolve(catItems[k]);
    return api(ACTION[kind], "category_id=" + encodeURIComponent(cat.id)).then(function (list) {
      return (catItems[k] = (list || []).map(function (s) { return toItem(kind, s, cat.name); }));
    });
  }

  function loadM3u() {
    return call("loadM3u", login.m3u, 90000).then(function (t) {
      var list = JSON.parse(t), groups = {}, order = [];
      list.forEach(function (c) {
        if (!groups[c.group]) { groups[c.group] = []; order.push(c.group); }
        groups[c.group].push({ kind: "live", id: c.url, name: c.name, img: c.logo || "", sub: c.group, url: c.url });
      });
      cats.live = homeFirst(order.map(function (g) { return { id: g, name: g }; }));
      order.forEach(function (g) { catItems["live:" + g] = groups[g]; });
      m3uChannels = list;
    });
  }

  // ---------------------------------------------------------------- progress (Continue watching)
  function progress() { try { return JSON.parse(B.getProgress() || "{}"); } catch (e) { return {}; } }
  function resumeItems() {
    var p = progress(), out = [];
    Object.keys(p).forEach(function (k) {
      var o = p[k];
      out.push({ kind: "resume", key: k, name: o.name, img: o.img || "", sub: o.sub || "", url: o.url, pos: o.pos, dur: o.dur, at: o.at });
    });
    return out.sort(function (a, b) { return b.at - a.at; });
  }
  function mins(ms) { var m = Math.round(ms / 60000); return m >= 60 ? Math.floor(m / 60) + "h " + (m % 60) + "m" : m + " min"; }

  // ---------------------------------------------------------------- rows
  var TABS = ["home", "live", "movies", "series", "search"];
  var tab = "home";
  var rows = [];
  var focus = { zone: "rows", tab: 0, row: 0, col: 0 };
  var PAGE = 24;

  function row(title, kind, loader, sub) { return { title: title, kind: kind, load: loader, sub: sub || "", items: null, limit: PAGE, el: null }; }

  function buildRows() {
    rows = [];
    if (tab === "home") {
      var r = resumeItems();
      if (r.length) rows.push(row("Continue watching", "resume", function () { return Promise.resolve(r); }));
      if (myList.length) rows.push(row("My list", "mixed", function () { return Promise.resolve(myList.slice()); }));
      return Promise.all([loadCats("live"), loadCats("movies"), loadCats("series")]).then(function () {
        cats.live.slice(0, 2).forEach(function (c) { rows.push(catRow("live", c, "Live now")); });
        cats.movies.slice(0, 2).forEach(function (c) { rows.push(catRow("movies", c, "Movies")); });
        cats.series.slice(0, 2).forEach(function (c) { rows.push(catRow("series", c, "Series")); });
        cats.live.slice(2, 5).forEach(function (c) { rows.push(catRow("live", c, "Live")); });
      });
    }
    return loadCats(tab).then(function (list) {
      list.forEach(function (c) { rows.push(catRow(tab, c)); });
    });
  }
  function catRow(kind, c, label) {
    return row(c.name, kind, function () { return loadCat(kind, c); }, label);
  }

  function render() {
    var track = $("rows-track");
    track.innerHTML = "";
    rows.forEach(function (r, i) {
      var el = document.createElement("div");
      el.className = "row";
      el.innerHTML = '<div class="row-title">' + esc(r.title) + (r.sub ? " <span>" + esc(r.sub) + "</span>" : "") + '</div><div class="row-track"></div>';
      r.el = el;
      track.appendChild(el);
      renderRow(i);
    });
    if (!rows.length) status(emptyText()); else status("");
    ensureLoaded();
    paintFocus();
  }
  function emptyText() {
    if (login && login.m3u && (tab === "movies" || tab === "series")) return "Your playlist link only has live channels. To see movies and series, sign in with your server, username and password (press Menu ☰, then Sign out).";
    return "Nothing here yet.";
  }

  function tileHTML(it, idx) {
    var poster = it.kind === "movie" || it.kind === "series";
    var logo = it.kind === "live";
    var cls = "tile" + (poster ? " poster" : "") + (logo ? " logo" : "") + (it.img ? "" : " noimg");
    var h = '<div class="' + cls + '" data-i="' + idx + '" style="background-image:' + cssUrl(it.img) + '">';
    if (!poster) h += '<div class="shade"></div>';
    h += '<div class="t">' + esc(it.name) + "</div>";
    if (it.kind === "resume" && it.dur) h += '<div class="prog"><i style="width:' + Math.round(100 * it.pos / it.dur) + '%"></i></div>';
    if (it.kind === "live") h += '<div class="badge">' + (idx + 1) + "</div>";
    return h + "</div>";
  }
  function renderRow(i) {
    var r = rows[i], t = r.el.querySelector(".row-track");
    if (!r.items) { t.innerHTML = '<div class="row-empty">' + (r.error ? esc(r.error) : "Loading…") + "</div>"; return; }
    if (!r.items.length) { t.innerHTML = '<div class="row-empty">Nothing in this category.</div>'; return; }
    var html = "";
    for (var k = 0; k < Math.min(r.items.length, r.limit); k++) html += tileHTML(r.items[k], k);
    t.innerHTML = html;
  }

  // Load the rows around the one in focus; a provider can have hundreds of categories.
  function ensureLoaded() {
    for (var i = Math.max(0, focus.row - 1); i < Math.min(rows.length, focus.row + 3); i++) loadRow(i);
  }
  function loadRow(i) {
    var r = rows[i];
    if (r.items || r.busy) return;
    r.busy = true;
    r.load().then(function (items) {
      r.items = items; r.busy = false;
      if (rows[i] === r) { renderRow(i); paintFocus(); }
    }, function (e) {
      r.busy = false; r.error = "Couldn't load this row. " + e.message;
      if (rows[i] === r) renderRow(i);
    });
  }

  function switchTab(name) {
    if (name === "search") return openSearch();
    tab = name;
    focus.row = 0; focus.col = 0;
    $("rows-track").innerHTML = "";
    status("Loading…");
    var me = tab;
    buildRows().then(function () { if (tab === me) render(); }, function (e) { status("Couldn't load your channels. " + e.message + "\n\nPress Menu ☰ to sign in again or reload."); });
    paintTabs();
  }

  function status(t) { var s = $("status"); s.textContent = t; s.hidden = !t; }

  // ---------------------------------------------------------------- focus and hero
  function current() {
    var r = rows[focus.row];
    return r && r.items ? r.items[focus.col] : null;
  }
  function paintTabs() {
    var bs = document.querySelectorAll("#tabs button");
    for (var i = 0; i < bs.length; i++) {
      bs[i].classList.toggle("on", bs[i].getAttribute("data-tab") === tab);
      bs[i].classList.toggle("focus", focus.zone === "tabs" && i === focus.tab);
    }
  }
  function paintFocus() {
    paintTabs();
    var old = document.querySelectorAll(".tile.focus");
    for (var i = 0; i < old.length; i++) old[i].classList.remove("focus");
    var r = rows[focus.row];
    if (!r || !r.el) return setHero(null);
    var rowsTrack = $("rows-track");
    rowsTrack.style.transform = "translateY(" + (-r.el.offsetTop) + "px)";
    var t = r.el.querySelector(".row-track");
    var tile = t.querySelector('[data-i="' + focus.col + '"]');
    if (tile) {
      if (focus.zone === "rows") tile.classList.add("focus");
      var first = t.firstElementChild;
      t.style.transform = "translateX(" + (-(tile.offsetLeft - first.offsetLeft)) + "px)";
    }
    setHero(current(), r);
  }

  function setHero(it, r) {
    var art = $("hero-art");
    if (!it) {
      art.className = "hero-art"; art.style.backgroundImage = "none";
      $("hero-eyebrow").innerHTML = ""; $("hero-title").textContent = r ? r.title : "My TV";
      $("hero-sub").textContent = ""; $("hero-hint").textContent = ""; return;
    }
    art.className = "hero-art" + (it.kind === "live" ? " logo" : it.kind === "movie" || it.kind === "series" ? " poster" : "");
    art.style.backgroundImage = cssUrl(it.img);
    var eb = "", sub = "", hint = "";
    if (it.kind === "live") { eb = '<span class="live">LIVE</span> Channel ' + (focus.col + 1) + " · " + esc(it.sub); hint = "OK to watch · Up and Down change channel while watching"; }
    else if (it.kind === "movie") { eb = "Movie" + (it.rating ? " · ★ " + esc(Number(it.rating).toFixed(1)) : ""); sub = it.plot || ""; hint = "OK for details"; }
    else if (it.kind === "series") { eb = "Series" + (it.year ? " · " + esc(it.year) : "") + (it.rating ? " · ★ " + esc(Number(it.rating).toFixed(1)) : ""); sub = it.plot || ""; hint = "OK for episodes"; }
    else if (it.kind === "resume") { eb = "Continue watching"; sub = it.sub; hint = mins(it.dur - it.pos) + " left · OK to carry on"; }
    $("hero-eyebrow").innerHTML = eb;
    $("hero-title").textContent = it.name;
    $("hero-sub").textContent = sub;
    $("hero-hint").innerHTML = esc(hint) + (it.kind === "resume" && it.dur ? '<div class="bar"><i style="width:' + Math.round(100 * it.pos / it.dur) + '%"></i></div>' : "");
  }

  function move(dx, dy) {
    if (focus.zone === "tabs") {
      if (dx) { focus.tab = Math.max(0, Math.min(TABS.length - 1, focus.tab + dx)); paintTabs(); }
      if (dy > 0 && rows.length) { focus.zone = "rows"; paintFocus(); }
      return;
    }
    if (dy) {
      var nr = focus.row + dy;
      if (nr < 0) { focus.zone = "tabs"; focus.tab = TABS.indexOf(tab); paintFocus(); return; }
      if (nr >= rows.length) return;
      focus.row = nr;
      var r = rows[nr];
      focus.col = r.items ? Math.min(focus.col, Math.max(0, r.items.length - 1)) : 0;
      ensureLoaded();
    } else {
      var cur = rows[focus.row];
      if (!cur || !cur.items || !cur.items.length) return;
      focus.col = Math.max(0, Math.min(cur.items.length - 1, focus.col + dx));
      if (focus.col > cur.limit - 8 && cur.limit < cur.items.length) { cur.limit += PAGE; renderRow(focus.row); }
    }
    paintFocus();
  }

  function activate() {
    if (focus.zone === "tabs") { switchTab(TABS[focus.tab]); if (TABS[focus.tab] !== "search") { focus.zone = "rows"; } return; }
    var r = rows[focus.row], it = current();
    if (!it) return;
    open(it, r ? r.items : [it], focus.col);
  }

  function open(it, siblings, idx) {
    if (it.kind === "live") {
      var lives = siblings.filter(function (x) { return x.kind === "live"; });
      var at = Math.max(0, lives.indexOf(it));
      play({ live: true, index: at, items: lives.map(function (x) { return { name: x.name, url: x.url, sub: x.sub }; }) });
    } else if (it.kind === "resume") {
      play({ live: false, index: 0, items: [{ name: it.name, url: it.url, key: it.key, img: it.img, sub: it.sub }] });
    } else {
      openDetails(it);
    }
  }
  function play(spec) {
    if (!NATIVE) { toast("Playing " + spec.items[spec.index].name + " (preview only)"); return; }
    B.play(JSON.stringify(spec));
  }

  // ---------------------------------------------------------------- layers
  var layer = null;
  function openLayer(id) {
    if (layer) $(layer).hidden = true;
    layer = id; $(id).hidden = false;
    var f = $(id).querySelector("[data-nav]");
    if (f) setLayerFocus(f);
  }
  function closeLayer() {
    if (!layer) return;
    $(layer).hidden = true; layer = null;
    var a = document.activeElement; if (a && a.blur) a.blur();
    paintFocus();
  }
  var layerFocus = null;
  function setLayerFocus(el) {
    if (layerFocus) layerFocus.classList.remove("focus");
    layerFocus = el;
    if (!el) return;
    el.classList.add("focus");
    if (el.tagName === "INPUT") el.focus(); else { var a = document.activeElement; if (a && a.tagName === "INPUT") a.blur(); }
    var track = el.closest(".track");
    if (track) {
      var box = track.parentNode, y = el.offsetTop - box.clientHeight / 2 + el.offsetHeight / 2;
      track.style.transform = "translateY(" + (-Math.max(0, y)) + "px)";
    }
  }
  function layerMove(dx, dy) {
    var all = Array.prototype.slice.call($(layer).querySelectorAll("[data-nav]")).filter(function (e) { return e.offsetParent !== null; });
    var i = all.indexOf(layerFocus);
    if (i < 0) return setLayerFocus(all[0]);
    var group = layerFocus.parentNode.getAttribute("data-dir") === "h" ? layerFocus.parentNode : null;
    var j = i;
    if (dx) { if (!group) return; j = i + dx; if (!all[j] || all[j].parentNode !== group) return; }
    else if (dy > 0) { j = i + 1; while (group && all[j] && all[j].parentNode === group) j++; }
    else { j = i - 1; while (group && all[j] && all[j].parentNode === group) j--;
      if (all[j] && all[j].parentNode.getAttribute("data-dir") === "h") { var g = all[j].parentNode; while (all[j - 1] && all[j - 1].parentNode === g) j--; } }
    if (all[j]) setLayerFocus(all[j]);
  }

  // ---------- details
  var detailsItem = null;
  function openDetails(it) {
    detailsItem = it;
    $("d-art").style.backgroundImage = cssUrl(it.img);
    $("d-eyebrow").textContent = it.kind === "movie" ? "Movie" : "Series";
    $("d-title").textContent = it.name;
    $("d-meta").textContent = [it.year, it.rating ? "★ " + Number(it.rating).toFixed(1) : ""].filter(Boolean).join(" · ");
    $("d-plot").textContent = it.plot || "";
    $("d-episodes").innerHTML = "";
    drawActions(it, null);
    openLayer("details");
    if (!NATIVE && !login.server) return;
    if (it.kind === "movie") {
      api("get_vod_info", "vod_id=" + it.id).then(function (d) {
        if (detailsItem !== it) return;
        var info = (d && d.info) || {};
        if (info.backdrop_path && info.backdrop_path[0]) $("d-art").style.backgroundImage = cssUrl(info.backdrop_path[0]);
        it.plot = info.plot || info.description || it.plot;
        $("d-plot").textContent = it.plot || "";
        $("d-meta").textContent = [(info.releasedate || "").slice(0, 4), info.duration, info.genre, it.rating ? "★ " + Number(it.rating).toFixed(1) : ""].filter(Boolean).join(" · ");
      }, function () {});
    } else {
      $("d-episodes").innerHTML = '<div class="season">Loading episodes…</div>';
      api("get_series_info", "series_id=" + it.id).then(function (d) {
        if (detailsItem !== it) return;
        var info = (d && d.info) || {};
        if (info.backdrop_path && info.backdrop_path[0]) $("d-art").style.backgroundImage = cssUrl(info.backdrop_path[0]);
        if (info.plot) $("d-plot").textContent = info.plot;
        var eps = [], seasons = d && d.episodes ? Object.keys(d.episodes).sort(function (a, b) { return a - b; }) : [];
        seasons.forEach(function (s) {
          (d.episodes[s] || []).forEach(function (e) {
            eps.push({ season: s, num: e.episode_num, title: e.title || ("Episode " + e.episode_num), key: "ep:" + e.id,
              url: base() + "/series/" + cred() + "/" + e.id + "." + (e.container_extension || "mp4") });
          });
        });
        it.episodes = eps;
        drawEpisodes(it);
        drawActions(it, eps);
        setLayerFocus($("d-actions").querySelector("[data-nav]"));
      }, function (e) { $("d-episodes").innerHTML = '<div class="season">Couldn\'t load episodes. ' + esc(e.message) + "</div>"; });
    }
  }
  function inList(it) { return myList.some(function (x) { return x.kind === it.kind && x.id === it.id; }); }
  function drawActions(it, eps) {
    var p = progress(), h = "";
    if (it.kind === "movie") {
      var pr = p[it.key];
      h += '<button class="btn primary" data-nav data-act="play">' + (pr ? "Resume · " + mins(pr.dur - pr.pos) + " left" : "▶ Play") + "</button>";
      if (pr) h += '<button class="btn" data-nav data-act="restart">Start over</button>';
    } else if (eps && eps.length) {
      var at = nextEpisode(eps, p);
      h += '<button class="btn primary" data-nav data-act="ep" data-ep="' + at + '">' + (p[eps[at].key] ? "Resume" : "▶ Play") + " S" + eps[at].season + " E" + eps[at].num + "</button>";
    }
    h += '<button class="btn" data-nav data-act="list">' + (inList(it) ? "✓ In My list" : "+ My list") + "</button>";
    $("d-actions").setAttribute("data-dir", "h");
    $("d-actions").innerHTML = h;
  }
  // The episode you're partway through, or the one after the last you finished. Starts at the first.
  function nextEpisode(eps, p) {
    var best = -1, bestAt = 0;
    eps.forEach(function (e, i) { if (p[e.key] && p[e.key].at > bestAt) { bestAt = p[e.key].at; best = i; } });
    return best < 0 ? 0 : best;
  }
  function drawEpisodes(it) {
    var p = progress(), h = '<div class="track">', last = null;
    it.episodes.forEach(function (e, i) {
      if (e.season !== last) { h += '<div class="season">Season ' + esc(e.season) + "</div>"; last = e.season; }
      var pr = p[e.key];
      h += '<button class="ep" data-nav data-act="ep" data-ep="' + i + '"><b>' + esc(e.num) + "</b>" + esc(e.title) +
        (pr ? '<span class="ep-prog">' + mins(pr.dur - pr.pos) + " left</span>" : "") + "</button>";
    });
    $("d-episodes").innerHTML = h + "</div>";
  }
  function detailsAction(el) {
    var it = detailsItem, act = el.getAttribute("data-act");
    if (act === "list") {
      if (inList(it)) myList = myList.filter(function (x) { return !(x.kind === it.kind && x.id === it.id); });
      else myList.unshift({ kind: it.kind, id: it.id, name: it.name, img: it.img, url: it.url, key: it.key, plot: it.plot, rating: it.rating, year: it.year });
      store.set("mylist", myList);
      el.textContent = inList(it) ? "✓ In My list" : "+ My list";
      toast(inList(it) ? "Added to My list" : "Removed from My list");
    } else if (act === "play" || act === "restart") {
      if (act === "restart" && NATIVE) B.clearProgress(it.key);
      play({ live: false, index: 0, items: [{ name: it.name, url: it.url, key: it.key, img: it.img, sub: "Movie" }] });
    } else if (act === "ep") {
      var i = +el.getAttribute("data-ep");
      // Queue the rest of the series so the next episode starts by itself.
      var q = it.episodes.slice(i, i + 30).map(function (e) {
        return { name: it.name + " · S" + e.season + " E" + e.num, url: e.url, key: e.key, img: it.img, sub: e.title };
      });
      play({ live: false, index: 0, items: q });
    }
  }

  // ---------- search
  var searchAll = null, searchTimer = null;
  function openSearch() {
    openLayer("search");
    $("q").value = "";
    $("search-results").innerHTML = "";
    if (searchAll) { $("search-note").textContent = "Press OK to type. Results appear as you go."; return; }
    $("search-note").textContent = "Getting everything ready to search…";
    var jobs = login.m3u ? [Promise.resolve(Object.keys(catItems).reduce(function (a, k) { return a.concat(catItems[k]); }, []))]
      : [api(ACTION.live), api(ACTION.movies), api(ACTION.series)].map(function (p, n) {
          var kind = ["live", "movies", "series"][n];
          return p.then(function (list) { return (list || []).map(function (s) { return toItem(kind, s, ""); }); }, function () { return []; });
        });
    Promise.all(jobs).then(function (parts) {
      searchAll = parts.reduce(function (a, b) { return a.concat(b); }, []);
      $("search-note").textContent = "Press OK to type. " + searchAll.length.toLocaleString() + " channels, movies and series to search.";
      runSearch();
    });
  }
  function runSearch() {
    var q = $("q").value.trim().toLowerCase(), box = $("search-results");
    if (!q || !searchAll) { box.innerHTML = ""; return; }
    var starts = [], has = [];
    for (var i = 0; i < searchAll.length && starts.length < 40; i++) {
      var n = searchAll[i].name.toLowerCase();
      if (n.indexOf(q) === 0) starts.push(searchAll[i]); else if (has.length < 40 && n.indexOf(q) > 0) has.push(searchAll[i]);
    }
    var found = starts.concat(has).slice(0, 30);
    lastResults = found;
    box.innerHTML = found.length ? found.map(function (it, k) {
      var kind = it.kind === "live" ? "Live" : it.kind === "movie" ? "Movie" : "Series";
      return '<button class="result" data-nav data-r="' + k + '"><span class="kind">' + kind + '</span><span class="thumb' + (it.kind === "live" ? " logo" : "") +
        '" style="background-image:' + cssUrl(it.img) + '"></span>' + esc(it.name) + "</button>";
    }).join("") : '<div class="search-note">Nothing matches "' + esc(q) + '".</div>';
  }
  var lastResults = [];
  $("q").addEventListener("input", function () { clearTimeout(searchTimer); searchTimer = setTimeout(runSearch, 250); });

  // ---------- sign in
  function showLogin(message) {
    var l = login || {};
    $("f-server").value = l.server || l.m3u || "";
    $("f-user").value = l.username || "";
    $("f-pass").value = l.password || "";
    $("login-error").hidden = !message;
    $("login-error").textContent = message || "";
    openLayer("login");
  }
  function parseServer(raw) {
    var s = raw.trim();
    if (!s) return null;
    if (!/^https?:\/\//i.test(s)) s = "http://" + s;
    var m = s.match(/^(https?:\/\/[^\/?#]+)(.*)$/i);
    if (!m) return null;
    var out = { server: m[1].replace(/\/$/, "") };
    var u = s.match(/[?&]username=([^&]+)/i), p = s.match(/[?&]password=([^&]+)/i);
    if (u && p) { out.username = decodeURIComponent(u[1]); out.password = decodeURIComponent(p[1]); }
    else if (/\.m3u8?($|\?)|get\.php/i.test(m[2])) out.m3u = s;
    return out;
  }
  function fmtDate(unix) {
    var d = new Date(+unix * 1000);
    return d.getDate() + " " + ["Jan","Feb","Mar","Apr","May","Jun","Jul","Aug","Sep","Oct","Nov","Dec"][d.getMonth()] + " " + d.getFullYear();
  }
  // Asks the provider about the account. Resolves with user_info, or rejects with a message for the person.
  function checkAccount(l) {
    login = l;
    return api("").then(function (d) {
      var u = d && d.user_info;
      if (!u) throw new Error("That server didn't answer like an IPTV provider. Check the server address.");
      if (String(u.auth) === "0") throw new Error("The username or password wasn't accepted. Check them against your provider's email.");
      if (u.status && u.status !== "Active") {
        throw new Error(u.status === "Expired" && u.exp_date
          ? "Your subscription ended on " + fmtDate(u.exp_date) + ". Renew it with your provider, then sign in again."
          : "Your provider says this account is " + u.status.toLowerCase() + ".");
      }
      return u;
    }, function (e) {
      throw new Error(/HTTP 40[13]/.test(e.message) ? "The username or password wasn't accepted, or the subscription has ended."
        : "Couldn't reach that server (" + e.message + "). Check the address, or try your provider's backup server.");
    });
  }
  function signIn() {
    var p = parseServer($("f-server").value);
    if (!p) return showLogin("Enter your provider's server address.");
    if (p.m3u && !p.username) {
      login = { m3u: p.m3u };
      $("login-error").hidden = false; $("login-error").textContent = "Loading your playlist…";
      return loadM3u().then(function () { B.setLogin(JSON.stringify(login)); closeLayer(); start(); },
        function (e) { showLogin("Couldn't load that playlist. " + e.message); });
    }
    p.username = p.username || $("f-user").value.trim();
    p.password = p.password || $("f-pass").value.trim();
    if (!p.username || !p.password) return showLogin("Enter your username and password.");
    $("login-error").hidden = false; $("login-error").textContent = "Signing in…";
    checkAccount(p).then(function (u) {
      login.exp = u.exp_date || null;
      B.setLogin(JSON.stringify(login));
      closeLayer();
      start();
    }, function (e) { showLogin(e.message); });
  }

  // ---------- menu
  function openMenu() {
    if (!login) return;
    $("menu-account").textContent = login.m3u ? "Using a playlist link." :
      "Signed in as " + login.username + " on " + login.server.replace(/^https?:\/\//, "") + "." + (login.exp ? " Subscription ends " + fmtDate(login.exp) + "." : "");
    $("menu-version").textContent = "Version " + (B.version() || "preview");
    openLayer("menu");
  }
  function menuAction(id) {
    if (id === "m-close") return closeLayer();
    if (id === "m-reload") { cats = {}; catItems = {}; searchAll = null; closeLayer(); return start(); }
    if (id === "m-signout") { B.setLogin(""); var keep = login; cats = {}; catItems = {}; searchAll = null; rows = []; render(); login = keep; showLogin(""); login = null; }
  }

  // ---------------------------------------------------------------- start up
  function start() {
    if (login.m3u && !cats.live) {
      status("Loading your playlist…");
      return loadM3u().then(function () { switchTab("home"); }, function (e) { showLogin("Couldn't load your playlist. " + e.message); });
    }
    switchTab("home");
  }
  function boot() {
    try { login = JSON.parse(B.getLogin() || "null"); } catch (e) { login = null; }
    if (!login) return showLogin("");
    if (login.m3u) return start();
    // Check the account each time the app opens, so an expired subscription says so instead of showing empty rows.
    status("Signing in…");
    checkAccount(login).then(function (u) { login.exp = u.exp_date || login.exp; start(); }, function (e) { status(""); showLogin(e.message); });
  }

  // ---------------------------------------------------------------- remote
  window.toast = function (msg) {
    var t = $("toast"); t.textContent = msg; t.hidden = false;
    clearTimeout(window.toast.t); window.toast.t = setTimeout(function () { t.hidden = true; }, 2600);
  };
  window.onBack = function () {
    if (layer === "login") { if (login) closeLayer(); else B.exit(); return; }
    if (layer) return closeLayer();
    if (focus.zone === "rows" && (focus.row > 0 || focus.col > 0)) { focus.row = 0; focus.col = 0; return paintFocus(); }
    if (tab !== "home") return switchTab("home");
    B.exit();
  };
  window.onMenu = function () { if (layer === "menu") closeLayer(); else if (!layer || layer === "details") openMenu(); };
  window.onResume = function () {
    if (!login || layer === "login") return;
    if (layer === "details" && detailsItem) { drawActions(detailsItem, detailsItem.episodes); if (detailsItem.episodes) drawEpisodes(detailsItem); setLayerFocus($("d-actions").querySelector("[data-nav]")); }
    if (tab === "home" && rows.length) {
      var r = resumeItems(), has = rows[0].kind === "resume";
      if (has && r.length) { rows[0].items = r; renderRow(0); }
      else if (has !== !!r.length) { var keep = focus.row; switchTab("home"); focus.row = keep; }
      paintFocus();
    }
  };

  document.addEventListener("keydown", function (e) {
    var k = e.keyCode, inInput = document.activeElement && document.activeElement.tagName === "INPUT";
    var dir = { 37: [-1, 0], 38: [0, -1], 39: [1, 0], 40: [0, 1] }[k];
    if (!NATIVE && (k === 27 || (k === 8 && !inInput))) { e.preventDefault(); return window.onBack(); }
    if (!NATIVE && e.key === "m" && !inInput) return window.onMenu();
    if (dir) {
      if (inInput && dir[0]) return; // Left and Right move the cursor while typing
      e.preventDefault();
      if (layer) layerMove(dir[0], dir[1]); else move(dir[0], dir[1]);
      return;
    }
    if (k === 13 || k === 23) {
      if (inInput) { if (layer === "login") { e.preventDefault(); layerMove(0, 1); } return; }
      e.preventDefault();
      if (!layer) return activate();
      var el = layerFocus; if (!el) return;
      if (el.tagName === "INPUT") return el.focus();
      if (layer === "login") return signIn();
      if (layer === "menu") return menuAction(el.id);
      if (layer === "details") return detailsAction(el);
      if (layer === "search" && el.hasAttribute("data-r")) { var it = lastResults[+el.getAttribute("data-r")]; return open(it, [it], 0); }
    }
  });

  setInterval(clock, 20000); clock();
  function clock() {
    var d = new Date(), h = d.getHours(), m = d.getMinutes();
    $("clock").textContent = ((h % 12) || 12) + ":" + (m < 10 ? "0" : "") + m + (h < 12 ? " am" : " pm");
  }

  // ---------------------------------------------------------------- desktop preview
  // Made-up channels, movies and series drawn as SVG, so the layout can be checked in a browser.
  function makePreviewBridge() {
    var signedOut = /login/.test(location.search);
    function svg(w, h, a, b, title, sub) {
      var s = '<svg xmlns="http://www.w3.org/2000/svg" width="' + w + '" height="' + h + '"><defs><linearGradient id="g" x1="0" y1="0" x2="1" y2="1">' +
        '<stop offset="0" stop-color="' + a + '"/><stop offset="1" stop-color="' + b + '"/></linearGradient></defs><rect width="100%" height="100%" fill="url(#g)"/>' +
        '<circle cx="' + w * 0.72 + '" cy="' + h * 0.34 + '" r="' + w * 0.18 + '" fill="#fff" fill-opacity=".16"/>' +
        (title ? '<text x="' + w * 0.08 + '" y="' + h * 0.86 + '" font-family="sans-serif" font-weight="700" font-size="' + w * 0.1 + '" fill="#fff">' + title + "</text>" : "") +
        (sub ? '<text x="50%" y="58%" text-anchor="middle" font-family="sans-serif" font-weight="800" font-size="' + h * 0.34 + '" fill="#fff">' + sub + "</text>" : "") + "</svg>";
      return "data:image/svg+xml;charset=utf-8," + encodeURIComponent(s);
    }
    var HUES = [["#2b1055", "#d1456b"], ["#0b3a7a", "#3aa0ff"], ["#0b6e4f", "#9be15d"], ["#5a2a0a", "#ffb347"], ["#3a0a3a", "#ff5ea8"], ["#102a43", "#48c9b0"], ["#3d0d0d", "#ff6b4a"], ["#1b1b3a", "#8c7bff"]];
    function pick(n) { return HUES[n % HUES.length]; }
    var live = { "1": ["AU | News", ["News 24", "World Report", "Business Now", "Weather Live", "Parliament", "Breaking AU"]],
      "2": ["AU | Sport", ["Sports 1", "Sports 2", "Footy Live", "Racing", "Cricket", "Motor TV", "Golf Live", "Tennis"]],
      "3": ["AU | Entertainment", ["Channel Ten", "Movies Plus", "Comedy Central", "Lifestyle", "Food", "Docs"]],
      "4": ["UK | Sport", ["Sport UK 1", "Sport UK 2", "Football Extra"]], "5": ["Kids", ["Kids Zone", "Cartoons", "Junior"]] };
    var movies = { "11": ["New releases", ["Red Horizon", "Neon Nights", "The Long Summer", "Night Train", "Golden Hour", "Paper Kites", "Saltwater", "The Quiet Year"]],
      "12": ["Action", ["Iron Coast", "Last Signal", "Overdrive", "Cold Front", "Blackout City"]], "13": ["Family", ["Paper Boats", "The Lantern", "Moon Dogs"]] };
    var series = { "21": ["Drama", ["Harbour Lights", "The Valley", "North Shore", "Glass House"]], "22": ["Comedy", ["Flatmates", "Office Hours", "Sunday Roast"]] };
    var n = 0, sid = 100;
    function streamsFor(map, kind, cat) {
      var out = [];
      Object.keys(map).forEach(function (cid) {
        if (cat && cid !== cat) return;
        map[cid][1].forEach(function (name) {
          var c = pick(n++), id = sid++;
          if (kind === "live") out.push({ stream_id: id, name: name, stream_icon: svg(320, 180, c[0], c[1], "", name.split(" ").map(function (w) { return w[0]; }).join("").slice(0, 3)) });
          else if (kind === "vod") out.push({ stream_id: id, name: name, rating_5based: (3 + (id % 20) / 10).toFixed(1), stream_icon: svg(300, 450, c[0], c[1], name), container_extension: "mp4" });
          else out.push({ series_id: id, name: name, cover: svg(300, 450, c[0], c[1], name), plot: "A made-up series for the preview. Episodes are listed by season.", releaseDate: "2025-03-01", rating_5based: 4.2 });
        });
      });
      return out;
    }
    function catsOf(map) { return Object.keys(map).map(function (k) { return { category_id: k, category_name: map[k][0] }; }); }
    function answer(url) {
      var a = (url.match(/action=([a-z_]*)/) || [])[1] || "", cat = (url.match(/category_id=([^&]+)/) || [])[1];
      if (!a) return { user_info: { auth: 1, status: "Active", exp_date: String(Date.now() / 1000 + 86400 * 200 | 0) } };
      if (a === "get_live_categories") return catsOf(live);
      if (a === "get_vod_categories") return catsOf(movies);
      if (a === "get_series_categories") return catsOf(series);
      if (a === "get_live_streams") return streamsFor(live, "live", cat);
      if (a === "get_vod_streams") return streamsFor(movies, "vod", cat);
      if (a === "get_series") return streamsFor(series, "series", cat);
      if (a === "get_vod_info") return { info: { plot: "A made-up film for the preview. On the Fire Stick this comes from your provider.", releasedate: "2025-06-01", duration: "1h 52m", genre: "Drama" } };
      if (a === "get_series_info") return { info: {}, episodes: { "1": [1, 2, 3, 4, 5, 6].map(function (e) { return { id: 900 + e, episode_num: e, title: "Episode " + e, container_extension: "mp4" }; }),
        "2": [1, 2, 3].map(function (e) { return { id: 950 + e, episode_num: e, title: "Episode " + e, container_extension: "mp4" }; }) } };
      return [];
    }
    var c0 = pick(5);
    return {
      getLogin: function () { return signedOut ? "" : JSON.stringify({ server: "http://preview.example", username: "preview", password: "preview" }); },
      setLogin: function () {},
      httpGet: function (url, id) { setTimeout(function () { window.__httpCb(id, true, JSON.stringify(answer(url))); }, 120); },
      loadM3u: function (url, id) { setTimeout(function () { window.__httpCb(id, false, "Not in preview"); }, 50); },
      getProgress: function () {
        return JSON.stringify({ "ep:902": { pos: 1100000, dur: 2700000, name: "Harbour Lights · S1 E2", sub: "Episode 2", url: "x", img: svg(300, 450, c0[0], c0[1], "Harbour Lights"), at: 2 },
          "mv:7": { pos: 3000000, dur: 6600000, name: "Golden Hour", sub: "Movie", url: "x", img: svg(300, 450, "#5a2a0a", "#ffb347", "Golden Hour"), at: 1 } });
      },
      clearProgress: function () {},
      play: function () {},
      version: function () { return "preview"; },
      exit: function () { window.toast("Back would close the app here"); }
    };
  }

  boot();
})();
