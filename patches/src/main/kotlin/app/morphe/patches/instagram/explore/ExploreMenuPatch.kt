/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.explore

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.liveAcrossInjection
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference

private const val MENU_PATCH = "Explore without personalization"
internal const val EXPLORE_MENU = "$EXTENSION_PACKAGE/explore/ExploreMenu;"
internal const val UNPERSONALIZE = "$EXPLORE_MENU->unpersonalize()Z"
internal const val RELOADED = "$EXPLORE_MENU->reloaded()V"
internal const val KEEP_UNPERSONALIZED = "$EXPLORE_MENU->keepUnpersonalized()Z"
internal const val MENU_BUTTON = "$EXPLORE_MENU->button(Landroid/view/View;)V"
internal const val OLD_MENU_BUTTON = "$EXPLORE_MENU->oldButton(II)Z"

/** What the timer logs when it brings the personalized grid back after ten minutes. */
internal const val DSA_EXPIRED = "explore_dsa_experience_expired"

/** The older top bar of Explore, a kept name, and the arguments of its button maker. */
internal const val EXPLORE_ACTION_BAR = "Lcom/instagram/discovery/actionbar/ExploreActionBar;"
private val BAR_BUTTON = listOf("Landroid/view/View\$OnClickListener;", "I", "I", "I", "I")

private val INVOKES_STATIC = setOf(Opcode.INVOKE_STATIC, Opcode.INVOKE_STATIC_RANGE)
private val INVOKES_VIRTUAL = setOf(Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE)
private val OBJECT_MOVES = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)
private val INT_CONSTS = setOf(Opcode.CONST, Opcode.CONST_HIGH16)

/**
 * Where the EU's Digital Services Act asks for it, Instagram gives Explore a menu button with a row
 * that switches the grid to one that isn't personalized, and a timer that switches it back ten
 * minutes later. One switch opens Explore on that grid every time, through the same reload the row
 * makes, and keeps the timer from switching back. Another hides the menu button. Both start off,
 * and outside the EU none of it shows. Everything is found before anything is written.
 */
@Suppress("unused")
val exploreMenuPatch = bytecodePatch(
    name = "Explore without personalization",
    description = "Where Instagram lets you turn off personalization in Explore (in the EU), opens Explore on " +
        "the grid that isn't personalized every time and keeps it from switching back after ten minutes. A " +
        "second switch hides the menu button for it. Both start off. Turn them on in HushGram settings > Explore.",
) {
    category("Privacy")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("exploreMenu")
        val timer = findExploreTimer()
        val resume = findExploreResume(timer)
        val buttons = findExploreMenuButtons(resume)
        keepExploreUnpersonalized(timer)
        unpersonalizeExploreResume(timer, resume)
        hideExploreMenuButtons(buttons)
        enableStatus("exploreMenu")
    }
}

/**
 * The ten-minute timer: its class and method, the Explore fragment it reloads, the static reload it
 * calls with the grid's state, the fragment and a boolean for personalized, and the timer's two
 * fields that hold the state and the fragment.
 */
internal class ExploreTimer(
    val type: String,
    val method: String,
    val fragment: String,
    val reload: MethodReference,
    val stateField: FieldReference,
    val fragmentField: FieldReference,
)

/**
 * Finds the one instance method answering a boolean with no arguments that loads [DSA_EXPIRED], and
 * in it the one static it calls that takes some state, the class it's on and a boolean: the reload
 * the menu's row calls too. That class has to be the Explore fragment, the one holding
 * [EXPLORE_FRAGMENT_TAG], and the timer has to keep one field of the state's type and one of the
 * fragment's.
 */
