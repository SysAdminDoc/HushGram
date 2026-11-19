/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.threads

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.util.ControlFlow
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Hide the Threads button"

internal const val FIRST_POST_PROMPT = "$THREADS_BUTTON->firstPostPrompt(Ljava/lang/Object;)V"
internal const val EMPTY_ROW = "$THREADS_BUTTON->emptyRow(Ljava/lang/Object;)Ljava/lang/Object;"

/**
 * What each of a profile's two grid adapters logs as it fills its grid's empty state, kept in
 * Instagram's builds: the grid of posts (0IQM.A12 on 456) and the media tabs' (0IMn.A12).
 */
internal val EMPTY_STATE_UPDATES = listOf(
    "ProfileGridItemTabAdapter.updateItemsWithEmptyState",
    "ProfileMediaTabAdapter.updateItemsWithEmptyState",
)

private const val CONTEXT = "Landroid/content/Context;"
private const val INTEGER = "Ljava/lang/Integer;"

/**
 * One grid adapter's empty state, in [owner]'s method [name]: it fills the "Create your first post"
 * prompt into the config in [config] with a call to [builder] at [build], and tests that config for
 * null at [test], adding no empty row on a null.
 */
internal class EmptyRowSite(
    val owner: String,
    val name: String,
    val parameters: List<String>,
    val returns: String,
    val builder: String,
    val configType: String,
    val build: Int,
    val test: Int,
    val config: Int,
)

/**
 * Finds where each grid adapter in [EMPTY_STATE_UPDATES] fills and adds your empty profile's
 * prompt, failing before anything changes when any of it isn't there exactly once. The one method
 * logging each name fills the prompt with one static void call taking a Context, the config and an
 * Integer (which of the prompt's three lines it shows), the same call for both adapters, with
 * nothing jumping in right after. The first test of the config on the way on is the null test
 * that adds the empty row only for a config, with the register untouched till then.
 */
internal fun BytecodePatchContext.findEmptyProfilePrompt(): List<EmptyRowSite> {
    val extension = classDefByOrNull(THREADS_BUTTON) ?: refuse("the extension has no $THREADS_BUTTON")
    for (hook in listOf(FIRST_POST_PROMPT, EMPTY_ROW)) {
        if (extension.methods.none { it.sig() == hook && it.isPublicStatic() }) refuse("the extension has no public static $hook")
    }
    val sites = EMPTY_STATE_UPDATES.map { update ->
        // A shared pool of strings loads the name too, but fills no prompt.
        val methods = classesLoadingString(update).flatMap { owner ->
            owner.methods.filter { method -> loadsString(method, update) && method.body().any { it.builderCall() != null } }
        }
        val method = methods.singleOrNull() ?: refuse("expected one method logging $update to fill a prompt, found ${methods.size}")
        siteIn(method)
    }
    val builders = sites.map { it.builder }.toSet()
    if (builders.size != 1) refuse("the grids fill their prompt with different calls: ${builders.sorted()}")
    return sites
}

private fun siteIn(method: Method): EmptyRowSite {
    val where = "${method.definingClass}->${method.name}"
    val code = method.body()
    val builds = code.indices.filter { code[it].builderCall() != null }
    val build = builds.singleOrNull() ?: refuse("expected $where to fill one prompt with a static (Context, config, Integer) call, found ${builds.size}")
    val call = code[build].builderCall()!!
    val config = code[build].registers()[1]
    val flow = try {
        ControlFlow.of(method)
    } catch (failure: IllegalArgumentException) {
        refuse("$where can't be followed: ${failure.message}")
    }
    if (code.indices.any { from -> from != build && build + 1 in flow.normal[from] + flow.exceptional[from] }) {
        refuse("something jumps in right after $where fills its prompt")
    }
    val tests = firstTests(code, flow, build, config)
    val test = tests.singleOrNull() ?: refuse("expected $where to test its prompt for null once on the way on, found ${tests.size}")
    return EmptyRowSite(
        method.definingClass, method.name, method.parameterTypes.map { it.toString() }, method.returnType,
        call.sig(), call.parameterTypes[1].toString(), build, test, config,
    )
}

/**
 * The first if-eqz or if-nez on [config] each way on from [build] reaches, with nothing on the
 * way writing [config]. A way that writes it first holds something else there by then.
 */
private fun firstTests(code: List<Instruction>, flow: ControlFlow, build: Int, config: Int): Set<Int> {
    val tests = sortedSetOf<Int>()
    val seen = HashSet<Int>()
    val next = ArrayDeque(flow.normal[build])
    while (next.isNotEmpty()) {
        val at = next.removeFirst()
        if (!seen.add(at)) continue
        val instruction = code[at]
        if ((instruction.opcode == Opcode.IF_EQZ || instruction.opcode == Opcode.IF_NEZ) &&
            (instruction as OneRegisterInstruction).registerA == config
        ) {
            tests += at
            continue
        }
        if (instruction.writesRegister(config)) continue
        next += flow.normal[at]
    }
    return tests
}

/**
 * Remembers each config right after the adapter fills it as the prompt, then hands the config the
 * null test reads to [EMPTY_ROW], which answers null for that prompt with the switch on, so the
 * adapter takes its own way for no empty row. The test's hook takes over the labels on the test,
 * so every way to it passes the hook. Only called once [findEmptyProfilePrompt] found everything.
 */
internal fun BytecodePatchContext.hideEmptyProfilePrompt(sites: List<EmptyRowSite>) {
    for (site in sites) {
        val method = mutableClassDefBy(site.owner).methods.single {
            it.name == site.name && it.parameterTypes.map { type -> type.toString() } == site.parameters && it.returnType == site.returns
        }
        val register = "v${site.config}"
        val filter = """
            invoke-static/range { $register .. $register }, $EMPTY_ROW
            move-result-object $register
            check-cast $register, ${site.configType}
        """
        val mark = "invoke-static/range { $register .. $register }, $FIRST_POST_PROMPT"
        // The later index first, so the earlier insert doesn't move it.
        if (site.test > site.build) {
            method.addInstructionsAtControlFlowLabel(site.test, filter)
            method.addInstructions(site.build + 1, mark)
        } else {
            method.addInstructions(site.build + 1, mark)
            method.addInstructionsAtControlFlowLabel(site.test, filter)
        }
    }
}

/** The method a static void (Context, config, Integer) call calls, or null for anything else. */
private fun Instruction.builderCall(): MethodReference? {
    if (opcode != Opcode.INVOKE_STATIC && opcode != Opcode.INVOKE_STATIC_RANGE) return null
    val call = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return null
    val parameters = call.parameterTypes.map { it.toString() }
    if (call.returnType != "V" || parameters.size != 3 || parameters[0] != CONTEXT || parameters[2] != INTEGER) return null
    val config = parameters[1]
    if (!config.startsWith("L") || config.startsWith("Ljava/") || config.startsWith("Landroid/")) return null
    return call
}

private fun Instruction.registers(): List<Int> = when (this) {
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    else -> emptyList()
}

private fun Instruction.writesRegister(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val written = (this as? OneRegisterInstruction)?.registerA ?: return false
    return written == register || (opcode.setsWideRegister() && written + 1 == register)
}

private fun Method.isPublicStatic() = AccessFlags.PUBLIC.isSet(accessFlags) && AccessFlags.STATIC.isSet(accessFlags)

private fun MethodReference.sig() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun Method.body(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: the empty profile prompt: $detail")
