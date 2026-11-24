/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.watchagain

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val WATCH_AGAIN_STATUS = "watchAgain"
internal const val WATCH_AGAIN_IN_FEED = "$EXTENSION_PACKAGE/feed/WatchAgain;->inFeed(I)Z"

internal const val WATCH_MORE_REELS = "$EXTENSION_PACKAGE/feed/WatchAgain;->moreReels(Landroid/view/View;)V"

internal const val WATCH_AGAIN_HERE = "$EXTENSION_PACKAGE/feed/WatchAgain;->replayHere()Z"

/** The source the end card's "Watch more reels" tap opens Reels with, loaded by its controller alone. */
internal const val CONTINUE_WATCHING = "continue_watching"

/** The name the end card's view holder throws with when its "Watch more reels" button is missing. */
internal const val KEEP_WATCHING_BUTTON = "feedPreviewKeepWatchingButton"

private const val TEXT_VIEW = "Landroid/widget/TextView;"
private const val VIEW_GROUP = "Landroid/view/ViewGroup;"
private const val CLICK_SETTER = "(Landroid/view/View\$OnClickListener;Landroid/view/View;)V"

private fun refuse(detail: String): Nothing = throw PatchException("Watch again in the feed: $detail")

/**
 * The end card controller and the flag field that has "Watch again" replay the reel in place, and
 * where the card hands "Watch again" its listener: the controller's setup [bind], and the in-place
 * listener it makes, a [listener] built by [init] from the controller and the number [case] (#568).
 */
internal class WatchAgainSite(
    val controller: String,
    val flag: String,
    val bind: String = "",
    val listener: String = "",
    val init: String = "",
    val case: Int = 0,
)

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
    // The in-place listener the copy hands on: built right before it from the controller and a case number.
    val source = (code[copy] as TwoRegisterInstruction).registerB
    val made = (copy - 1 downTo 0).firstOrNull { code[it].writes(source) } ?: refuse("nothing makes the in-place listener")
    val init = code[made].takeIf { it.opcode == Opcode.INVOKE_DIRECT }?.reference() as? MethodReference
        ?: refuse("the in-place listener isn't built right before the copy")
    val call = code[made] as FiveRegisterInstruction
    val self = (bind.implementation ?: refuse("the card's setup has no body")).registerCount - 1
    if (init.name != "<init>" || init.parameterTypes.map { it.toString() } != listOf("Ljava/lang/Object;", "I") ||
        call.registerCount != 3 || call.registerD != self || bind.parameterTypes.isNotEmpty() ||
        AccessFlags.STATIC.isSet(bind.accessFlags)
    ) {
        refuse("the in-place listener isn't built from the controller and a number in its setup: $init")
    }
    val created = (made - 1 downTo 0).firstOrNull { code[it].writes(source) }?.let { code[it] }
    if (created?.opcode != Opcode.NEW_INSTANCE || created.reference().toString() != init.definingClass) {
        refuse("the in-place listener isn't a new ${init.definingClass}")
    }
    val number = (made - 1 downTo 0).firstOrNull { code[it].writes(call.registerE) }?.let { code[it] as? NarrowLiteralInstruction }
        ?: refuse("the in-place listener's number isn't a constant")
    return WatchAgainSite(controller.type, flag.toString(), methodText(bind), init.definingClass, init.toString(), number.narrowLiteral)
}

/** Whether this writes register [register] as its first, written register (a move-result counts for the call before it). */
private fun Instruction.writes(register: Int): Boolean = when {
    opcode == Opcode.NEW_INSTANCE || opcode == Opcode.MOVE_RESULT || opcode == Opcode.MOVE_RESULT_OBJECT ||
        opcode == Opcode.CONST_4 || opcode == Opcode.CONST_16 || opcode == Opcode.CONST || opcode == Opcode.CONST_HIGH16 ||
        opcode == Opcode.CONST_STRING || opcode == Opcode.IGET_OBJECT || opcode == Opcode.IGET_BOOLEAN || opcode == Opcode.SGET_OBJECT ||
        opcode in MOVES || opcode == Opcode.MOVE || opcode == Opcode.CHECK_CAST ->
        (this as OneRegisterInstruction).registerA == register
    // A constructor fills the object it's handed in place.
    opcode == Opcode.INVOKE_DIRECT && (reference() as? MethodReference)?.name == "<init>" ->
        (this as FiveRegisterInstruction).registerC == register
    else -> false
}

/**
 * Right before the card hands "Watch again" its listener, a new in-place listener takes the
 * listener's place while WatchAgain.replayHere says so (#568). Past the replay flag the card can
 * still pick a listener that opens the reel in another viewer: in Saved, on a profile's posts and in
 * the other lists of posts, where the card shows Watch again alone.
 */
