# Quietboard

A Gboard-style Android keyboard with no telemetry. The app has **no INTERNET permission**, no
Google/AndroidX/analytics libraries, and opts out of cloud backup. Everything it learns stays in
its private storage on your phone.

## Features
- Gboard layout and look, Material You colors (or light/dark), optional number row
- Suggestions + autocorrect from a bundled 104k-word list (incl. modern words and brands); backspace
  undoes a correction. Measured: 93.5% of common-word typos fixed (`DictionaryBenchmark`)
- A built-in phrase model (which words follow the one or two before, counted from ~940 million words
  of film and TV dialogue) drives next-word predictions, completions, autocorrect and glide typing,
  blended with your own habits, including three-word phrases you repeat ("ok sounds" -> your word,
  not the usual one). On everyday sentences it never saw, the next word is in the strip
  before you type a letter 29.5% of the time (12.4% in 1.8.1), 59.8% of letters never need typing
  (44.8%), and typos are fixed 96.2% of the time with 3.6% wrong fixes (92.9% / 6.8%;
  `PredictionBenchmark`)
- Glide typing with a live preview of the word while you swipe, tuned on simulated sloppy swipes of
  the 1,500 most common words (79.4% exactly right on words it was not tuned on, 92% in the strip;
  `GlideBenchmark`), using the words before to settle ties (88.7% exactly right on words in everyday
  sentences, 82.3% in 1.8.1) and gliding words you've taught it
- Foldables and tablets: on a big screen (an unfolded Galaxy Z Fold, 600 dp both ways) letters and
  symbols split into two phone-sized halves at the edges, one for each thumb, with a space bar in each.
  Glides across the gap decode as on one keyboard; a palm resting in the gap types nothing. One-handed
  mode stays phone-sized there. Folding or unfolding switches on its own; Settings → Keys → Split
  keyboard turns it off, or on for phones in landscape too
- Space-bar cursor control, swipe-left-on-backspace to delete words
- Long-press accents/digits/symbols, emoji panel with recents, 1-hour local clipboard history
- Crisp per-key haptics (lighter for repeats and cursor steps, firmer for space/enter), or the phone's
  own keyboard haptics
- Number, phone and date/time pads, email/URL keys, smart enter key, double-space period, auto-caps
- Light, dark, black (OLED) and Material You themes, plus ten fun ones: Halloween, Christmas, Neon,
  Sakura, Ocean, Retro terminal, Galaxy, Autumn, Valentine and New Year. Each is a whole scene behind
  glass keys, with gentle motion while you type (snow, petals, bubbles, fireworks; it pauses when you
  stop, and Scene motion turns it off), its own swipe trail, and tap sparkles if you turn them on. Seasonal follows the
  calendar: New Year, Valentine's, spring, summer, Halloween, autumn and Christmas. TalkBack support
- Your email addresses and street addresses in the suggestion strip as you start typing them: right
  away in email and address fields, and once it's clearly them elsewhere (three letters on the right
  of the strip, or the whole strip after an "@"). Tap to type the rest. Save them in Settings → Typing,
  or just type them once in an email or address field and they're remembered (whole ones only; switch
  it off in Settings, long-press one in the strip to forget it). Kept with your text shortcuts on this
  phone, never on the lock screen, never learned from password or incognito fields
- Logins from your password manager (Proton Pass, Bitwarden, 1Password...) in the suggestion strip on
  Android 11+. The system draws them and fills the fields; the keyboard never sees them
- Works right after a reboot, before the first unlock (Direct Boot): only settings live in the storage
  that's readable then; learned words, shortcuts and clipboard history open once you unlock
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
- Phrase model: counts of word pairs and triples from OpenSubtitles 2018 (opensubtitles.org, via OPUS:
  P. Lison and J. Tiedemann, 2016); see `tools/BuildPhrases.java`. Only counts ship in the app, never
  sentences.
- Benchmark sentences: Tatoeba (tatoeba.org), CC BY 2.0 FR (`app/src/test/resources/sentences_en.txt`).
- Font: Google Sans (SIL Open Font License 1.1, `app/src/main/assets/fonts/OFL-GoogleSans.txt`).
