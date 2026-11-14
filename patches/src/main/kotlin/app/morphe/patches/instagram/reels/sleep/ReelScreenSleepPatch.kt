/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.sleep

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireFreeAt
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities

private const val PATCH = "Let the screen sleep after a reel"

/** The extension's answer for Instagram's window keep-awake helper: set the flag, or drop it. */
internal const val ALLOW_AWAKE = "$EXTENSION_PACKAGE/reels/ReelScreenAwake;->allowAwake(I)Z"

/**
 * The extension's answer for a wake lock Instagram is about to take or hold: the lock, and the
 * {@code PowerManager} flags it was made with, so a lock that does not hold the display is left be.
 */
internal const val HOLD_WAKE_LOCK =
    "$EXTENSION_PACKAGE/reels/ReelScreenAwake;->keepScreenAwakeLock(Ljava/lang/Object;I)Z"



/** The reel viewer being handed a playback state. */
internal const val PROGRESSED = "$EXTENSION_PACKAGE/reels/ReelScreenAwake;->progressed()V"

/** The player checking its own playback progress. */
internal const val CHECKED = "$EXTENSION_PACKAGE/reels/ReelScreenAwake;->checked()V"

/** The player changing between playing and stopped. */
internal const val PLAYBACK_STATE = "$EXTENSION_PACKAGE/reels/ReelScreenAwake;->playbackState(I)V"

@Suppress("unused")
val letScreenSleepAfterReelPatch = bytecodePatch(
    name = "Let the screen sleep after a reel",
    description = "Keeps the screen awake while a reel is playing, then lets the phone's own screen timeout " +
        "sleep the display once playback stops, with a short dimmed pause first. Auto scroll in Reels has " +
        "to be off, since reels that keep playing keep the screen awake. On to start. Turn it off in " +
        "HushGram settings > Reels.",
) {
    category("Fixes")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.instagram())
    dependsOn(instagramExtensionPatch)

    execute {
        routeReelWindowAwake()
        routeReelProgress()
        routeReelWakeLock()
        routeReelWakeLockManager()
        enableStatus("screenSleepAfterReel")
    }
}

/**
 * Asks the extension first thing in Instagram's window keep-awake helper, before it decides whether
 * to set or clear the flag, and lets its answer replace what Instagram asked for.
 *
 * <p>The helper is untouched otherwise: with the switch off the answer is Instagram's own, and with
 * it on only the setting of the flag is turned down. That keeps this patch out of the way of any
 * other patch that rewrites the helper's own calls, such as the one that routes every window flag
 * call through the screenshot guard.
 */
internal fun BytecodePatchContext.routeReelWindowAwake() {
    val helper = reelWindowAwakeHelper()
    helper.addInstructions(
        0,
        """
            invoke-static { p1 }, $ALLOW_AWAKE
            move-result p1
        """,
    )
}

/**
 * Tells the extension every time the reel viewer is handed a playback state, which is what a playing
 * reel does on every player update and what a finished or paused one stops doing. The screen is held
 * on that, so it is let go when the pushes stop.
 */
internal fun BytecodePatchContext.routeReelProgress() {
    clipStateDispatchers().forEach { dispatcher ->
        dispatcher.addInstructions(
            0,
            """
                invoke-static { }, $PROGRESSED
            """,
        )
    }
    playerProgressChecks().forEach { check ->
        check.addInstructions(
            0,
            """
                invoke-static { }, $CHECKED
            """,
        )
    }
    playStateSetter().addInstructions(
        0,
        """
            invoke-static { p1 }, $PLAYBACK_STATE
        """,
    )
}

/**
 * Asks the extension at both doors of the wake lock wrapper Instagram's player uses: the static
 * method that takes the lock, and the instance method that makes it and hands it over. The wrapper
 * carries more than one kind of lock — the player's partial lock for sound keeps running with the
 * screen off — so the level goes along and only screen locks are turned down.
 *
 * <p>The extension also releases a lock already held, which the wrapper alone never does on a tick
 * that decides to keep it.
 */
internal fun BytecodePatchContext.routeReelWakeLock() {
    val wrapper = wakeLockWrapper()
    val type = wrapper.definingClass
    val classDef = mutableClassDefBy(type)
    val lock = classDef.fields.firstOrNull { it.type == WAKE_LOCK }
        ?: throw PatchException("$PATCH: $type has no screen wake lock field")
    val level = classDef.fields.singleOrNull { it.type == "I" }
        ?: throw PatchException("$PATCH: $type has no wake lock level field")
    listOf(wrapper, wrapperCreator()).forEach { method ->
        method.requireLocals(PATCH, 3)
        method.requireFreeAt(PATCH, 0, listOf(0, 1, 2))
        method.addInstructionsWithLabels(
            0,
            """
                iget-object v0, p0, $type->${lock.name}:$WAKE_LOCK
                iget v1, p0, $type->${level.name}:I
                invoke-static { v0, v1 }, $HOLD_WAKE_LOCK
                move-result v2
                if-eqz v2, :awake
                return-void
            """,
            ExternalLabel("awake", method.getInstruction(0)),
        )
    }
}

/**
 * Asks the extension first thing in the method that keeps the player's own wake lock on the
 * player's state, and leaves before it takes that lock when the switch is on.
 *
 * <p>This lock is made with {@code SCREEN_BRIGHT} and {@code ACQUIRE_CAUSES_WAKEUP} in the player's
 * constructor, so its level is not read here: a negative level is how the extension is told this is
 * a screen lock whose flags are already known.
 */
internal fun BytecodePatchContext.routeReelWakeLockManager() {
    val manager = reelWakeLockRunner()
    val type = manager.definingClass
    val player = playerFieldOf(type)
        ?: throw PatchException("$PATCH: $type does not hold the player it manages the wake lock of")
    val playerType = player.type.toString()
    val lock = wakeLockFieldOf(playerType)
        ?: throw PatchException("$PATCH: $playerType has no screen wake lock field")
    manager.requireLocals(PATCH, 3)
    manager.requireFreeAt(PATCH, 0, listOf(0, 1, 2))
    manager.addInstructionsWithLabels(
        0,
        """
            iget-object v0, p0, $type->${player.name}:$playerType
            iget-object v0, v0, $playerType->${lock.name}:$WAKE_LOCK
            const/4 v1, -1
            invoke-static { v0, v1 }, $HOLD_WAKE_LOCK
            move-result v2
            if-eqz v2, :awake
            return-void
        """,
        ExternalLabel("awake", manager.getInstruction(0)),
    )
}
