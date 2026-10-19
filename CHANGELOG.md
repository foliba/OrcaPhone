# Changelog

## Unreleased

### Licences
- The *About & licences* screen now lists every library in the app, including indirect
  dependencies, with version and licence. The list is generated at build time, and the build fails
  if a library with a licence that isn't explicitly allowed is added.

## 0.2.1 — licence

OrcaPhone is now open source under the AGPL-3.0.

### Licence
- OrcaPhone is now licensed under the GNU Affero General Public License v3.0 (`LICENSE`).
- New *About & licences* screen (Prepare → ⋮): copyright, no-warranty and licence notice, link to the
  source code, and the licence texts of all bundled third-party components.
- `THIRD_PARTY_NOTICES.md` lists the bundled OrcaSlicer profiles and libraries with their licences;
  README has a licence section and a not-affiliated statement.

## 0.2.0 — Printables import and multiple beds

Import models straight from Printables links and spread big jobs across several beds.

### Import from Printables
- Paste a printables.com model link (Add → From Printables link). The app lists the model's STL and
  3MF files with Printables' preview images, file sizes, author and licence; pick files and import.
- Downloads show progress and can be cancelled; downloads over 50 MB on mobile data ask first.
- 3MF files are imported as geometry only for now; full 3MF support is coming soon. Other file
  types (STEP, ZIP, G-code, …) are skipped, and a model without STL files explains why.
- Importing onto a non-empty plate asks whether to replace or add; parts larger than the build
  volume ask whether to scale down or keep their size.

### Multiple beds
- Imports, copies and Arrange put objects that don't fit onto additional beds.
- Bed switcher with chips, ‹ › arrows and an n/m indicator on Prepare and Preview.
- Add and delete beds, move objects between beds, arrange all beds or only the current one.
- Slicing works per bed; each bed keeps its own result, time and G-code file.

### Notes
- Printables has no official public API; the app uses the GraphQL API the Printables website
  uses, anonymously (no Printables/Prusa login). Paid models can't be imported.

### Testing
- Device tests now run on Android 11 and Android 15 emulators, including a UI test that imports
  a real Printables model and checks the Import button stays on screen.
- A non-blocking CI check talks to the live Printables API so changes on their side show up early.

## 0.1.0 — first preview

First public preview of OrcaPhone, a native Android slicer inspired by OrcaSlicer.

### Slicing
- Kotlin slicing engine: walls with seam control, top/bottom shells, ironing, six infill patterns
  (grid, rectilinear, triangles, cubic, gyroid, concentric), skirt/brim, spiral vase mode
- Supports: **organic tree supports (default)**, tree supports and normal (grid) supports, with
  interface roofs and "build plate only"
- G-code for Marlin, Klipper and RepRapFirmware with retraction, Z-hop, acceleration control,
  volumetric speed limit, cooling (fan + minimum layer time), M73 progress and a look-ahead time estimate

### App
- Prepare: 3D plate with touch controls, STL/3MF/OBJ import (also via "Open with"/"Share"),
  rotate, scale, lay flat, duplicate, auto-arrange, overhang highlighting
- Presets for printer, filament and process with OrcaSlicer-like saving; quick settings sheet;
  OrcaSlicer printer library with 1,600+ printers
- Preview: 3D toolpaths by feature or speed, layer and move sliders, print time/filament/cost summary
- Device: OctoPrint, Klipper (Moonraker) and PrusaLink — status, upload, start/pause/resume/cancel
- Orca Cloud: sign in with an OrcaSlicer account, two-way sync of printer/filament/process presets,
  cloud printer status, sync diagnostics
- Light/dark theme (or follow the system)

### Known limitations
- Not a port of OrcaSlicer's C++ engine; no bridge detection, Arachne walls, multi-material or variable layer height
- OrcaSlicer G-code scripting (`{if …}`) is not evaluated — check start G-code imported from OrcaSlicer presets
- Printing through Orca Cloud and Bambu Lab network printing are not supported
- The release APK is signed with a debug key (fine for sideloading, not for the Play Store)
