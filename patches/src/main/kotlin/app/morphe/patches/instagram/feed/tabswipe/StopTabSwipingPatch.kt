/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.tabswipe

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Stop swiping between tabs"
internal const val TAB_SWIPE = "$EXTENSION_PACKAGE/feed/TabSwipe;"
internal const val MAIN_PAGER = "$TAB_SWIPE->mainPager(Landroid/view/View;)V"
internal const val INPUT = "$TAB_SWIPE->input(Landroid/view/View;I)Z"

/** The pager the main tabs sit in. androidx keeps its name and its setter's. */
internal const val VIEW_PAGER = "Landroidx/viewpager2/widget/ViewPager2;"
internal const val SET_USER_INPUT = "setUserInputEnabled"
internal const val RECYCLER_VIEW = "Landroidx/recyclerview/widget/RecyclerView;"
internal const val MOTION_EVENT = "Landroid/view/MotionEvent;"

/** The two touch methods of the pager's own list, which ask the pager's input flag first. */
internal val TOUCH_METHODS = listOf("onInterceptTouchEvent", "onTouchEvent")

/**
 * Two lines Instagram logs from the method that sets up the main tabs' pager, when the pager's view
 * is missing and when there are no tabs to page between.
 */
internal const val PAGER_MISSING = "initViewPagerIfNeeded: ViewPager view not found in layout for id="
internal const val PAGER_BINDER = "SwipeableTabsPagerBinder"

@Suppress("unused")
val stopTabSwipingPatch = bytecodePatch(
    name = "Stop swiping between tabs",
    description = "Keeps a sideways swipe from moving between Home, Reels and the other main tabs. Tapping the " +
        "tab bar still changes tabs, and Reels still scroll up and down.",
    default = true,
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("tabSwipe")
        stopTabSwiping(findTabSwiping())
        enableStatus("tabSwipe")
    }
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Where the hooks go: the main tabs' setup method, the instruction storing their pager and its
 * register, and the pager's own list class with the input flag its touch methods read.
 */
internal class TabSwipeSite(
    val owner: String,
    val setup: String,
    val setupShape: String,
    val store: Int,
    val pager: Int,
    val list: String,
    val flag: FieldReference,
)

/**
 * Finds the places, failing before an instruction changes when anything about them isn't there
 * exactly once:
 * - The one method holding [PAGER_MISSING] and [PAGER_BINDER], and in it the one instruction storing
 *   a [VIEW_PAGER] into a field of the method's own class: Instagram keeps the main tabs' pager
 *   there once it's set up. Nothing may jump to the instruction after the store.
 * - [VIEW_PAGER]'s one [SET_USER_INPUT] taking a boolean, and the one boolean field of the pager it
 *   writes: the pager's input flag.
 * - The one class [VIEW_PAGER] makes that extends [RECYCLER_VIEW] and holds a [VIEW_PAGER] field:
 *   the pager's own list. Each of its [TOUCH_METHODS] reads the input flag once and keeps `this`
 *   in its register up to there, since the hook hands it on.
 */
