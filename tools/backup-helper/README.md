# Auxio private-data backup helper

Status: signed with the original certificate, installed in place at the user's request,
and used to export the actual app data on Android 17. Export checksums, archive
inventory and host SQLite/WAL recovery passed. **Android archive restoration and
the upgraded player remain unverified for the real installation.** The user declined
emulators. Never substitute the production phone for a restore test device.

This is a temporary backup helper, not the requested Auxio 4.1.5 player upgrade.
The upstream upgrade is being implemented on `upgrade/upstream-4.1.5-data-safe`; the original
`dev` branch remains at `6154404ca`.

## Original installation

- Device observed using read-only ADB: Pixel 9 Pro, Android 17.
- Package: `org.oxycblt.auxio`, version 4.0.10, code 69, target SDK 36, non-debuggable.
- `run-as` was denied because the release app is non-debuggable.
- Installed APK was pulled to `/tmp/auxio-upgrade/installed-4.0.10.apk`.
- It is byte-identical to `/home/mrindeciso/StudioProjects/Auxio/app/release/app-release.apk`.
- Original APK SHA-256: `56d7d00c53b3ef7b94782f591b606e366bbad801051ca130e9a2d4d0d0acee1a`.
- Required signing certificate SHA-256: `0a0a80b95b50102b662d3854572f4d85891d0b3b12a5e308bdcb27bd4f164d61`.
- Supplied keystore location: `/home/mrindeciso/Android/keystore.jks`.
- The user supplied alias `key0`. The two saved passwords were retrieved through the
  desktop's native keychain with authorization; neither was displayed or retained.

Signed helper: `release/backup-helper/auxio-backup-helper-signed.apk`.
SHA-256: `9877e9ea6c253f8dbb1eb2c4aa970b34e7d8f4f016edb0de55f11ed5d9fe814b`.
APK v3 signature, matching certificate, package/version, non-debuggable manifest,
16 KiB native library alignment, and unchanged payloads after signing were verified.
The helper was installed using an authorized in-place update; UID 10425 and the app
private directory were retained. The factory launched BackupActivity without a player
crash. The user exported `auxio-backup-1788653265091.zip` to phone Downloads.
The phone copy and `release/backup-helper/backups/` copy have identical SHA-256:
`96ee14f4082e26fb23aa252060b9eb68e18c23296e5114092a854700174daf28`.
The archive contains 98 entries and all four expected databases including WAL files.
Host validation found 7,100 events, 883 song-stat rows, two playlists, 721 memberships,
a 14-item queue and 921 cached songs. Per-song aggregates and event totals agree.
The private verification JSON records complete logical table hashes and integrity checks.
This checks the saved data; it cannot establish that every past listening session was recorded.

## How the helper avoids running the player

`build.py` requires the exact original APK and verifies its existing signature.
It compiles the helper's framework-only Java sources with Java 21 and D8's release
configuration, appends a separate DEX, and changes the manifest's component factory.
The factory supplies a plain Android Application, a backup Activity, and inactive
implementations for every service, receiver, and provider. Original Auxio classes,
Hilt, Room, preferences, native music parsing and AndroidX Startup are not instantiated
through any manifest entrypoint. The helper launch was verified on the user's phone.

All original resource and code payloads are preserved byte-for-byte. The manifest
keeps the package, permissions, component names, provider authorities, widget metadata,
version code, and target SDK. The only other manifest change is minimum SDK 24 → 30,
because the helper requires Android's component-factory and storage-volume APIs.
The actual phone is above that minimum. Host tests compare the complete decoded manifests.

The helper retains version code 69 so reinstalling the exact original APK can use a
same-version replacement rather than a downgrade. This sequence has not been device-tested.
Component declarations are retained to reduce disruption to Android-managed associations;
preservation of widgets and permission grants still needs runtime verification.

## Archive contents and consistency

The activity exports credential-protected and device-protected app storage, and the
package's external data/media directories on every mounted storage volume. Persistent
roots that do not exist are recorded. Unavailable volumes, unreadable persistent files,
and unexpected symbolic links abort export. The existing `stats.db` must be present.

Source inventory from the old fork:

| Data | Location / schema |
|---|---|
| Listening history and statistics | `databases/stats.db`, schema 2; `SongStats`, `PlayEvent` |
| Playlists and song membership | `databases/user_music.db`, schema 30 |
| Playback position, parent, queue and shuffle mapping | `databases/playback_persistence.db`, schema 38 |
| Cached music metadata and ReplayGain values | `databases/music_cache.db`, schema 67 |
| Settings, music locations, tab order, preamps | `shared_prefs/` |
| Stored cover images | `files/covers/` |
| Other private files, diagnostic graph files, no-backup storage | Included by recursive inventory |

All database sidecars, including `-wal`, `-shm`, and journals, are copied as ordinary
files. The exporter never opens SQLite, checkpoints a database, changes its schema,
reads preferences through SharedPreferences, or modifies source files. It inventories
and hashes every persistent file before copying, checks copied bytes, then repeats the
complete inventory and hashes before writing the completion manifest.

Only root-level `cache`, `code_cache`, and `lib` are excluded as runtime/generated
storage; every encountered exclusion is listed. `no_backup` is included. Databases
are included even when Android's own backup rules exclude them.

