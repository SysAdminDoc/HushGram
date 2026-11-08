/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.watchagain

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val WATCH_AGAIN_STATUS = "watchAgain"
internal const val WATCH_AGAIN_IN_FEED = "$EXTENSION_PACKAGE/feed/WatchAgain;->inFeed(I)Z"

/** The source the end card's "Watch more reels" tap opens Reels with, loaded by its controller alone. */
internal const val CONTINUE_WATCHING = "continue_watching"

private const val TEXT_VIEW = "Landroid/widget/TextView;"
private const val CLICK_SETTER = "(Landroid/view/View\$OnClickListener;Landroid/view/View;)V"

private fun refuse(detail: String): Nothing = throw PatchException("Watch again in the feed: $detail")

/** The end card controller and the flag field that has "Watch again" replay the reel in place. */
internal class WatchAgainSite(val controller: String, val flag: String)

/**
 * The end card controller is the class whose "Watch more reels" tap loads [CONTINUE_WATCHING], built
 * from a Context, its parts and five server flags. One of its methods hands "Watch again" (the one
 * TextView it reads off the card and gives a click listener) its listener, which is Instagram's
 * in-place replay only where a `move-object` copies that listener in under an `if-eqz` on one of
 * those flags. That flag is the one.
 */
internal fun BytecodePatchContext.findWatchAgain(): WatchAgainSite {
    val controllers = classesLoadingString(CONTINUE_WATCHING).filter { owner ->
        owner.methods.any { loadsString(it, CONTINUE_WATCHING) } && owner.methods.any { it.isController() }
    }
    val controller = controllers.singleOrNull() ?: refuse("expected one end card controller, found ${controllers.size}")
    val sites = controller.methods.flatMap { method ->
        val code = method.code()
        code.indices.mapNotNull { at -> watchAgainListener(code, at)?.let { Triple(method, at, it) } }
    }
    val (bind, at, listener) = sites.singleOrNull() ?: refuse("expected one Watch again listener, found ${sites.size}")
    val code = bind.code()
    val copies = (0 until at).filter { copy ->
        code[copy].opcode in MOVES && (code[copy] as OneRegisterInstruction).registerA == listener
    }
    val copy = copies.singleOrNull() ?: refuse("expected one in-place listener copy, found ${copies.size}")
    val test = code.getOrNull(copy - 1)?.takeIf { it.opcode == Opcode.IF_EQZ } ?: refuse("no flag test over the copy")
    val read = code.getOrNull(copy - 2)?.takeIf { it.opcode == Opcode.IGET_BOOLEAN } ?: refuse("no flag read over the copy")
    if ((read as TwoRegisterInstruction).registerA != (test as OneRegisterInstruction).registerA) refuse("the test reads another register")
    val flag = read.reference() as FieldReference
    if (flag.definingClass != controller.type) refuse("the flag $flag is not the controller's")
    return WatchAgainSite(controller.type, flag.toString())
}

/** Every constructor storing the flag hands it to the extension first, so the controller keeps the answer. */
internal fun BytecodePatchContext.replayWatchAgainInFeed(site: WatchAgainSite) {
    var hooked = 0
    for (constructor in mutableClassDefBy(site.controller).methods.filter { it.name == "<init>" }) {
        val code = constructor.code()
        val stores = code.indices.filter { code[it].opcode == Opcode.IPUT_BOOLEAN && code[it].reference()?.toString() == site.flag }
        for (at in stores.reversed()) {
            val value = (code[at] as TwoRegisterInstruction).registerA
            constructor.addInstructions(at, """
                invoke-static/range { v$value .. v$value }, $WATCH_AGAIN_IN_FEED
                move-result v$value
            """.trimIndent())
            hooked++
        }
    }
    if (hooked == 0) refuse("no constructor stores ${site.flag}")
}

private val MOVES = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)

/** The controller's constructor: a Context first and at least three flags after it. */
private fun Method.isController() =
    name == "<init>" && parameterTypes.firstOrNull()?.toString() == "Landroid/content/Context;" &&
        parameterTypes.count { it.toString() == "Z" } >= 3

/**
 * The listener register when [at] reads a TextView off the card and, past an optional null check,
 * hands it to a static click setter as the view.
 */
private fun watchAgainListener(code: List<Instruction>, at: Int): Int? {
    val read = code[at].takeIf { it.opcode == Opcode.IGET_OBJECT } ?: return null
    if ((read.reference() as FieldReference).type != TEXT_VIEW) return null
    val view = (read as TwoRegisterInstruction).registerA
    var next = at + 1
    if (code.getOrNull(next)?.opcode == Opcode.IF_EQZ) next++
    val call = code.getOrNull(next)?.takeIf { it.opcode == Opcode.INVOKE_STATIC } ?: return null
    val method = call.reference() as MethodReference
    val shape = "(${method.parameterTypes.joinToString("")})${method.returnType}"
    if (shape != CLICK_SETTER || (call as FiveRegisterInstruction).registerCount != 2 || call.registerD != view) return null
    return call.registerC
}

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

/**
 * Has "Watch again" on a feed reel's end card replay it in place instead of opening Reels (#568).
 * See WatchAgain in the extension. "Watch more reels" still opens Reels. In the default selection
 * with its switch off.
 */
@Suppress("unused")
val watchAgainInFeedPatch = bytecodePatch(
    name = "Watch again in the feed",
    description = "When a reel in your feed ends, Watch again plays it over right there instead of opening it in " +
        "Reels. Watch more reels still opens Reels. Starts off. Turn it on in HushGram settings > Feed.",
) {
    category("Fixes")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod(WATCH_AGAIN_STATUS)
        replayWatchAgainInFeed(findWatchAgain())
        enableStatus(WATCH_AGAIN_STATUS)
    }
}
