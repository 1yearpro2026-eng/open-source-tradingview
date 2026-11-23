# Third-party notices

This project is AGPL-3.0-only. It bundles or ships the following open-source components, each under its own licence.

| Component | Version | Licence | Where | Project |
|---|---|---|---|---|
| Vela (`@luxalgo/vela`) | 0.9.3 | Apache-2.0 + NOTICE (attribution) | `hub/vela.bundle.js` | https://github.com/luxalgo/vela |
| Vela-pinets (`@luxalgo/vela-pinets`) | 0.2.18 | AGPL-3.0-only | `hub/vela.bundle.js` | https://github.com/LuxAlgo/Vela-pinets |
| PineTS (`pinets`) | 0.12.1 | AGPL-3.0-only | `hub/vela.bundle.js` | https://github.com/LuxAlgo/PineTS |

Licence texts: [LICENSE](LICENSE) (AGPL-3.0), [licenses/Apache-2.0.txt](licenses/Apache-2.0.txt).
Notice file: [licenses/NOTICE-vela.txt](licenses/NOTICE-vela.txt).

## Attribution requirements you must keep

- **Vela:** every screen that shows a Vela chart must show a visible Vela attribution. The library draws its
  logomark on the chart by default. Do not hide it unless you show "Vela" with a link to https://velacharts.dev
  on the same screen.

## Rebuilding the bundled files

`hub/vela.bundle.js` is built from the exact versions pinned in `build/package-lock.json`:

```bash
cd build
npm ci
npm run build     # -> ../hub/vela.bundle.js
```
