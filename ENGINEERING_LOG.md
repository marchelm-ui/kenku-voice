# Engineering log

A running record of each improvement cycle: what was found with fresh eyes, what
shipped, and what was learned. Newest first.

## Cycle 21 — TalkBack hears what matters, not the meters (v4.21, 2026-10-20)

**Fault found:** the backlog's accessibility audit was parked as "needs human
testing" — but half of it doesn't. Code inspection found the gaps directly: the
Speak status line and the clone recorder's verdict changed silently (a TalkBack
user never heard "Stealing voices… 3 of 9" or "Got 9 seconds of speech"), while
the three decorative level/progress meters *were* focusable noise.

**Shipped:** `accessibilityLiveRegion = POLITE` on the two lines whose changes
are the information (generation status, recording verdict);
`importantForAccessibility = NO` on the three meters whose information lives in
the text beside them. Audited the rest: every button carries real text, fields
have hints, the volume slider and tab bar were already labeled. What still
needs a human with TalkBack on — focus order, gesture flow — stays in the
backlog, now precisely scoped.

**Learned:** "needs human testing" is often two items fused: the part a code
audit settles today and the part that truly needs ears. Split them and ship
the first half.

## Cycle 20 — the phrasebook is data too (v4.20, 2026-10-20)

**Fault found:** after cycle 9 guarded the sound catalog, one data-as-code
surface remained bare: the 62 phrasebook phrases, which are one-tap content —
pinnable straight to Quick lines since cycle 7. Nothing stopped a future edit
from adding a 30-word monologue, a duplicate, an unpunctuated fragment the
voices would mis-read, or a phrase with a [tag] the picker can't preview.

**Shipped:** `PhrasebookSanityTest` (4 tests): sections named, iconed,
non-empty; no duplicate phrases; every phrase 1–12 words, cleanly ended, tag
free; and every phrase splits losslessly into model-sized chunks across seeds.
README gains a "How quality is kept" section pointing here.

**Learned:** cycle 9's rule ("anything the app reads to decide behavior gets a
guard") applies to things the app *offers the user to say*, too — content is
an interface.

## Cycle 19 — the founding promise gets its tests (v4.19, 2026-10-20)