The ZIP contains `data/<root>/...` plus `manifest.json` (format 1), including file
sizes, modification times, SHA-256 hashes, directories, root locations, omissions,
package/version/certificate metadata, and persisted URI permission information.
An export interrupted before the completion manifest is invalid. The activity accepts
only the system Downloads document provider so the output cannot recurse into app data.
Keep it foregrounded until completion; interruption is handled by retrying, not by
accepting a partial archive. No passwords or signing keys are included in the APK/archive.

Music files outside app-owned storage are **not** part of this archive. Keep their
existing separate copies, filenames, folder layout, and tags unchanged. Android-owned
permissions, widget placements, shortcuts, MediaStore IDs and SAF grants are not files
the app can restore itself. URI grants are recorded for diagnosis and may require
reauthorization on another installation. Do not label this a full-device image.

## Build and check

Run from the repository root:

```sh
python3 tools/backup-helper/build.py \
  --original /tmp/auxio-upgrade/installed-4.0.10.apk \
  --sdk /home/mrindeciso/Android/Sdk \
  --jdk /home/mrindeciso/Applications/android-studio/jbr \
  --out release/backup-helper

python3 tools/backup-helper/test_helper.py \
  --original /tmp/auxio-upgrade/installed-4.0.10.apk \
  --sdk /home/mrindeciso/Android/Sdk \
  --jdk /home/mrindeciso/Applications/android-studio/jbr
```

Build output is an aligned, **unsigned** release helper under `release/backup-helper/`.
For this workstation, run `bash tools/backup-helper/sign-local.sh` in your own terminal.
It uses alias `key0` and prompts privately for the password. A failed password attempt
does not change the keystore or create a final APK; rerun the command to try another
password manually. Nothing is installed by this command.

Alternatively, `sign_from_keychain.py` retrieves only the two exact saved entries for
this keystore/alias via `secret-tool lookup` and signs without exposing passwords.
The entry identifiers and serialization were checked against the installed Android
Studio implementation. It does not enumerate the keychain, try password guesses,
read vault files, or search other accounts. Temporary password files are mode 600
inside a private temporary directory and removed on exit. A desktop unlock prompt
must be handled normally; access denial is not bypassed.

`sign.py` also accepts `--jdk`, `--sdk`, `--apk`, `--out`, `--keystore`, `--alias`,
and optional `--store-password-file` / `--key-password-file`. Password files must have
mode 600. Password contents go directly to apksigner via terminal prompts or those
files, never shell arguments, environment variables, build logs, or source control.
The final output is created only after the expected certificate is verified.

Host checks cover: WAL-only committed records, history totals, Unicode filenames,
empty directories, binary files, no-backup storage, unchanged source bytes, runtime
exclusions, mid-export changes, symlinks, archive corruption, incomplete export,
unsafe archive paths, manifest preservation, payload preservation and ZIP alignment.

## Real-phone procedure and recovery boundaries

1. Obtain signing inputs, sign, and verify the certificate, package ID, version code,
   non-debuggable manifest, APK contents, and alignment. Preserve the exact original
   APK in two durable locations before installation; `/tmp` is not durable.
2. Explain that Android execution/restoration remains unverified without a spare
   device. Ask the user to review the specific signed helper before installation.
3. Have the user pause playback and allow the old app's asynchronous listening-session
   save to finish. Record its displayed history totals/last event and current queue.
   Do not kill the player mid-session. Pending in-memory state is not in a file backup.
4. After the user requests installation, stop old app processes and replace it with
   the signed helper using an in-place `adb install -r` targeted to the selected device.
   Never uninstall, clear storage, enable debuggability, or unlock the bootloader.
5. Open Auxio's existing launcher entry, tap **Save full backup**, choose Downloads,
   and keep the screen open. If it reports any failure, do not accept the archive.
6. Pull the completed ZIP to an owner-only directory. Run `verify_backup.py ARCHIVE
   --report NEW_REPORT.json`. It checks every ZIP member against the manifest and
   inspects SQLite only after extraction to a fresh, disposable host directory.
   Its report includes database versions, row counts and deterministic hashes of every
   table, statistics/event totals, integrity and foreign-key checks. Reports are private.
7. Retain two independently checksummed archive copies on separate storage. Record
   any absent expected databases and compare the history totals to the old UI evidence.
   Do not infer completeness merely from a nonempty ZIP or successful export screen.
8. With the user's request, reinstall the archived original APK using `adb install -r`.
   This changes the APK, not the saved database contents. Verify history, playlists,
   queue, folder permissions, widgets, and persistence after restarting.

Reinstalling the old APK after a helper export **does not test archive restoration**:
the original data never left the phone. Explicitly keep that distinction in reporting.

## Recovery and next stage

`verify_backup.py` reconstructs all archive files in a fresh host directory and verifies
SQLite/WAL recovery there. That proves archive readability and SQLite consistency only.
Restoring these files into Android requires a separate controlled test installation and
an app-authorized import mechanism (or root on that separate test device); ordinary ADB
cannot write the production app's private directory. No Android importer has been
implemented or validated yet. **Full Android recovery remains a release blocker.**

Do not experiment with restoration on the real phone. When a spare-device route is
available, restore a copy into the old build, compare all logical records and file
relationships, then restart it. Keep the master archive untouched.

The 4.1.5 upgrade implements explicit migrations for custom history and playback UIDs,
a conflict-aborting playlist UID migration, an additive metadata cache conversion and
idempotent search preference conversion. JVM tests use disposable copies of the actual
backup. See `verification/UPGRADE.md` for final evidence and remaining release blockers.