internal fun BytecodePatchContext.findExploreTimer(): ExploreTimer {
    val found = classesLoadingString(DSA_EXPIRED).flatMap { classDef ->
        classDef.methods.filter {
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "Z" && it.parameterTypes.isEmpty() &&
                it.implementation != null && loadsString(it, DSA_EXPIRED)
        }
    }
    val timer = found.singleOrNull() ?: menuRefuse("expected one timer loading $DSA_EXPIRED, found ${found.size}")
    val where = "${timer.definingClass}->${timer.name}"
    val reloads = timer.implementation!!.instructions.filter { it.opcode in INVOKES_STATIC }
        .map { (it as ReferenceInstruction).reference as MethodReference }
        .filter { reference ->
            val parameters = reference.parameterTypes.map(CharSequence::toString)
            reference.returnType == "V" && parameters.size == 3 && parameters[0].startsWith("L") &&
                parameters[1] == reference.definingClass && parameters[2] == "Z"
        }
        .distinctBy { it.toString() }
    val reload = reloads.singleOrNull() ?: menuRefuse("$where reloads Explore through ${reloads.size} statics, expected one")
    val fragment = reload.definingClass
    val tagged = classesLoadingString(EXPLORE_FRAGMENT_TAG)
        .filter { classDef -> classDef.methods.any { loadsString(it, EXPLORE_FRAGMENT_TAG) } }
        .map { it.type }
    if (fragment !in tagged) menuRefuse("$where reloads $fragment, which isn't the Explore fragment")
    if (timer.localRegisterCount() < 1) menuRefuse("$where has no local register for the hook")

    val timerClass = classDefBy(timer.definingClass)
    fun field(type: String): FieldReference {
        val fields = timerClass.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == type }
        val field = fields.singleOrNull()
            ?: menuRefuse("${timer.definingClass} keeps ${fields.size} fields of $type, expected one")
        if (AccessFlags.PRIVATE.isSet(field.accessFlags)) menuRefuse("${timer.definingClass}->${field.name} is private")
        return ImmutableFieldReference(field.definingClass, field.name, field.type)
    }
    return ExploreTimer(
        timer.definingClass, timer.name, fragment, reload,
        field(reload.parameterTypes[0].toString()), field(fragment),
    )
}

/**
 * Where the Explore fragment resumes and decides whether the timer runs. Instagram's own code reads
 * the controller's personalized flag, then asks a remote flag whether Explore offers the menu, then
 * reads the timer from the controller and asks it:
 *
 *     iget-boolean vX, vC, personalized    if-nez vX
 *     invoke-static { arguments }, source  move-result-object vS
 *     const-wide vF, flag                  invoke-static { vS, vF, vF+1 }, check
 *     move-result vX  if-eqz vX            iget-object vT, vC, timer
 *     if-eqz vT  invoke-virtual { vT }, the timer  move-result  if-eqz
 *
 * and writes the personalized flag right after. The hook goes in front of the flag read, and also
 * reads the grid field the reload checks first, so it never reloads a fragment without a grid.
 */
internal class ExploreResume(
    val fragment: String,
    val hookAt: Int,
    val controller: Int,
    val personalized: FieldReference,
    val timerField: FieldReference,
    val flagSource: MethodReference,
    val arguments: List<Int>,
    val flagCheck: MethodReference,
    val flag: Long,
    val grid: FieldReference,
    val scratch: Int,
    val pair: Int,
)

internal fun BytecodePatchContext.findExploreResume(timer: ExploreTimer): ExploreResume {
    val fragmentClass = classDefBy(timer.fragment)
    val method = fragmentClass.methods.singleOrNull {
        it.name == "onResume" && it.parameterTypes.isEmpty() && it.returnType == "V" && it.implementation != null
    } ?: menuRefuse("${timer.fragment} has no onResume")
    val where = "${timer.fragment}->onResume"
    val code = method.implementation!!.instructions.toList()
    val calls = code.indices.filter { at ->
        code[at].opcode in INVOKES_VIRTUAL && (reference(code[at]) as MethodReference).let {
            it.definingClass == timer.type && it.name == timer.method && it.parameterTypes.isEmpty() && it.returnType == "Z"
        }
    }
    val call = calls.singleOrNull() ?: menuRefuse("$where asks the timer ${calls.size} times, expected once")
    fun shape(holds: Boolean, what: String) {
        if (!holds) menuRefuse("$where $what, a shape this patch hasn't seen")
    }
    shape(call >= 10 && call + 6 < code.size, "asks the timer too near an end")
    val timerRegister = firstRegister(code[call])

    val answer = code[call + 1]
    shape(answer.opcode == Opcode.MOVE_RESULT, "drops the timer's answer")
    shape(code[call + 2].opcode == Opcode.IF_EQZ && register(code[call + 2]) == register(answer), "doesn't branch on the timer's answer")
    shape(code[call - 1].opcode == Opcode.IF_EQZ && register(code[call - 1]) == timerRegister, "doesn't null-check the timer")
    val timerRead = code[call - 2]
    shape(timerRead.opcode == Opcode.IGET_OBJECT && register(timerRead) == timerRegister, "doesn't read the timer from a field")
    val timerField = reference(timerRead) as FieldReference
    shape(timerField.type == timer.type, "reads the timer from a ${timerField.type}")
    val controller = (timerRead as TwoRegisterInstruction).registerB

    shape(code[call - 3].opcode == Opcode.IF_EQZ, "doesn't branch on the menu flag")
    val flagged = register(code[call - 3])
    shape(code[call - 4].opcode == Opcode.MOVE_RESULT && register(code[call - 4]) == flagged, "drops the menu flag's answer")
    val check = code[call - 5]
    shape(check.opcode == Opcode.INVOKE_STATIC && check is FiveRegisterInstruction && check.registerCount == 3, "asks the menu flag some other way")
    val flagCheck = reference(check) as MethodReference
    shape(
        flagCheck.returnType == "Z" && flagCheck.parameterTypes.map(CharSequence::toString).let { it.size == 2 && it[0].startsWith("L") && it[1] == "J" },
        "asks the menu flag through ${flagCheck.name}",
    )
    check as FiveRegisterInstruction
    val wide = code[call - 6]
    shape(wide.opcode == Opcode.CONST_WIDE && register(wide) == check.registerD && check.registerE == check.registerD + 1, "doesn't load the menu flag's number")
    val flag = (wide as WideLiteralInstruction).wideLiteral
    shape(code[call - 7].opcode == Opcode.MOVE_RESULT_OBJECT && register(code[call - 7]) == check.registerC, "drops the flags' source")
    val sourceCall = code[call - 8]
    shape(sourceCall.opcode == Opcode.INVOKE_STATIC && sourceCall is FiveRegisterInstruction, "gets the flags' source some other way")
    val flagSource = reference(sourceCall) as MethodReference
    shape(flagSource.returnType.startsWith("L"), "gets the flags' source as a ${flagSource.returnType}")
    val arguments = (sourceCall as FiveRegisterInstruction).let {
        listOf(it.registerC, it.registerD, it.registerE, it.registerF, it.registerG).take(it.registerCount)
    }

    shape(code[call - 9].opcode == Opcode.IF_NEZ, "doesn't skip the timer on the personalized grid")
    val known = code[call - 10]
    shape(known.opcode == Opcode.IGET_BOOLEAN && register(known) == register(code[call - 9]), "doesn't read the personalized flag first")
    shape((known as TwoRegisterInstruction).registerB == controller, "reads the personalized flag from something other than the controller")
    val personalized = reference(known) as FieldReference
    shape(personalized.definingClass == timerField.definingClass, "keeps the personalized flag apart from the timer")
    shape(register(known) != controller && register(known) !in arguments, "reuses a register it still needs")
    shape((call - 10 until call - 2).none { writes(code[it], controller) }, "writes over the controller")
    shape(
        (call + 3..call + 6).any {
            code[it].opcode == Opcode.IPUT_BOOLEAN && (code[it] as TwoRegisterInstruction).registerB == controller &&
                (reference(code[it]) as FieldReference).toString() == personalized.toString()
        },
        "doesn't write the personalized flag after the timer",
    )
    if (controller > 15) menuRefuse("$where keeps the controller in v$controller, out of reach of a field read")

    val grid = gridField(timer)
    val hookAt = call - 10
    val live = method.liveAcrossInjection(hookAt)
    val free = (0 until minOf(method.localRegisterCount(), 16)).filter { it !in live && it != controller && it !in arguments }
    val pair = free.firstOrNull { it + 1 in free }
        ?: menuRefuse("$where has no two neighbouring free registers for the menu flag's number")
    val scratch = free.firstOrNull { it != pair && it != pair + 1 }
        ?: menuRefuse("$where has no third free register for the hook")
    return ExploreResume(
        timer.fragment, hookAt, controller, personalized, timerField, flagSource, arguments, flagCheck, flag,
        grid, scratch, pair,
    )
}

