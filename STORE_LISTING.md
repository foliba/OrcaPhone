# Google Play listing — checklist and suggested answers

Everything the Play Console asks for, with the files in this repository and suggested answers. The answers
describe what the code does today (Phorca 0.5.3); check them again when features change.

## Store listing (Grow users → Store presence → Main store listing)

| Field | Source |
|---|---|
| App name (≤ 30) | `fastlane/metadata/android/<lang>/title.txt` |
| Short description (≤ 80) | `…/short_description.txt` |
| Full description (≤ 4000) | `…/full_description.txt` |
| App icon 512 × 512 | `…/images/icon.png` |
| Feature graphic 1024 × 500 | `…/images/featureGraphic.png` |
| Phone screenshots (2–8) | run *Actions → Store screenshots*, artifact `store-screenshots-phone` |
| Tablet screenshots (10-inch) | same run, artifact `store-screenshots-tablet` |
| Release notes ("What's new", ≤ 500) | `…/changelogs/<versionCode>.txt` |

Languages: **English (en-US)** as default, **German (de-DE)** as translation. The app itself is English
only; the German description says so.

The texts and graphics are in the layout fastlane and F-Droid read, so they can later be uploaded
automatically. To redraw the icon or feature graphic: `tools/store/` (open the HTML files, or
`node tools/store/render.js tools/store`).

## Store settings

- **Category:** Tools (alternative: Productivity)
- **Tags:** 3D printing, utilities
- **Contact details:** an e-mail address is required and shown publicly. Use a dedicated address rather than
  a private one. Website: <https://github.com/foliba/Phorca>
- **Privacy policy URL:** <https://github.com/foliba/Phorca/blob/main/PRIVACY.md> (also linked in the app
  under ⋮ → About & licences → Privacy)

## App content (Policy → App content)

- **Ads:** No ads.
- **App access:** *All functionality is available without special access.* Optional: note "Signing in is only
  needed for the optional Orca Cloud sync with the user's own OrcaSlicer account; every other feature works
  without an account."
- **Target audience:** 18 and over (keeps the app out of the Families programme; it is a tool for 3D printing).
- **Content rating (IARC questionnaire):** category *Utility, Productivity, Communication, or Other*; no
  violence, sexuality, language, controlled substances, gambling; no user-to-user communication or sharing
  inside the app; no location sharing; no digital purchases. Expected rating: Everyone / PEGI 3 / USK 0.
- **News app:** No. **Government app:** No. **Financial features:** None. **Health:** None.
- **Data safety:** see below.

## Data safety (suggested answers)

Phorca has no server of its own and sends nothing to the developer. Data leaves the device only to services
the user chooses to use (see `PRIVACY.md`).

- **Does your app collect or share any of the required user data types?** Yes, because of the optional Orca
  Cloud sign-in:
  - **Personal info → User IDs** (Orca Cloud user ID, user name): *collected*, not shared; **optional**;
    purpose *App functionality* and *Account management*; processed on the device and exchanged with Orca Cloud.
  - **App activity → Other user-generated content** (the printer, filament and process presets synced with
    Orca Cloud): *collected*, not shared; **optional**; purpose *App functionality*.
  - Everything else (location, contacts, files and photos, app interactions, diagnostics, device IDs): not
    collected. Model files and G-code stay on the device or go to the user's own printer at their request.
- **Is all of the user data encrypted in transit?** Yes (HTTPS to Orca Cloud, Printables and GitHub).
  Connections to the user's own printers on the local network are not part of this question.
- **Do you provide a way for users to request that their data is deleted?** Users can sign out in the app;
  data in Orca Cloud is managed with the user's OrcaSlicer account. Answer *Yes* and point to `PRIVACY.md`.
- **Independent security review:** No.

"Collected" in Google's sense means data that leaves the device; the Orca Cloud data does, so it is
declared even though the developer never receives it. Printables and GitHub requests carry no personal
data beyond the IP address that every network request has.

## Release

1. **App signing:** turn on *Play App Signing* and upload the first App Bundle; Google then knows the upload
   key that signs `Phorca-<version>.aab` (repository secrets `PHORCA_KEYSTORE*`).
2. **App bundle:** `Phorca-<version>.aab` from the GitHub release.
3. **Closed test first:** new personal developer accounts need a closed test with at least 12 testers for
   14 days before production access.
4. **Production:** then apply for production; review usually takes a few days.
