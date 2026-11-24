// Madar Chart theme: presets + the user's own overrides (Settings panel), shared by the wrapper and all engines.
// Saved in localStorage "chartHubTheme" (same origin, so every engine iframe reads the same settings).
// Running inside the Android app (Capacitor serves the UI from https://localhost; the Mac server is http://localhost:8770).
window.MadarAndroid = location.protocol === "https:" && location.hostname === "localhost";
window.HubPresets = {
  graphite: { label: "Graphite & amber", mode: "dark",   // chosen 2026-11-02 (_design/concepts/A_graphite_amber_1.jpg)
    bg: "#151412", panel: "#1b1a17", raised: "#24221e", line: "#2f2c27", grid: "#1f1d1a",
    text: "#e6dfd2", dim: "#958c7e", faint: "#5e584f", accent: "#e3a33a", accentInk: "#1a1407",
    up: "#e3a33a", down: "#b4472f", ok: "#8fb36a", bad: "#c9553c" },
  black: { label: "Pure black", mode: "dark",
    bg: "#000000", panel: "#0b0b0b", raised: "#181818", line: "#262626", grid: "#121212",
    text: "#ececec", dim: "#8a8a8a", faint: "#4a4a4a", accent: "#e3a33a", accentInk: "#141005",
    up: "#e3a33a", down: "#b4472f", ok: "#8fb36a", bad: "#c9553c" },
  light: { label: "Light (white)", mode: "light",
    bg: "#ffffff", panel: "#f6f4f0", raised: "#ebe7e0", line: "#dcd6cc", grid: "#f0ede8",
    text: "#1d1b18", dim: "#6f685e", faint: "#b3ab9f", accent: "#b8761a", accentInk: "#ffffff",
    up: "#1d1b18", down: "#c0442b", ok: "#4e7d2c", bad: "#c0442b" },
  mono: { label: "Black & white", mode: "dark",
    bg: "#0a0a0a", panel: "#121212", raised: "#1c1c1c", line: "#2a2a2a", grid: "#141414",
    text: "#f0f0f0", dim: "#9a9a9a", faint: "#555555", accent: "#ffffff", accentInk: "#0a0a0a",
    up: "#f2f2f2", down: "#6e6e6e", ok: "#8fb36a", bad: "#c9553c" },
  paper: { label: "White & black", mode: "light",
    bg: "#ffffff", panel: "#f5f7fa", raised: "#e9edf3", line: "#d6dce6", grid: "#eef1f6",
    text: "#151a23", dim: "#5e6a7d", faint: "#aab3c2", accent: "#111111", accentInk: "#ffffff",
    up: "#111111", down: "#b3b3b3", ok: "#4e7d2c", bad: "#c0442b" },
  blueorange: { label: "Blue & orange", mode: "dark",
    bg: "#0e1117", panel: "#151a23", raised: "#1d2430", line: "#2a3345", grid: "#141922",
    text: "#e6ebf2", dim: "#8b98ad", faint: "#4f5b70", accent: "#2962ff", accentInk: "#ffffff",
    up: "#2962ff", down: "#ff9800", ok: "#8fb36a", bad: "#c9553c" },
  blueorangelight: { label: "Blue & orange (light)", mode: "light",
    bg: "#ffffff", panel: "#f5f7fa", raised: "#e9edf3", line: "#d6dce6", grid: "#eef1f6",
    text: "#151a23", dim: "#5e6a7d", faint: "#aab3c2", accent: "#2962ff", accentInk: "#ffffff",
    up: "#2962ff", down: "#ff9800", ok: "#4e7d2c", bad: "#c0442b" },
  cyanpurple: { label: "Cyan & purple", mode: "dark",
    bg: "#0b0d14", panel: "#12151f", raised: "#1b1f2d", line: "#282d40", grid: "#11141e",
    text: "#e8ecf8", dim: "#8a93b0", faint: "#4e5570", accent: "#22d3ee", accentInk: "#04141a",
    up: "#22d3ee", down: "#a855f7", ok: "#8fb36a", bad: "#c9553c" },
  navy: { label: "Midnight blue", mode: "dark",
    bg: "#0b1220", panel: "#101a2e", raised: "#17233b", line: "#22314f", grid: "#121c31",
    text: "#e5ecf7", dim: "#8294b3", faint: "#4d5d7a", accent: "#4cc9f0", accentInk: "#04141a",
    up: "#4cc9f0", down: "#f72585", ok: "#8fb36a", bad: "#c9553c" },
  goldblue: { label: "Gold & blue", mode: "dark",
    bg: "#101114", panel: "#17181d", raised: "#202228", line: "#2e3038", grid: "#16171b",
    text: "#ece8dd", dim: "#9a968a", faint: "#5a5850", accent: "#facc15", accentInk: "#171305",
    up: "#facc15", down: "#3b82f6", ok: "#8fb36a", bad: "#c9553c" },
  sky: { label: "Sky & rose (light)", mode: "light",
    bg: "#ffffff", panel: "#f5f7fa", raised: "#e9edf3", line: "#d6dce6", grid: "#eef1f6",
    text: "#151a23", dim: "#5e6a7d", faint: "#aab3c2", accent: "#0ea5e9", accentInk: "#ffffff",
    up: "#0284c7", down: "#e11d74", ok: "#4e7d2c", bad: "#c0442b" },
  slateMono: { label: "Gray & white", mode: "dark",
    bg: "#2b2e33", panel: "#33373d", raised: "#3d4249", line: "#4b515a", grid: "#32363c",
    text: "#e9ecef", dim: "#a3a9b2", faint: "#6b727c", accent: "#ffffff", accentInk: "#10131a",
    up: "#f5f5f5", down: "#101215", ok: "#8fb36a", bad: "#c9553c" },
  slateBO: { label: "Gray & blue-orange", mode: "dark",
    bg: "#2b2e33", panel: "#33373d", raised: "#3d4249", line: "#4b515a", grid: "#32363c",
    text: "#e9ecef", dim: "#a3a9b2", faint: "#6b727c", accent: "#4da3ff", accentInk: "#10131a",
    up: "#4da3ff", down: "#ffa94d", ok: "#8fb36a", bad: "#c9553c" },
  slateCP: { label: "Gray & cyan-purple", mode: "dark",
    bg: "#2b2e33", panel: "#33373d", raised: "#3d4249", line: "#4b515a", grid: "#32363c",
    text: "#e9ecef", dim: "#a3a9b2", faint: "#6b727c", accent: "#38e0f0", accentInk: "#10131a",
    up: "#38e0f0", down: "#b58cff", ok: "#8fb36a", bad: "#c9553c" },
  slateGB: { label: "Gray & gold-blue", mode: "dark",
    bg: "#2b2e33", panel: "#33373d", raised: "#3d4249", line: "#4b515a", grid: "#32363c",
    text: "#e9ecef", dim: "#a3a9b2", faint: "#6b727c", accent: "#ffd54a", accentInk: "#10131a",
    up: "#ffd54a", down: "#5aa0ff", ok: "#8fb36a", bad: "#c9553c" },
  charcoalMY: { label: "Charcoal & yellow-magenta", mode: "dark",
    bg: "#1f2124", panel: "#26292d", raised: "#2f3338", line: "#3d4249", grid: "#2a2d31",
    text: "#e6e8eb", dim: "#9aa0a8", faint: "#5f656d", accent: "#ffe066", accentInk: "#10131a",
    up: "#ffe066", down: "#ff4fd8", ok: "#8fb36a", bad: "#c9553c" },
  charcoalIA: { label: "Charcoal & indigo-amber", mode: "dark",
    bg: "#1f2124", panel: "#26292d", raised: "#2f3338", line: "#3d4249", grid: "#2a2d31",
    text: "#e6e8eb", dim: "#9aa0a8", faint: "#5f656d", accent: "#818cf8", accentInk: "#10131a",
    up: "#818cf8", down: "#f59e0b", ok: "#8fb36a", bad: "#c9553c" },
  steelSP: { label: "Steel & sky-pink", mode: "dark",
    bg: "#3a3f47", panel: "#424852", raised: "#4c535e", line: "#5a626e", grid: "#434954",
    text: "#f0f2f5", dim: "#b3bac4", faint: "#7c8590", accent: "#7dd3fc", accentInk: "#10131a",
    up: "#7dd3fc", down: "#f9a8d4", ok: "#8fb36a", bad: "#c9553c" },
  lgrayBO: { label: "Light gray & blue-orange", mode: "light",
    bg: "#e4e6ea", panel: "#d8dbe0", raised: "#cbcfd5", line: "#b9bec6", grid: "#d3d6db",
    text: "#14171c", dim: "#4f5865", faint: "#8f97a3", accent: "#2962ff", accentInk: "#ffffff",
    up: "#2962ff", down: "#f08a00", ok: "#8fb36a", bad: "#c9553c" },
  lgrayBW: { label: "Light gray & black-gray", mode: "light",
    bg: "#e4e6ea", panel: "#d8dbe0", raised: "#cbcfd5", line: "#b9bec6", grid: "#d3d6db",
    text: "#14171c", dim: "#4f5865", faint: "#8f97a3", accent: "#14171c", accentInk: "#ffffff",
    up: "#14171c", down: "#7b8089", ok: "#8fb36a", bad: "#c9553c" },
  lgrayIA: { label: "Light gray & indigo-amber", mode: "light",
    bg: "#e4e6ea", panel: "#d8dbe0", raised: "#cbcfd5", line: "#b9bec6", grid: "#d3d6db",
    text: "#14171c", dim: "#4f5865", faint: "#8f97a3", accent: "#4f46e5", accentInk: "#ffffff",
    up: "#4f46e5", down: "#e08a00", ok: "#8fb36a", bad: "#c9553c" },
  yellowViolet: { label: "Yellow & violet", mode: "dark",
    bg: "#0d0d12", panel: "#14141b", raised: "#1d1d27", line: "#2b2b3a", grid: "#121218",
    text: "#ececf4", dim: "#8e8ea8", faint: "#52526a", accent: "#fde047", accentInk: "#151203",
    up: "#fde047", down: "#8b5cf6", ok: "#8fb36a", bad: "#c9553c" },
  blueYellow: { label: "Blue & yellow", mode: "dark",
    bg: "#0b0f17", panel: "#111722", raised: "#192130", line: "#26324a", grid: "#0f1520",
    text: "#e6ecf6", dim: "#8795ae", faint: "#4c5a73", accent: "#3b82f6", accentInk: "#ffffff",
    up: "#3b82f6", down: "#fde047", ok: "#8fb36a", bad: "#c9553c" },
};
// Font choices: the default system font plus smoother, rounder faces (loaded from Google Fonts when picked).
window.HubFonts = {
  system: { label: "Segoe UI (default)", css: '"Segoe UI Variable Text", "Segoe UI", system-ui, sans-serif' },
  nunito: { label: "Nunito (rounded)", css: '"Nunito", "Segoe UI", sans-serif', gf: "Nunito:wght@400;600;700" },
  manrope: { label: "Manrope (soft)", css: '"Manrope", "Segoe UI", sans-serif', gf: "Manrope:wght@400;600;700" },
  quicksand: { label: "Quicksand (very round)", css: '"Quicksand", "Segoe UI", sans-serif', gf: "Quicksand:wght@400;600;700" },
  inter: { label: "Inter (neutral)", css: '"Inter", "Segoe UI", sans-serif', gf: "Inter:wght@400;600;700" },
};
window.HubThemeKeys = ["bg", "panel", "raised", "line", "grid", "text", "dim", "faint", "accent", "accentInk", "up", "down", "ok", "bad"];

