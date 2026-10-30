/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.tab

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Hide the Reels tab"

/** What the extension answers in place of Home, see ReelsTab.root. */
internal const val HOME_ROOT = "$EXTENSION_PACKAGE/reels/ReelsTab;->root(Ljava/lang/Object;)Ljava/lang/Object;"

internal const val BACK_STACK = "Ljava/util/Stack;"
private const val STRING = "Ljava/lang/String;"

/**
 * Where Instagram's tab navigation takes Home as the tab everything returns to, for Hide the Home tab:
 *
 *  - [root], the method of the tab list builder's class that answers Home as the tab a back stack
 *    starts from, with every return of it;
 *  - [restore], the navigation class's constructor, which reads a saved back stack and turns each
 *    saved name into a tab, with the register holding that tab at [restored], the index of its
 *    move-result;
 *  - [push], the navigation class's switch between two tabs, which can clear the stack and start it
 *    again from [root], with the registers of its two tabs.
 */
internal class HomeTabHooks(
    val tabType: String,
    val root: Method,
    val restore: Method,
    val restored: Int,
    val push: Method,
    val pushTabs: List<Int>,
)

/**
 * Finds where the back stack takes Home, failing before anything changes when any of it isn't there
 * exactly once. From what [tabs] found:
 *
 *  - the root is the one method of the tab list builder's class, other than the home tab method,
 *    that reads Home and returns it straight away, or the home tab method itself when there's no
 *    such method and the start tab falls back to it;
 *  - the navigation class is the one the tab host's constructor makes that takes the host and the
 *    tab list and keeps its back stack in one Stack field;
 *  - its constructor asks the builder's class for one tab, its start, and that method falls back to
 *    the root without naming Home itself; and it turns exactly one saved name into a tab;
 *  - every method of it that clears the back stack starts it again from the root and never names
 *    Home, and exactly one of them takes two tabs.
 *
 * Those checks are what make the three hooks enough: each place the back stack gets Home from is
 * one of them.
 */
