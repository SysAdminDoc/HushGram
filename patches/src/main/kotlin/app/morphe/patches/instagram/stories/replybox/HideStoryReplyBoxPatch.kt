/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.replybox

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.media.taptoplay.REEL_VIEWER_FRAGMENT
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Hide the reply box on stories"
internal const val STORY_REPLY_BOX = "$EXTENSION_PACKAGE/stories/StoryReplyBox;"
internal const val STORY_REPLY_BOX_READER = "$EXTENSION_PACKAGE/stories/StoryReplyBoxReader;"
internal const val REPLY_BOX_RESUMED = "$STORY_REPLY_BOX->resumed(Ljava/lang/Object;)V"
internal const val REPLY_BOX_VIEW_CREATED = "$STORY_REPLY_BOX->viewCreated(Ljava/lang/Object;)V"

private const val LIFECYCLE = "onResume"
private const val VIEW_CREATED = "onViewCreated"
private const val VIEW = "Landroid/view/View;"
private val VIEW_CREATED_PARAMS = listOf(VIEW, "Landroid/os/Bundle;")
private const val VIEW_STUB = "(Ljava/lang/Object;)$VIEW"

/**
 * Keeps the Send message box off other people's stories (#486), and on switches of their own the
 * Like, Comment and Share buttons (#618) and the comments floating over stories (#826), and lifts
 * the line for writing a comment on a choice (#826). See StoryReplyBox in the extension. In the
 * default selection with its switches off.
 *
 * The patch fills the extension's one stub with the story viewer's (ReelViewerFragment's) own view
 * and, right after the viewer's onResume hands on to its superclass, tells the extension the viewer
 * came back. It also tells it first thing in the viewer's onViewCreated, since Instagram draws a
 * story opened from a ring for a few frames before the viewer resumes, and those frames showed the
 * hidden buttons (#618). Everything else happens in the extension, on the viewer's window. Nothing
 * of Instagram's changes besides those two calls.
 */
