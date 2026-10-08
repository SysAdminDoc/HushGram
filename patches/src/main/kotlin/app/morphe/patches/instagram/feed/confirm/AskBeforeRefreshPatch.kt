/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.confirm

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation

private const val REFRESH_PATCH = "Ask before a refresh"

internal const val REFRESH_CONFIRM = "$EXTENSION_PACKAGE/feed/RefreshConfirm;"
internal const val REFRESH_LISTENER =
    "$REFRESH_CONFIRM->listener(Landroid/view/View;Ljava/lang/Object;Landroid/view/animation/Animation\$AnimationListener;)Ljava/lang/Object;"
internal const val ANIMATION_LISTENER = "Landroid/view/animation/Animation\$AnimationListener;"
internal const val PULL_LISTENER = "$REFRESH_CONFIRM->pull(Landroid/view/View;Ljava/lang/Object;)Ljava/lang/Object;"

/**
 * Instagram's nested-scrolling refresh layout, which Home, the inbox and most lists pull with on
 * 450. It keeps its name, since screens find it by name in their layouts.
 */
internal const val NESTED_LAYOUT = "Lcom/instagram/ui/widget/refresh/RefreshableNestedScrollingParent;"

/** The extension's stub the patch fills with a direct call of the layout's setRefreshing(false). */
internal const val SPINNER_OFF = "spinnerOff"

/** The extension's stub the patch fills with a direct call of the nested layout's refresh listener. */
internal const val REFRESH_NOW = "refresh"
private const val VIEW = "Landroid/view/View;"
private const val ANIMATION = "Landroid/view/animation/Animation;"

/**
 * Pulling down to refresh a list waits for a question while the switch is on. Included in the
 * default selection with its switch off, so asking is the user's pick.
 */
@Suppress("unused")
val askBeforeRefreshPatch = bytecodePatch(
    name = "Ask before a refresh",
    description = "Asks before pulling down refreshes Home, Reels or another list, so a stray pull keeps what's on " +
        "screen. Refresh goes ahead, and Cancel stops the spinner. Its switch, under Feed, starts off.",
    default = true,
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("askBeforeRefresh")
        askBeforeRefresh()
        enableStatus("askBeforeRefresh")
    }
}

private fun refuse(why: String): Nothing = throw PatchException("$REFRESH_PATCH: $why")

/**
 * The end-of-pull animation's onAnimationEnd, the index of its read of the refresh listener, the
 * listener's type, the registers holding the listener, the layout and the animation itself, and the
 * layout's class, whose public setRefreshing(boolean) Cancel calls.
 */
internal class PullEndTarget(
    val method: MutableMethod,
    val read: Int,
    val listenerType: String,
    val held: Int,
    val layout: Int,
    val self: Int,
    val layoutType: String,
)

/**
 * Finds where a pull-down refresh layout's end-of-pull animation reads the layout's refresh
 * listener, and checks it before anything changes. The layout is the one class with a
 * setOnRefreshListener that only stores its listener in a field of its own and a
 * setRefreshing(boolean), which must be public in a public class, since Cancel calls it from the
 * extension to stop the spinner. The read is the one in an animation listener's onAnimationEnd,
 * into a register of its own, checked for null right after and then called with no arguments, in
 * registers the hook can reach, with nothing jumping to the check and the animation itself still in
 * its register there.
 */
internal fun BytecodePatchContext.findPullEnd(): PullEndTarget {
    val layouts = mutableListOf<FieldReference>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        classDef.listenerField()?.let { layouts += it }
    }
    val listener = layouts.singleOrNull()
        ?: refuse("expected one pull-down refresh layout that only stores its listener, found ${layouts.size}")
    val layoutClass = classDefBy(listener.definingClass)
    if (!AccessFlags.PUBLIC.isSet(layoutClass.accessFlags) ||
        layoutClass.publicInstance("setRefreshing", listOf("Z"), "V") == null
    ) {
        refuse("${layoutClass.type}'s setRefreshing(boolean) isn't public, so Cancel couldn't stop its spinner")
    }

    val sites = mutableListOf<Pair<Method, Int>>()
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_ROOT) || ANIMATION_LISTENER !in classDef.interfaces) return@classDefForEach
        val end = classDef.methods.singleOrNull {
            it.name == "onAnimationEnd" && it.returnType == "V" && it.parameterTypes.map(Any::toString) == listOf(ANIMATION) &&
                !AccessFlags.STATIC.isSet(it.accessFlags)
        } ?: return@classDefForEach
        val code = end.implementation?.instructions?.toList().orEmpty()
        code.indices.filter { code[it].reads(listener) }.forEach { sites += end to it }
    }
    val (end, read) = sites.singleOrNull()
        ?: refuse("expected one end-of-pull animation reading ${listener.definingClass}->${listener.name}, found ${sites.size}")
    val code = end.implementation!!.instructions.toList()
    val load = code[read] as TwoRegisterInstruction
    val held = load.registerA
    if (held == load.registerB) refuse("the end-of-pull animation reads the listener over the layout it reads it from")
    val test = code.getOrNull(read + 1)
    if (test?.opcode != Opcode.IF_EQZ || (test as OneRegisterInstruction).registerA != held) {
        refuse("the end-of-pull animation doesn't check the refresh listener right after reading it")
    }
    val calls = code.getOrNull(read + 2)
    val called = (calls as? ReferenceInstruction)?.reference as? MethodReference
    if (calls?.opcode != Opcode.INVOKE_INTERFACE || called?.definingClass != listener.type || called.returnType != "V" ||
        called.parameterTypes.isNotEmpty() || (calls as? FiveRegisterInstruction)?.registerC != held
    ) {
        refuse("the end-of-pull animation doesn't call the refresh listener right after checking it")
    }
    val self = end.localRegisterCount()
    if (listOf(held, load.registerB, self).any { it > 15 }) {
        refuse("the end-of-pull animation keeps what the hook needs in a register it can't reach")
    }
    if (read + 1 in end.jumpTargets()) refuse("a jump lands on the end-of-pull animation's listener check")
    end.requireThisIntact(REFRESH_PATCH, listOf(read + 1))

    val extension = classDefByOrNull(REFRESH_CONFIRM) ?: refuse("the extension has no $REFRESH_CONFIRM")
    extension.publicStatic(REFRESH_LISTENER) ?: refuse("the extension has no public static $REFRESH_LISTENER")
    extension.spinnerStub() ?: refuse("the extension has no static $SPINNER_OFF(View) to fill")
    val method = mutableClassDefBy(end.definingClass).methods.single {
        it.name == end.name && it.parameterTypes.map(Any::toString) == listOf(ANIMATION)
    }
    return PullEndTarget(method, read, listener.type, held, load.registerB, self, layoutClass.type)
}

/**
 * Where the nested-scrolling refresh layout calls its refresh listener: [method], the index of its
 * read of the listener, the listener's type, the call the layout makes on it, and the registers
 * holding the listener and the layout.
 */
internal class PullTriggerTarget(
    val method: MutableMethod,
    val read: Int,
    val listenerType: String,
    val call: MethodReference,
    val held: Int,
    val layout: Int,
)

/**
 * Finds where [NESTED_LAYOUT] calls its refresh listener once a pull goes far enough, and checks it
 * before anything changes. The layout must be public with a public setRefreshing(boolean), which
 * Cancel calls, and a setListener that only stores its listener in a field of its own. The call is
 * the one place in the layout reading that field into a register of its own, checked for null right
 * after and then called with no arguments, with nothing jumping to the check. An object field read
 * keeps both its registers within an invoke's reach.
 */
