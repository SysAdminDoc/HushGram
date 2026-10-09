/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.heartbeat

import app.morphe.patcher.Fingerprint

/**
 * The method that sets Instagram's next heartbeat alarm. The heartbeat is Instagram's note to itself
 * that it's still running, which lets a later start tell that Android killed it. The method sets the
 * alarm through AlarmManager, or logs that there isn't one to set, and takes its own class, so the
 * two strings and the shape tell it from the class's other methods.
 */
internal object HeartbeatAlarmFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    strings = listOf("WarmHeartbeat", "AlarmManager not available, cannot schedule heartbeat"),
)

/**
 * The method that schedules Instagram's analytics uploads. One of the ways it can schedule them is an
 * AlarmManager alarm that wakes the phone five minutes on, tagged AnalyticsUploadAlarm and carrying the
 * action_batch_upload intent, and it holds both strings.
 */
internal object UploadAlarmFingerprint : Fingerprint(
    returnType = "V",
    strings = listOf("AnalyticsUploadAlarm", "action_batch_upload"),
)