internal fun BytecodePatchContext.replayWatchAgainHere(site: WatchAgainSite) {
    val bind = mutableClassDefBy(site.controller).methods.single { methodText(it) == site.bind }
    val code = bind.code()
    val reads = code.indices.mapNotNull { at -> watchAgainListener(code, at)?.let { at to it } }
    val (read, listener) = reads.singleOrNull() ?: refuse("expected one Watch again listener in ${site.bind}, found ${reads.size}")
    val setter = if (code[read + 1].opcode == Opcode.IF_EQZ) read + 2 else read + 1
    if (setter in bind.jumpTargets()) refuse("something branches onto the Watch again listener's setter")
    val self = bind.implementation!!.registerCount - 1
    val view = (code[setter] as FiveRegisterInstruction).registerD
    if (listener > 15 || self > 15) refuse("the listener v$listener or the controller v$self is past v15")
    val free = bind.freeLocalsAt("Watch again in the feed", setter, 1, except = listOf(listener, view, self)).single()
    if (free > 15) refuse("no free register up to v15 before the Watch again listener's setter")
    bind.addInstructionsWithLabels(
        setter,
        """
            invoke-static { }, $WATCH_AGAIN_HERE
            move-result v$free
            if-eqz v$free, :keep
            new-instance v$listener, ${site.listener}
            const v$free, ${site.case}
            invoke-direct { v$listener, v$self, v$free }, ${site.init}
        """,
        ExternalLabel("keep", bind.getInstruction(setter)),
    )
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

/** The controller method that gives "Watch more reels" its tap, the holder field, and the button's getter. */
internal class WatchMoreReelsSite(val controller: String, val bind: String, val holder: String, val button: String)

/**
 * The view holder's getter for "Watch more reels" is the one no-argument ViewGroup getter that throws
 * with [KEEP_WATCHING_BUTTON]. The controller method that hands what it returns to the click setter
 * is where the card has shown the button and wired its tap.
 */
internal fun BytecodePatchContext.findWatchMoreReels(controller: String): WatchMoreReelsSite {
    val getters = classesLoadingString(KEEP_WATCHING_BUTTON).flatMap { owner ->
        owner.methods.filter { it.parameterTypes.isEmpty() && it.returnType == VIEW_GROUP && loadsString(it, KEEP_WATCHING_BUTTON) }
    }
    val getter = getters.singleOrNull() ?: refuse("expected one Watch more reels getter, found ${getters.size}")
    val owner = mutableClassDefBy(controller)
    val holders = owner.fields.filter { it.type == getter.definingClass && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val holder = holders.singleOrNull() ?: refuse("expected one view holder field, found ${holders.size}")
    val binds = owner.methods.filter { wiresButton(it.code(), getter) }
    val bind = binds.singleOrNull() ?: refuse("expected one method wiring Watch more reels, found ${binds.size}")
    return WatchMoreReelsSite(controller, methodText(bind), fieldText(holder), methodText(getter))
}

/** Before each return of that method, the button goes to the extension, which hides it while the switch is on. */
internal fun BytecodePatchContext.hideWatchMoreReels(site: WatchMoreReelsSite) {
    val bind = mutableClassDefBy(site.controller).methods.single { methodText(it) == site.bind }
    val code = bind.code()
    val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_VOID }
    if (returns.isEmpty()) refuse("${site.bind} never returns")
    for (at in returns.reversed()) {
        bind.addInstructionsAtControlFlowLabel(at, """
            move-object/from16 v0, p0
            iget-object v0, v0, ${site.holder}
            invoke-virtual { v0 }, ${site.button}
            move-result-object v0
            invoke-static { v0 }, $WATCH_MORE_REELS
        """.trimIndent())
    }
}

/** Whether [code] reads the button off the holder and hands it, as the view, to a static click setter. */
private fun wiresButton(code: List<Instruction>, getter: Method): Boolean {
    val target = methodText(getter)
    for (at in code.indices) {
        if (code[at].opcode != Opcode.INVOKE_VIRTUAL || code[at].reference()?.toString() != target) continue
        val result = code.getOrNull(at + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } ?: continue
        val button = (result as OneRegisterInstruction).registerA
        val wired = code.drop(at + 2).any { call ->
            if (call.opcode != Opcode.INVOKE_STATIC) return@any false
            val method = call.reference() as MethodReference
            val shape = "(${method.parameterTypes.joinToString("")})${method.returnType}"
            shape == CLICK_SETTER && (call as FiveRegisterInstruction).registerCount == 2 && call.registerD == button
        }
        if (wired) return true
    }
    return false
}

private fun fieldText(field: Field) = "${field.definingClass}->${field.name}:${field.type}"
private fun methodText(method: Method) =
    "${method.definingClass}->${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}"

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
 * Has "Watch again" on a feed reel's end card replay it in place instead of opening Reels (#568), in
 * Saved and the other lists of posts too, and can take "Watch more reels" off that card (#652). See
 * WatchAgain in the extension. In the default selection with both switches off.
 */
@Suppress("unused")
val watchAgainInFeedPatch = bytecodePatch(
    name = "Watch again in the feed",
    description = "When a reel in your feed ends, Watch again plays it over right there instead of opening it in " +
        "Reels, and Hide Watch more reels takes the other button off that card. Both start off. Turn them on in " +
        "HushGram settings > Feed.",
) {
    category("Fixes")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod(WATCH_AGAIN_STATUS)
        val site = findWatchAgain()
        replayWatchAgainInFeed(site)
        replayWatchAgainHere(site)
        hideWatchMoreReels(findWatchMoreReels(site.controller))
        enableStatus(WATCH_AGAIN_STATUS)
    }
}