internal fun BytecodePatchContext.findPullTrigger(): PullTriggerTarget {
    val layoutClass = classDefByOrNull(NESTED_LAYOUT) ?: refuse("$NESTED_LAYOUT isn't in this build")
    if (!AccessFlags.PUBLIC.isSet(layoutClass.accessFlags) ||
        layoutClass.publicInstance("setRefreshing", listOf("Z"), "V") == null
    ) {
        refuse("$NESTED_LAYOUT's setRefreshing(boolean) isn't public, so Cancel couldn't stop its spinner")
    }
    val listener = layoutClass.storedListener("setListener")
        ?: refuse("$NESTED_LAYOUT has no setListener that only stores its listener")
    val sites = layoutClass.methods.flatMap { method ->
        val code = method.implementation?.instructions?.toList().orEmpty()
        code.indices.filter { code[it].reads(listener) && code.calledAt(it) != null }.map { method to it }
    }
    val (trigger, read) = sites.singleOrNull()
        ?: refuse("expected one place in $NESTED_LAYOUT that checks and calls its refresh listener, found ${sites.size}")
    val code = trigger.implementation!!.instructions.toList()
    val load = code[read] as TwoRegisterInstruction
    val held = load.registerA
    if (held == load.registerB) refuse("$NESTED_LAYOUT reads its listener over the layout it reads it from")
    if (read + 1 in trigger.jumpTargets()) refuse("a jump lands on $NESTED_LAYOUT's listener check")
    val call = code.calledAt(read)!!
    val method = mutableClassDefBy(NESTED_LAYOUT).methods.single {
        it.name == trigger.name && it.parameterTypes.map(Any::toString) == trigger.parameterTypes.map(Any::toString)
    }
    return PullTriggerTarget(method, read, listener.type, call, held, load.registerB)
}

/**
 * The method called right after the null check that follows [read], a read of a listener: an
 * interface call of a method of the listener's type taking nothing and answering nothing, on the
 * register just read. Null when anything else follows the read.
 */
private fun List<Instruction>.calledAt(read: Int): MethodReference? {
    val held = (this[read] as TwoRegisterInstruction).registerA
    val type = ((this[read] as ReferenceInstruction).reference as FieldReference).type
    val test = getOrNull(read + 1)
    if (test?.opcode != Opcode.IF_EQZ || (test as OneRegisterInstruction).registerA != held) return null
    val calls = getOrNull(read + 2)
    val called = (calls as? ReferenceInstruction)?.reference as? MethodReference
    if (calls?.opcode != Opcode.INVOKE_INTERFACE || called?.definingClass != type || called.returnType != "V" ||
        called.parameterTypes.isNotEmpty() || (calls as FiveRegisterInstruction).registerC != held
    ) {
        return null
    }
    return called
}

/** The extension's static void spinnerOff(View), or null. */
private fun ClassDef.spinnerStub(): Method? = methods.singleOrNull {
    it.name == SPINNER_OFF && it.returnType == "V" && it.parameterTypes.map(Any::toString) == listOf(VIEW) &&
        AccessFlags.STATIC.isSet(it.accessFlags)
}

/** The extension's static void refresh(Object), or null. */
private fun ClassDef.refreshStub(): Method? = methods.singleOrNull {
    it.name == REFRESH_NOW && it.returnType == "V" && it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;") &&
        AccessFlags.STATIC.isSet(it.accessFlags)
}

/** [stub] with its body replaced by [body], in [registers] registers. */
private fun replaced(stub: Method, registers: Int, body: String): MutableMethod =
    ImmutableMethod(
        stub.definingClass, stub.name, stub.parameters, stub.returnType, stub.accessFlags, null,
        stub.hiddenApiRestrictions, ImmutableMethodImplementation(registers, emptyList(), null, null),
    ).toMutable().apply { addInstructionsWithLabels(0, body) }

/**
 * Hands the listener each refresh layout just read to the extension, and puts the answer back in its
 * place, ahead of Instagram's own null check: the nested-scrolling layout's with the layout, and the
 * SwipeRefreshLayout end-of-pull animation's with the layout and the animation itself. Both are
 * found before either changes.
 */
