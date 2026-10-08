/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.confirm

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.Field

private const val LIKE_PATCH = "Ask before a like"

internal const val LIKE_CONFIRM = "$EXTENSION_PACKAGE/feed/LikeConfirm;"
internal const val HOLD_LIKE =
    "$LIKE_CONFIRM->hold(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/String;Ljava/lang/Object;I)Z"

/** The extension's stubs the patch fills: the handler's screen, and a second call to the handler. */
internal const val CONTEXT_OF_STUB = "contextOf"
internal const val LIKE_AGAIN_STUB = "likeAgain"

internal const val MEDIA = "Lcom/instagram/feed/media/Media;"
internal const val FRAGMENT = "Landroidx/fragment/app/Fragment;"
internal const val GET_CONTEXT = "$FRAGMENT->getContext()Landroid/content/Context;"
private const val FUNCTION0 = "Lkotlin/jvm/functions/Function0;"
private const val OBJECT = "Ljava/lang/Object;"

/**
 * The Like button's handler: an instance method taking the post, its position, the module name, a
 * callback and an index, which likes or unlikes the post and files "like_media" for a like. A
 * double tap goes through other code.
 */
internal object LikeButtonFingerprint : Fingerprint(
    returnType = "V",
    parameters = listOf(MEDIA, "L", "Ljava/lang/String;", FUNCTION0, "I"),
    strings = listOf("like_media"),
    custom = { method, _ -> !AccessFlags.STATIC.isSet(method.accessFlags) && !method.definingClass.startsWith(EXTENSION_ROOT) },
)

/**
 * The Like button under a post waits for a question while the switch is on. Included in the
 * default selection with its switch off, so asking is the user's pick.
 */
@Suppress("unused")
val askBeforeLikePatch = bytecodePatch(
    name = "Ask before a like",
    description = "Asks before the Like button under a post likes or unlikes it, so a stray tap doesn't. Continue " +
        "goes ahead and Cancel doesn't. Its switch, under Feed, starts off.",
    default = true,
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("askBeforeLike")
        askBeforeLike()
        enableStatus("askBeforeLike")
    }
}

private fun refuse(why: String): Nothing = throw PatchException("$LIKE_PATCH: $why")

/** The Like button's handler, the fragment its class keeps, and the extension's two stubs. */
internal class LikeButtonTargets(
    val handler: MutableMethod,
    val fragment: Field,
    val contextOf: MutableMethod,
    val likeAgain: MutableMethod,
)

/**
 * Finds the Like button's handler and checks it before anything changes: it's the one method of
 * its shape filing like_media, it and its class and the post's position class are public, its class
 * keeps one public fragment, the app's Fragment has getContext, it has a local for the hook's answer
 * and nothing jumps to its first instruction.
 */
internal fun BytecodePatchContext.findLikeButton(): LikeButtonTargets {
    val handler = uniqueMethod(LIKE_PATCH, "Like button handler filing like_media", LikeButtonFingerprint)
    val owner = classDefByOrNull(handler.definingClass) ?: refuse("${handler.definingClass} isn't in the app")
    if (!AccessFlags.PUBLIC.isSet(owner.accessFlags) || !AccessFlags.PUBLIC.isSet(handler.accessFlags)) {
        refuse("the Like button handler ${handler.definingClass}->${handler.name} isn't public")
    }
    val position = handler.parameterTypes[1].toString()
    val positionClass = classDefByOrNull(position)
    if (positionClass == null || !AccessFlags.PUBLIC.isSet(positionClass.accessFlags)) {
        refuse("the Like button handler takes $position, which isn't a public class in the app")
    }
    val fragments = owner.fields.filter { it.type == FRAGMENT && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val fragment = fragments.singleOrNull() ?: refuse("${owner.type} keeps ${fragments.size} fragments, not one")
    if (!AccessFlags.PUBLIC.isSet(fragment.accessFlags)) refuse("${owner.type}->${fragment.name} isn't public")
    if (classDefByOrNull(FRAGMENT)?.publicInstance("getContext", emptyList(), "Landroid/content/Context;") == null) {
        refuse("the app's Fragment has no public getContext()")
    }
    if (handler.localRegisterCount() < 1) refuse("${owner.type}->${handler.name} has no local for the answer")
    if (0 in handler.jumpTargets()) refuse("a jump or exception handler enters ${owner.type}->${handler.name} at its first instruction")

    val extension = classDefByOrNull(LIKE_CONFIRM) ?: refuse("the extension has no $LIKE_CONFIRM")
    extension.publicStatic(HOLD_LIKE) ?: refuse("the extension has no public static $HOLD_LIKE")
    val stubs = mutableClassDefBy(LIKE_CONFIRM)
    return LikeButtonTargets(
        handler = handler,
        fragment = fragment,
        contextOf = stubs.staticStub(CONTEXT_OF_STUB, listOf(OBJECT), "Landroid/content/Context;", 1)
            ?: refuse("$LIKE_CONFIRM has no static Context $CONTEXT_OF_STUB(Object)"),
        likeAgain = stubs.staticStub(LIKE_AGAIN_STUB, listOf(OBJECT, OBJECT, OBJECT, "Ljava/lang/String;", OBJECT, "I"), "V", 6)
            ?: refuse("$LIKE_CONFIRM has no static void $LIKE_AGAIN_STUB taking what the handler takes"),
    )
}

/**
 * Fills the stubs with the handler's screen (its fragment's context) and a call back into the
 * handler, then asks the extension first in the handler, with its own arguments. A yes returns
 * before Instagram does anything. The context stub works in its parameter's register alone, since a
 * compiled `return null` with an unused parameter may have no other.
 */
internal fun BytecodePatchContext.askBeforeLike() {
    val found = findLikeButton()
    val handler = found.handler
    val owner = handler.definingClass
    val position = handler.parameterTypes[1].toString()
    found.contextOf.addInstructions(
        0,
        """
            check-cast p0, $owner
            iget-object p0, p0, $owner->${found.fragment.name}:$FRAGMENT
            if-nez p0, :attached
            const/4 p0, 0x0
            return-object p0
            :attached
            invoke-virtual { p0 }, $GET_CONTEXT
            move-result-object p0
            return-object p0
        """,
    )
    found.likeAgain.addInstructions(
        0,
        """
            check-cast p0, $owner
            check-cast p1, $MEDIA
            check-cast p2, $position
            check-cast p4, $FUNCTION0
            invoke-virtual/range { p0 .. p5 }, $owner->${handler.name}($MEDIA${position}Ljava/lang/String;${FUNCTION0}I)V
            return-void
        """,
    )
    handler.addInstructionsWithLabels(
        0,
        """
            invoke-static/range { p0 .. p5 }, $HOLD_LIKE
            move-result v0
            if-eqz v0, :instagram
            return-void
        """,
        ExternalLabel("instagram", handler.getInstruction(0)),
    )
}
