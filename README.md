# OrcaPhone

A native Android slicer for FDM 3D printers, inspired by [OrcaSlicer](https://github.com/OrcaSlicer/OrcaSlicer) and designed for touch screens.
Import a model, tweak a few settings with your thumb, slice it on the phone, inspect every layer, and send it to your printer.

## Features

**Prepare**
- Import **STL** (binary/ASCII), **3MF** (incl. multi-object and component files from Orca/Bambu/Prusa) and **OBJ** from the file picker, or via *Open with* / *Share* from other apps
- 3D plate view (OpenGL ES 3): one finger orbits, two fingers pan and pinch-zoom, double tap fits the view
- Tap to select, drag a selected object to move it on the bed
- Rotate (±15°/±90° per axis), **lay flat** on the largest face, uniform/free scale by % or mm, center, duplicate, delete, auto-arrange
- Overhang highlighting, out-of-volume warning, auto-scale of models larger than the printer
- Printer, filament and process presets with OrcaSlicer semantics (system presets, user presets, *modified* state, Save / Save as / Discard)
- Quick-settings sheet: layer height, infill density and pattern, walls, shells, supports, brim, vase mode, ironing

**Slicing engine** (`slicer-core`, pure Kotlin, multi-threaded)
- Mesh slicing via edge topology, even-odd hole detection, simplification
- Walls with Orca flow spacing, inner/outer order, aligned / back / nearest / random seams
- Top/bottom shell detection, monotonic or concentric surfaces, ironing
- Sparse infill: grid, rectilinear, triangles, cubic, gyroid, concentric
- Normal supports with threshold angle, Z/XY gaps, interface layers, "build plate only"
- Skirt, brim, elephant-foot compensation, spiral vase mode
- G-code for Marlin / Klipper / RepRapFirmware: relative E, retraction + Z-hop, travel without retraction inside parts, accelerations (M204), volumetric speed limit, min-layer-time slowdown and fan control, M73 progress, Klipper layer info, `;TYPE:` / `;LAYER_CHANGE` comments compatible with other viewers
- Look-ahead (junction-deviation) time estimate, filament length/weight/cost, time per feature

**Preview**
- Instanced 3D extrusion rendering, colored by feature type or speed
- Tall layer slider with step buttons and haptics, per-layer move scrubbing
- Toggle features and travel moves, print summary sheet
- Save G-code (Storage Access Framework) or share it

**Device**
- OctoPrint, Klipper (Moonraker — Mainsail/Fluidd) and PrusaLink
- Live status (state, progress, temperatures), pause / resume / cancel
- Upload with progress, optionally start printing

**Orca Cloud**
- Sign in with your OrcaSlicer account (Device tab), using the same hosted login page as OrcaSlicer desktop
- Two-way sync of your printer, filament and process presets with OrcaSlicer on your computer
  (download on sign-in / start / "Sync now", upload when you save, deletes synced; conflicts keep a phone copy)
- Your Orca Cloud printers with online status and job progress
- "Add from OrcaSlicer library": any of OrcaSlicer's 1,600+ printers with its default process and filament

Synced presets only store differences to OrcaSlicer's system presets, so the app bundles those
(`slicer-core/src/main/resources/orca/system_presets.json.gz`). Regenerate after an OrcaSlicer update with
`tools/gen_orca_catalog.py <OrcaSlicer>/resources/profiles`.

## Built-in presets
Generic Marlin, Generic Klipper, Creality Ender-3/V2, Ender-3 V3 SE, K1C, Prusa MK4, MK3S, Voron 2.4 350, Voron Trident 300 —
machine limits taken from the OrcaSlicer system profiles, with simplified start/end G-code. Filaments: PLA, PLA+, PETG, ABS, ASA, TPU.
Process: 0.08–0.28 mm plus a vase-mode profile. Everything is editable and can be saved as your own preset.

## Building

Requirements: JDK 17, Android SDK (API 35).

```bash
./gradlew :slicer-core:test          # engine unit tests (plain JVM)
./gradlew :app:assembleDebug         # app/build/outputs/apk/debug/app-debug.apk
```

Every push runs the GitHub Actions workflow in `.github/workflows/android.yml`, which runs the tests and uploads the
debug and release APKs as the `OrcaPhone-apk` artifact.

## Project layout

```
slicer-core/   Kotlin/JVM library: mesh import, plate, profiles, slicing, G-code, preview geometry
app/           Android app (Jetpack Compose + Material 3, OpenGL ES 3)
  gl/          renderer, camera, touch handling
  data/        preset storage, printer host clients
  ui/          prepare, preview, device and settings screens
```

## Limitations
OrcaPhone is a from-scratch Kotlin slicer, not a port of OrcaSlicer's C++ engine. Not (yet) supported: tree supports,
bridge detection, arachne variable-width walls, multi-material, variable layer height, modifiers/per-object settings,
Bambu Lab network printing (proprietary protocol — save the G-code instead), and OrcaSlicer's scripted G-code expressions
(`{if …}` blocks; simple `[placeholder]` / `{placeholder}` values are supported).

Always check the start G-code for your printer before the first print.
