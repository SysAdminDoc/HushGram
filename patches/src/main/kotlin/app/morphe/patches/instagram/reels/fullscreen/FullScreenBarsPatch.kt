/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.fullscreen

import app.morphe.patcher.Fingerprint
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.string
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val FULL_SCREEN_BARS_PATCH = "Full screen Reels and Home"
internal const val FULL_SCREEN_BARS = "$EXTENSION_PACKAGE/reels/FullScreenBars;"
internal const val BAR_HEIGHT_HOOK = "$FULL_SCREEN_BARS->barHeight(I)I"
internal const val NAV_BAR_HEIGHT_HOOK = "$FULL_SCREEN_BARS->navBarHeight(I)I"
internal const val KEEP_STATUS_BAR_HEIGHT = "$FULL_SCREEN_BARS->keepStatusBarHeight(I)V"
internal const val KEEP_NAV_BAR_HEIGHT = "$FULL_SCREEN_BARS->keepNavBarHeight(I)V"
internal const val HEIGHTS_ASKED = "$FULL_SCREEN_BARS->heightsAsked(Landroid/app/Activity;Ljava/lang/Object;)V"
internal const val HEIGHTS_DROPPED = "$FULL_SCREEN_BARS->heightsDropped(Landroid/app/Activity;Ljava/lang/Object;)V"
internal const val TELL_HEIGHTS = "$FULL_SCREEN_BARS->tellHeights(Ljava/lang/Object;II)V"
private const val ACTIVITY = "Landroid/app/Activity;"

/** What Instagram's window insets listener logs when it keeps a new status bar height. */
internal const val STATUS_BAR_CHANGED =
    "Status bar height: old=%d new=%d, Activity=%s RetryCount=%d isFullScreenFlagSet=%b windowFlags=0x%X"

/** The same for the navigation bar, spelled as Instagram spells it. */
internal const val NAV_BAR_CHANGED =
    "Nab bar height: old=%d new=%d, Activity=%s RetryCount=%d isFullScreenFlagSet=%b windowFlags=0x%X"

/** Instagram's window insets listener on each activity's window, by the two lines it logs. */
internal object BarHeightsFingerprint : Fingerprint(
    filters = listOf(string(STATUS_BAR_CHANGED)),
    custom = { method, _ -> method.holdsString(STATUS_BAR_CHANGED) && method.holdsString(NAV_BAR_CHANGED) },
)

/**
 * Has both bar heights Instagram's window insets listener keeps ask FullScreenBars first, once for
 * every patch that changes them: Full screen Reels and Home (#147) and Glass tab bar, whose floating
 * pill lets the screens run down behind the gesture handle (#144). Written twice, the listener would
 * ask twice for each height. The status bar's height asks [BAR_HEIGHT_HOOK], the navigation bar's
 * [NAV_BAR_HEIGHT_HOOK]. The extension can put each height back where the listener keeps it
 * ([fillKeptHeights], #196). Declared above [fullScreenBarsPatch]: a file's properties start in order,
 * and a patch that names this one before it exists would name nothing.
 */
internal val barHeightsPatch = bytecodePatch {
    dependsOn(instagramExtensionPatch)

    execute {
        val listener = findBarHeights()
        val stores = listener.barHeightStores()
        val holder = stores.map { it.field.definingClass }.distinct().singleOrNull()
            ?: refuse("the window insets listener keeps its heights in two classes")
        fillKeptHeights(stores)
        followHeightListeners(holder, listener.heightsListenerCall())
        routeBarHeights(listener)
    }
}

/**
 * Hides the status bar and the navigation bar while Reels or Home shows. See FullScreenBars in the
 * extension.
 *
 * The extension finds the reels viewer and the tab bar's tabs by the names Instagram looks them up by
 * (clips_viewer_container, tab_bar, clips_tab, feed_tab, the same ids on all seven 450 builds) once an
 * activity resumes, and the application-start hook the settings patch already writes starts the
 * watching. Instagram's code changes in one place (#147): its window insets listener keeps each bar's
 * height in a static field its screens pad by, and keeps the old height while the bar is hidden, so
 * the screens left an empty strip where each bar was. Both stores of those heights ask the extension
 * first ([barHeightsPatch]), which answers 0 while it has the bars hidden. In the default selection with
 * both switches off.
 */
