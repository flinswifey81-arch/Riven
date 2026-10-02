# Riven reminder and alarm implementation record

## Scope

Reminder and alarm v1 uses a separate `riven-reminders.db` Room database. This avoids taking the main `RivenDatabase` version number while the memory branch is preparing its own schema v9. The reminder schema is exported independently at version 2, with a non-destructive v1-to-v2 migration for the durable ring deadline.

The store is canonical for local reminder title, note, requested wall time, timezone policy, effective scheduled time, feature controls, quiet hours, delivery state, schedule revision, delivery token, failure details, and the reminder event audit stream.

## Delivery and recovery

- Notification reminders use `AlarmManager.setAndAllowWhileIdle`. Audible alarms use `setExactAndAllowWhileIdle` only when Android reports exact-alarm access.
- Notification permission, global notification enablement, the reminder channel, the alarm channel, and exact-alarm access are separate visible states. A rejected schedule is stored as `FAILED` with the real reason; it is never described as scheduled.
- Each reschedule updates a stable `PendingIntent` identity and advances the durable schedule revision. A broadcast must atomically claim the matching revision and a new delivery token before it can notify or ring. Edit, cancel, and complete compare the revision, status, and token read from the database; if delivery wins between read and mutation, they retry against the winning row and target that exact token for audio cleanup.
- Notification claims remain durably `DELIVERING` until `notify()` returns and the claim is acknowledged; process recovery safely reposts the same deterministic notification with `onlyAlertOnce` before retrying that acknowledgement. Alarm timeout uses the same outbox transition instead of marking delivery complete before its persistent notification is posted.
- Boot, package replacement, clock changes, timezone changes, app startup, and exact-alarm access changes enqueue one unique reminder recovery worker. Recovery cancels and rebuilds active schedules idempotently.
- Notification deletion is a deliberate dismissal. Dismissed, completed, and cancelled reminders are terminal and are not recovered or reissued.
- The foreground ringing service uses alarm audio attributes without DND bypass, releases `MediaPlayer`, audio focus, coroutine work, and its bounded wake lock on stop or destruction, and stops audio after ten minutes if no action is taken. Async service-start validation is token-scoped: a stale latest `startId` cannot stop another active alarm, and a foreground-start failure terminalizes only the failing token before advancing the queue.

## Restore and reset boundary

Portable archive format v2 includes a self-contained `riven-reminders.db` snapshot with an independent schema version and SHA-256. Restore validates both databases, normalizes `DELIVERING`, `RINGING`, and `DELIVERED` rows to terminal `DISMISSED` state, and clears nonterminal delivery tokens and ring deadlines. Before the atomic database swap it cancels existing app alarm/notification/audio delivery state. After restore or a successful rollback, normal application startup enqueues reminder recovery; active rows are rescheduled through the current notification and exact-alarm permission checks.

Legacy archive format v1 remains supported and never replaces the installation's reminder database. Restore journal v1 is likewise read compatibly and upgraded without opting it into reminder replacement.

In-flight portable restore journals created while the independent reminder schema was version 1 remain recoverable. Bootstrap accepts supported reminder versions, opens them through Room's non-destructive migrations, and then requires a verified version 2 database before install or rollback completes.

The main and reminder databases intentionally have no cross-database IDs, foreign keys, transactions, or state invariants: reminder titles, schedule policy, delivery tokens, quiet hours, and audit events are self-contained in `riven-reminders.db`. Export therefore uses bounded fuzzy semantics rather than pretending SQLite can provide a cross-file transaction. It takes one transactional `VACUUM INTO` snapshot of `riven.db`, then one of `riven-reminders.db`; each snapshot is internally coherent and hashed, while writes occurring between those two snapshots may appear in only the later reminder snapshot. The bounded window is the duration of those two snapshot operations. Restore validates and installs the pair as one journaled package, while legacy v1 restores preserve the current reminder database.

Factory Reset explicitly cancels every app-owned `AlarmManager` entry, cancels notifications, stops alarm playback, deletes the reminder database and sidecars, and creates a fresh empty reminder database. This prevents ghost alarms after reset.

## Audio asset status

System-default alarm sound and user-selected local audio through Android's document picker are supported. Durable URI permission is requested only after the user selects a file.

The supplied Drive `Riven-Alarm.mp3` metadata was verified as `audio/mpeg`, 74,464 bytes. Streamed materialization failed with `cannot create attachment directory: Access is denied. (os error 5)`. No bundled Riven voice asset is claimed by this branch; attaching that exact verified file through an approved local materialization path remains pending.

## Integration point

`ReminderAlarmScreen` is a standalone Compose surface using the existing navy and muted-brass theme plus hunter-green panels. It deliberately does not edit `RivenApp` or its chat navigation. Parent integration can add one destination that hosts this composable without merging reminder state into chat UI state.

Physical-device validation remains required for OEM exact-alarm behavior, permission and channel changes, reboot/time-change delivery, foreground-service restrictions, and audible cleanup. No APK was installed and no real device alarm or permission was exercised by these local tests.
