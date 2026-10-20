# Engineering log

A running record of each improvement cycle: what was found with fresh eyes, what
shipped, and what was learned. Newest first.

## Cycle 10 — the intelligibility contract is now law (v4.10, 2026-10-20)

**Fault found:** the clear-mode clamps — the fix for the one fault Marcus ever
reported by ear ("hard to hear what some voices are saying when pitched too
high or too low... or sped up too quickly") — were four magic numbers inline in
`VoiceEngine.speak()`, untestable behind an Android dependency and a hidden
`Random`. Nothing stopped a future style or a careless edit from tuning a voice
right back past understanding.

**Shipped:** `StyleTuning` (pure, injectable Random): named clamp constants and
the full pitch/rate/speed derivation, with `VoiceEngine.speak()` rewired onto
it. 7 tests sweep every catalog style × smooth × wild × 50 seeds and pin: clear
mode can never leave 0.88–1.16 pitch / 0.86–1.12 rate, wild stays inside its
own bounds, model speed stays in its sweet spot, every catalog style's base
values already sit *inside* the clamps (so clamping never distorts a design),
natural stays within jitter of the voice's own sound, smooth jitters less than
lively, and tuning is seed-deterministic.

**Learned:** the most important numbers in the app were the least protected.
When a user-reported fault gets fixed by constants, promote the constants to a
named, tested contract in the same change — otherwise the fix is one refactor
away from silently unhappening.

## Cycle 9 — the sound catalog can no longer lie (v4.9, 2026-10-20)

**Fault found:** assets/sfx/catalog.json drives the whole Sounds tab, yet
nothing checked it against the 467 ogg files it points at. An entry whose file
vanishes plays *silence at the table with no error*; an ogg the catalog forgot
ships as dead weight in a 600 MB APK. Audited today: all 467 entries resolve —
the fault class, not a live fault. Guarded anyway, because a catalog edit is
exactly the kind of change a future cycle will make casually.

**Shipped:** `CatalogSanityTest` (4 tests): every entry's file exists; every
ogg on disk is referenced (no orphans); ids and category keys unique, labels
non-blank, durations sane, paths sfx/-rooted; and each entry's `dur` roughly
agrees with its file's size (sub-half-second clips exempt from the upper bound
— the ogg header dominates them, which the audit itself discovered).

**Learned:** data files are code without a compiler. Anything the app *reads
to decide behavior* deserves the same guard a function gets — this is the
fixture lesson from cycle 4 applied to content instead of benchmarks.

## Cycle 8 — lint becomes a zero-warning gate (v4.8, 2026-10-20)

**Fault found:** the CI lint step was "report only" — 36 warnings were being
published to a branch nobody reads. 31 were i18n noise (the app is English-only
by design), but buried in the noise sat real signals: a static-Context flag on
`VoiceEngine.shared`, two stale androidx versions, and the unflagged guarantee
that any *future* real warning would drown just the same.

**Shipped:** `lint { warningsAsErrors = true }` with three checks disabled, each
carrying its product reason in the build file (English-only; arm64/Pixel-only;
targetSdk bump backlogged pending a visual check). The StaticFieldLeak flag is
suppressed at the one field with proof it's safe (only the application context
is ever stored). The whole toolchain moved to the newest stable line in one go:
Gradle 8.7→8.14.3, AGP 8.5.2→8.13.0, Kotlin 2.0.21→2.1.21, compileSdk 34→36
(compile-time only; targetSdk stays 34), core-ktx 1.13.1→1.17.0, appcompat
1.7.0→1.8.0. The CI step now fails the build on any lint finding and still
publishes the report first.

**Learned:** a warning list that can only grow is a warning list nobody reads.
Drive it to zero once, then make zero the contract — same lesson as cycle 2's
"a benchmark that only prints is decoration", one layer up. Also: the first
push rode core-ktx 1.19.1, which demands the AGP 9 *preview* — "newest" and
"newest stable" are different upgrade policies; this app rides the latter. And
the new toolchain's lint immediately earned its keep: its UseKtx check found
six pre-KTX `prefs.edit().apply()` call sites, all converted to `edit {}`.

## Cycle 7 — pin a phrase + the line list becomes pure (v4.7, 2026-10-20)

**Fault found:** two in one place. (a) The phrasebook could only dump a phrase
into the text box — getting "Roll for initiative!" onto the ⚡ Quick row took
four manual steps mid-game. (b) The saved-lines bookkeeping (JSON round-trip,
the 80-line eviction) lived untested inside `SpeakTab`, and the eviction rule
that must never break — *a pinned line is never deleted* — was enforced by
nothing but hope.

