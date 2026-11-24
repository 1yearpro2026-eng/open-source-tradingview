// Madar Chart: Bar Replay control bar, 2026-11-23.
// Vela has a replay API (chart.replay.start/step/play/pause/stop) but no button for it. This adds a small floating
// bar over the chart: a "Replay" button opens the controls (start point, step, play/pause, speed, stop).
// It always acts on the active chart cell. Start point: a quick preset or an exact date/time.
(function () {
  const css = document.createElement("style");
  css.textContent = `
  .mc-rp{position:absolute;z-index:29;top:8px;left:50%;transform:translateX(-50%);display:flex;align-items:center;gap:4px;
    padding:3px;border-radius:8px;flex-wrap:wrap;justify-content:center;max-width:calc(100% - 90px);background:var(--panel,#16212b);border:1px solid var(--line,#2b3946);
    box-shadow:0 4px 14px rgba(0,0,0,.28);user-select:none;color:var(--text,#d6dee5);font:12px system-ui,sans-serif}
  .mc-rp button,.mc-rp select,.mc-rp input{height:26px;border:1px solid transparent;border-radius:6px;background:transparent;
    color:inherit;font:inherit;padding:0 8px;cursor:pointer;box-sizing:border-box}
  .mc-rp select,.mc-rp input{background:var(--raised,#1d2a36);border-color:var(--line,#2b3946)}
  .mc-rp button:hover{background:var(--raised,#1d2a36)}
  .mc-rp button:disabled{opacity:.4;cursor:default}
  .mc-rp button.on{background:var(--accent,#e3a33a);color:var(--accentInk,#1a1407)}
  .mc-rp .mc-rp-info{color:var(--faint,#93a2af);padding:0 6px;white-space:nowrap;min-width:90px}
  .mc-rp .mc-rp-sep{width:1px;height:18px;background:var(--line,#2b3946)}
  .mc-rp [hidden],.mc-rp[hidden]{display:none!important}
  .mc-bm{position:absolute;top:calc(100% + 6px);right:0;width:340px;max-width:90vw;padding:8px;border-radius:8px;
    background:var(--panel,#16212b);border:1px solid var(--line,#2b3946);box-shadow:0 6px 20px rgba(0,0,0,.35);text-align:left}
  .mc-bm-head{display:flex;gap:4px;margin-bottom:6px}
  .mc-bm-head input{flex:1;min-width:0}
  .mc-bm-list{max-height:260px;overflow:auto;display:flex;flex-direction:column;gap:2px}
  .mc-bm-row{display:flex;align-items:center;gap:4px;padding:4px 6px;border-radius:6px;cursor:pointer}
  .mc-bm-row:hover{background:var(--raised,#1d2a36)}
  .mc-bm-main{flex:1;min-width:0}
  .mc-bm-name{font-weight:600;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .mc-bm-meta{color:var(--faint,#93a2af);font-size:11px;white-space:nowrap;overflow:hidden;text-overflow:ellipsis}
  .mc-bm-row button{height:22px;padding:0 5px}
  .mc-bm-msg{color:var(--faint,#93a2af);font-size:11px;min-height:14px;margin-top:4px}
  .mc-bm-empty{color:var(--faint,#93a2af);padding:6px}
  .mc-touch{position:absolute;z-index:31;left:50%;bottom:64px;transform:translateX(-50%);display:flex;align-items:center;gap:6px;padding:6px;border-radius:10px;
    background:var(--panel,#16212b);border:1px solid var(--line,#2b3946);box-shadow:0 6px 20px rgba(0,0,0,.4);font:14px system-ui,sans-serif;color:var(--text,#d6dee5);touch-action:manipulation}
  .mc-touch button{height:46px;min-width:56px;border:1px solid var(--line,#2b3946);border-radius:8px;background:var(--raised,#1d2a36);color:inherit;font:inherit;font-size:20px;padding:0 14px}
  .mc-touch button.go{background:var(--accent,#e3a33a);color:var(--accentInk,#1a1407);font-size:15px;font-weight:700}
  .mc-touch .hint{max-width:220px;font-size:12px;color:var(--faint,#93a2af);padding:0 4px}
  @media (pointer: coarse) {
    .mc-rp{gap:6px;padding:5px}
    .mc-rp button,.mc-rp select,.mc-rp input{height:38px;font-size:13px;padding:0 11px}
    .mc-rp input[type=range]{height:38px;padding:0}
    .mc-rp .mc-rp-info{font-size:12px}
    .mc-bm{width:380px}
    .mc-bm-row{padding:9px 8px}
    .mc-bm-row button{height:36px;min-width:40px}
    .mc-bm-name{font-size:14px}.mc-bm-meta{font-size:12px}
  }`;
  document.head.appendChild(css);

  // Speed = bars per second, on a log slider from 0.05 bar/s (20 s per bar) to 100 bars/s (10 ms per bar), plus quick chips.
  const MIN_MS = 10, MAX_MS = 20000;
  const CHIPS = [0.05, 0.1, 0.2, 0.3, 0.4, 0.5, 1, 2, 5, 10, 25, 50, 100];
  const msToPos = (ms) => Math.round(100 * Math.log(MAX_MS / ms) / Math.log(MAX_MS / MIN_MS));
  const posToMs = (p) => Math.round(MAX_MS * Math.pow(MIN_MS / MAX_MS, p / 100));
  const speedLabel = (ms) => { const r = 1000 / ms; return (r >= 10 ? Math.round(r) : r >= 1 ? Math.round(r * 10) / 10 : Math.round(r * 100) / 100) + " bar/s"; };
  const savedSpeed = (() => { try { const v = Number(localStorage.getItem("madarReplaySpeed")); return v >= MIN_MS && v <= MAX_MS ? v : 1000; } catch (_) { return 1000; } })();

  // Moves inside each candle (replay_motion.js): 0 = off (the candle appears whole), else 8 ... 240 moves per candle.
  const savedMoves = (() => { try { const v = Number(localStorage.getItem("madarReplayMoves")); return v === 0 || (window.MadarReplayMotion && MadarReplayMotion.MOVES.includes(v)) ? v : 0; } catch (_) { return 0; } })();
  let ws = null, bar = null, timer = null, speed = savedSpeed, moves = savedMoves, motionKey = null, motionUsed = "", wasActive = false;
  // Speed stays "candles per second": with moves on, each move waits speed / moves.
  const playMs = () => (moves ? Math.max(1, Math.round(speed / moves)) : speed);
  const chart = () => { try { return (ws.active || ws).chart; } catch (_) { return null; } };
  const el = (tag, props, kids) => { const e = Object.assign(document.createElement(tag), props || {}); (kids || []).forEach((k) => e.append(k)); return e; };
  const pad = (n) => String(n).padStart(2, "0");
  const toLocalInput = (ms) => { const d = new Date(ms); return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`; };
  const fmt = (ms) => { const d = new Date(ms); return `${pad(d.getDate())}/${pad(d.getMonth() + 1)} ${pad(d.getHours())}:${pad(d.getMinutes())}`; };

  function build() {
    const ui = {};
    ui.cut = el("button", { textContent: "✂ Select bar", title: "Click a bar on the chart to start the replay from there (Esc cancels)" });
    ui.close = el("button", { textContent: "✕", title: "Close Replay" });
    ui.back = el("button", { textContent: "⏮ Back", title: "Go back one bar" });
    ui.step = el("button", { textContent: "⏭ Step", title: "Next bar (Shift+→)" });
    ui.move = el("button", { textContent: "▸ Move", title: "One move inside the candle", hidden: true });
    ui.motion = el("select", { title: "Moves inside each candle: the candle forms live instead of appearing whole" }, [el("option", { textContent: "Motion: off", value: "0" }),
      ...(window.MadarReplayMotion ? MadarReplayMotion.MOVES : []).map((n) => el("option", { textContent: n + " moves/candle", value: String(n) }))]);
    ui.motion.value = String(moves);
    ui.play = el("button", { textContent: "▶ Play", title: "Play / pause (Shift+Space)" });
    ui.slider = el("input", { type: "range", min: 0, max: 100, step: 1, value: msToPos(speed), title: "Replay speed (log scale)" });
    ui.slider.style.cssText = "width:110px;padding:0;accent-color:var(--accent,#e3a33a);cursor:pointer";
    ui.slabel = el("span", { className: "mc-rp-info", textContent: speedLabel(speed) });
    ui.slabel.style.cssText = "min-width:58px;text-align:right;padding:0 2px";
    ui.chips = CHIPS.map((r) => el("button", { textContent: r + "x", title: r + " bar/s" }));
    ui.chips.forEach((b, i) => { b.dataset.ms = String(Math.round(1000 / CHIPS[i])); b.style.cssText = "padding:0 5px;min-width:0;font-size:11px"; });
    ui.speed = el("span", {}, [ui.slider, ui.slabel, ...ui.chips]);
    ui.speed.style.cssText = "display:flex;align-items:center;gap:2px";
    ui.stop = el("button", { textContent: "⏹ Exit", title: "Stop and return to live" });
    ui.info = el("span", { className: "mc-rp-info" });
    ui.bm = el("button", { textContent: "🔖 Bookmarks", title: "Save and jump to named replay checkpoints (all symbols, with drawings)" });
    ui.panel = el("div", { className: "mc-bm", hidden: true });
    ui.bmName = el("input", { type: "text", placeholder: "Name this point (optional)", maxLength: 60 });
    ui.bmSave = el("button", { textContent: "🔖 Save here", title: "Save the current replay position, symbol, timeframe and drawings" });
    ui.bmList = el("div", { className: "mc-bm-list" });
    ui.bmMsg = el("div", { className: "mc-bm-msg" });
    ui.panel.append(el("div", { className: "mc-bm-head" }, [ui.bmName, ui.bmSave]), ui.bmList, ui.bmMsg);
    ui.setup = [ui.close];
    ui.run = [ui.back, ui.step, ui.move, ui.play, ui.motion, ui.speed, ui.stop, ui.info];
    bar = el("div", { className: "mc-rp", hidden: true }, [ui.cut, ...ui.setup, ...ui.run, ui.bm, ui.panel]);
    bar.ui = ui;
    return bar;
  }

  // Scissors mode: a transparent layer over the chart. A band marks the exact candle under the mouse (the label gives its
  // open time in UTC, like the chart axis, and in local time). Click or Enter cuts there, Left/Right arrows nudge by one bar,
  // Esc cancels. The replay then starts with that candle as the last one shown.
  let cutLayer = null, hostEl = null, starting = false;
  // Time in the chart's own timezone (chart settings; UTC by default), so the label always matches the time axis.
  const fmtUtc = (ms) => {
    let tz = "UTC"; try { const z = chart().rendererPort.scene.timezone; if (z) tz = z; } catch (_) {}
    try {
      const p = Object.fromEntries(new Intl.DateTimeFormat("en-GB", { timeZone: tz, day: "2-digit", month: "2-digit", hour: "2-digit", minute: "2-digit", hourCycle: "h23" }).formatToParts(new Date(ms)).map((x) => [x.type, x.value]));
      return `${p.day}/${p.month} ${p.hour}:${p.minute} ${tz === "UTC" || tz === "Etc/UTC" ? "UTC" : tz}`;
    } catch (_) { const d = new Date(ms); return `${pad(d.getUTCDate())}/${pad(d.getUTCMonth() + 1)} ${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())} UTC`; }
  };
  function endCut() { if (cutLayer) { cutLayer.remove(); cutLayer = null; } document.removeEventListener("keydown", cutKey, true); if (bar) bar.ui.cut.classList.remove("on"); }
  let cutKey = () => {};
  function beginCut() {
    const c = chart(); if (!c || !hostEl) return;
    if (cutLayer) { endCut(); return; }
    const co = c.rendererPort && c.rendererPort.coords, b = c.replay.bounds;
    if (!co || !b) return;
    cutLayer = el("div", {}, []);
    cutLayer.style.cssText = "position:absolute;inset:0;z-index:28;cursor:crosshair;";
    const band = el("div"), tag = el("div");
    band.style.cssText = "position:absolute;top:0;bottom:0;background:var(--accent,#e3a33a);opacity:.22;border-left:1px solid var(--accent,#e3a33a);border-right:1px solid var(--accent,#e3a33a);pointer-events:none;display:none";
    tag.style.cssText = "position:absolute;bottom:34px;transform:translateX(-50%);padding:2px 6px;border-radius:4px;background:var(--accent,#e3a33a);color:var(--accentInk,#1a1407);font:12px system-ui,sans-serif;pointer-events:none;display:none;white-space:nowrap";
    cutLayer.append(band, tag);
    hostEl.appendChild(cutLayer);
    bar.ui.cut.classList.add("on");
    // While a replay runs only bars up to the replay cursor are on screen, so a re-cut can go back (or stay) but not ahead.
    const limits = () => { const rs = c.replay.state; return [b.first, rs && rs.active && rs.cursorTime ? rs.cursorTime : b.last]; };
    let sel = null, rect = null;       // sel = logical index of the marked candle, rect = the plot canvas under the mouse
    const valid = (lg) => { const t = co.logicalToTime(lg), [lo, hi] = limits(); return isFinite(t) && t >= lo && t <= hi; };
    const show = () => {
      if (sel === null || !rect || !valid(sel)) { band.style.display = tag.style.display = "none"; return; }
      const t = co.logicalToTime(sel), hr = hostEl.getBoundingClientRect();
      const x = co.logicalToX(sel) + rect.left - hr.left, w = Math.max(co.pxPerBar(), 3);
      band.style.display = tag.style.display = "block";
      band.style.left = (x - w / 2) + "px"; band.style.width = w + "px";
      tag.style.left = x + "px"; tag.textContent = `✂ ${fmtUtc(t)}  (${fmt(t)} local)`;
    };
    const pick = (ev) => {
      const cv = [...hostEl.querySelectorAll("canvas")].find((k) => { const r = k.getBoundingClientRect(); return ev.clientX >= r.left && ev.clientX <= r.right && ev.clientY >= r.top && ev.clientY <= r.bottom; });
      if (!cv) { sel = null; return; }
      rect = cv.getBoundingClientRect();
      const x = ev.clientX - rect.left;
      sel = x < 0 || x > co.widthPx ? null : Math.round(co.xToLogical(x));
    };
    const commit = () => {
      if (sel === null || !valid(sel)) return;
      const t = co.logicalToTime(sel);
      endCut();
      const was = c.replay.state, resume = was.active && was.playing;
      starting = true;
      // Re-cut: leave the running replay first, then start again from the new bar (keeps playing if it was playing).
      Promise.resolve(was.active ? (c.replay.pause(), c.replay.stop()) : null)
        .then(() => c.replay.start({ from: t }))
        .then(() => { open = true; starting = false; if (resume) c.replay.play(playMs()); setTimeout(refresh, 50); })
        .catch((e) => { starting = false; console.warn("replay start", e); });
    };
    cutKey = (e) => {
      if (e.key === "Escape") { e.preventDefault(); endCut(); }
      else if (e.key === "Enter") { e.preventDefault(); commit(); }
      else if ((e.key === "ArrowLeft" || e.key === "ArrowRight") && sel !== null) {
        e.preventDefault(); e.stopPropagation();
        const n = sel + (e.key === "ArrowLeft" ? -1 : 1);
        if (valid(n)) { sel = n; show(); }
      }
    };
    document.addEventListener("keydown", cutKey, true);
    // Mouse: the band follows the pointer, a click cuts. Finger: touch or drag to place the band, then nudge with the buttons and
    // confirm (a tap alone never cuts, because a fingertip is too wide to be exact).
    let ptype = (window.matchMedia && matchMedia("(pointer: coarse)").matches) ? "touch" : "mouse";
    const nudge = (d) => { if (sel === null) return; const n = sel + d; if (valid(n)) { sel = n; show(); } };
    const mk = (txt, cls, fn) => { const b = el("button", { textContent: txt, className: cls || "" }); b.onclick = (e) => { e.stopPropagation(); fn(); }; return b; };
    const tb = el("div", { className: "mc-touch" }, [mk("◀", "", () => nudge(-1)), mk("▶", "", () => nudge(1)), mk("✂ Cut here", "go", commit), mk("✕", "", endCut),
      el("span", { className: "hint", textContent: "Touch or drag on the chart to place the candle, then press ◀ ▶ to fine-tune." })]);
    tb.hidden = ptype !== "touch";
    tb.onpointerdown = (e) => e.stopPropagation();
    cutLayer.append(tb);
    cutLayer.style.touchAction = "none";
    cutLayer.onpointerdown = (ev) => {
      ptype = ev.pointerType || "mouse"; tb.hidden = ptype !== "touch";
      if (ptype === "touch") { try { cutLayer.setPointerCapture(ev.pointerId); } catch (_) {} pick(ev); show(); }
    };
    cutLayer.onpointermove = (ev) => { if (ev.pointerType === "touch" ? ev.buttons : true) { pick(ev); show(); } };
    cutLayer.onclick = (ev) => { if (ptype === "touch") return; pick(ev); commit(); };
  }

  // Step one bar back: restart the replay from the previous loaded bar (the engine can only step forward).
  function stepBack() {
    const c = chart(); if (!c || !c.replay.state.active) return;
    const co = c.rendererPort.coords, n = co.times.length, prev = co.times[n - 2];
    if (!isFinite(prev)) return;
    const resume = c.replay.state.playing;
    c.replay.pause();
    Promise.resolve(c.replay.stop()).then(() => c.replay.start({ from: prev })).then(() => { if (resume) c.replay.play(playMs()); setTimeout(refresh, 50); }).catch((e) => console.warn("replay back", e));
  }

  function togglePlay() {
    const c = chart(); if (!c) return;
    const s = c.replay.state;
    if (!s.active) return;
    if (s.playing) c.replay.pause(); else c.replay.play(playMs());
    refresh();
  }
  function step() { const c = chart(); if (c && c.replay.state.active) { c.replay.pause(); c.replay.step(); refresh(); } }
  function stopReplay() { const c = chart(); if (c) c.replay.stop(); refresh(); }

  // ---- Bookmarks: named checkpoints, global (every symbol), each with symbol, timeframe, replay bar, drawings and speed. ----
  // "Last position" is saved automatically while a replay runs, so closing the app and coming back resumes where you were.
  const BM_KEY = "madarReplayBookmarks", TF_CODE = { M1: "1", M5: "5", M15: "15", M30: "30", H1: "60", H4: "240", D1: "1D" };
  const TF_NAME = Object.fromEntries(Object.entries(TF_CODE).map(([k, v]) => [v, k]));
  const TF_SHORT = { M1: "1m", M5: "5m", M15: "15m", M30: "30m", H1: "1H", H4: "4H", D1: "D" };
  const bmLoad = () => { try { const v = JSON.parse(localStorage.getItem(BM_KEY)); return Array.isArray(v) ? v : []; } catch (_) { return []; } };
  const bmStore = (list) => { try { localStorage.setItem(BM_KEY, JSON.stringify(list)); } catch (_) { bmSay("Could not save (browser storage is full or blocked)"); } };
  let jumping = false, lastAuto = 0, confirmId = null, renameId = null;
  const bmSay = (t) => { if (bar) bar.ui.bmMsg.textContent = t || ""; };
  const marketNow = () => { const c = chart(), m = c && c.market; return m ? { symbol: String(m.symbol).replace(/^lab:/, ""), tf: TF_NAME[String(m.timeframe)] || "M1" } : null; };
  function snapshot(name, auto) {
    const c = chart(), s = c && c.replay.state, m = marketNow();
    if (!s || !s.active || !s.cursorTime || !m) return null;
    let drawings = null; try { drawings = c.drawings.toJSON(); } catch (_) {}
    return { id: auto ? "auto" : String(Date.now()) + Math.random().toString(36).slice(2, 5), auto: !!auto, name, symbol: m.symbol, tf: m.tf,
      time: s.cursorTime, drawings, speed, moves, created: Date.now() };
  }
  function autoSave() {
    if (jumping || Date.now() - lastAuto < 2000) return;
    const b = snapshot("Last position (auto)", true); if (!b) return;
    lastAuto = Date.now();
    const list = bmLoad().filter((x) => x.id !== "auto"); list.unshift(b); bmStore(list);
  }
  function bmSave() {
    const name = bar.ui.bmName.value.trim();
    const m = marketNow(), s = chart() && chart().replay.state;
    const b = snapshot(name || `${m ? m.symbol : ""} ${m ? TF_SHORT[m.tf] : ""} ${s && s.cursorTime ? fmtUtc(s.cursorTime) : ""}`.trim(), false);
    if (!b) { bmSay("Start a replay first, then save the point you stopped at."); return; }
    const list = bmLoad(); const auto = list.filter((x) => x.id === "auto"); bmStore([...auto, b, ...list.filter((x) => x.id !== "auto")]);
    bar.ui.bmName.value = ""; bmSay("Saved: " + b.name); bmRender();
  }
  const waitFor = (fn, ms) => new Promise((res) => { const t0 = Date.now(); (function poll() { let ok = false; try { ok = fn(); } catch (_) {} if (ok) res(true); else if (Date.now() - t0 > ms) res(false); else setTimeout(poll, 120); })(); });
  async function bmJump(b) {
    if (jumping) return; jumping = true; endCut(); bmSay("Jumping to " + b.name + " ...");
    try {
      let c = chart(), m = marketNow();
      if (!m || m.symbol !== b.symbol || m.tf !== b.tf) {
        if (c.replay.state.active) { c.replay.pause(); c.replay.stop(); }
        if (!window.labChart || !window.labChart.setMarket) throw new Error("cannot switch market here");
        window.labChart.setMarket(b.symbol, b.tf);
        const ok = await waitFor(() => { const mm = marketNow(), bb = chart().replay.bounds; return mm && mm.symbol === b.symbol && mm.tf === b.tf && bb && bb.first <= b.time && bb.last >= b.time; }, 25000);
        if (!ok) throw new Error("that bar is older than the loaded history of this symbol");
        c = chart();
      } else {
        const bb = c.replay.bounds;
        if (!bb || b.time < bb.first || b.time > bb.last) throw new Error("that bar is older than the loaded history");
        if (c.replay.state.active) { c.replay.pause(); await c.replay.stop(); }
      }
      await c.replay.start({ from: b.time });
      if (b.drawings) { try { c.drawings.fromJSON(b.drawings); } catch (e) { console.warn("restore drawings", e); } }
      if (b.speed) bar.ui.slider.oninput && (bar.ui.slider.value = msToPos(b.speed), bar.ui.slider.oninput());
      if (b.moves !== undefined && String(b.moves) !== bar.ui.motion.value) { bar.ui.motion.value = String(b.moves); bar.ui.motion.onchange(); }
      open = true; bmSay("Back at " + fmtUtc(b.time) + " (paused)."); refresh();
    } catch (e) { bmSay("Could not jump: " + (e && e.message || e)); }
    jumping = false; lastAuto = Date.now();
  }
  function bmRender() {
    const u = bar.ui, list = bmLoad(), tz = (ms) => fmtUtc(ms);
    u.bmList.textContent = "";
    if (!list.length) { u.bmList.append(el("div", { className: "mc-bm-empty", textContent: "No bookmarks yet. Start a replay, then press Save here." })); return; }
    for (const b of list) {
      const nd = b.drawings && b.drawings.drawings ? b.drawings.drawings.length : 0;
      const name = renameId === b.id
        ? el("input", { type: "text", value: b.name, maxLength: 60 })
        : el("div", { className: "mc-bm-name", textContent: (b.auto ? "⟲ " : "🔖 ") + b.name });
      if (renameId === b.id) {
        name.onclick = (e) => e.stopPropagation();
        name.onkeydown = (e) => { e.stopPropagation(); if (e.key === "Enter") { const v = name.value.trim(); if (v) bmStore(bmLoad().map((x) => x.id === b.id ? { ...x, name: v } : x)); renameId = null; bmRender(); } else if (e.key === "Escape") { renameId = null; bmRender(); } };
        setTimeout(() => name.focus(), 0);
      }
      const main = el("div", { className: "mc-bm-main" }, [name, el("div", { className: "mc-bm-meta", textContent: `${b.symbol} ${TF_SHORT[b.tf] || b.tf} · ${tz(b.time)}${nd ? ` · ${nd} drawing${nd > 1 ? "s" : ""}` : ""}` })]);
      const row = el("div", { className: "mc-bm-row", title: "Jump here" }, [main]);
      row.onclick = () => bmJump(b);
      if (!b.auto) {
        const ed = el("button", { textContent: "✎", title: "Rename" }); ed.onclick = (e) => { e.stopPropagation(); renameId = b.id; bmRender(); };
        const rm = el("button", { textContent: confirmId === b.id ? "Delete?" : "✕", title: "Delete" });
        rm.onclick = (e) => { e.stopPropagation(); if (confirmId === b.id) { confirmId = null; bmStore(bmLoad().filter((x) => x.id !== b.id)); } else { confirmId = b.id; setTimeout(() => { if (confirmId === b.id) { confirmId = null; bmRender(); } }, 2500); } bmRender(); };
        row.append(ed, rm);
      }
      u.bmList.append(row);
    }
  }

  // Give the replay its tick source (or remove it). Runs again whenever a new replay starts or the setting changes.
  function applyMotion() {
    const c = chart(); if (!c || !window.MadarReplayMotion) return;
    const key = String(moves);
    if (motionKey === key) return;
    motionKey = key;
    try {
      c.replay.setTicks(moves ? MadarReplayMotion.makeSource({ market: () => { const m = marketNow(); return m ? { symbol: m.symbol, tfCode: TF_CODE[m.tf] } : null; }, moves: () => moves, onSource: (t) => { motionUsed = t; } }) : null);
    } catch (e) { console.warn("replay motion", e); }
  }

  let open = false, lastOn = null;
  function refresh() {
    if (!bar) return;
    const u = bar.ui, c = chart(), s = c && c.replay && c.replay.state;
    const active = !!(s && s.active);
    if (open && !active && !cutLayer && !starting && u.panel.hidden) open = false;   // scissors cancelled before a replay began: close
    const on = open || active;
    bar.hidden = !on;
    u.setup.forEach((e) => { e.hidden = !open || active; });
    u.run.forEach((e) => { e.hidden = !active; });
    if (!on) u.panel.hidden = true;
    if (on !== lastOn) { lastOn = on; try { window.parent.postMessage({ evt: "replay:state", on }, location.origin); } catch (_) {} }
    u.bmSave.disabled = !active;
    if (active && !wasActive) motionKey = null;      // a new replay: hand it the tick source again
    wasActive = active;
    if (active) { applyMotion(); autoSave(); }
    u.move.hidden = !active || !moves;
    u.motion.title = "Moves inside each candle: the candle forms live instead of appearing whole" + (moves && motionUsed ? ` (last candle: ${motionUsed} data)` : "");
    u.cut.textContent = active ? "✂ Re-cut" : "✂ Select bar";
    u.chips.forEach((b) => b.classList.toggle("on", Math.abs(Number(b.dataset.ms) - speed) <= 2));
    if (active) {
      u.play.textContent = s.playing ? "⏸ Pause" : "▶ Play";
      u.play.classList.toggle("on", s.playing);
      u.step.disabled = u.play.disabled = !s.remaining;
      u.info.textContent = `${s.cursorTime ? fmtUtc(s.cursorTime) : ""} · ${s.remaining} left`;
    }
  }

  function mount(workspace, host) {
    if (bar && bar.parentNode) bar.remove();
    clearInterval(timer);
    ws = workspace;
    host = host || document.getElementById("chart");
    if (getComputedStyle(host).position === "static") host.style.position = "relative";
    host.appendChild(build());
    const u = bar.ui;
    u.close.onclick = () => { endCut(); open = false; refresh(); };
    hostEl = host;
    u.cut.onclick = beginCut;
    u.bm.onclick = () => { u.panel.hidden = !u.panel.hidden; if (!u.panel.hidden) { bmSay(""); bmRender(); } };
    u.bmSave.onclick = bmSave;
    u.bmName.onkeydown = (e) => { e.stopPropagation(); if (e.key === "Enter") bmSave(); };
    document.addEventListener("pointerdown", (e) => { if (!u.panel.hidden && !bar.contains(e.target)) u.panel.hidden = true; });
    u.step.onclick = step;
    u.move.onclick = () => { const c = chart(); if (c && c.replay.state.active) { c.replay.pause(); applyMotion(); c.replay.stepUpdate(); refresh(); } };
    u.motion.onchange = () => { moves = Number(u.motion.value); try { localStorage.setItem("madarReplayMoves", String(moves)); } catch (_) {} motionKey = null; applyMotion(); const c = chart(); if (c && c.replay.state.playing) c.replay.play(playMs()); refresh(); };
    u.back.onclick = stepBack;
    u.play.onclick = togglePlay;
    u.stop.onclick = () => { stopReplay(); open = false; refresh(); };
    const setSpeed = (ms) => {
      if (!isFinite(ms)) return;
      speed = Math.max(MIN_MS, Math.min(MAX_MS, ms));
      u.slider.value = msToPos(speed); u.slabel.textContent = speedLabel(speed);
      try { localStorage.setItem("madarReplaySpeed", String(speed)); } catch (_) {}
      const c = chart(); if (c && c.replay.state.playing) c.replay.play(playMs());
    };
    u.slider.oninput = () => setSpeed(posToMs(Number(u.slider.value)));
    u.chips.forEach((b) => { b.onclick = () => setSpeed(Number(b.dataset.ms)); });
    document.addEventListener("keydown", (e) => {
      const c = chart(); if (!e.shiftKey || !c || !c.replay.state.active) return;
      if (e.key === "ArrowRight") { e.preventDefault(); step(); }
      else if (e.code === "Space") { e.preventDefault(); togglePlay(); }
    });
    timer = setInterval(refresh, 300);
    refresh();
  }

  // Android back button: close the innermost thing that is open. Returns true when it did something.
  window.madarBack = () => {
    if (cutLayer) { endCut(); return true; }
    if (bar && !bar.ui.panel.hidden) { bar.ui.panel.hidden = true; return true; }
    return false;
  };
  // The Replay button of the shell (top bar) calls this: closed -> open the scissors at once; replay running -> exit; open -> close.
  function toggle() {
    const c = chart(); if (!c || !bar) return;
    if (c.replay.state.active) { stopReplay(); open = false; endCut(); refresh(); return; }
    if (open) { endCut(); open = false; refresh(); return; }
    open = true;
    if (!cutLayer) beginCut();
    refresh();
  }
  window.MadarReplayBar = { mount, toggle };
})();