function hexA(hex, a) { // "#rrggbb" + alpha -> rgba()
  const n = parseInt(hex.slice(1), 16);
  return `rgba(${(n >> 16) & 255},${(n >> 8) & 255},${n & 255},${a})`;
}
window.HubSettingsLoad = () => {
  let s = {};
  try { s = JSON.parse(localStorage.getItem("chartHubTheme") || "{}"); } catch (_) {}
  return { preset: HubPresets[s.preset] ? s.preset : "graphite", font: HubFonts[s.font] ? s.font : "system", overrides: s.overrides || {}, indicatorBars: !!s.indicatorBars };
};
window.HubSettingsSave = (s) => { try { localStorage.setItem("chartHubTheme", JSON.stringify(s)); } catch (_) {} };

// Build window.HubTheme from the saved settings and push it into CSS variables. Returns the theme.
window.HubThemeApply = () => {
  const s = HubSettingsLoad(), base = HubPresets[s.preset], f = HubFonts[s.font];
  const t = Object.assign({}, base, s.overrides);
  t.preset = s.preset; t.fontKey = s.font; t.font = f.css;
  t.mono = '"Cascadia Mono", Consolas, "SFMono-Regular", monospace';
  t.upVol = hexA(t.up, 0.42); t.downVol = hexA(t.down, 0.42);
  t.lines = t.mode === "light" ? ["#1d1b18", t.accent, "#8a7f6e", "#c0442b"] : [t.text, t.accent, "#9c8f7a", "#c97a4a"];
  window.HubTheme = t;
  const st = document.documentElement.style;
  for (const k of HubThemeKeys) st.setProperty("--" + k, t[k]);
  st.setProperty("--font", t.font); st.setProperty("--mono", t.mono);
  document.documentElement.style.colorScheme = t.mode;
  if (f.gf && !document.getElementById("gf-" + s.font)) {
    const l = document.createElement("link"); l.id = "gf-" + s.font; l.rel = "stylesheet";
    l.href = `https://fonts.googleapis.com/css2?family=${f.gf}&display=swap`; document.head.appendChild(l);
  }
  return t;
};
HubThemeApply();
