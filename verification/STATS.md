# Auxio 4.1.5-stats.1 — implementation and verification

Source branch: `feature/stats-sessions-dashboard`, based on the installed upgrade
branch `upgrade/upstream-4.1.5-data-safe`. Version code **76**; package and signing
identity retained. The user subsequently requested installation. The corrected APK is installed in place on the Pixel 9 Pro and its dashboard startup is verified.

## Listening sessions

`StatsTracker` observes ExoPlayer on its application looper. It samples actual
position while playing at 250 ms intervals and takes ordered boundary samples.
The pure `ListeningSessionMachine` caps media advancement by monotonic elapsed
time, adjusted for playback speed. Seek discontinuities account for the old
position before rebasing. Buffering, paused playback and focus suppression have
no advancing intervals. Paused playback has no polling timer.

Timeline window identity distinguishes queue occurrences. Queue edits and shuffle
preserve the playing occurrence. Explicit selections and natural repeats start
new attempts, including the same song twice. Previous rewinds are seeks.
Overlapping discontinuity/event callbacks are deduplicated. Final-track completion
is sampled before the existing player behavior returns to the queue beginning.

Eight accumulated audible seconds qualify one event. Pause/resume keeps that
attempt; subsequent checkpoints update the same event. The event starts at the
first audible sample's wall-clock time. Five-second advancing checkpoints,
qualification, pauses, seeks, transitions and service shutdown persist progress.
Subthreshold attempts contribute neither a play nor listening time to history.

`ListeningSessionRecorder` is an application singleton with one ordered command
worker, so service replacement cannot overtake shutdown progress. Failed writes
stay queued, show an error in the dashboard, and retry every five seconds. The
repository mutex and Room transactions serialize recording, editing and deletion.
The journal makes checkpoint retries idempotent. Active edits retain the edited
timestamp/duration and add only subsequently committed listening. Deleting an
active event marks its journal suppressed, including across reopen/restoration.

A new process can recover only journaled progress, and only explicit restoration
of the same song resumes it. A fresh selection closes prior unfinished attempts.
No time is inferred while the process is absent. An uncommitted tail can be lost,
including during abrupt shutdown. Persistent storage failure can retain queued
observations in memory until storage recovers or the process exits.

## Database preservation

The 3→4 migration **only creates `ListeningSession`**. It does not rewrite existing
`PlayEvent` or `SongStats` data, IDs, timestamps, UID relationships, or the event
sequence. The existing 2→3 UID migration remains available.

Disposable copies of the original backup are migrated through both 2→3→4 and the
installed 3→4 schema path, closed and reopened. Complete typed row multisets and
SQLite integrity are compared. The source fixture retains **7,100 PlayEvents**,
**883 SongStats**, and **1,167,646,409 ms**. No test database path points at the
master archive or the production installation.

New writes apply count/duration deltas transactionally. Existing inconsistent
aggregates are reported, not silently rebuilt. Dashboard reads use an atomic
snapshot of events and aggregates; overall totals always come from events.

## Dashboard and history

One RecyclerView owns the compact controls, summary, one-month calendar and one
ranking. Songs, Albums and Artists share that ranking; listening time is the
default sort, with a Plays switch. Ten entries precede a See all destination.
Existing CoverView loading and song/album/artist detail destinations are reused.

All existing presets remain, with Today and custom inclusive local dates added.
Queries use exclusive end boundaries derived from local calendar dates. All Time
includes every event, even unavailable songs. Unavailable contributions have their
own filtered history action and are excluded from library rankings. Library UID
aliases are respected; unresolved identities are never guessed.

Daily averages include every date in the period, including inactive days. All Time
extends from the first recorded date through today (or a later recorded event).
Weekly averages are total time divided by the period's days/7, so partial weeks
and inactive days use the same denominator. Live presets refresh across midnight
and timezone changes. Obsolete period subscriptions are canceled.

