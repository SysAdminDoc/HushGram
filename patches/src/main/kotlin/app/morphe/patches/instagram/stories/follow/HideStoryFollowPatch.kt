/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.follow

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.parameterRegister
import app.morphe.patches.instagram.misc.extension.requireLocals
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.stories.seen.BIND_BUTTON
import app.morphe.patches.instagram.stories.seen.StoryHeaderBinderFingerprint
import app.morphe.patches.instagram.stories.seen.ViewHolderFingerprint
import app.morphe.patches.instagram.stories.seen.itemViewField
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.extendsClass
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

private const val PATCH = "Hide Follow on stories"
internal const val STORY_FOLLOW_BUTTON = "$EXTENSION_PACKAGE/stories/StoryFollowButton;"
internal const val BIND_FOLLOW = "$STORY_FOLLOW_BUTTON->bind(Ljava/lang/Object;)V"

private const val OBJECT = "Ljava/lang/Object;"
private const val VIEW = "Landroid/view/View;"

/**
 * Hides the Follow button a story's header shows for an account you don't follow (#237), and leaves
 * the rest of the header as it is. Instagram's server used to keep that button off for most
 * accounts; since the flag behind it went away, the header shows it beside More actions.
 *
 * The button is a TextView, `reel_header_unconnected_follow_button_stub`, inflated from a ViewStub
 * of that id into the header's row of buttons. The extension keeps it gone while the header is on
 * screen, so it leaves no gap, and never taps or calls anything on it. In the default selection
 * with its switch off, since it changes how Instagram looks.
 */
@Suppress("unused")
val hideStoryFollowPatch = bytecodePatch(
    name = "Hide Follow on stories",
    description = "Takes the Follow button off the top of stories from accounts you don't follow. The name, " +
        "time and menu stay. Starts off. Turn it on in HushGram settings > Stories.",
) {
    category("Stories")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("storyFollow")
        hideStoryFollow()
        enableStatus("storyFollow")
    }
}

/** Finds everything first, so a build missing any of it changes nothing, then hooks the binder. */
internal fun BytecodePatchContext.hideStoryFollow() {
    val found = findStoryFollow()
    hookStoryFollow(found)
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Where the hook goes: the story item binder's toolbar bind, the parameter holding the story's view
 * holder, the view holder base's item view field the stub reads, and the stub itself.
 */
internal class StoryFollowTargets(
    val binder: String,
    val binderName: String,
    val binderParameters: List<String>,
    val holder: Int,
    val itemView: FieldReference,
    val itemViewStub: MutableMethod,
)

/**
 * Finds the toolbar bind View stories anonymously's Mark as seen button hooks, the one static method
 * holding [app.morphe.patches.instagram.stories.seen.STORY_BINDER_TRACE], and its one view holder
 * parameter, then the item view the view holder base keeps. Fails before anything changes when any
 * of it isn't there exactly once, or the extension can't reach it.
 */
internal fun BytecodePatchContext.findStoryFollow(): StoryFollowTargets {
    val binder = uniqueMethod(PATCH, "story item binder's toolbar bind", StoryHeaderBinderFingerprint)
    val where = "${binder.definingClass}->${binder.name}"
    if (!AccessFlags.STATIC.isSet(binder.accessFlags)) refuse("$where isn't static, as the story item binder's toolbar bind is")
    val parameters = binder.parameterTypes.map(Any::toString)
    val holderInit = uniqueMethod(PATCH, "view holder constructor", ViewHolderFingerprint)
    val base = holderInit.definingClass
    val holders = parameters.indices.filter { parameters[it].startsWith("L") && extendsClass(parameters[it], base) }
    val holder = holders.singleOrNull() ?: refuse("expected $where to take one view holder, found ${holders.size}")
    binder.requireLocals(PATCH, 1)

    val itemView = itemViewField(holderInit, PATCH)
    val baseClass = classDefByOrNull(base) ?: refuse("$base isn't in this build")
    if (!AccessFlags.PUBLIC.isSet(baseClass.accessFlags)) refuse("$base isn't public, so the extension can't reach it")

    val button = classDefByOrNull(STORY_FOLLOW_BUTTON)?.let { mutableClassDefBy(STORY_FOLLOW_BUTTON) }
        ?: refuse("$STORY_FOLLOW_BUTTON isn't in the extension")
    button.methods.singleOrNull {
        "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == BIND_FOLLOW.substringAfter("->") &&
            AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
    } ?: refuse("$STORY_FOLLOW_BUTTON has no public static hook ${BIND_FOLLOW.substringAfter("->")}")
    val stub = button.methods.singleOrNull {
        it.name == "itemView" && it.returnType == VIEW && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(Any::toString) == listOf(OBJECT)
    } ?: refuse("$STORY_FOLLOW_BUTTON has no static $VIEW itemView($OBJECT)")

    return StoryFollowTargets(binder.definingClass, binder.name, parameters, holder, itemView, stub)
}

/**
 * Hands the view holder to [BIND_FOLLOW] at the start of the toolbar bind, through one borrowed
 * local, and fills the stub that reads its item view.
 *
 * View stories anonymously puts its own hook first in the same method, and a rule holds it first
 * there. So when its three moves and its call are already at the start, this hook goes right after
 * them; when they aren't, it goes first, and that patch, applied later, puts its own in front.
 * Either way the method starts with its hook, then this one, then Instagram's code. Nothing in
 * either hook branches, and the locals they borrow are free until Instagram's first instruction.
 */
internal fun BytecodePatchContext.hookStoryFollow(found: StoryFollowTargets) {
    val binder = mutableClassDefBy(found.binder).methods.single {
        it.name == found.binderName && it.parameterTypes.map(Any::toString) == found.binderParameters
    }
    val where = "${binder.definingClass}->${binder.name}"
    val code = binder.implementation?.instructions?.toList().orEmpty()
    val at = if (code.seenButtonFirst()) 4 else 0
    if (code.count { it.reference() == BIND_FOLLOW } != 0) refuse("$where already calls $BIND_FOLLOW")
    if (at in binder.jumpTargets()) refuse("a branch in $where lands on its first instruction, where the hook goes")
    binder.addInstructionsWithLabels(
        at,
        """
            move-object/from16 v0, ${binder.parameterRegister(found.holder)}
            invoke-static { v0 }, $BIND_FOLLOW
        """,
    )
    found.itemViewStub.addInstructionsWithLabels(
        0,
        """
            check-cast p0, ${found.itemView.definingClass}
            iget-object p0, p0, ${found.itemView}
            return-object p0
        """,
    )
}

/** Whether the method starts with View stories anonymously's hook: three moves, then its call. */
private fun List<Instruction>.seenButtonFirst(): Boolean =
    size > 4 && take(3).all { it.opcode == Opcode.MOVE_OBJECT_FROM16 } &&
        this[3].opcode == Opcode.INVOKE_STATIC && this[3].reference() == BIND_BUTTON

private fun Instruction.reference(): String? = (this as? ReferenceInstruction)?.reference?.toString()
