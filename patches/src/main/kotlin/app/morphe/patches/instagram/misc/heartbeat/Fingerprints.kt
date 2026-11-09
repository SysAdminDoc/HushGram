/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.heartbeat

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.download.video.mutable
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.loadsConstStringContaining
import app.morphe.patches.instagram.misc.extension.loadsString
import com.android.tools.smali.dexlib2.AccessFlags

/**
 * The method that sets Instagram's next heartbeat alarm. The heartbeat is Instagram's note to itself
 * that it's still running, which lets a later start tell that Android killed it. The method sets the
 * alarm through AlarmManager, or logs that there isn't one to set, and takes its own class, so the
 * two strings and the shape tell it from the class's other methods.
 */
internal object HeartbeatAlarmFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf("L"),
    filters = listOf(string("AlarmManager not available, cannot schedule heartbeat")),
    custom = { method, _ -> method.loadsConstStringContaining("WarmHeartbeat") },
)

/**
 * The method that schedules Instagram's analytics uploads. One of the ways it can schedule them is an
 * AlarmManager alarm that wakes the phone five minutes on, tagged AnalyticsUploadAlarm and carrying the
 * action_batch_upload intent, and it holds both strings.
 */
internal object UploadAlarmFingerprint : Fingerprint(
    returnType = "V",
    filters = listOf(string("action_batch_upload")),
    custom = { method, _ -> method.loadsConstStringContaining("AnalyticsUploadAlarm") },
)

/** Instagram's background prefetch job, which keeps its framework name since Android starts it by that name. */
internal const val BG_FETCH_SERVICE = "Lcom/instagram/jobscheduler/bgfetch/scheduler/IgBgFetchSchedulerService;"

/** What the prefetch scheduler logs when JobScheduler, or WorkManager, refuses a job. */
internal const val BG_FETCH_JOB_FAILED = "scheduleJobWithJobScheduler() failed: "
internal const val BG_FETCH_WORK_FAILED = "scheduleJobWithWorkManager() failed: "

/**
 * The static method that queues Instagram's background prefetch jobs. For each job it either asks
 * JobScheduler for a persisted, network-bound run about half an hour on, aimed at the class it's handed,
 * or enqueues the same run with WorkManager, and it logs a failure of each by its own message. Found
 * through the Redex string pool too, since 450's builds pool strings differently from build to build.
 */
internal fun BytecodePatchContext.findBgFetchScheduler(): MutableMethod {
    val found = classesLoadingString(BG_FETCH_JOB_FAILED).flatMap { owner ->
        owner.methods.filter { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
                method.parameterTypes.map(Any::toString).let { it.size == 3 && it[0].startsWith("L") && it.drop(1) == SCHEDULER_INPUTS } &&
                loadsString(method, BG_FETCH_JOB_FAILED) && loadsString(method, BG_FETCH_WORK_FAILED)
        }
    }
    val scheduler = found.singleOrNull()
        ?: throw PatchException("Stop background wake-ups: expected one background prefetch scheduler, found ${found.size}")
    return mutable(scheduler)
}

private val SCHEDULER_INPUTS = listOf("Ljava/lang/Class;", "Ljava/util/List;")

/** The prefetch job's onStartJob, the framework's own, so its name and shape are kept. */
internal object BgFetchJobStartFingerprint : Fingerprint(
    definingClass = BG_FETCH_SERVICE,
    name = "onStartJob",
    returnType = "Z",
    parameters = listOf("Landroid/app/job/JobParameters;"),
)