/**
 * The grid field the reload checks before anything else: the first object field of the fragment it
 * reads from its fragment argument, followed through copies, before its first branch.
 */
private fun BytecodePatchContext.gridField(timer: ExploreTimer): FieldReference {
    val parameters = timer.reload.parameterTypes.map(CharSequence::toString)
    val reload = classDefBy(timer.fragment).methods.singleOrNull {
        it.name == timer.reload.name && it.parameterTypes.map(CharSequence::toString) == parameters &&
            it.returnType == "V" && it.implementation != null
    } ?: menuRefuse("${timer.fragment} has no ${timer.reload.name} to reload Explore")
    val held = mutableSetOf(reload.parameterRegisterNumber(1))
    for (instruction in reload.implementation!!.instructions) {
        if (instruction is OffsetInstruction) break
        if (instruction.opcode == Opcode.IGET_OBJECT && (instruction as TwoRegisterInstruction).registerB in held) {
            val field = reference(instruction) as FieldReference
            if (field.definingClass == timer.fragment) return field
        }
        if (instruction.opcode in OBJECT_MOVES && (instruction as TwoRegisterInstruction).registerB in held) {
            held += (instruction as TwoRegisterInstruction).registerA
            continue
        }
        if (instruction.opcode.setsRegister()) {
            val written = (instruction as? OneRegisterInstruction)?.registerA ?: continue
            held -= written
            if (instruction.opcode.setsWideRegister()) held -= written + 1
        }
    }
    menuRefuse("${timer.fragment}->${timer.reload.name} doesn't read a field of the fragment before it branches")
}

/**
 * The menu button, on both top bar layouts. The newer one makes it behind the same remote flag the
 * fragment checks on resume and keeps it in a view field of the controller: [keptAt] is that field
 * write, with the view in [view]. The older one, [EXPLORE_ACTION_BAR], makes each of its buttons
 * through one maker taking a click listener and four ints, the icon first, and the menu's icon is
 * the one icon constant both layouts load for it.
 */
internal class ExploreMenuButtons(
    val fragment: String,
    val method: String,
    val parameters: List<String>,
    val keptAt: Int,
    val view: Int,
    val menuIcon: Int,
    val bar: String,
    val maker: String,
    val icon: Int,
)

