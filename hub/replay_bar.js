// TradeX: Bar Replay control bar, 2026-11-23.
// Vela has a replay API (chart.replay.start/step/play/pause/stop) but no button for it. This adds a small floating
// bar over the chart: a "Replay" button opens the controls (start point, step, play/pause, speed, stop).
// It always acts on the active chart cell. Start point: a quick preset or an exact date/time.
(function () {
  const css = document.createElement("style");
  css.textContent = `
  .tx-rp{position:absolute;z-index:29;top:8px;left:50%;transform:translateX(-50%);display:flex;align-items:center;gap:4px;
    padding:3px;border-radius:8px;flex-wrap:wrap;justify-content:center;max-width:calc(100% - 90px);background:var(--panel,#16212b);border:1px solid var(--line,#2b3946);
    box-shadow:0 4px 14px rgba(0,0,0,.28);user-select:none;color:var(--text,#d6dee5);font:12px system-ui,sans-serif}
  .tx-rp button,.tx-rp select,.tx-rp input{height:26px;border:1px solid transparent;border-radius:6px;background:transparent;
    color:inherit;font:inherit;padding:0 8px;cursor:pointer;box-sizing:border-box}
  .tx-rp select,.tx-rp input{background:var(--raised,#1d2a36);border-color:var(--line,#2b3946)}
  .tx-rp button:hover{background:var(--raised,#1d2a36)}
  .tx-rp button:disabled{opacity:.4;cursor:default}
  .tx-rp button.on{background:var(--accent,#e3a33a);color:var(--accentInk,#1a1407)}
  .tx-rp .tx-rp-info{color:var(--faint,#93a2af);padding:0 6px;white-space:nowrap;min-width:90px}
  .tx-rp .tx-rp-sep{width:1px;height:18px;background:var(--line,#2b3946)}
  .tx-rp [hidden]{display:none!important}`;
  document.head.appendChild(css);

  const PRESETS = [["1 hour ago", 3600e3], ["3 hours ago", 3 * 3600e3], ["6 hours ago", 6 * 3600e3], ["1 day ago", 864e5],
    ["3 days ago", 3 * 864e5], ["1 week ago", 7 * 864e5], ["Oldest loaded bar", -1]];
  // Speed = bars per second, on a log slider from 0.2 bar/s (5 s per bar) to 100 bars/s (10 ms per bar), plus quick chips.
  const MIN_MS = 10, MAX_MS = 5000;
  const CHIPS = [0.5, 1, 2, 5, 10, 25, 50, 100];
  const msToPos = (ms) => Math.round(100 * Math.log(MAX_MS / ms) / Math.log(MAX_MS / MIN_MS));
  const posToMs = (p) => Math.round(MAX_MS * Math.pow(MIN_MS / MAX_MS, p / 100));
  const speedLabel = (ms) => { const r = 1000 / ms; return (r >= 10 ? Math.round(r) : Math.round(r * 10) / 10) + " bar/s"; };
  const savedSpeed = (() => { try { const v = Number(localStorage.getItem("tradexReplaySpeed")); return v >= MIN_MS && v <= MAX_MS ? v : 1000; } catch (_) { return 1000; } })();

  let ws = null, bar = null, timer = null, speed = savedSpeed;
  const chart = () => { try { return (ws.active || ws).chart; } catch (_) { return null; } };
  const el = (tag, props, kids) => { const e = Object.assign(document.createElement(tag), props || {}); (kids || []).forEach((k) => e.append(k)); return e; };
  const pad = (n) => String(n).padStart(2, "0");
  const toLocalInput = (ms) => { const d = new Date(ms); return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`; };
  const fmt = (ms) => { const d = new Date(ms); return `${pad(d.getDate())}/${pad(d.getMonth() + 1)} ${pad(d.getHours())}:${pad(d.getMinutes())}`; };

  function build() {
    const ui = {};
    ui.toggle = el("button", { textContent: "⏪ Replay", title: "Bar Replay" });
    ui.cut = el("button", { textContent: "✂ Select bar", title: "Click a bar on the chart to start the replay from there (Esc cancels)" });
    ui.preset = el("select", { title: "Start point" }, PRESETS.map(([l, v]) => el("option", { textContent: l, value: v })));
    ui.preset.append(el("option", { textContent: "Pick date/time…", value: "custom" }));
    ui.date = el("input", { type: "datetime-local", hidden: true, title: "Start date/time (your local time)" });
    ui.start = el("button", { textContent: "Start", title: "Start replay from the chosen point" });
    ui.back = el("button", { textContent: "⏮ Back", title: "Go back one bar" });
    ui.step = el("button", { textContent: "⏭ Step", title: "Next bar (Shift+→)" });
    ui.play = el("button", { textContent: "▶ Play", title: "Play / pause (Shift+Space)" });
    ui.slider = el("input", { type: "range", min: 0, max: 100, step: 1, value: msToPos(speed), title: "Replay speed (log scale)" });
    ui.slider.style.cssText = "width:110px;padding:0;accent-color:var(--accent,#e3a33a);cursor:pointer";
    ui.slabel = el("span", { className: "tx-rp-info", textContent: speedLabel(speed) });
    ui.slabel.style.cssText = "min-width:58px;text-align:right;padding:0 2px";
    ui.chips = CHIPS.map((r) => el("button", { textContent: r + "x", title: r + " bar/s" }));
    ui.chips.forEach((b, i) => { b.dataset.ms = String(Math.round(1000 / CHIPS[i])); b.style.cssText = "padding:0 5px;min-width:0;font-size:11px"; });
    ui.speed = el("span", {}, [ui.slider, ui.slabel, ...ui.chips]);
    ui.speed.style.cssText = "display:flex;align-items:center;gap:2px";
    ui.stop = el("button", { textContent: "⏹ Exit", title: "Stop and return to live" });
    ui.info = el("span", { className: "tx-rp-info" });
    ui.setup = [ui.preset, ui.date, ui.start];
    ui.run = [ui.back, ui.step, ui.play, ui.speed, ui.stop, ui.info];
    const sep = () => el("span", { className: "tx-rp-sep" });
    bar = el("div", { className: "tx-rp" }, [ui.toggle, sep(), ui.cut, ...ui.setup, ...ui.run]);
    bar.querySelector(".tx-rp-sep").hidden = true;
    ui.sep = bar.querySelector(".tx-rp-sep");
    bar.ui = ui;
    return bar;
  }

  // Scissors mode: a transparent layer over the chart. A band marks the exact candle under the mouse (the label gives its
  // open time in UTC, like the chart axis, and in local time). Click or Enter cuts there, Left/Right arrows nudge by one bar,
  // Esc cancels. The replay then starts with that candle as the last one shown.
  let cutLayer = null, hostEl = null;
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
      // Re-cut: leave the running replay first, then start again from the new bar (keeps playing if it was playing).
      Promise.resolve(was.active ? (c.replay.pause(), c.replay.stop()) : null)
        .then(() => c.replay.start({ from: t }))
        .then(() => { open = true; if (resume) c.replay.play(speed); setTimeout(refresh, 50); })
        .catch((e) => console.warn("replay start", e));
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
    cutLayer.onmousemove = (ev) => { pick(ev); show(); };
    cutLayer.onclick = (ev) => { pick(ev); commit(); };
  }

  // Step one bar back: restart the replay from the previous loaded bar (the engine can only step forward).
  function stepBack() {
    const c = chart(); if (!c || !c.replay.state.active) return;
    const co = c.rendererPort.coords, n = co.times.length, prev = co.times[n - 2];
    if (!isFinite(prev)) return;
    const resume = c.replay.state.playing;
    c.replay.pause();
    Promise.resolve(c.replay.stop()).then(() => c.replay.start({ from: prev })).then(() => { if (resume) c.replay.play(speed); setTimeout(refresh, 50); }).catch((e) => console.warn("replay back", e));
  }

  function startReplay() {
    const c = chart(); if (!c) return;
    const b = c.replay.bounds; if (!b) return;
    let from;
    if (bar.ui.preset.value === "custom") from = new Date(bar.ui.date.value).getTime();
    else { const v = Number(bar.ui.preset.value); from = v < 0 ? b.first : b.last - v; }
    if (!isFinite(from)) return;
    from = Math.max(from, b.first);
    Promise.resolve(c.replay.start({ from })).then(() => { setTimeout(refresh, 50); }).catch((e) => console.warn("replay start", e));
  }
  function togglePlay() {
    const c = chart(); if (!c) return;
    const s = c.replay.state;
    if (!s.active) return;
    if (s.playing) c.replay.pause(); else c.replay.play(speed);
    refresh();
  }
  function step() { const c = chart(); if (c && c.replay.state.active) { c.replay.pause(); c.replay.step(); refresh(); } }
  function stopReplay() { const c = chart(); if (c) c.replay.stop(); refresh(); }

  let open = false;
  function refresh() {
    if (!bar) return;
    const u = bar.ui, c = chart(), s = c && c.replay && c.replay.state;
    const active = !!(s && s.active);
    u.toggle.classList.toggle("on", open || active);
    u.setup.forEach((e) => { e.hidden = !open || active; });
    u.date.hidden = u.date.hidden || u.preset.value !== "custom" || !open || active;
    if (open && !active && u.preset.value === "custom") u.date.hidden = false;
    u.run.forEach((e) => { e.hidden = !active; });
    u.sep.hidden = u.cut.hidden = !(open || active);
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
    u.toggle.onclick = () => { endCut(); const c = chart(); if (c && c.replay.state.active) return; open = !open; if (open) { const b = c && c.replay.bounds; if (b && !u.date.value) u.date.value = toLocalInput(b.last - 864e5); } refresh(); };
    u.preset.onchange = () => { u.date.hidden = u.preset.value !== "custom"; };
    hostEl = host;
    u.cut.onclick = beginCut;
    u.start.onclick = startReplay;
    u.step.onclick = step;
    u.back.onclick = stepBack;
    u.play.onclick = togglePlay;
    u.stop.onclick = () => { stopReplay(); open = false; refresh(); };
    const setSpeed = (ms) => {
      if (!isFinite(ms)) return;
      speed = Math.max(MIN_MS, Math.min(MAX_MS, ms));
      u.slider.value = msToPos(speed); u.slabel.textContent = speedLabel(speed);
      try { localStorage.setItem("tradexReplaySpeed", String(speed)); } catch (_) {}
      const c = chart(); if (c && c.replay.state.playing) c.replay.play(speed);
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

  window.TradeXReplayBar = { mount };
})();