**Fault found:** "a different voice and accent every 2–3 words" — the original
request the whole app grew from — was enforced by `Performer.pick()`, private,
on a hidden `Random.Default`, tested by nothing. The no-repeat rule and the
group-fairness rule (so twenty American voices don't drown two British ones)
were one refactor from silently unhappening, exactly like cycle 10's clamps.

**Shipped:** `VoicePick` (pure, injectable Random) with `pick` and `groupOf`
moved out of Performer. 6 tests pin: no speaker repeats, no accent repeats when
another exists, each accent group lands within 3 % of an equal share over
15 000 picks regardless of group size, a clone counts as a group of its own
(half the picks against three builtin voices), a lone speaker may repeat, and
two voices in one accent alternate without deadlock.

**Learned:** the oldest behavior in an app is the likeliest to be untested —
it predates the test suite, and nothing since has forced it under one. Audit
the founding features first, not last.

## Cycle 18 — releases say what changed (v4.18, 2026-10-20)

**Fault found:** every release carried the same generic blurb, so from the
phone there was no way to tell build 39 from build 22 — seventeen cycles of
fixes, invisible at the point of install. The information existed all along,
in this log.

**Shipped:** `tools/release_notes.py` builds the release body from this log's
newest entry (title + its Shipped paragraph), so the GitHub release page now
documents each build. CI asserts the parser found an entry — header-only notes
fail the build rather than shipping silently degraded.

**Learned:** a record nobody can see from where they stand isn't a record,
it's an archive. Surface the log at the point of use and it starts paying
rent.

## Cycle 17 — the sound cache stops growing forever (v4.17, 2026-10-20)

**Fault found:** `SfxPlayer.preload` decoded each on-screen category into
SoundPool and *never evicted anything* except on delete. Browsing all eleven
categories in one session accumulates ~460 decoded PCM buffers in memory — next
to a 310 MB voice model on a phone. Unbounded growth, invisible until the OS
kills the app mid-game.

**Shipped:** preload now evicts every decoded sound that is neither in the
category coming on screen nor currently playing (`SfxCache.toEvict`, pure),
also clearing its ready/pending bookkeeping. 4 tests pin the invariants:
switching categories drops the old one, a playing sound is never cut, re-showing
the same category evicts nothing, and a simulated browse of all 11 categories
keeps the cache bounded to one category plus playing sounds.

**Learned:** every cache needs a story for how things *leave* it, told at the
moment it's written. This one's story was "the user deletes a recording" —
true for one sound in five hundred.

## Cycle 16 — search stops rebuilding the world per keystroke (v4.16, 2026-10-20)

**Fault found:** the saved-lines search rebuilt every card view on *every
keystroke* — with the 80-line cap that's hundreds of views inflated per typed
word, on the main thread, mid-session. The filter/sort logic itself (word-AND
match, pinned-first) was inline in `SpeakTab`, untested like the lines logic
was before cycle 7.

**Shipped:** `LineBook.visible(lines, query)` (every query word must match,
pinned first, saved order within groups) and `LineBook.renderKey` (a
fingerprint of what the cards would show). `renderCards` now skips entirely
when the key is unchanged, and the search box re-renders 150 ms after the user
pauses instead of per keystroke. 3 new tests pin matching, ordering, and that
the key changes whenever anything a card displays changes (so the skip can
never show stale voices or a stale pin).

**Learned:** the skip-if-unchanged optimization is only safe because the
fingerprint covers *everything rendered* — the test asserting that is the real
deliverable; the speedup is just its side effect.

## Cycle 15 — the start gate is a contract, and the APK has a size band (v4.15, 2026-10-20)

**Fault found:** the streamed-playback start decision — the exact math behind
"50 words or less replies within seconds" — was five lines of inline estimator
in `Performer.perform()` with an unexplained `* 1.15` and `>= 0.3`. The CI
benchmark asserts the *outcome* on a fast ARM runner, but nothing pinned the
*decision logic* for the slow cases a phone under load actually hits. Also: the
APK's existence was checked, its size wasn't — a dropped model (−310 MB) or a
duplicated asset dir would ship.

**Shipped:** `StartGate` (pure): named HEADROOM and MIN_LEAD_SEC constants, the
estimator formula, and guards for the zero cases. 6 tests: fast generation
(benchmark RTF 0.6) starts on the first chunk, slow generation (RTF 1.4) waits
until the remainder is affordable, the last chunk always starts, zero words /
zero audio never divide, the decision never flips back as buffering grows, and
the headroom holds back a chunk at break-even RTF 0.95 where the raw math says
go. CI now also asserts 400 MB < APK < 700 MB.

**Learned:** the benchmark asserting "wait < 3 s on this runner" and the gate
deciding "start now on this phone" are different guarantees — one is a
measurement, the other is logic, and logic can be pinned exactly.

## Cycle 14 — table talk is the real input format (v4.14, 2026-10-20)

**Fault found:** the app's own fallback message — "try plain words and avoid
unusual symbols" — admits an untested input class: dice notation, ALL CAPS,
"What?!?!", emoji, pasted tabs/newlines, 30-letter names. Testing it found the
cycle-1 quote bug's cousin: an ellipsis word (`think…`) wasn't recognized as
punctuation, so the model received `think…,` doubled up, and `…` was never a
preferred break point either.

**Shipped:** `…` joins the punctuation sets in `Chunker.spoken` and the break
cost. `ChunkerRobustnessTest` (4 tests): ten lines of real table talk split
losslessly across seeds, every spoken piece stays punctuated and 1–4 words,
ellipses never double-punctuate, blank input yields nothing while symbol soup
survives. CI gains a table-talk canary: nine tricky inputs (digits, caps,
interrobangs, ellipses, quotes-in-parens, a 34-letter name, emoji-only) run
through the real Kokoro model — a crash fails the build, and anything with
words must produce audible speech.

**Learned:** the error message a developer writes to excuse a fault is a test
plan in disguise. "Avoid unusual symbols" told me exactly what to feed the
tests — and one symbol was indeed broken.

## Cycle 13 — surviving recreation at sunset (v4.13, 2026-10-20)

**Fault found:** the activity recreates on any uiMode change — which happens
*daily*, mid-session, when the Pixel's scheduled dark mode flips at sunset. Two
casualties: (1) the half-typed line died, because the input field's id came
from `View.generateViewId()` — a *different* id every recreation, so Android's
automatic view-state restore could never match it; (2) `ioThread` was
per-activity and shut down in `onDestroy`, so a clone save queued just before
recreation could land on a dead executor — `RejectedExecutionException`, crash.

**Shipped:** stable ids (`R.id.speak_input`, plus the two search boxes) so the
platform restores the typed line, selection and filters for free; `ioThread`
promoted to a process-wide executor like the engine thread, shutdown removed.
Guard: the platform's own view-state contract — the id resources — rather than
JVM tests, which can't see an Activity; noted here so the pattern (never
`generateViewId()` on a view whose state matters) is on the record.