@Suppress("unused")
val fullScreenBarsPatch = bytecodePatch(
    name = "Full screen Reels and Home",
    description = "Hides the status bar and the navigation bar while Reels shows, and optionally while Home or your messages do. " +
        "Swipe in from the edge to bring them back for a moment. Back and the keyboard work as usual. " +
        "Starts off. Turn it on in HushGram settings > Reels (Full screen Reels), Feed (Full screen Home) and " +
        "Messages (Full screen Messages).",
) {
    category("Reels")
    dependsOn(settingsPatch, instagramExtensionPatch, barHeightsPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("fullScreenBars")
        fillReelScreen()
        fitReelScreen()
        enableStatus("fullScreenBars")
    }
}

private fun refuse(why: String): Nothing = throw PatchException("$FULL_SCREEN_BARS_PATCH: $why")

private fun Method.holdsString(value: String) = implementation?.instructions?.any {
    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == value
} == true

/** One store of a bar height: where it is, the register it stores and the field it stores to. */
internal data class BarHeightStore(val index: Int, val register: Int, val field: FieldReference)

/** The insets listener, proved to hold both stores before anything changes. */
internal fun BytecodePatchContext.findBarHeights(): MutableMethod {
    val listener = uniqueMethod(FULL_SCREEN_BARS_PATCH, "window insets listener", BarHeightsFingerprint)
    listener.barHeightStores()
    return listener
}

/**
 * The listener's stores of the status bar's and the navigation bar's heights, in that order. The
 * fields are the two it reads for each of its height listeners: two static int reads right before
 * an interface call taking two ints. Each is stored once, from a register an invoke can name.
 */
internal fun Method.barHeightStores(): List<BarHeightStore> {
    val code = implementation?.instructions?.toList().orEmpty()
    val notify = code.heightsNotifyAt()
    val stores = listOf(code[notify - 2], code[notify - 1]).map { read ->
        val field = (read as ReferenceInstruction).reference as FieldReference
        val at = code.indices.filter { index ->
            code[index].opcode == Opcode.SPUT && ((code[index] as ReferenceInstruction).reference as FieldReference).sameAs(field)
        }
        if (at.size != 1) refuse("the window insets listener stores ${field.name} ${at.size} times, not once")
        val register = (code[at.single()] as OneRegisterInstruction).registerA
        if (register > 15) refuse("the window insets listener stores ${field.name} from v$register, past what an invoke names")
        BarHeightStore(at.single(), register, field)
    }
    if (stores[0].field.sameAs(stores[1].field)) refuse("the window insets listener reads one height twice")
    return stores
}

/** Where the listener hands each height listener the two stored heights: an interface call on two static int reads. */
private fun List<Instruction>.heightsNotifyAt(): Int = (2 until size).firstOrNull { at ->
    val call = (this[at] as? ReferenceInstruction)?.reference as? MethodReference
    this[at].opcode == Opcode.INVOKE_INTERFACE && call != null && call.returnType == "V" &&
        call.parameterTypes.map(CharSequence::toString) == listOf("I", "I") &&
        this[at - 2].opcode == Opcode.SGET && this[at - 1].opcode == Opcode.SGET
} ?: refuse("the window insets listener hands its height listeners no two stored heights")

/** The height listeners' method the listener calls with the two heights, like 0Dvn.GES(II)V on 451. */
internal fun Method.heightsListenerCall(): MethodReference {
    val code = implementation?.instructions?.toList().orEmpty()
    return (code[code.heightsNotifyAt()] as ReferenceInstruction).reference as MethodReference
}

private fun FieldReference.sameAs(other: FieldReference) =
    definingClass == other.definingClass && name == other.name && type == other.type

/**
 * Fills the extension's two stubs that store a bar's height where the listener keeps it, the status
 * bar's [KEEP_STATUS_BAR_HEIGHT] and the navigation bar's [KEEP_NAV_BAR_HEIGHT] (#196). As an
 * activity pauses, FullScreenBars puts back each height it answered 0 for, since a chat opening in its
 * own activity reads them before that window's insets reach the listener. The fields must be public
 * statics of a public class, as on 451, for the extension to write them.
 */
