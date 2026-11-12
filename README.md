# Prepare for Backup

A small Android app. The user picks files with the system document picker, and the app
makes local copies of them. The last batch and the result of every file survive a restart.

## Summary

- **Time spent:** about 4 hours, including setup, tests, emulator checks and this README.
- **Complete:** everything in the required scope. Not done: the items listed under
  "Consciously not done".
- **Tested on:** Android Studio emulator "Medium Phone", Android 16 (API 36, arm64).
- **AI use:** I used Claude Code throughout. It wrote most of the first draft of every file
  from the decisions I set: staging copy in app-private storage, the failure categories,
  the `.part` then rename rule, the naming rules and what to leave out. I then ran separate
  AI review passes, as a strict reviewer and as a tester on the emulator, read the findings,
  decided which ones to fix and which to leave, and checked the AI test results. Examples of fixes
  from that review: a temp file clash between `foo.part` and `foo`, an uncaught database
  error on a full disk, file names too long in bytes for Cyrillic text, and URI grants not
  given back after a process kill. I reviewed the code and can walk through any part of it.

## Build and run

Requirements: JDK 17 or newer, Android SDK with platform 36. The Gradle wrapper downloads
Gradle 9.3.1.

```
./gradlew assembleDebug testDebugUnitTest
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open the folder in Android Studio and press Run. minSdk 26, compileSdk and targetSdk 36.
Stack: Kotlin 2.2, Jetpack Compose (Material 3), Room 2.8 with KSP, coroutines. One module,
no DI framework.

## How it works

1. "Select files" opens the system picker (`OpenMultipleDocuments`).
2. Each file is copied, one after another, to app-private storage:
   `filesDir/staging/<batchId>/<name>`.
3. The screen shows name, size, status and a plain failure reason per file, and a summary
   like "4 copied, 1 failed".
4. After a restart the latest batch is shown again.

I read "prepare for backup" as the staging step of a backup pipeline: before anything is
chunked, encrypted or uploaded, the app takes a stable local snapshot of each file. Then
later steps do not depend on the source still existing, being unchanged, or the URI grant
still being valid. That is why copies go to app-private storage and why a partial copy
must never look like a success.

```
ui/    MainActivity, BackupViewModel, BackupScreen   Compose, observes Room
copy/  BackupService      runs batches one after another: describe, record, copy, record
       FileCopier         streams one file, maps every failure to a FailureReason
       DocumentSource     read side (ContentResolver)
       BackupDestination  write side (.part, fsync, rename, discard)
       FileNames          safe names, " (1)" on collisions
data/  Room: BatchEntity, ItemEntity, BackupDao, BackupStore
```

The copy runs in an application-scoped coroutine, not in the ViewModel, so rotation does
not touch it. The screen only observes the database.

## Decisions

- **Streaming copy** with a 256 KB buffer on `Dispatchers.IO`.
- **Write `name.part`, fsync, rename.** A final name means a complete copy. The directory is
  not fsynced, so after a power loss (not a process kill) a rename may be lost. Startup does
  not check DONE rows against the disk; that is a known gap.
- **Size check** against `OpenableColumns.SIZE` when the provider reports it. Unknown size
  means no check. Same-size changes are not detected without a hash.
- **One failure does not stop the batch.** Reasons: source unreadable, permission lost, no
  space, write failed, size mismatch, virtual document (for example Google Docs), unexpected.
  A database failure marks the batch interrupted instead of crashing the app.
- **Interrupted work is visible.** On start, unfinished items become INTERRUPTED, their
  `.part` and final files are deleted and their URI grants are given back. A file on disk
  means DONE in the database. No automatic resume.
- **Names** come from `DISPLAY_NAME`, are sanitized and cut to 240 UTF-8 bytes (the file
  system limit is 255 bytes). Duplicates get " (1)", compared case-insensitively so names
  stay valid if copied to a case-insensitive file system later. The same URI picked twice
  is copied once. `.part` is reserved for temp files.
- **URI permissions.** The picker grant belongs to the Activity. The app takes a persistable
  read grant when a batch starts and gives it back when the batch ends, so leaving the
  screen does not break the copy. If a provider refuses, the file fails with "access was
  lost".
- **Only the latest batch is shown.** Older batches stay in the database and on disk.

## Consciously not done

- Encryption, chunking, upload: out of scope. They would be another `BackupDestination`.
- WorkManager or a foreground service: background execution is out of scope.
- Resume, SHA-256, progress bars, history screen, retry, cancel, clean-up of old batches:
  kept out to keep the core small. Retry and SHA-256 would be the first to add.

## Privacy

No `INTERNET` permission. `allowBackup` is off, so the staging area is not copied to Google
backup. Copies live in app-private storage. Only exception types are logged, not messages
that could contain a path.

## Tests

`./gradlew testDebugUnitTest` runs 29 JVM tests on real temp files: byte-equal copies with no
`.part` left, every failure reason, one failure not stopping the batch, a database failure
mid-batch, two batches not overlapping, name rules, startup clean-up, and URI grants given
back. Checked on the emulator: a 50 MB file and a 0-byte file with matching MD5,
duplicate names, a source deleted before copy, a kill mid-copy, rotation, a full disk.
