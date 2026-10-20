# Engineering log

A running record of each improvement cycle: what was found with fresh eyes, what
shipped, and what was learned. Newest first.

## Cycle 2 — regression gates + crash path (v4.3, 2026-10-20)

**Fault found:** the CI voice test *measured* quality (loudness, speed, latency)
but asserted almost none of it, so a quiet regression would ship silently. And a
crash on a real phone left no trace at all — the one fault class the build
machines can never see.

**Shipped:**
- CI now fails if any of the 54 voices falls outside the 0.075–0.135 loudness
  band after the clarity chain, if generation RTF exceeds 0.85, or if a 50-word
  line would take over 3 s to start speaking.
- `CrashReporter`: uncaught exceptions are written to a capped report; the next
  launch offers one-tap sharing. Formatter is unit-tested (content + size cap).

**Learned:** a benchmark that only prints is decoration. Convert every printed
number the team cares about into an assertion with headroom (measured 0.60 RTF →
0.85 budget; measured 0.41 s start → 3 s budget).

## Cycle 1 — unit test suite + quote-blind punctuation (v4.2, 2026-10-20)

**Fault found:** all pure logic (Chunker, AudioDsp, LineEffect) had zero JVM
tests — the Python CI mirror exercises the *models*, not the Kotlin that ships.
Writing the tests immediately surfaced a real bug: a chunk ending in a closing
quote (`bird."`) was treated as unpunctuated, so the voice model received
doubled punctuation (`bird.".`).

**Shipped:** 29 unit tests run by `:app:testReleaseUnitTest` on every build:
split losslessness, chunk-size bounds, [sound]-tag ordering, spoken-text
punctuation (quotes included), WAV round-trips, band-limited pitch-shift
anti-aliasing, trim bounds, loudness targets and clamps, polish boundedness,
equal-power crossfades, streamed-effect stability. Plus the quote fix.

**Learned:** the first test file written against "obviously correct" code found
a shipping bug within minutes. The Python mirror in `tools/voice_test.py` can
drift from the Kotlin; the JVM tests are the authority on the Kotlin side, the
Python side is the authority on the models.

## Backlog (next cycles)

- targetSdk 36 + edge-to-edge layout (required for any future Play release).
- Eliminate the Python/Kotlin chunker mirror drift (golden fixtures both read).
- Accessibility audit: TalkBack labels exist, but ordering/grouping untested.
- Phrasebook lines could feed Quick lines directly ("pin a phrase").
- Clone quality meter: warn when a sample is too noisy before saving.