internal fun BytecodePatchContext.findTabSwiping(): TabSwipeSite {
    val setups = classesHolding(PAGER_MISSING, PAGER_BINDER).flatMap { classDef ->
        classDef.methods.filter { it.holds(PAGER_MISSING) && it.holds(PAGER_BINDER) }
    }
    val setup = setups.singleOrNull() ?: refuse("expected one method holding the pager setup's log lines, found ${setups.size}")
    val owner = setup.definingClass
    val code = setup.implementation!!.instructions.toList()
    val stores = code.indices.filter { at ->
        val field = (code[at] as? ReferenceInstruction)?.reference as? FieldReference
        code[at].opcode == Opcode.IPUT_OBJECT && field?.type == VIEW_PAGER && field.definingClass == owner
    }
    val store = stores.singleOrNull() ?: refuse("$owner->${setup.name} stores ${stores.size} pagers, not one")
    if (store + 1 >= code.size) refuse("$owner->${setup.name} ends at its pager's store")
    if (store + 1 in setup.jumpTargets()) refuse("$owner->${setup.name} jumps to the instruction after its pager's store")

    val pagerClass = classDefByOrNull(VIEW_PAGER) ?: refuse("$VIEW_PAGER isn't in this build")
    val setters = pagerClass.methods.filter {
        it.name == SET_USER_INPUT && it.parameterTypes.map(CharSequence::toString) == listOf("Z") && it.returnType == "V"
    }
    val setter = setters.singleOrNull() ?: refuse("expected one $SET_USER_INPUT(Z)V in $VIEW_PAGER, found ${setters.size}")
    val written = setter.implementation?.instructions?.toList().orEmpty()
        .filter { it.opcode == Opcode.IPUT_BOOLEAN }
        .map { (it as ReferenceInstruction).reference as FieldReference }
        .filter { it.definingClass == VIEW_PAGER }
    val flag = written.singleOrNull() ?: refuse("$VIEW_PAGER->$SET_USER_INPUT writes ${written.size} flags of its own, not one")

    val lists = pagerClass.methods.flatMap { method ->
        method.implementation?.instructions?.toList().orEmpty().filter { it.opcode == Opcode.NEW_INSTANCE }
            .map { ((it as ReferenceInstruction).reference as TypeReference).type }
    }.distinct().mapNotNull { classDefByOrNull(it) }.filter { made ->
        made.superclass == RECYCLER_VIEW && made.fields.any { it.type == VIEW_PAGER && !AccessFlags.STATIC.isSet(it.accessFlags) }
    }
    val list = lists.singleOrNull() ?: refuse("expected one list class $VIEW_PAGER makes, found ${lists.map { it.type }}")
    TOUCH_METHODS.forEach { name ->
        val method = list.touchMethod(name) ?: refuse("${list.type} has no instance $name($MOTION_EVENT)Z")
        val reads = method.implementation!!.instructions.withIndex().filter { it.value.reads(flag) }.map { it.index }
        if (reads.size != 1) refuse("${list.type}->$name reads the input flag ${reads.size} times, not once")
        method.requireThisIntact(PATCH, listOf(reads.single() + 1))
    }

    return TabSwipeSite(
        owner,
        setup.name,
        setup.shape(),
        store,
        (code[store] as TwoRegisterInstruction).registerA,
        list.type,
        flag,
    )
}

/**
 * Hands the main tabs' pager to TabSwipe right after Instagram stores it, and the input flag each
 * touch method of a pager's list reads to TabSwipe right after the read, with the list. TabSwipe
 * answers no for the main tabs' list while the switch is on, so a sideways swipe there goes nowhere.
 * Every other pager in the app keeps its own answer.
 */
internal fun BytecodePatchContext.stopTabSwiping(site: TabSwipeSite) {
    val list = mutableClassDefBy(site.list)
    TOUCH_METHODS.forEach { name ->
        val method = list.methods.single { it.name == name && it.parameterTypes.map(CharSequence::toString) == listOf(MOTION_EVENT) }
        val code = method.implementation!!.instructions.toList()
        val at = code.indexOfFirst { it.reads(site.flag) }
        val answer = (code[at] as TwoRegisterInstruction).registerA
        method.addInstructions(
            at + 1,
            """
                invoke-static { p0, v$answer }, $INPUT
                move-result v$answer
            """,
        )
    }
    val setup = mutableClassDefBy(site.owner).methods.single { it.name == site.setup && it.shape() == site.setupShape }
    setup.addInstructions(
        site.store + 1,
        """
            invoke-static/range { v${site.pager} .. v${site.pager} }, $MAIN_PAGER
        """,
    )
}

private fun ClassDef.touchMethod(name: String): Method? = methods.singleOrNull {
    it.name == name && it.parameterTypes.map(CharSequence::toString) == listOf(MOTION_EVENT) && it.returnType == "Z" &&
        !AccessFlags.STATIC.isSet(it.accessFlags)
}

private fun Method.shape(): String = parameterTypes.joinToString("", "(", ")") + returnType

private fun Instruction.reads(field: FieldReference): Boolean =
    opcode == Opcode.IGET_BOOLEAN && ((this as ReferenceInstruction).reference as FieldReference).let {
        it.definingClass == field.definingClass && it.name == field.name && it.type == field.type
    }

private fun Method.holds(string: String): Boolean = implementation?.instructions?.any {
    ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == string
} == true