internal fun BytecodePatchContext.findHomeTab(tabs: ReelsTabHooks): HomeTabHooks {
    val tabType = tabs.tabType
    val tabClass = classDefByOrNull(tabType) ?: refuse("the tab enum $tabType isn't in the app")
    val setup = tabClass.methods.singleOrNull { it.name == "<clinit>" }?.code() ?: refuse("$tabType has no setup")
    val home = tabField(tabType, setup, HOME)

    val builderClass = classDefByOrNull(tabs.builder.definingClass) ?: refuse("the tab list builder's class ${tabs.builder.definingClass} isn't in the app")
    val returnsHome = builderClass.methods.filter { method ->
        method.returnType == tabType && method.code().let { code ->
            code.indices.any { index ->
                val read = code[index]
                val back = code.getOrNull(index + 1)
                read.opcode == Opcode.SGET_OBJECT && read.fieldReference()?.sameField(home) == true &&
                    back?.opcode == Opcode.RETURN_OBJECT &&
                    (back as OneRegisterInstruction).registerA == (read as OneRegisterInstruction).registerA
            }
        }
    }
    val roots = returnsHome.filter { !it.sameAs(tabs.home) }

    val hostClass = classDefByOrNull(tabs.switch.definingClass) ?: refuse("the tab host ${tabs.switch.definingClass} isn't in the app")
    val made = hostClass.methods.filter { it.name == "<init>" }.flatMap { it.code() }
        .filter { it.opcode == Opcode.NEW_INSTANCE }
        .mapNotNull { ((it as ReferenceInstruction).reference as? TypeReference)?.type }
        .toSet()
    val navigations = made.mapNotNull { type -> classDefByOrNull(type) }.filter { navigation ->
        navigation.fields.count { it.type == BACK_STACK && !AccessFlags.STATIC.isSet(it.accessFlags) } == 1 &&
            navigation.methods.any { it.name == "<init>" && it.takes(hostClass.type) && it.takes(LIST) }
    }
    val navigation = navigations.singleOrNull()
        ?: refuse("expected the tab host ${hostClass.type} to make one class that takes it and the tab list and keeps a back stack, found ${navigations.size}")
    val stack = navigation.fields.single { it.type == BACK_STACK && !AccessFlags.STATIC.isSet(it.accessFlags) }

    val restores = navigation.methods.filter { it.name == "<init>" && it.takes(hostClass.type) && it.takes(LIST) }
    val restore = restores.singleOrNull()
        ?: refuse("expected one constructor of ${navigation.type} taking the tab host and the tab list, found ${restores.size}")
    val restoreCode = restore.code()
    val starts = restoreCode.mapNotNull { it.methodReference() }
        .filter { it.definingClass == builderClass.type && it.returnType == tabType }
    val start = starts.singleOrNull()
        ?: refuse("${navigation.type}'s constructor asks ${builderClass.type} for ${starts.size} tabs, expected one")
    val startMethod = builderClass.methods.singleOrNull { it.sameAs(start) } ?: refuse("${builderClass.type} has no ${start.name}")
    // 452.1's x86 build folds the root into the home tab method, which then names Reels, Home and the
    // feed switcher itself. It's the root only when nothing else is and the start tab falls back to it.
    val root = roots.singleOrNull()
        ?: returnsHome.singleOrNull { roots.isEmpty() && it.sameAs(tabs.home) && startMethod.calls(it) }
        ?: refuse("expected one method in ${builderClass.type} that returns $HOME as a tab, found ${roots.size}")
    if (!startMethod.sameAs(root)) {
        if (!startMethod.calls(root)) refuse("the start tab ${startMethod.describe()} doesn't fall back to ${root.describe()}")
        if (startMethod.reads(home)) refuse("the start tab ${startMethod.describe()} names $HOME itself")
    }
    val named = restoreCode.indices.filter { index ->
        val call = restoreCode[index].methodReference()
        call != null && restoreCode[index].opcode == Opcode.INVOKE_STATIC && call.definingClass == tabType &&
            call.name == "valueOf" && call.parameterTypes.map(CharSequence::toString) == listOf(STRING) && call.returnType == tabType &&
            restoreCode.getOrNull(index + 1)?.opcode == Opcode.MOVE_RESULT_OBJECT
    }
    val restored = (named.singleOrNull() ?: refuse("${navigation.type}'s constructor turns ${named.size} saved names into tabs, expected one")) + 1

    val clearers = navigation.methods.filter { method ->
        method.name != "<init>" && method.reads(stack) && method.code().any { instruction ->
            instruction.methodReference()?.let { it.name == "clear" && it.parameterTypes.isEmpty() && it.returnType == "V" } == true
        }
    }
    if (clearers.isEmpty()) refuse("no method of ${navigation.type} clears its back stack")
    for (clearer in clearers) {
        if (!clearer.calls(root)) refuse("${clearer.describe()} clears the back stack without starting it again from ${root.describe()}")
        if (clearer.reads(home)) refuse("${clearer.describe()} clears the back stack and names $HOME itself")
    }
    val pushes = clearers.filter { method -> method.parameterTypes.count { it.toString() == tabType } == 2 }
    val push = pushes.singleOrNull()
        ?: refuse("expected one method of ${navigation.type} that clears the back stack and takes two tabs, found ${pushes.size}")
    val pushTabs = push.parameterTypes.indices.filter { push.parameterTypes[it].toString() == tabType }.map { push.parameterRegisterNumber(it) }

    return HomeTabHooks(tabType, root, restore, restored, push, pushTabs)
}