internal fun BytecodePatchContext.findExploreMenuButtons(resume: ExploreResume): ExploreMenuButtons {
    val controller = resume.personalized.definingClass
    class Site(val method: Method, val at: Int, val branch: Int)
    val sites = classDefBy(resume.fragment).methods.flatMap { method ->
        val code = method.implementation?.instructions?.toList() ?: return@flatMap emptyList<Site>()
        code.indices.mapNotNull { at ->
            val write = code[at]
            if (write.opcode != Opcode.IPUT_OBJECT || at < 2) return@mapNotNull null
            val field = reference(write) as FieldReference
            if (field.definingClass != controller || field.type != "Landroid/view/View;") return@mapNotNull null
            val result = code[at - 1]
            if (result.opcode != Opcode.MOVE_RESULT_OBJECT || register(result) != register(write)) return@mapNotNull null
            val made = (reference(code[at - 2]) as? MethodReference) ?: return@mapNotNull null
            if (made.returnType != "Landroid/view/View;") return@mapNotNull null
            val branch = (at - 1 downTo 0).firstOrNull { code[it] is OffsetInstruction } ?: return@mapNotNull null
            val behindFlag = (maxOf(0, branch - 8) until branch).any {
                code[it].opcode == Opcode.CONST_WIDE && (code[it] as WideLiteralInstruction).wideLiteral == resume.flag
            }
            if (behindFlag) Site(method, at, branch) else null
        }
    }
    val site = sites.singleOrNull()
        ?: menuRefuse("${resume.fragment} keeps ${sites.size} views made behind the menu flag, expected one")
    val where = "${resume.fragment}->${site.method.name}"
    if (site.at in site.method.jumpTargets()) menuRefuse("$where jumps onto where it keeps the menu button")
    val code = site.method.implementation!!.instructions.toList()
    val view = register(code[site.at])
    if (view > 15) menuRefuse("$where keeps the menu button in v$view, out of reach of invoke-static")
    val blockIcons = (site.branch + 1 until site.at).filter { code[it].opcode in INT_CONSTS }
        .map { (code[it] as NarrowLiteralInstruction).narrowLiteral }.toSet()

    val bar = classDefByOrNull(EXPLORE_ACTION_BAR) ?: menuRefuse("$EXPLORE_ACTION_BAR is gone")
    val makers = bar.methods.filter {
        it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == BAR_BUTTON && it.implementation != null
    }
    val maker = makers.singleOrNull() ?: menuRefuse("$EXPLORE_ACTION_BAR has ${makers.size} button makers, expected one")
    val icon = maker.parameterRegisterNumber(1)
    if (icon > 15) menuRefuse("$EXPLORE_ACTION_BAR->${maker.name} keeps its icon in v$icon, out of reach of invoke-static")
    if (maker.localRegisterCount() < 1) menuRefuse("$EXPLORE_ACTION_BAR->${maker.name} has no local register for the hook")
    val barIcons = bar.methods.flatMap { method ->
        val barCode = method.implementation?.instructions?.toList() ?: return@flatMap emptyList<Int>()
        barCode.filter { instruction ->
            (reference(instruction) as? MethodReference)?.let {
                it.definingClass == EXPLORE_ACTION_BAR && it.name == maker.name && it.parameterTypes.map(CharSequence::toString) == BAR_BUTTON
            } == true
        }.flatMap { invoke ->
            val iconRegister = when (invoke) {
                is RegisterRangeInstruction -> invoke.startRegister + 2
                is FiveRegisterInstruction -> invoke.registerE
                else -> return@flatMap emptyList<Int>()
            }
            barCode.filter { it.opcode in INT_CONSTS && register(it) == iconRegister }
                .map { (it as NarrowLiteralInstruction).narrowLiteral }
        }
    }.toSet()
    val shared = blockIcons intersect barIcons
    val menuIcon = shared.singleOrNull()
        ?: menuRefuse("the two top bars share ${shared.size} icons for the menu button, expected one")
    return ExploreMenuButtons(
        resume.fragment, site.method.name, site.method.parameterTypes.map(CharSequence::toString), site.at, view,
        menuIcon, EXPLORE_ACTION_BAR, maker.name, icon,
    )
}

/** At the top of the timer, a yes from [KEEP_UNPERSONALIZED] answers false: nothing switched back. */
internal fun BytecodePatchContext.keepExploreUnpersonalized(timer: ExploreTimer) {
    val method = mutableClassDefBy(timer.type).methods.single {
        it.name == timer.method && it.parameterTypes.isEmpty() && it.returnType == "Z"
    }
    method.addInstructionsWithLabels(
        0,
        """
            invoke-static { }, $KEEP_UNPERSONALIZED
            move-result v0
            if-eqz v0, :run
            const/4 v0, 0x0
            return v0
        """,
        ExternalLabel("run", method.getInstruction(0)),
    )
}

/**
 * In front of the personalized flag read in onResume, on its own label since a branch lands there:
 * on the personalized grid, with the switch on, where the menu flag says Explore offers the menu
 * and the timer, its fragment and the fragment's grid are all there, it does what the menu's row
 * does. It clears the flag and calls the reload with the timer's state, the fragment and false. The
 * code after it then sees the grid that isn't personalized, and the timer (kept by
 * [keepExploreUnpersonalized]) leaves it be.
 */
