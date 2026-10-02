# Riven reminder and alarm implementation record

## Scope

Reminder and alarm v1 uses a separate `riven-reminders.db` Room database. This avoids taking the main `RivenDatabase` version number while the memory branch is preparing its own schema v9. The reminder schema is exported independently at version 1.

The store is canonical for local reminder title, note, requested wall time, timezone policy, effective scheduled time, feature controls, quiet hours, delivery state, schedule revision, delivery token, failure details, and the reminder event audit stream.

## Delivery and recovery

- Notification reminders use `AlarmManager.setAndAllowWhileIdle`. Audible alarms use `setExactAndAllowWhileIdle` only when Android reports exact-alarm access.
- Notification permission, global notification enablement, and exact-alarm access are separate visible states. A rejected schedule is stored as `FAILED` with the real reason; it is never described as scheduled.
- Each reschedule updates a stable `PendingIntent` identity and advances the durable schedule revision. A broadcast must atomically claim the matching revision and a new delivery token before it can notify or ring.
- Boot, package replacement, clock changes, timezone changes, app startup, and exact-alarm access changes enqueue one unique reminder recovery worker. Recovery cancels and rebuilds active schedules idempotently.
- Notification deletion is a deliberate dismissal. Dismissed, completed, and cancelled reminders are terminal and are not recovered or reissued.
- The foreground ringing service uses alarm audio attributes without DND bypass, releases `MediaPlayer`, audio focus, coroutine work, and its bounded wake lock on stop or destruction, and stops audio after ten minutes if no action is taken.

## Restore and reset boundary

The existing main-database restore flow does not replace or delete `riven-reminders.db`. After restore settles, normal application startup enqueues reminder recovery, so reminders on the current installation remain present and are rescheduled.

Factory Reset explicitly cancels every app-owned `AlarmManager` entry, cancels notifications, stops alarm playback, deletes the reminder database and sidecars, and creates a fresh empty reminder database. This prevents ghost alarms after reset.

Portable archive inclusion is not implemented in this isolated slice. The existing archive format v1 contains only `riven.db` and attachment blobs, so reminders do not travel to a new installation and do not return after Factory Reset followed by archive restore. Product integration must either extend the versioned archive atomically to include and validate `riven-reminders.db`, or present this exclusion clearly before export/reset. It must not imply reminder portability until that work lands.

## Audio asset status

System-default alarm sound and user-selected local audio through Android's document picker are supported. Durable URI permission is requested only after the user selects a file.

The supplied Drive `Riven-Alarm.mp3` metadata was verified as `audio/mpeg`, 74,464 bytes. Streamed materialization failed with `cannot create attachment directory: Access is denied. (os error 5)`. No bundled Riven voice asset is claimed by this branch; attaching that exact verified file through an approved local materialization path remains pending.

## Integration point

`ReminderAlarmScreen` is a standalone Compose surface using the existing navy and muted-brass theme plus hunter-green panels. It deliberately does not edit `RivenApp` or its chat navigation. Parent integration can add one destination that hosts this composable without merging reminder state into chat UI state.