internal fun BytecodePatchContext.fillKeptHeights(stores: List<BarHeightStore>) {
    val bars = classDefByOrNull(FULL_SCREEN_BARS)?.let { mutableClassDefBy(FULL_SCREEN_BARS) }
        ?: refuse("$FULL_SCREEN_BARS isn't in the extension")
    for ((store, stub) in stores.zip(listOf(KEEP_STATUS_BAR_HEIGHT, KEEP_NAV_BAR_HEIGHT))) {
        val holder = classDefByOrNull(store.field.definingClass) ?: refuse("${store.field.definingClass} isn't in the app")
        val field = holder.fields.singleOrNull { it.name == store.field.name && it.type == store.field.type }
            ?: refuse("${store.field.definingClass} has no field ${store.field.name}")
        if (!AccessFlags.PUBLIC.isSet(holder.accessFlags) || !AccessFlags.PUBLIC.isSet(field.accessFlags) ||
            !AccessFlags.STATIC.isSet(field.accessFlags)
        ) {
            refuse("${store.field.definingClass}->${store.field.name} isn't a public static the extension can write")
        }
        val name = stub.substringAfter("->").substringBefore('(')
        val method = bars.methods.singleOrNull {
            it.name == name && it.returnType == "V" && it.parameterTypes.map(Any::toString) == listOf("I") &&
                AccessFlags.STATIC.isSet(it.accessFlags)
        } ?: refuse("$FULL_SCREEN_BARS has no static V $name(I)")
        method.addInstructions(0, "sput p0, ${store.field.definingClass}->${store.field.name}:${store.field.type}")
    }
}

/**
 * Lets FullScreenBars hear about each screen's bar heights listener and tell it a new pair (#200).
 * The class holding the heights registers a listener through a static (Activity, listener, once) and
 * removes one through (Activity, listener), which tell [HEIGHTS_ASKED] and [HEIGHTS_DROPPED] first.
 * The [TELL_HEIGHTS] stub calls the listener's own method, [call]. Instagram calls most listeners once
 * and lets them go, so the Reels viewer built before Full screen Reels hid the bars kept their old
 * heights as black strips until it resumed.
 */
internal fun BytecodePatchContext.followHeightListeners(holderType: String, call: MethodReference) {
    val type = call.definingClass
    val listener = classDefByOrNull(type) ?: refuse("$type isn't in the app")
    if (!AccessFlags.PUBLIC.isSet(listener.accessFlags) || !AccessFlags.INTERFACE.isSet(listener.accessFlags)) {
        refuse("$type isn't a public interface the extension can call")
    }
    val holder = classDefByOrNull(holderType)?.let { mutableClassDefBy(holderType) } ?: refuse("$holderType isn't in the app")
    fun registration(parameters: List<String>) = holder.methods.filter {
        AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" && it.parameterTypes.map(Any::toString) == parameters
    }.singleOrNull() ?: refuse("$holderType has no single static V (${parameters.joinToString("")})")
    registration(listOf(ACTIVITY, type, "Z")).addInstructions(0, "invoke-static { p0, p1 }, $HEIGHTS_ASKED")
    registration(listOf(ACTIVITY, type)).addInstructions(0, "invoke-static { p0, p1 }, $HEIGHTS_DROPPED")

    val bars = classDefByOrNull(FULL_SCREEN_BARS)?.let { mutableClassDefBy(FULL_SCREEN_BARS) }
        ?: refuse("$FULL_SCREEN_BARS isn't in the extension")
    val tell = bars.methods.singleOrNull {
        "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == TELL_HEIGHTS.substringAfter("->") &&
            AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuse("$FULL_SCREEN_BARS has no static ${TELL_HEIGHTS.substringAfter("->")}")
    tell.addInstructions(
        0,
        """
            check-cast p0, $type
            invoke-interface { p0, p1, p2 }, $type->${call.name}(II)V
        """,
    )
}

/**
 * Each store asks the extension first: the store becomes the call, and the answer is stored in its
 * place. The status bar's store asks [BAR_HEIGHT_HOOK] and the navigation bar's [NAV_BAR_HEIGHT_HOOK].
 * A jump to the store lands on the call, and one to what followed it still skips the store, as
 * before. Later stores go first, so the earlier indexes still hold.
 */
internal fun routeBarHeights(listener: MutableMethod) {
    val (status, nav) = listener.barHeightStores()
    for ((store, hook) in listOf(status to BAR_HEIGHT_HOOK, nav to NAV_BAR_HEIGHT_HOOK).sortedByDescending { it.first.index }) {
        val field = "${store.field.definingClass}->${store.field.name}:${store.field.type}"
        listener.replaceInstruction(store.index, "invoke-static { v${store.register} }, $hook")
        listener.addInstructions(
            store.index + 1,
            """
                move-result v${store.register}
                sput v${store.register}, $field
            """,
        )
    }
}