/** Checks the three hooks [found], then writes them. A build that fails any check is left as it was. */
internal fun BytecodePatchContext.hideHomeTab(found: HomeTabHooks) {
    val root = mutable(found.root)
    val restore = mutable(found.restore)
    val push = mutable(found.push)
    // Code put in front of an instruction something jumps to would be skipped by the jump.
    if (restore.labelled(found.restored + 1)) refuse("something jumps past the saved tab in ${restore.describe()}")
    if (push.labelled(0)) refuse("something jumps to the start of ${push.describe()}")
    // move-result-object and check-cast name their register in eight bits.
    for (register in found.pushTabs) {
        if (register > 255) refuse("${push.describe()} keeps a tab in v$register, past v255")
    }
    val restoredRegister = (restore.getInstructionAt(found.restored) as OneRegisterInstruction).registerA

    // Each return becomes the ask, so whatever jumps to a return jumps to the ask.
    val returns = root.implementation!!.instructions.indices.filter { root.getInstructionAt(it).opcode == Opcode.RETURN_OBJECT }
    for (index in returns.sortedDescending()) {
        val register = (root.getInstructionAt(index) as OneRegisterInstruction).registerA
        root.replaceInstruction(index, "invoke-static/range { v$register .. v$register }, $HOME_ROOT")
        root.addInstructions(index + 1, """
            move-result-object v$register
            check-cast v$register, ${found.tabType}
            return-object v$register
        """)
    }
    askAt(restore, found.restored + 1, listOf(restoredRegister), found.tabType)
    askAt(push, 0, found.pushTabs, found.tabType)
}

/** Each tab in [registers] goes to the extension at [index], and its answer, cast back to a tab, takes its place. */
private fun askAt(method: MutableMethod, index: Int, registers: List<Int>, tabType: String) {
    method.addInstructions(index, registers.joinToString("\n") { register ->
        """
            invoke-static/range { v$register .. v$register }, $HOME_ROOT
            move-result-object v$register
            check-cast v$register, $tabType
        """.trimIndent()
    })
}

/** The static field [type]'s setup stores the tab called [name] in: the first one of that type written after the name. */
private fun tabField(type: String, setup: List<Instruction>, name: String): FieldReference {
    val named = setup.indices.filter { setup[it].stringLoaded() == name }
    val at = named.singleOrNull() ?: refuse("$type's setup names $name ${named.size} times, expected once")
    return setup.drop(at).firstNotNullOfOrNull { instruction ->
        instruction.fieldReference()?.takeIf { instruction.opcode == Opcode.SPUT_OBJECT && it.definingClass == type && it.type == type }
    } ?: refuse("$type's setup doesn't store its $name tab")
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single { it.sameAs(method) }

private fun MutableMethod.labelled(index: Int): Boolean =
    (implementation!!.instructions[index] as BuilderInstruction).location.labels.isNotEmpty()

private fun MutableMethod.getInstructionAt(index: Int): Instruction = implementation!!.instructions[index]

private fun Method.takes(type: String): Boolean = parameterTypes.any { it.toString() == type }

private fun Method.calls(other: Method): Boolean = code().any { instruction ->
    instruction.methodReference()?.let { it.definingClass == other.definingClass && other.sameAs(it) } == true
}

private fun Method.reads(field: FieldReference): Boolean = code().any { it.fieldReference()?.sameField(field) == true }

private fun Method.sameAs(other: MethodReference): Boolean =
    name == other.name && returnType == other.returnType &&
        parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

private fun Method.sameAs(other: Method): Boolean =
    definingClass == other.definingClass && name == other.name && returnType == other.returnType &&
        parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

private fun FieldReference.sameField(other: FieldReference): Boolean =
    definingClass == other.definingClass && name == other.name && type == other.type

private fun Method.describe(): String = "$definingClass->$name"

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.stringLoaded(): String? =
    if (opcode != Opcode.CONST_STRING && opcode != Opcode.CONST_STRING_JUMBO) null
    else ((this as ReferenceInstruction).reference as StringReference).string

private fun Instruction.fieldReference(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference
