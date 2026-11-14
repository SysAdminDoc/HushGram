/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.sleep

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction

/** Instagram's own call to keep the reel window awake: `Window.addFlags(FLAG_KEEP_SCREEN_ON)`. */
internal const val ADD_FLAGS = "Landroid/view/Window;->addFlags(I)V"

/** Instagram's own call to let the reel window sleep again. */
internal const val CLEAR_FLAGS = "Landroid/view/Window;->clearFlags(I)V"

/** Where the reel window the two calls above act on comes from. */
internal const val GET_WINDOW = "Landroid/app/Activity;->getWindow()Landroid/view/Window;"

/** The type of the screen-on wake locks Instagram's video player takes. */
internal const val WAKE_LOCK = "Landroid/os/PowerManager\$WakeLock;"

/** The player's own call that makes one of its wake locks. */
internal const val NEW_WAKE_LOCK =
    "Landroid/os/PowerManager;->newWakeLock(ILjava/lang/String;)Landroid/os/PowerManager\$WakeLock;"

/** The player's own call that takes one of its wake locks. */
internal const val ACQUIRE = "Landroid/os/PowerManager\$WakeLock;->acquire()V"

/** The player's own test for whether a wake lock is still held. */
internal const val IS_HELD = "Landroid/os/PowerManager\$WakeLock;->isHeld()Z"

private const val PATCH = "Let the screen sleep after a reel"

/**
 * The one helper that keeps the Reels window awake, as the mutable method to hook: an instance
 * `void (boolean)` that takes an activity's window and clears the keep-awake flag on it.
 *
 * <p>`addFlags` is not part of the test. Another patch in this bundle, the screenshot guard, hands
 * every `Window.addFlags` call in Instagram to the extension, so by the time this patch runs the
 * helper may set the flag through that guard instead of calling the window directly. Clearing the
 * flag and reaching the window stay as they were, and they are what shapes this method apart from
 * the other window-flag callers.
 */
internal fun BytecodePatchContext.reelWindowAwakeHelper(): MutableMethod {
    val found = mutableListOf<Method>()
    classDefForEach { classDef ->
        classDef.methods.forEach { method ->
            if (method.keepsReelWindowAwake()) found += method
        }
    }
    val setter = found.filter { ADD_FLAGS in it.windowCalls() }
    val only = (if (setter.size == 1) setter.single() else null) ?: found.singleOrNull()
    if (only == null) {
        throw PatchException(
            "$PATCH: expected exactly one reel window awake helper in this Instagram build, found " +
                if (found.isEmpty()) {
                    "none"
                } else {
                    found.joinToString { "${it.definingClass}->${it.name} calls ${it.windowCalls().joinToString()}" }
                },
        )
    }
    return mutableClassDefBy(only.definingClass).methods.singleOrNull { it.keepsReelWindowAwake() }
        ?: throw PatchException("$PATCH: ${only.definingClass} no longer holds the helper it was found in")
}

/** An instance method taking one boolean and returning void, before its body is read. */
internal fun Method.hasKeepAwakeShape(): Boolean =
    !AccessFlags.STATIC.isSet(accessFlags) &&
        returnType == "V" &&
        parameterTypes.size == 1 &&
        parameterTypes.first().toString() == "Z"

/** Whether [this] is shaped like the reel keep-awake helper: it reads the window and clears the flag. */
internal fun Method.keepsReelWindowAwake(): Boolean {
    if (!hasKeepAwakeShape()) return false
    val calls = windowCalls()
    return GET_WINDOW in calls && CLEAR_FLAGS in calls
}

/**
 * The one method that takes the player's wrapper wake lock, as the mutable method to gate: a static
 * `void` on a class that holds a screen wake lock, taking one of its own objects and acquiring it.
 */
internal fun BytecodePatchContext.wakeLockWrapper(): MutableMethod {
    val found = mutableListOf<Method>()
    classDefForEach { classDef ->
        if (classDef.fields.any { it.type == WAKE_LOCK }) {
            classDef.methods.forEach { method ->
                if (method.takesScreenWakeLock()) found += method
            }
        }
    }
    val only = found.singleOrNull() ?: throw PatchException(
        "$PATCH: expected exactly one reel wake lock wrapper in this Instagram build, found " +
            if (found.isEmpty()) "none" else found.joinToString { "${it.definingClass}->${it.name}" },
    )
    return mutableClassDefBy(only.definingClass).methods.singleOrNull { it.takesScreenWakeLock() }
        ?: throw PatchException("$PATCH: ${only.definingClass} no longer holds the wrapper it was found in")
}

/** Whether [this] takes a wake lock on a wrapper of its own class. */
internal fun Method.takesScreenWakeLock(): Boolean =
    AccessFlags.STATIC.isSet(accessFlags) &&
        returnType == "V" &&
        parameterTypes.size == 1 &&
        parameterTypes.first().toString() == definingClass &&
        ACQUIRE in references()

/**
 * The instance method on the same wrapper that makes the lock and hands it over, the other door the
 * player's screen lock comes in by: a `void (boolean)` that creates a wake lock from its level.
 */
internal fun BytecodePatchContext.wrapperCreator(): MutableMethod {
    val type = wakeLockWrapper().definingClass
    val creator = mutableClassDefBy(type).methods.singleOrNull {
        it.returnType == "V" &&
            it.parameterTypes.size == 1 &&
            it.parameterTypes.first().toString() == "Z" &&
            NEW_WAKE_LOCK in it.references()
    } ?: throw PatchException("$PATCH: $type no longer holds the wake lock creator beside its taker")
    return creator
}

/**
 * The reel viewer's playback state dispatchers, as the mutable methods to read: the methods on the
 * state object that hand it to the viewer's listener, which is how every player update reaches the
 * reel viewer. They keep being called while a reel plays and stop when it does, which makes them the
 * tick this build actually has.
 */
internal fun BytecodePatchContext.clipStateDispatchers(): List<MutableMethod> {
    val helperType = reelWindowAwakeHelper().definingClass
    var viewer: ClassDef? = null
    classDefForEach { classDef ->
        if (viewer == null && classDef.fields.any { it.type == helperType }) viewer = classDef
    }
    val fragment = viewer
        ?: throw PatchException("$PATCH: found no class holding the reel keep-awake helper")
    val states = fragment.methods
        .mapNotNull { it.parameterTypes.singleOrNull()?.toString() }
        .distinct()
        .filter { type -> (classDefByOrNull(type)?.fields?.count { it.type == "F" } ?: 0) >= 2 }
    val stateType = states.singleOrNull() ?: throw PatchException(
        "$PATCH: expected one playback state type in ${fragment.type}, found " +
            if (states.isEmpty()) "none" else states.joinToString(),
    )
    val dispatchers = mutableClassDefBy(stateType).methods.filter {
        it.returnType == "V" && it.parameterTypes.size == 1
    }
    if (dispatchers.isEmpty()) throw PatchException("$PATCH: $stateType hands its state to nobody")
    return dispatchers
}
/** The methods on the player that read its own progress, the candidate per-tick signal. */
internal fun BytecodePatchContext.playerProgressChecks(): List<MutableMethod> {
    val manager = reelWakeLockRunner()
    val player = playerFieldOf(manager.definingClass)
        ?: throw PatchException("$PATCH: ${manager.definingClass} does not hold the player")
    val playerType = player.type.toString()
    val progress = classDefByOrNull(playerType)?.fields?.singleOrNull { it.type == "D" }
        ?: throw PatchException("$PATCH: $playerType has no progress field")
    val field = "$playerType->${progress.name}:D"
    val mutable = mutableClassDefBy(playerType)
    return mutable.methods.filter {
        it.returnType == "V" && it.parameterTypes.isEmpty() && field in it.references()
    }
}

/**
 * The method Instagram hands its player's play state to, as the mutable method to read: an instance
 * `void (boolean)` outside the wrapper that passes the new state to it.
 */
internal fun BytecodePatchContext.playStateSetter(): MutableMethod {
    val creator = wrapperCreator()
    val wrapperType = creator.definingClass
    val handOver = "$wrapperType->${creator.name}("
    val found = mutableListOf<Method>()
    classDefForEach { classDef ->
        if (classDef.type != wrapperType) {
            classDef.methods.forEach { method ->
                if (!AccessFlags.STATIC.isSet(method.accessFlags) &&
                    method.returnType == "V" &&
                    method.parameterTypes.size == 1 &&
                    method.parameterTypes.first().toString() == "Z" &&
                    method.references().any { it.startsWith(handOver) }
                ) {
                    found += method
                }
            }
        }
    }
    val only = found.singleOrNull() ?: throw PatchException(
        "$PATCH: expected exactly one play state setter in this Instagram build, found " +
            if (found.isEmpty()) "none" else found.joinToString { "${it.definingClass}->${it.name}" },
    )
    return mutableClassDefBy(only.definingClass).methods.single {
        it.name == only.name && it.parameterTypes.size == 1
    }
}
internal fun BytecodePatchContext.reelWakeLockRunner(): MutableMethod {
    val found = mutableListOf<Method>()
    classDefForEach { classDef ->
        if (playerFieldOf(classDef.type) != null) {
            classDef.methods.forEach { method ->
                if (method.managesScreenWakeLock()) found += method
            }
        }
    }
    val only = found.singleOrNull() ?: throw PatchException(
        "$PATCH: expected exactly one player screen wake lock manager in this Instagram build, found " +
            if (found.isEmpty()) "none" else found.joinToString { "${it.definingClass}->${it.name}" },
    )
    return mutableClassDefBy(only.definingClass).methods.singleOrNull { it.managesScreenWakeLock() }
        ?: throw PatchException("$PATCH: ${only.definingClass} no longer holds the lock manager it was found in")
}

/** Whether [this] is the lock manager: a `run()` that takes a wake lock and asks if one is held. */
internal fun Method.managesScreenWakeLock(): Boolean {
    if (AccessFlags.STATIC.isSet(accessFlags)) return false
    if (returnType != "V" || parameterTypes.isNotEmpty()) return false
    val references = references()
    return ACQUIRE in references && IS_HELD in references
}

/**
 * The player a lock manager watches: the field of [owner] whose type is a class that holds a
 * screen-on wake lock. Null when [owner] is not a lock manager's class.
 */
internal fun BytecodePatchContext.playerFieldOf(owner: String): Field? =
    classDefByOrNull(owner)?.fields?.firstOrNull { wakeLockFieldOf(it.type.toString()) != null }

/** The screen-on wake lock held by [owner], or null when that class holds none. */
internal fun BytecodePatchContext.wakeLockFieldOf(owner: String): Field? =
    classDefByOrNull(owner)?.fields?.firstOrNull { it.type == WAKE_LOCK }

/** Which of the three window calls this patch reads [this] method makes. */
internal fun Method.windowCalls(): Set<String> {
    val found = mutableSetOf<String>()
    implementation?.instructions?.forEach { instruction ->
        val reference = (instruction as? ReferenceInstruction)?.reference?.toString() ?: return@forEach
        if (reference == ADD_FLAGS || reference == CLEAR_FLAGS || reference == GET_WINDOW) found += reference
    }
    return found
}

/** Every method reference [this] method's body names, for the shape tests above. */
internal fun Method.references(): Set<String> {
    val found = mutableSetOf<String>()
    implementation?.instructions?.forEach { instruction ->
        val reference = (instruction as? ReferenceInstruction)?.reference?.toString() ?: return@forEach
        found += reference
    }
    return found
}