internal fun BytecodePatchContext.askBeforeRefresh() {
    val found = findPullEnd()
    val trigger = findPullTrigger()
    classDefBy(REFRESH_CONFIRM).let { extension ->
        extension.publicStatic(PULL_LISTENER) ?: refuse("the extension has no public static $PULL_LISTENER")
        extension.refreshStub() ?: refuse("the extension has no static $REFRESH_NOW(Object) to fill")
    }
    trigger.method.addInstructions(
        trigger.read + 1,
        """
            invoke-static { v${trigger.layout}, v${trigger.held} }, $PULL_LISTENER
            move-result-object v${trigger.held}
            check-cast v${trigger.held}, ${trigger.listenerType}
        """,
    )
    found.method.addInstructions(
        found.read + 1,
        """
            invoke-static { v${found.layout}, v${found.held}, v${found.self} }, $REFRESH_LISTENER
            move-result-object v${found.held}
            check-cast v${found.held}, ${found.listenerType}
        """,
    )
    // The stubs' own bodies find their methods by reflection, for the tests. In their place go direct
    // calls, in bodies of their own size, since a compiled stub may have only its parameter's register.
    val confirm = mutableClassDefBy(REFRESH_CONFIRM)
    val spinner = confirm.methods.single { it.name == SPINNER_OFF && it.parameterTypes.map(Any::toString) == listOf(VIEW) }
    confirm.methods.remove(spinner)
    confirm.methods.add(
        replaced(
            spinner, 2,
            """
                instance-of v0, p0, $NESTED_LAYOUT
                if-eqz v0, :swipe
                check-cast p0, $NESTED_LAYOUT
                const/4 v0, 0x0
                invoke-virtual { p0, v0 }, $NESTED_LAYOUT->setRefreshing(Z)V
                return-void
                :swipe
                check-cast p0, ${found.layoutType}
                const/4 v0, 0x0
                invoke-virtual { p0, v0 }, ${found.layoutType}->setRefreshing(Z)V
                return-void
            """,
        ),
    )
    val refresh = confirm.methods.single { it.name == REFRESH_NOW && it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;") }
    confirm.methods.remove(refresh)
    confirm.methods.add(
        replaced(
            refresh, 1,
            """
                check-cast p0, ${trigger.listenerType}
                invoke-interface { p0 }, ${trigger.call}
                return-void
            """,
        ),
    )
}

/**
 * The field a pull-down refresh layout keeps its listener in: the class has a setRefreshing(boolean)
 * and one instance setOnRefreshListener whose whole body stores its argument in a field of the
 * class's own, of the argument's type. Null for any other class.
 */
private fun ClassDef.listenerField(): FieldReference? {
    if (methods.none { it.name == "setRefreshing" && it.returnType == "V" && it.parameterTypes.map(Any::toString) == listOf("Z") }) {
        return null
    }
    return storedListener("setOnRefreshListener")
}

/**
 * The field this class's one instance [setter] stores its argument in, when that's the setter's
 * whole body and the field is the class's own, of the argument's type. Null otherwise.
 */
private fun ClassDef.storedListener(setter: String): FieldReference? {
    val stores = methods.singleOrNull {
        it.name == setter && it.returnType == "V" && it.parameterTypes.size == 1 && !AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: return null
    val code = stores.implementation?.instructions?.toList().orEmpty()
    if (code.size != 2 || code[0].opcode != Opcode.IPUT_OBJECT || code[1].opcode != Opcode.RETURN_VOID) return null
    val field = (code[0] as ReferenceInstruction).reference as FieldReference
    return field.takeIf { it.definingClass == type && it.type == stores.parameterTypes.single().toString() }
}

/** Whether this reads [field] into a register. */
private fun Instruction.reads(field: FieldReference): Boolean {
    if (opcode != Opcode.IGET_OBJECT) return false
    val read = (this as ReferenceInstruction).reference as FieldReference
    return read.definingClass == field.definingClass && read.name == field.name && read.type == field.type
}
