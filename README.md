# Kenku Voice

An Android app for playing a kenku in D&D. Type a line and press Enter: every
2–3 words are spoken by a different stolen voice — new speaker, accent and
style — and the whole line is saved as one audio file you can replay or share.

Voices come from **Kokoro**, a free open-source neural voice model, running
entirely on the phone through **sherpa-onnx**. No account, no internet, no
permissions.

- 54 human-sounding voices: American, British, and English spoken with Indian,
  Chinese, Japanese, Spanish, French, Italian and Brazilian accents
- 10 speaking styles (Giant, Menacing, Child, Squeaky, Fast talker…)
- Saved lines, pinning, sharing as WAV

## Install on your phone

1. On your Android phone, open this repository's **Releases** page.
2. Download **KenkuVoice.apk** from the newest release (about 200 MB).
3. Open it. If Android asks, allow your browser to install unknown apps.

Every push to `main` builds a new release automatically. Newer builds install
over older ones and keep your saved lines. Built for 64-bit ARM phones such as
Google Pixel.

## Credits

- Kokoro-82M by hexgrad (Apache-2.0)
- sherpa-onnx by k2-fsa (Apache-2.0)