internal fun BytecodePatchContext.unpersonalizeExploreResume(timer: ExploreTimer, resume: ExploreResume) {
    val method = mutableClassDefBy(resume.fragment).methods.single {
        it.name == "onResume" && it.parameterTypes.isEmpty() && it.returnType == "V"
    }
    val a = resume.scratch
    val b = resume.pair
    val c = resume.pair + 1
    val control = resume.controller
    val arguments = resume.arguments.joinToString { "v$it" }
    method.addInstructionsAtControlFlowLabel(
        resume.hookAt,
        """
            iget-boolean v$a, v$control, ${fieldText(resume.personalized)}
            if-eqz v$a, :keep
            invoke-static { }, $UNPERSONALIZE
            move-result v$a
            if-eqz v$a, :keep
            invoke-static { $arguments }, ${methodText(resume.flagSource)}
            move-result-object v$a
            const-wide v$b, ${resume.flag}L
            invoke-static { v$a, v$b, v$c }, ${methodText(resume.flagCheck)}
            move-result v$a
            if-eqz v$a, :keep
            iget-object v$b, v$control, ${fieldText(resume.timerField)}
            if-eqz v$b, :keep
            iget-object v$a, v$b, ${fieldText(timer.fragmentField)}
            if-eqz v$a, :keep
            iget-object v$c, v$a, ${fieldText(resume.grid)}
            if-eqz v$c, :keep
            iget-object v$b, v$b, ${fieldText(timer.stateField)}
            if-eqz v$b, :keep
            invoke-static { }, $RELOADED
            const/4 v$c, 0x0
            iput-boolean v$c, v$control, ${fieldText(resume.personalized)}
            invoke-static { v$b, v$a, v$c }, ${methodText(timer.reload)}
            :keep
            nop
        """,
    )
}

/**
 * Hands the newer bar's menu button to [MENU_BUTTON] before the controller keeps it, and starts the
 * older bar's button maker with [OLD_MENU_BUTTON], which says to skip making the menu's button.
 */
internal fun BytecodePatchContext.hideExploreMenuButtons(buttons: ExploreMenuButtons) {
    val holder = mutableClassDefBy(buttons.fragment).methods.single {
        it.name == buttons.method && it.parameterTypes.map(CharSequence::toString) == buttons.parameters
    }
    holder.addInstructions(buttons.keptAt, "invoke-static { v${buttons.view} }, $MENU_BUTTON")

    val maker = mutableClassDefBy(buttons.bar).methods.single {
        it.name == buttons.maker && it.parameterTypes.map(CharSequence::toString) == BAR_BUTTON
    }
    maker.addInstructionsWithLabels(
        0,
        """
            const v0, ${buttons.menuIcon}
            invoke-static { v${buttons.icon}, v0 }, $OLD_MENU_BUTTON
            move-result v0
            if-eqz v0, :run
            return-void
        """,
        ExternalLabel("run", maker.getInstruction(0)),
    )
}

private fun reference(instruction: Instruction) = (instruction as? ReferenceInstruction)?.reference

private fun register(instruction: Instruction) = (instruction as OneRegisterInstruction).registerA

private fun firstRegister(instruction: Instruction) = when (instruction) {
    is RegisterRangeInstruction -> instruction.startRegister
    is FiveRegisterInstruction -> instruction.registerC
    else -> menuRefuse("an invoke of an unknown form")
}

/** Whether [instruction] writes [target], or the pair a wide write starts below it. */
private fun writes(instruction: Instruction, target: Int): Boolean {
    if (!instruction.opcode.setsRegister()) return false
    val written = (instruction as? OneRegisterInstruction)?.registerA ?: return false
    return written == target || (instruction.opcode.setsWideRegister() && written + 1 == target)
}

private fun fieldText(field: FieldReference) = "${field.definingClass}->${field.name}:${field.type}"

private fun methodText(method: MethodReference) =
    "${method.definingClass}->${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}"

private fun menuRefuse(detail: String): Nothing = throw PatchException("$MENU_PATCH: $detail")