**Shipped:** `LineBook` (pure): JSON round-trip, `pin(text)` (stars an existing
line, keeping its cached audio, or inserts a new starred one), and `evict()`
returning the dropped ids whose audio files the caller deletes. Tapping a
phrasebook phrase now asks: put it in the text box, or ⚡ pin it as a Quick line
(it bakes its audio on first tap). 7 new tests pin the round-trip, corrupt-JSON
safety, eviction order and id reporting, the never-evict-pinned rule (even at
cap 0), the exact-cap boundary, and pin dedupe. Real `org.json` added to the
test classpath — the SDK stub throws in JVM tests.

**Learned:** state that lives in an Activity is state nobody tests. This was
the last pure-logic island left inline; Speak-tab persistence is now covered
end to end.

## Cycle 6 — releases stop hoarding gigabytes (v4.6, 2026-10-20)

**Fault found:** every green build published a ~450 MB release and none was ever
deleted — 19 releases, 8.6 GB, growing forever. Marcus only ever installs the
latest; the rest is dead weight on his GitHub account.

**Shipped:** a workflow step right after publishing deletes every release (and
its tag) beyond the newest 3, then *asserts* at most 3 remain — the prune is a
gate, not a hope. Three stay so a bad build can be rolled back two steps.

**Learned:** infrastructure accumulates faults too, not just code. "Fresh eyes
on the whole app" includes the repo around it.

## Cycle 5 — the clone recorder judges its input (v4.6, 2026-10-20)

**Fault found:** the Clones tab accepted any recording with 3 s of speech and
said "listen back and save" — no matter how much room noise or mic clipping it
carried. A bad sample poisons *every* line that clone ever speaks, and the user
only discovers it after naming and saving, when re-recording feels expensive.

**Shipped:** `SampleQuality` (pure object): per-frame RMS analysis gives
speech-seconds, SNR (quietest-tenth noise floor vs loudest-third voice level,
the same convention the loudness matcher uses), and clipped-sample percentage.
Verdicts: >1 % clipped → "hold the phone farther away"; <14 dB SNR → "a quieter
room clones much better"; else a clean bill with the measured headroom. Wired
into `acceptSample` as a warn-only hint — the user can still save, because a
noisy clone of the right voice beats no clone. 5 new tests with synthetic
recordings (clean / noisy-room / clipped square / overload-vs-noise precedence /
tiny-input safety) pin the thresholds.

**Learned:** warn-only is the right strength for quality gates on *user*
material — hard gates belong on our own code (CI), not on their living room.

## Cycle 4 — the Python chunker mirror is dead (v4.5, 2026-10-20)

**Fault found:** `tools/voice_test.py` carried a hand-written Python copy of the
Kotlin chunker (leaner list, DP splitter, spoken-text rules) purely to build the
CI benchmark line. Cycle 1 flagged the drift risk; cycle 1's own quote fix had
already made the copies diverge.

**Shipped:** the benchmark now reads `tools/benchmark_chunks.txt`, a committed
fixture of the line exactly as the app chunks it. ~45 lines of mirror deleted.
`FixtureSanityTest` (3 tests) guards the fixture: it must say exactly the
benchmark text, be shaped by the app's own punctuation/size rules, and the real
Chunker must agree across seeds. The DSP mirror (`trim`/`match`) remains, by
design: it gates the models, and the Kotlin DSP has its own JVM tests.

**Learned:** when two languages must agree, don't synchronize code — commit the
*artifact* one side produces and make both sides answer to it.

## Cycle 3 — the stitcher becomes pure and tested (v4.4, 2026-10-20)

**Fault found:** the crossfade/gap assembly — the single most audible piece of
logic, the thing "seamless switching" literally is — lived inline in
`Performer.perform()` next to threading and engine calls, untestable.

**Shipped:** extracted `Stitcher` (pure: crossfades, punctuation gaps, sound
splices, final fade) and rewired `Performer` onto it. 8 new tests pin exact
overlap lengths, true-silence gaps, sample-continuity (no clicks even joining
anti-phase tones), padded sound splices, faded endings, tiny/empty-snippet
safety, and energy conservation. Performer shrank by ~30 lines.

**Learned:** the inline version also shadowed the `LineEffect fx` variable with
a destructured `(fx, name)` in the sound branch — legal Kotlin, but exactly the
kind of trap that bites a future edit. Extraction removed it. Pure-core,
thin-shell is now the house style: new audio math starts life in a testable
object, never inline.

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

- Self-scheduled loop: a wake-up fires every 5 hours; each cycle picks the top
  backlog item, ships it gated, logs here, and re-arms the next wake-up.
- targetSdk 36 + edge-to-edge layout (required for any future Play release).
- Accessibility audit: TalkBack labels exist, but ordering/grouping untested.
