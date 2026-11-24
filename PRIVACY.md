# Phorca privacy policy

*Last updated: 24 November 2026*

Phorca is a 3D-printing slicer for Android. It is free, open-source software (GNU AGPL-3.0), developed
independently and published at <https://github.com/foliba/Phorca>.

**Short version:** Phorca has no server of its own, no analytics, no advertising, no tracking and no crash
reporting. Your models and G-code stay on your device unless you send them somewhere yourself. The app talks
only to the services listed below, and only for the features you use.

## What stays on your device

- Models you import, plates, sliced G-code, your printer, filament and process presets, and the app's
  settings. They are stored in the app's private storage and removed when you uninstall the app.
- Connection details of your printers (address, API key or password), stored on the device.
- If you sign in to Orca Cloud: your sign-in tokens, encrypted with a key held in the Android Keystore.

## Services the app connects to

| Service | When | What is sent |
|---|---|---|
| **Your own printers** (OctoPrint, Klipper/Moonraker, PrusaLink) on your network | When you add a printer, view its status or send a print | The G-code you choose to send, status requests and the API key or password you entered |
| **Orca Cloud** (`auth.orcaslicer.com`, `api.orcaslicer.com`, `cloud.orcaslicer.com`), the account service of OrcaSlicer | Only if you sign in with your OrcaSlicer account | Your sign-in on OrcaSlicer's own login page; then your printer, filament and process presets are synced, and your cloud printers' status is read. Phorca receives your user ID, user name/display name and avatar address from it |
| **Printables** (`printables.com`) | When you open a Printables model link | The model link; model files and previews are downloaded |
| **GitHub** (`api.github.com`, `github.com`) | At start-up (can be switched off in Setup) and when you tap *Check now* | A request for the latest OrcaSlicer version; if you choose *Download*, the profile pack is downloaded from Phorca's GitHub releases |

These services receive your device's IP address with each request, as any web request does. Their own privacy
policies apply to what they do with the data: OrcaSlicer/Orca Cloud, Printables (Prusa Research) and GitHub.

All connections to these internet services use HTTPS. Connections to your own printers use whatever your
printer offers, often plain HTTP inside your home network.

## What the developer receives

Nothing. Phorca does not send any data to the developer. If you contact the developer (for example by opening an
issue on GitHub), you share what you write there yourself.

## Your choices

- Orca Cloud is optional; sign out in *Setup* at any time. To delete the presets stored in Orca Cloud or your
  account, use OrcaSlicer or its account service.
- The start-up check for new profiles can be switched off in *Setup → OrcaSlicer profiles*.
- Uninstalling the app deletes everything it stored on your device.

## Children

Phorca is a tool for 3D printing and is not directed at children.

## Changes

Changes to this policy are published in this file; the history is visible in the repository.

## Contact

Questions: open an issue at <https://github.com/foliba/Phorca/issues>.