@Suppress("unused")
val hideStoryReplyBoxPatch = bytecodePatch(
    name = "Hide the reply box on stories",
    description = "Takes the Send message box off the bottom of other people's stories. The like and share " +
        "buttons next to it stay where they are. Switches of their own take the Like, Comment and Share " +
        "buttons off too, with no empty space left behind, and the comments floating over stories. A choice lifts " +
        "the line for writing a comment. All start off. Set them in HushGram settings > Stories.",
) {
    category("Stories")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("storyReplyBox")
        hookStoryReplyBox(findStoryReplyBox())
        enableStatus("storyReplyBox")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Finds, before anything changes, the public viewer, its own onResume with one call on to its
 * superclass's on `this` whose next instruction nothing jumps to, and the extension's stub. Answers
 * the viewer's type.
 */
internal fun BytecodePatchContext.findStoryReplyBox(): String {
    val viewer = classDefByOrNull(REEL_VIEWER_FRAGMENT) ?: refuse("this build has no $REEL_VIEWER_FRAGMENT")
    if (!AccessFlags.PUBLIC.isSet(viewer.accessFlags)) refuse("$REEL_VIEWER_FRAGMENT isn't public")
    viewer.resumeSite()
    viewer.viewCreatedSite()
    val reader = classDefByOrNull(STORY_REPLY_BOX_READER) ?: refuse("the extension has no $STORY_REPLY_BOX_READER")
    reader.methods.singleOrNull { it.name == "view" && AccessFlags.STATIC.isSet(it.accessFlags) && it.shape() == VIEW_STUB }
        ?: refuse("$STORY_REPLY_BOX_READER has no static view$VIEW_STUB")
    return viewer.type
}

/**
 * The index just past the viewer's one call on to its superclass's onResume, whose first register
 * is `this`, refused when it already tells the extension, when something jumps there or when `this`
 * may hold something else by then.
 */
private fun ClassDef.resumeSite(): Int {
    val method = methods.singleOrNull {
        it.name == LIFECYCLE && it.returnType == "V" && it.parameterTypes.isEmpty() &&
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null
    } ?: refuse("$REEL_VIEWER_FRAGMENT has no $LIFECYCLE() of its own")
    val code = method.code()
    if (code.any { it.method()?.definingClass == STORY_REPLY_BOX }) refuse("$REEL_VIEWER_FRAGMENT->$LIFECYCLE already calls $STORY_REPLY_BOX")
    val self = method.localRegisterCount()
    val supers = code.indices.filter { at ->
        val called = code[at].method()
        (code[at].opcode == Opcode.INVOKE_SUPER || code[at].opcode == Opcode.INVOKE_SUPER_RANGE) && called != null &&
            called.name == LIFECYCLE && called.parameterTypes.isEmpty() && called.returnType == "V"
    }
    val call = supers.singleOrNull() ?: refuse("expected $REEL_VIEWER_FRAGMENT->$LIFECYCLE to call its superclass's once, found ${supers.size}")
    val first = (code[call] as? FiveRegisterInstruction)?.registerC
    if (first != self) refuse("$REEL_VIEWER_FRAGMENT->$LIFECYCLE calls its superclass's on something other than this")
    val at = call + 1
    if (at >= code.size) refuse("$REEL_VIEWER_FRAGMENT->$LIFECYCLE ends at its call to its superclass's")
    if (at in method.jumpTargets()) refuse("something in $REEL_VIEWER_FRAGMENT->$LIFECYCLE jumps to just after its call to its superclass's")
    method.requireThisIntact(PATCH, listOf(at))
    return at
}

/**
 * The viewer's own onViewCreated(View, Bundle), whose first instruction the new call goes before,
 * refused when it already tells the extension or when something jumps back to its start.
 */
private fun ClassDef.viewCreatedSite(): Method {
    val method = methods.singleOrNull {
        it.name == VIEW_CREATED && it.returnType == "V" && it.params() == VIEW_CREATED_PARAMS &&
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null
    } ?: refuse("$REEL_VIEWER_FRAGMENT has no $VIEW_CREATED(View, Bundle) of its own")
    if (method.code().any { it.method()?.definingClass == STORY_REPLY_BOX }) {
        refuse("$REEL_VIEWER_FRAGMENT->$VIEW_CREATED already calls $STORY_REPLY_BOX")
    }
    if (0 in method.jumpTargets()) refuse("something in $REEL_VIEWER_FRAGMENT->$VIEW_CREATED jumps back to its start")
    return method
}

/** Fills the stub and puts the calls in, finding the sites again on the class as it is now. */
internal fun BytecodePatchContext.hookStoryReplyBox(viewer: String) {
    // The stub's own register is its parameter, so the code below uses that.
    mutableClassDefBy(STORY_REPLY_BOX_READER).methods.single { it.name == "view" && it.shape() == VIEW_STUB }.addInstructions(
        0,
        """
            check-cast p0, $viewer
            invoke-virtual { p0 }, $viewer->getView()$VIEW
            move-result-object p0
            return-object p0
        """,
    )
    val target = mutableClassDefBy(viewer)
    val at = target.resumeSite()
    target.methods.single { it.name == LIFECYCLE && it.parameterTypes.isEmpty() && it.returnType == "V" }
        .addInstructions(at, "invoke-static { p0 }, $REPLY_BOX_RESUMED")
    target.viewCreatedSite()
    target.methods.single { it.name == VIEW_CREATED && it.params() == VIEW_CREATED_PARAMS && it.returnType == "V" }
        .addInstructions(0, "invoke-static/range { p0 .. p0 }, $REPLY_BOX_VIEW_CREATED")
}

private fun Method.shape(): String = "(${parameterTypes.joinToString("")})$returnType"

private fun Method.params(): List<String> = parameterTypes.map { it.toString() }

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.method(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference
