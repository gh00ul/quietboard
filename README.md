# Quietboard

A Gboard-style Android keyboard with no telemetry. The app has **no INTERNET permission**, no
Google/AndroidX/analytics libraries, and opts out of cloud backup. Everything it learns stays in
its private storage on your phone.

## Features
- Gboard layout and look, Material You colors (or light/dark), optional number row
- Suggestions + autocorrect from a bundled 104k-word list (incl. modern words and brands); backspace
  undoes a correction. Measured: 93.5% of common-word typos fixed (`DictionaryBenchmark`)
- Next-word predictions from day one (common phrases), then from your own typing
- Glide typing with trail, space-bar cursor control, swipe-left-on-backspace to delete words
- Long-press accents/digits/symbols, emoji panel with recents, 1-hour local clipboard history
- Crisp per-key haptics (lighter for repeats and cursor steps, firmer for space/enter), or the phone's
  own keyboard haptics
- Number, phone and date/time pads, email/URL keys, smart enter key, double-space period, auto-caps
- Light, dark, black (OLED) and Material You themes; TalkBack support
- Never learns (words or emoji) from password or incognito fields; clipboard history skips clips marked
  sensitive, images, and copies made while a password/incognito field is active (best effort)

## Download
Get the latest APK from [Releases](https://github.com/gh00ul/quietboard/releases/latest), open it on your
phone and allow the install. Every release is signed with the same key, so new versions install over old ones.

## Build
Push to GitHub and the included Action builds `app/build/outputs/apk/release/app-release.apk`
(Actions tab → latest run → Artifacts; it is `app-release-unsigned.apk` if the signing secrets below
aren't set). Or locally: `./gradlew assembleRelease`.

### Signing
The signing key is **not** in the repo: anyone holding it can build an "update" that installs over
your keyboard. Every build that should update an installed copy must use the same key:

- **Your PC:** create `keystore.properties` next to this README (it is git-ignored):
  ```
  storeFile=../signing/quietboard.jks
  storePassword=...
  keyAlias=quietboard
  keyPassword=...
  ```
  `storeFile` is relative to this folder; keep the `.jks` outside the repo.
- **GitHub Actions:** add repository secrets `QB_KEYSTORE_BASE64` (the `.jks`, base64-encoded),
  `QB_KEYSTORE_PASSWORD`, `QB_KEY_ALIAS` and `QB_KEY_PASSWORD`. Without them the release APK is
  built unsigned.

Without a key, debug builds use the default Android debug key (they can't update a release install).

## Setup
Install the APK → open Quietboard → step 1 (enable) → step 2 (switch).

## Credits
- Word list: AOSP LatinIME dictionary (Apache-2.0) blended with FrequencyWords / OpenSubtitles 2018
  frequencies (CC BY-SA 4.0); see `tools/build_words.py`. The bundled `words_en.txt` is a derived work
  under CC BY-SA 4.0.
- Font: Google Sans (SIL Open Font License 1.1, `app/src/main/assets/fonts/OFL-GoogleSans.txt`).
