# Custom Auxio 4.1.5 upgrade — verification record

Status: implementation and signed optimized release build complete; Android recovery validation remains blocked on a test device. **Not yet verified safe to update the real installation.**

## Source and customizations

The working tree was clean at original custom commit `6154404ca` (app 4.0.10,
version code 69). Work uses dedicated branch `upgrade/upstream-4.1.5-data-safe`;
`dev` is retained and existing history is not rewritten. GitHub's latest stable
release was checked on 2026-09-05: [v4.1.5](https://github.com/OxygenCobalt/Auxio/releases/tag/v4.1.5),
published 2026-08-04. Upstream was merged with its Gradle/AGP/Kotlin/dependency
configuration and recursively pinned media, FFmpeg, TagLib and utfcpp submodules.

Custom behavior retained: listening events and per-song statistics; album/artist
aggregates; time-period filters; calendar and daily listening views; editable/deletable
history; the statistics home tab, its persisted integer code and visibility/order;
statistics navigation; playback-service tracking including the original 8-second
minimum and the latest wrong-history-entry fix. The calendar dependency is retained.
Statistics stays excluded from the Android Auto browse tabs. A compressed tab ID
was added for the retained statistics enum to match the upgraded media-browser API.

## Actual persisted data and migration paths

The backup is from the actual signed code-69 custom installation, not an assumed
stock 4.0.10 schema. All database journals, preference files, app-owned CE/DE and
external roots were inventoried. Only generated runtime caches/code caches/library
paths are excluded; absent roots are recorded. No-backup data is included if present.

| Database | Actual old schema → new | Preserved records / relationships |
|---|---|---|
| stats.db | 2 → 3 | 7,100 PlayEvent rows; 883 SongStats; autoincrement sequence; full event fields and totals |
| playback_persistence.db | 38 → 39 | 1 playback state; 14 queue rows; 14 shuffle mappings; position, parent and order |
| user_music.db | 30 → 76 | 2 playlists; 921 song records; 721 memberships, names and order |
| music_cache.db | 67 → 71 | all 921 original rows archived, copied to new cache, original added dates retained |

The history and queue migrations canonicalize old UID string representations to
upstream's compressed format. The playlist migration goes directly from 30 to 76,
bypassing upstream's conflict-replacing 30→75 conversion. All updates use ABORT;
malformed UIDs or collisions fail transactionally instead of dropping records.
There are no destructive migration fallbacks. Unsupported database versions fail.
Secondary 75→76 playlist and 70→71 cache paths are registered, but the actual
backup exercises 30→76 and 67→71 only.

Cache conversion keeps the complete old table as `AuxioLegacyCachedSongData67` and
creates the new nullable file-cache schema. A parser revision marks all copied entries
stale so the new parser runs (including the Opus fix), retaining original added dates.
Normal later library changes may update the active cache; its original archived table
is retained. The existing song UID generation remains based on v3.6.3 identities;
upstream lookup aliases for v4.0.0 and v4.0.1 identities are also retained.
Actual music-file reindexing and library-to-history binding on Android still need testing.

Preferences: the actual single SharedPreferences file survives all five startup
migrations unchanged, including playback/ReplayGain and location configuration.
The old search filter conversion now runs once, preserves the old key and respects
an existing new selection. Legacy image/playback/UI/home conversions were inspected;
the actual saved settings do not trigger their old-key branches. Stored covers and
other app files have no upgrade conversion and remain in the existing app directory.

## Backup and recovery evidence

The signed temporary backup helper was installed in place at the user's explicit
request. Package/UID/private storage were retained and BackupActivity launch was
verified on the Pixel 9 Pro running Android 17. The player was not run during export.
The user saved `auxio-backup-1788653265091.zip` in phone Downloads.

Computer copy: `release/backup-helper/backups/auxio-backup-1788653265091.zip`.
Phone copy: `/sdcard/Download/auxio-backup-1788653265091.zip`.
Both SHA-256: `96ee14f4082e26fb23aa252060b9eb68e18c23296e5114092a854700174daf28`.
The private computer directory/files use owner-only permissions. The 98-entry ZIP's
full inventory and every file hash passed independent verification. Fresh host
extraction recovered WAL-backed data, and all four databases passed integrity and
foreign-key checks. Complete typed logical-row hashes are in the adjacent private
`verification-1788653265091.json` report.

The 7,100 events sum to 1,167,646,409 ms (324.35 hours). Every SongStats per-song
aggregate matches the events. The backup cannot prove unrecorded historic sessions
existed. The source tracker records sessions only after eight seconds; no arbitrary
history limit was found in the export. Music files outside app-owned storage are not
included, and Android-managed grants/MediaStore IDs are not a full-device image.

Recovery procedure and helper build instructions are in `tools/backup-helper/README.md`.
The exact original APK is retained at `release/backup-helper/original-4.0.10.apk` and
in the original StudioProjects checkout. Replacing the helper with this same-code,
same-certificate original APK restores the player over its existing data; it does
**not** restore from the archive. No production uninstall or storage clear is allowed.

**No Android archive importer or full Android restore/update cycle has been validated.**
Ordinary ADB cannot restore private files to this non-debuggable app. Full recovery
requires a controlled spare installation with an authorized importer or a rooted
spare test device. A spare physical device has been requested; emulators are prohibited.
Never test restoration against the real installation. Preserve both master backup
copies, restore another copy into the old test build, verify its data, update that
installation using the final signed APK, reindex identical music files, compare all
records/relationships/UI, and restart. This remains a release blocker.

## Bug findings

- [Android notification #1380](https://github.com/OxygenCobalt/Auxio/issues/1380):
  released fixes `f537ec6c9`, `1cf46f47d`, `f15531a91` are present unmodified.
  The final implementation sizes covers with `MediaSessionCompat.getBitmapDimensionLimit()`,
  reads but does not write this request to the bitmap memory cache, and disables hardware
  bitmaps on Android 17+. [PR #1421](https://github.com/OxygenCobalt/Auxio/pull/1421)
  was closed without merging; its proposal is not used as evidence of the released fix.
  Large artwork/Android bitmap handling is supported by the released changes; 24-bit
  audio is not an established cause. No device reproduction of the new APK yet.
- Separate [stale artwork #1179](https://github.com/OxygenCobalt/Auxio/issues/1179):
  the released null-art handling and transparent 2×2 notification fallback remain.
- [Opus #1137](https://github.com/OxygenCobalt/Auxio/issues/1137) / merged
  [PR #1279](https://github.com/OxygenCobalt/Auxio/pull/1279): raw zero R128 values
  now pass through conversion to +5 dB. Upstream track/album regression tests pass;
  cache invalidation ensures old cached parsing does not mask this change.
- [xHE-AAC #1340](https://github.com/OxygenCobalt/Auxio/issues/1340) remains open.
  Codec loudness plus app ReplayGain could explain double normalization for that format,
  but no universal fix is applied without evidence. The actual cache contains no AAC.
- The user's affected album, “La Migliore Stagione”, is FLAC. Saved track adjustments
  range from about −5.99 to −9.48 dB; album adjustment is −9.48 dB. Saved mode is Track,
  with both preamps at default 0 dB. These explain attenuation but cannot establish
  whether the tags match measured audio loudness. The user declined further sample
  analysis. No music files or volume defaults were changed. Settings → Audio →
  Volume normalization → ReplayGain strategy → Off disables tag-based adjustment.

## Validation

- 116 JVM/Robolectric tests passed (app 6, musikr 110), zero failures/skips.
  No Android emulator was used. Actual private fixtures were copied again for each test.
- Actual four-database Room migrations validate the generated schemas and compare
  every typed row as a multiset, canonicalizing only expected UID representations.
  The migrated databases are closed/reopened; history DAO binding and playlist
  relationship queries/edits are exercised. Cache archive/new rows and stale-result
  original dates are checked. Collision and invalid-UID tests verify rollback.
- Actual saved preferences survive repeated startup migrations. A synthetic search
  filter test verifies conversion and respect for later user choices.
- PCM ReplayGain tests verify track/album selection, fallbacks, dynamic parent handling,
  tagged/untagged preamp selection, repeated settings changes without cumulative gain,
  and Off at zero preamp. Upstream parser tests include zero R128 gains.
- Seven backup helper host checks passed, including WAL recovery, unchanged source
  data, archive corruption/incompleteness, symlinks, payload and manifest comparison.
- Release build runs R8 minification and resource shrinking; required lintVitalRelease
  passed. Broader lintRelease failed with 68 errors, 103 warnings and one hint.
  Every error location is in a file byte-identical to upstream v4.1.5 (restricted
  Material APIs, Media3 opt-in, DiffUtil comparisons, constants and media-search intent).
  No baseline or suppression was added to hide these errors. Full report is in
  `app/build/reports/lint-results-release.html`.
- Minified APK execution, full Android restore/update/restart, actual library binding
  and notification playback behavior remain unverified. Compilation is not proof of
  migration safety. Do not install the upgraded APK on the real phone yet.

## Signed release artifact

- APK: `/home/mrindeciso/Documents/Auxio/release/Auxio-custom-v4.1.5.apk`
- SHA-256: `aff5741333571770f482e1a251bef748f1a45064748da6a755fc54437e844d0b`
- Package `org.oxycblt.auxio`, version 4.1.5, code 75 (greater than installed 69).
- APK v2/v3 signatures valid; certificate SHA-256
  `0a0a80b95b50102b662d3854572f4d85891d0b3b12a5e308bdcb27bd4f164d61` matches the original installed APK.
- Non-debuggable, min SDK 24, target 36; ARM64, ARMv7, x86 and x86_64.
- 16 KiB ZIP library alignment and unchanged unsigned/signed app payloads verified.
- R8 and resource shrinking enabled. Final `spotlessCheck :app:assembleRelease` passed.
- Machine-readable verification: `release/release-verification.json`.
- No upgraded APK installation or remote push was performed. **Not yet verified safe
  to update the real installation.**
