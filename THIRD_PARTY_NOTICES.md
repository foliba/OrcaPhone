# Third-party notices

OrcaPhone is licensed under the GNU Affero General Public License v3.0 (see [LICENSE](LICENSE)).
It includes or links the following third-party components. The same list, with the full licence
texts, is shown in the app under ⋮ → *About & licences* (texts in `app/src/main/assets/licenses/`).

| Component | Copyright | Licence |
|---|---|---|
| OrcaSlicer system profiles (printers, filaments, processes), bundled as `slicer-core/src/main/resources/orca/system_presets.json.gz` | SoftFever and the OrcaSlicer contributors; profiles derived from Bambu Studio (Bambu Lab) and PrusaSlicer (Prusa Research) | AGPL-3.0 |
| Kotlin standard library, kotlinx.coroutines, kotlinx.serialization | JetBrains s.r.o. and contributors | Apache-2.0 |
| AndroidX (Core, Activity, Lifecycle, WebKit) and Jetpack Compose (UI, Foundation, Material 3) | The Android Open Source Project | Apache-2.0 |
| Material Icons | Google LLC | Apache-2.0 |
| OkHttp, Okio | Square, Inc. | Apache-2.0 |
| JTS Topology Suite | Vivid Solutions, the Eclipse Foundation and contributors | EDL-1.0 (chosen from EDL-1.0 / EPL-2.0) |

Notes

- OrcaPhone's slicing engine is an independent Kotlin implementation. It follows OrcaSlicer's
  settings and concepts (for example organic tree supports) but does not contain OrcaSlicer source code.
- OrcaPhone is not affiliated with or endorsed by OrcaSlicer / SoftFever, Prusa Research, Printables or
  Bambu Lab. Names are used only to describe compatibility.
- Models imported from Printables keep the licence chosen by their authors; the app shows it before
  importing. Respect it when printing or sharing (for example attribution or non-commercial terms).
