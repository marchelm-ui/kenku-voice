# Kenku Voice

An Android app for playing a kenku in D&D. Everything runs on the phone: no
account, no internet, no subscription.

## Three tabs

**🗣️ Speak** – Type a line and press Enter. It is split into natural 2–3 word
snippets and each one is spoken by a different stolen voice, blended together and
streamed so it starts talking in under a second. Add sound effects mid-sentence
with **+ Sound** (for example `Pay up [coins] or else`). Every line is saved for
instant replay, re-voicing, pinning and sharing; pinned lines become one-tap
**⚡ Quick lines** at the top.

**🎭 Clones** – Record about 10 seconds of someone talking (or import an audio
file), give the voice a name, and your kenku can mimic them. Each clone can be
tested, renamed, deleted and switched in or out of the voice mix. Only clone
people who are happy for you to.

**🔔 Sounds** – A medieval soundboard with 467 sounds in 11 categories: Doors &
Locks, Creaks & Wood, Coins & Treasure, Tavern & Food, Home & Workshop, Weapons &
Armor, People, Animals, Monsters, Magic, and Nature & Weather. Tap to play, tap
again to stop, hold for loop / favorite / use in a line, search by name, and set
the volume. **🦜 My Mimicry** records real sounds at the table (a laugh, a door,
a friend's catchphrase) to replay later or drop into lines, just like a kenku.

## Install on your phone

1. On your Android phone, open this repository's **Releases** page.
2. Download **KenkuVoice.apk** from the newest release (large: about 600 MB).
3. Open it. If Android asks, allow your browser to install unknown apps.

Every push to `main` builds a new release automatically, after an ARM voice test
checks all voices, cloning and speed. Newer builds install over older ones and
keep your saved lines and clones. Built for 64-bit ARM phones such as Google Pixel.

## Credits and licenses

- Kokoro-82M by hexgrad (Apache-2.0) – built-in voices
- Pocket TTS by Kyutai, ONNX export by KevinAHM – voice cloning (non-commercial license)
- sherpa-onnx by k2-fsa (Apache-2.0) – on-device speech engine
- Sound effects: CC0 (public domain) packs from OpenGameArt.org, listed in
  `app/src/main/assets/sfx/CREDITS.txt`
