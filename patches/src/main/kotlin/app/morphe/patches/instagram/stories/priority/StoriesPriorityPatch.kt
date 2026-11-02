/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.priority

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.download.USER
import app.morphe.patches.instagram.download.pandoGetter
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.reels.partnership.fillStub
import app.morphe.patches.instagram.reels.partnership.isStaticStub
import app.morphe.patches.instagram.stories.seen.StoryTrayTargets
import app.morphe.patches.instagram.stories.seen.findStoryRings
import app.morphe.patches.instagram.stories.seen.findStoryTray
import app.morphe.patches.instagram.stories.seen.orderStoryTray
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags

private const val PATCH = "Stories priority"
internal const val STORY_PRIORITY = "$EXTENSION_PACKAGE/stories/StoryPriority;"
internal const val PRIORITY_ORDER = "$STORY_PRIORITY->order(Ljava/util/List;Ljava/lang/Object;)Ljava/util/List;"

private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"

/**
 * Puts the accounts on the user's list first in Home's stories row while they have a story the user
 * hasn't watched, in the list's order. It hands the same reel store lists View stories anonymously
 * orders to the extension, after that patch's own hook, and reads each reel's account name through
 * the User model's username getter. In the default selection with its switch off and an empty list,
 * so nothing changes until the user types accounts in. Asked for in #451.
 */
@Suppress("unused")
val storiesPriorityPatch = bytecodePatch(
    name = "Stories priority",
    description = "Puts the accounts on your list first in the stories row at the top of Home while they have a " +
        "story you haven't watched. Starts off. Turn it on and type the accounts in HushGram settings > Stories.",
) {
    category("Stories")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("storyPriority")
        val rings = findStoryRings()
        val tray = findStoryTray(rings.reelClass, STORY_PRIORITY, PRIORITY_ORDER)
        val username = findPriorityUsername(tray)
        prioritizeStories(tray, username)
        enableStatus("storyPriority")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/** The body of the extension's username stub, written once the getters it calls are found. */
internal fun BytecodePatchContext.findPriorityUsername(tray: StoryTrayTargets): String {
    val extension = classDefByOrNull(STORY_PRIORITY) ?: refuse("the extension has no $STORY_PRIORITY")
    if (extension.methods.none { it.isStaticStub("username", listOf(OBJECT), STRING) }) {
        refuse("the extension has no static username(Object)String to fill")
    }
    val getter = pandoGetter(PATCH, USER, "username", STRING)
    if (!AccessFlags.PUBLIC.isSet((classDefByOrNull(USER) ?: refuse("$USER isn't in this build")).accessFlags)) {
        refuse("$USER isn't public, so the extension can't reach it")
    }
    if (!AccessFlags.PUBLIC.isSet(getter.accessFlags)) refuse("$USER->${getter.name} isn't public")
    return """
        instance-of v0, p0, ${tray.reelClass}
        if-eqz v0, :none
        check-cast p0, ${tray.reelClass}
        invoke-virtual { p0 }, ${tray.identity.owner}
        move-result-object v0
        if-eqz v0, :none
        invoke-virtual { v0 }, $USER->${getter.name}()$STRING
        move-result-object v0
        return-object v0
        :none
        const/4 v0, 0x0
        return-object v0
    """
}

/** Fills the username stub, then hands each tray list to [PRIORITY_ORDER] just before it returns. */
internal fun BytecodePatchContext.prioritizeStories(tray: StoryTrayTargets, username: String) {
    fillStub(STORY_PRIORITY, "username", STRING, 2, username)
    orderStoryTray(tray)
}