SavedStateHandle retains period, custom dates, category, sorting, visible month,
and dashboard/ranking scroll states. History retains its loaded limit and scroll.
Room and library changes refresh data without resetting these selections.
History is grouped by local date, with period/day, unavailable and song filters.
Room limits results in increments of 50 with a stable timestamp/ID order.
Edit validation rejects invalid dates, trailing input, negative/invalid duration
components and overflow. Invalid input remains in the dialog with field errors;
failed saves remain retryable. Deletion still requires confirmation.

New labels use the existing English fallback for custom statistics. Material
surface colors follow light/dark themes; text wraps, main controls use at least
48 dp height, calendar days have 48 dp height, and dates/headings expose
accessibility labels. On a 360 dp host layout, calendar columns are approximately
49 dp wide. Smaller displays necessarily have narrower seven-column cells.

## Signed artifact

- APK: `release/Auxio-v4.1.5-stats.1.apk` (version code 76).
- SHA-256: `8e25a4b4148726975dfd38b6ec874150967597a0858b72dd23f29d90144ecb01`.
- Certificate SHA-256: `0a0a80b95b50102b662d3854572f4d85891d0b3b12a5e308bdcb27bd4f164d61`, matching the installed upgrade and original backup APK.
- 150 tests passed: 40 app tests and 110 Musikr tests; zero failures or skips.
- Full release lint reports 68 errors, all in files byte-identical to upstream v4.1.5. Release-critical lint passes.

## Evidence and practical limits

Run `bash tools/stats-check.sh spotlessCheck :app:testDebugUnitTest
:musikr:testDebugUnitTest :app:assembleRelease --offline` from the repository root.
The script selects the existing JDK and disposable migration fixture directory.
It needs access to the existing Gradle/Android caches outside the workspace.

Evidence is in `release/stats-startup-fix-build.log`, `release/stats-build-tests.log`,
`release/stats-lint.log`, and `release/stats-release-verification.json`.
The release verifier checks certificate identity against both archived releases,
package/version, non-debuggable status, APK payload equality, four native ABIs,
16 KiB ZIP alignment, R8 output, tests, and the unchanged master backup checksum.

Host tests cover threshold/checkpoint boundaries, pause/resume, seeks, stalls,
buffering/focus suppression, speed, rapid skips, repeated songs, natural repeat,
queue preservation, final completion, duplicate callbacks, service replacement,
committed recovery and failed-write retry. Room tests cover atomic rollback,
idempotency, active edits/deletion, concurrent writers and filtered pagination.
Dashboard tests cover 7,100 overall vs 6,934 resolved/166 unavailable plays,
library refresh, rapid filters, empty/error/retry states, date/DST/year boundaries,
inactive-day averages and saved selections. Host layout inflation/measurement
checks light/dark themes at 100% and 200% text size. Existing migration, preference,
ReplayGain and Musikr regressions are retained.

The optimized APK is built with R8 and resource shrinking; lintVitalRelease passes.
Full lintRelease retains upstream errors; the verifier requires every error's file
to match the upstream v4.1.5 version. These are recorded without a new lint baseline.

**No emulator or production-data experiment was performed.** Following the user's
installation request, the initial APK crashed because the dashboard header was
inflated before its RecyclerView had a LayoutManager. The original parentless
layout test missed this. A real-parent regression test reproduced the exact
exception, then passed after moving LayoutManager initialization into the XML.
The corrected APK was rebuilt, signed, verified and reinstalled without clearing
data. Its running process was observed after launch; the rendered Stats screen
showed all 7,100 plays and 166 unavailable plays. No startup exception was found
in its captured process log. Package app ID 10425 and first-install time are
unchanged. The phone disconnected before a final extra PID check. See
`release/stats-installation-verification.json` and the screenshot.

Physical TalkBack behavior, real audio callback timing, and full Android backup
restoration remain explicitly unverified. The minified APK's startup and dashboard
rendering are now verified on the actual phone; this is not a full device test suite.
