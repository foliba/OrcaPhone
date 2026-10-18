# Changelog

## Unreleased

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
