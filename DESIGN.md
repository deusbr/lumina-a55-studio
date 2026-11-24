# DESIGN.md — Lumina A55 Studio

Audited via `ui-craft-studio` (`Obsidian Craft Studio` darkroom architecture + WCAG 2.1 AA >= `4.5:1`).

## Color Tokens (`LuminaColors`)
- `VoidBg`: `#0A0B0D` — unobstructed viewfinder & lighttable canvas
- `PanelSurface`: `#131519` — tactile bottom darkroom deck & cards
- `ElevatedSurface`: `#1B1E24` — recessed chips, dials, and sliders
- `HairlineBorder`: `#2B313A` — 1dp structural containment border
- `InkPrimary`: `#F3F5F7` — primary labels & numerals (>16:1 contrast)
- `InkSecondary`: `#A3ABB8` — telemetry metadata & subtitles (>7:1 contrast)
- `OpticalAmber`: `#E59449` — active Pro controls, optical focal ruler & primary actions (>7.8:1 contrast on `#0A0B0D`)
- `StudioEmerald`: `#10B981` — automatic scene lock & saved confirmation
- `ShutterCrimson`: `#D94E41` — active shutter pulse & recording dot

## Dual-Mode Ergonomics (`NORMAL` vs `PRO`)
- **`NORMAL` Mode:** Zero manual choice clutter. Displays only full-bleed viewfinder, live AI context readout pill, optical lens switcher (`0.5x`, `1x`, `2x`, `M`, `32M`), gallery/shutter controls, and `NORMAL | PRO` switch.
- **`PRO` Mode:** Unlocks a compact 3-tab darkroom sheet (`Exposição`, `Película`, `Motor`), session look lock, Camera2 hardware inspector, and 4 editorial fine-tuning sliders in the Lighttable review screen.
- **Touch Targets:** Minimum `44dp` interactive targets across top bar, focal pills, mode tabs, and shutter controls.