**Learned:** `configChanges` in the manifest covered rotation, which made
recreation *feel* handled — but uiMode wasn't in the list, deliberately, since
dark mode must re-resolve colors. When recreation is sometimes wanted, state
restore has to work, and unstable ids disable it silently.

## Cycle 12 — one authority for "how much speech is that" (v4.12, 2026-10-20)

**Fault found:** `CloneStore.speechSeconds` (fixed 0.02 threshold) and
`SampleQuality.assess` (adaptive threshold) measured the same quantity two
different ways — the clone recorder's hint could call a take 8 s of speech
while its quality meter saw 6. And `CloneStore.prepare`, which every cloned
voice's reference audio flows through, had no tests at all.

**Shipped:** `speechSeconds` deleted; both callers (clone recorder, mimic
recorder) now read `SampleQuality.assess().speechSeconds`. Consolidating
exposed a real edge: a *continuous* mimicked sound (coin jingle — no pauses)
has noise floor ≈ signal, so the adaptive threshold collapsed its audible
seconds to zero and would have rejected a good recording; the threshold is now
capped at half the voice level, with a steady-tone regression test. 5 new
`CloneStoreTest`s pin prepare(): silence trimmed, 20 s cap, level-matching to
the model's 0.09 target (and the deliberate 0.35× attenuation clamp on very
loud takes), click-free faded edges, no clipping.

**Learned:** two implementations of one concept aren't redundancy, they're two
*answers*. Consolidation isn't just hygiene — forcing one authority is what
surfaced the continuous-sound edge neither copy handled.

## Cycle 11 — the sound matcher is pure, and its crash is dead (v4.11, 2026-10-20)

**Fault found:** a real latent crash, found the moment the matcher was read with
the intent to test it: `SfxLibrary.find`'s category pass did
`byCat.random().sounds.random()`, so a tag matching an *empty* category —
`[mimicry]` with nothing recorded yet, exactly a new user's first experiment —
threw `NoSuchElementException` and killed the line. The whole four-pass
matching ladder (exact label → all-words-in-label → category → any-word) was
also untestable behind a Context.

**Shipped:** `SfxMatch` (pure, injectable Random) with the crash fixed — an
empty category is skipped and the tag falls through to the loose pass.
`SfxLibrary.find` is now a two-line shell. 7 tests: ladder precedence,
singular/plural, the empty-category regression (both with and without a
recording), quiet nulls for nonsense, and — against the real catalog.json —
every one of the 467 labels and all 11 category keys must resolve, since the
"Any <category> sound" picker inserts bare keys as tags.

**Learned:** cycle 3's shadowed-variable find repeats: making code testable
keeps finding bugs *before* the tests run. The crash lived in the one branch
no happy path exercises — empty collections are where untested code goes to
throw.

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
- Accessibility: labels and live regions done (cycle 21); focus order and
  gesture flow still need a human with TalkBack on.
