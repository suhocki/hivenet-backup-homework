# Prepare for Backup

A small Android app that lets a user pick files with the system document picker and
creates local backup copies of them. The last batch and the result of every file
survive an app restart.

## Build and run

Requirements: JDK 17 or newer, Android SDK with platform 36 installed. No global Gradle
is needed, the wrapper downloads Gradle 9.3.1.

```
./gradlew assembleDebug testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open the folder in Android Studio and press Run.

Tested on the Android Studio emulator "Medium Phone", Android 16 (API 36, arm64).
minSdk is 26, compileSdk and targetSdk are 36.

Stack: Kotlin 2.2, Jetpack Compose (Material 3), Room 2.8 with KSP, Kotlin coroutines.
One module, no DI framework.

## What the app does

1. Tap "Select files". The system picker (`ActivityResultContracts.OpenMultipleDocuments`) opens.
2. Every picked file is copied, one after another, to app-private storage:
   `filesDir/staging/<batchId>/<name>`.
3. The screen shows the batch: file name, size, status and a human readable failure reason,
   plus a summary line like "4 copied, 1 failed".
4. After the app is closed and reopened, the latest batch is shown again with the recorded outcomes.

## Interpretation

I read "prepare for backup" as the staging step of a backup pipeline. Before anything is
chunked, encrypted or uploaded, the app takes a stable local snapshot of each selected file.
After that, the later steps no longer depend on the source still existing, being unchanged, or
the URI permission still being valid. Encryption, chunking and upload are not implemented.
They get a seam: `BackupDestination` is the interface a later encrypting or uploading
destination would implement.

## Architecture

```
ui/      MainActivity, BackupViewModel, BackupScreen      Compose, observes Room
copy/    BackupService      runs one batch: describe, record, copy each item, record result
         FileCopier         streams one file, maps every failure to a FailureReason
         DocumentSource     read side (ContentResolver), interface for tests
         BackupDestination  write side (.part then rename), interface for tests and future sinks
         FileNames          sanitize display names, add " (1)" on collisions
data/    Room: BatchEntity, ItemEntity, BackupDao, BackupStore (interface + Room impl)
AppContainer                hand wiring, application scoped CoroutineScope
```

The copy runs in an application-scoped coroutine, not in the ViewModel. The screen only
observes the database through a `StateFlow`. Rotation does not touch the running copy.
Leaving the screen does not either, as long as the provider lets the app keep the URI grant
(see "URI permissions" below). If it does not, the remaining files fail with "access was lost"
and can be picked again. Nothing is lost silently.

## Decisions

- **Streaming copy** with a 256 KB buffer on `Dispatchers.IO`. Memory stays flat for large files.
- **Write to `name.part`, fsync, then rename.** A final file name always means a complete copy.
  The parent directory is not fsynced, so on power loss (not process death) a rename can be lost;
  the startup cleanup then removes the orphaned `.part`.
- **Size check.** If the provider reports `OpenableColumns.SIZE`, the copied byte count must
  match, otherwise the item fails with "size mismatch" and the partial file is deleted.
  If the size is unknown, the check is skipped.
- **One failure does not stop the batch.** Each item ends as DONE or FAILED with a reason:
  source unreadable, permission lost, no space, write failed, size mismatch, virtual document
  (for example Google Docs, which has no byte stream), unexpected. A failure inside the
  database itself (for example SQLite on a full disk) marks the batch interrupted instead of
  crashing the app.
- **Interrupted work is made visible.** On app start, items still PENDING or COPYING and
  batches still RUNNING become INTERRUPTED, and their files are deleted, both the `.part` file
  and a final file that was renamed just before the process died. So a file on disk always
  means DONE in the database. There is no automatic resume.
- **Names.** Taken from `OpenableColumns.DISPLAY_NAME`, sanitized (path separators, control
  characters), with a fallback of "file". Two files with the same name in one batch become
  `name.ext` and `name (1).ext`. The same URI picked twice is copied once. A name ending in
  `.part` gets a `_` appended, because `.part` is reserved for temp files.
- **0-byte files** are a normal success.
- **Only the latest batch is shown.** Older batches stay in the database and on disk.
- **Single `:app` module and hand wiring** instead of multi-module and Hilt. The project is
  small; the interfaces (`BackupStore`, `DocumentSource`, `BackupDestination`) are what make
  the logic testable.
- **Batches run one after another.** A second selection waits until the first batch is done.
- **URI permissions.** The grant from the picker belongs to the Activity result and can go
  away when the Activity finishes. Before the copy starts the app takes a persistable read
  permission for every picked URI and releases it after the batch. Providers may refuse this,
  then the copy still works while the Activity lives. A "Retry failed" feature would keep
  the permissions instead of releasing them.

## Consciously not done

- Encryption, chunking, upload: out of scope, the destination interface is the seam.
- WorkManager or foreground service: the assignment does not require background execution.
- Resume of partial copies: not required, the partial file is deleted and marked instead.
- Batch history UI: only the latest batch is shown, the data for older ones is kept.
- SHA-256 of copies: size check only, a hash would be the first thing to add for integrity.
- Per-file progress bars: a "Copying" state with a spinner is enough for this scope.
- Retry failed, cancel: nice to have, left out to keep the core small and reliable.
- Settings, theme, custom icon: default Material 3 theme.
- Room migrations: version 1 only. The schema is exported to `app/schemas/` so a migration test is possible later.

## Privacy

The app has no `INTERNET` permission. Copies live in app-private storage that other apps
cannot read. File names and URIs are not written to logcat.

## Production next steps

1. SHA-256 while streaming, stored per item, verified before upload.
2. A foreground service or WorkManager so large batches finish when the app is in the background.
3. Keep the URI permissions and add a "Retry failed" action.
4. Encrypting destination and chunked upload behind `BackupDestination`.
5. Quota handling: check free space before starting and skip files over a limit.
6. Clean up staged batches once they are uploaded.

## Tests

`./gradlew testDebugUnitTest` runs 27 JVM tests, no emulator needed:

- copy produces identical bytes and leaves no `.part` file, 0-byte file succeeds
- size mismatch, unreadable source, lost permission, read error mid-stream, virtual document, no space vs write failed
- one failing file does not stop the rest of the batch
- a database failure mid-batch does not crash and leaves no file without a DONE row
- two batches started quickly run one after the other
- same URI picked twice is copied once, equal names get " (1)", unsafe names are sanitized, long names are cut by bytes,
  a file named `foo.part` cannot clash with the temp file of `foo`
- startup turns PENDING and COPYING into INTERRUPTED and deletes their `.part` and final files

Manually verified on the emulator: a 13-byte text file, a 50 MB random binary and a 0-byte
file picked together, all copied with matching MD5, batch shown again after force-stop.

## Time spent

About 3.5 hours including setup, tests and this README.

Complete: everything in the required scope. Unfinished: nothing from the required scope.
The nice-to-have items are listed above as not done.

## AI assistance

I used Claude Code throughout. It wrote most of the first draft of every file from the
decisions I set: staging copy in app-private storage, the failure categories, the `.part`
then rename rule, the naming rules and what to leave out. I then ran separate AI review
passes as a strict reviewer and as a tester on the emulator, read the findings, decided
which ones to fix and which to leave, and checked the fixes on the emulator myself.
Examples of fixes from that review: a temp-file clash between `foo.part` and `foo`, an
uncaught database error on a full disk, and file names that were too long in bytes for
Cyrillic text. I have read every line and can explain and change any part.
