// Madar Chart: live motion inside each candle during Bar Replay.
// Vela's replay can reveal a candle tick by tick when it is given a tick source: source(bar, {end, signal}) -> ticks.
// This builds that source. For a chart timeframe with a smaller stored/available timeframe (5m chart -> 1m candles)
// the path is REAL: every smaller candle contributes open -> low/high -> close in the order it really moved. When there is
// nothing smaller (1m chart, or the smaller data is not available) the path is SIMULATED: a repeatable random walk that
// starts at the open, touches the high and the low and ends on the close. Either way the candle ends exactly as it did.
// The number of moves per candle is chosen by the user (8 ... 240); the path is resampled to that count and always keeps
// the open, high, low and close.
(function () {
  const TF = { "1": ["M1", 60], "5": ["M5", 300], "15": ["M15", 900], "30": ["M30", 1800], "60": ["H1", 3600], "240": ["H4", 14400], "1D": ["D1", 86400] };
  const LOWER = [["M1", 60], ["M5", 300], ["M15", 900], ["M30", 1800], ["H1", 3600]];
  const MOVES = [8, 16, 30, 60, 120, 240];

  const rng = (seed) => { let a = seed >>> 0; return () => { a |= 0; a = (a + 0x6d2b79f5) | 0; let t = Math.imul(a ^ (a >>> 15), 1 | a); t = (t + Math.imul(t ^ (t >>> 7), 61 | t)) ^ t; return ((t ^ (t >>> 14)) >>> 0) / 4294967296; }; };
  const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

  // ---- real path from smaller candles ----
  function realPath(low, bar) {
    const p = [bar.open];
    for (const b of low) {
      const pts = b.close >= b.open ? [b.open, b.low, b.high, b.close] : [b.open, b.high, b.low, b.close];
      for (const v of pts) if (v !== p[p.length - 1]) p.push(v);
    }
    // the larger candle is the truth: it starts on its open, reaches its high and low, ends on its close
    if (Math.max(...p) < bar.high) p.splice(Math.max(1, p.length >> 1), 0, bar.high);
    if (Math.min(...p) > bar.low) p.splice(Math.max(1, p.length >> 2), 0, bar.low);
    p[0] = bar.open; p[p.length - 1] = bar.close;
    return p;
  }

  // ---- simulated path (repeatable: seeded by the candle time) ----
  function synthPath(bar, n) {
    const r = rng(Math.floor(bar.time / 1000) ^ 0x9e3779b9), range = Math.max(bar.high - bar.low, 1e-9);
    const upFirst = bar.close >= bar.open ? r() < 0.62 : r() < 0.38;      // bullish candles usually dip first, bearish ones pop first
    const ctrl = upFirst ? [bar.open, bar.low, bar.high, bar.close] : [bar.open, bar.high, bar.low, bar.close];
    const w = [], tot = []; let sum = 0;
    for (let i = 0; i < 3; i++) { const d = Math.abs(ctrl[i + 1] - ctrl[i]) + range * 0.15; w.push(d); sum += d; }
    const out = [ctrl[0]];
    let left = n - 1 - 3;                                                   // points after the open, minus the 3 control points
    const per = w.map((d) => Math.max(0, Math.round((d / sum) * left)));
    const diff = left - per.reduce((a, b) => a + b, 0); per[1] = Math.max(0, per[1] + diff);
    for (let i = 0; i < 3; i++) {
      const a = ctrl[i], b = ctrl[i + 1], k = per[i];
      for (let j = 1; j <= k; j++) {
        const f = j / (k + 1), base = a + (b - a) * f, amp = range * 0.07 * Math.sin(Math.PI * f) + range * 0.015;
        out.push(clamp(base + (r() - 0.5) * 2 * amp, bar.low, bar.high));
      }
      out.push(b);
    }
    return out;
  }

  // ---- resample a path to exactly n moves, keeping the first, last, highest and lowest points ----
  function resample(path, n, bar) {
    const len = path.length;
    if (len === n) return path;
    if (len > n) {
      let hi = 0, lo = 0; path.forEach((v, i) => { if (v > path[hi]) hi = i; if (v < path[lo]) lo = i; });
      const keep = new Set([0, len - 1, hi, lo]);
      for (let i = 0; keep.size < n && i < n * 4; i++) keep.add(Math.round((i * (len - 1)) / (n - 1)));
      let idx = [...keep].sort((a, b) => a - b);
      while (idx.length > n) { const drop = idx.findIndex((x, i) => i > 0 && i < idx.length - 1 && x !== hi && x !== lo); idx.splice(drop, 1); }
      return idx.map((i) => path[i]);
    }
    const r = rng(Math.floor(bar.time / 1000) ^ 0x51ed270b), range = Math.max(bar.high - bar.low, 1e-9);
    const extra = n - len, segs = len - 1, out = [path[0]];
    for (let s = 0; s < segs; s++) {
      const k = Math.floor((extra * (s + 1)) / segs) - Math.floor((extra * s) / segs);
      const a = path[s], b = path[s + 1];
      for (let j = 1; j <= k; j++) {
        const f = j / (k + 1), amp = range * 0.04 * Math.sin(Math.PI * f);
        out.push(clamp(a + (b - a) * f + (r() - 0.5) * 2 * amp, bar.low, bar.high));
      }
      out.push(b);
    }
    return out;
  }

  // ---- smaller candles, fetched in chunks and kept in memory ----
  const chunks = new Map();                                                 // key -> Promise<bars (ms times)>
  async function fetchBars(sym, tf, toMs, limit) {
    const src = window.srcOf ? window.srcOf(sym) : "binance";
    const r = await fetch(`/api/bars?symbol=${encodeURIComponent(sym)}&tf=${tf}&limit=${limit}&source=${src}&to=${Math.floor(toMs)}`);
    const j = await r.json();
    if (!Array.isArray(j)) throw new Error(j.error || "no data");
    return j.map((b) => ({ ...b, time: b.time * 1000 }));
  }
  function lowerChoices(chartSec) {
    return LOWER.filter(([, s]) => s < chartSec && chartSec / s >= 3 && chartSec / s <= 60);
  }
  async function lowerBars(sym, chartSec, t0, t1) {
    for (const [name, sec] of lowerChoices(chartSec)) {
      const ratio = chartSec / sec, perChunk = Math.max(1, Math.floor(900 / ratio)), span = perChunk * chartSec * 1000;
      const idx = Math.floor(t0 / span), key = `${sym}|${name}|${idx}`;
      if (!chunks.has(key)) {
        const to = (idx + 1) * span - 1;
        chunks.set(key, fetchBars(sym, name, to, Math.ceil(span / (sec * 1000)) + 2).catch(() => []));
        if (chunks.size > 60) chunks.delete(chunks.keys().next().value);
      }
      const all = await chunks.get(key);
      const mine = all.filter((b) => b.time >= t0 && b.time < t1);
      if (mine.length >= 2) return { bars: mine, name };
    }
    return { bars: [], name: null };
  }

  // ctx: { market: () => ({symbol, tfCode}), moves: () => n, onSource: (text) => void }
  function makeSource(ctx) {
    return async (bar, opt) => {
      const n = ctx.moves(), m = ctx.market(), tf = m && TF[m.tfCode];
      const chartSec = tf ? tf[1] : 60;
      let path = null, used = "simulated";
      if (m) {
        try {
          const lo = await lowerBars(m.symbol, chartSec, bar.time, opt && opt.end ? opt.end : bar.time + chartSec * 1000);
          if (lo.bars.length) { path = realPath(lo.bars, bar); used = "real " + lo.name; }
        } catch (_) { /* fall through to the simulated path */ }
      }
      if (!path) path = synthPath(bar, n);
      ctx.onSource && ctx.onSource(used);
      const pts = resample(path, n, bar);
      return pts.map((p, i) => (i === 0 ? { price: p, open: p } : { price: p }));
    };
  }

  window.MadarReplayMotion = { makeSource, MOVES, _t: { realPath, synthPath, resample } };
})();
