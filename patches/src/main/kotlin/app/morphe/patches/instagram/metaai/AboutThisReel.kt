/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.metaai

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.classesLoading
import app.morphe.patches.instagram.misc.extension.typesMarked
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val ABOUT_THIS_REEL = "$EXTENSION_PACKAGE/metaai/MetaAi;->aboutThisReel(Ljava/lang/Object;)Ljava/lang/Object;"
internal const val ASK_META_AI_BOX = "$EXTENSION_PACKAGE/metaai/MetaAi;->askMetaAiBox(Landroid/view/ViewGroup;Landroid/view/View;)V"

/** The server flag Instagram 450's About this reel summary factory reads to pick how it fetches the summary. */
internal const val SUMMARY_FLAG = 0x8110e200115bacL

/** Two strings the summary row's binder holds on 450, the method that adds its Ask Meta AI box. */
internal val SUMMARY_ROW_STRINGS = listOf("overflow_open", "defer_eligibility_enabled")

/** The lambda the binder hands the Ask Meta AI box to, a name Kotlin kept: AiDiscoveryMenuHelper's createComposerView. */
internal val COMPOSER_LAMBDA = Regex("Lcom/instagram/metaai/aidiscovery/AiDiscoveryMenuHelper\\\$createComposerView\\\$\\d+;")
private const val VIEW = "Landroid/view/View;"
private const val ADD_VIEW = "Landroid/view/ViewGroup;->addView(Landroid/view/View;)V"
private val MOVES = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)

/** The purge marker only the Reels viewer's More menu (ClipsOrganicMediaItemViewMoreOptionsController) holds. */
internal const val REELS_MENU_MARKER = "hasAiDiscoveryMenu"

private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"
private val FACTORY_PARAMETERS = listOf(USER_SESSION, "Ljava/lang/String;", "Z", "Z")

/** One call of the summary factory: the method holding it, and the index and register of its move-result. */
internal class SummaryCall(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val moveResult: Int,
    val register: Int,
)

/** The summary factory and every call that takes its answer. */
internal class AboutSummary(val factory: String, val calls: List<SummaryCall>)

/**
 * Instagram 450 asks one static factory, `(UserSession, String media id, boolean, boolean)` answering
 * its own class, for the About this reel summary at the top of a reel's More menu: the summary, its
 * Sources and the Ask Meta AI box. It answers null when Instagram has no summary to show, and every
 * menu that asks then leaves the whole block out.
 *
 * The factory is the one such method that loads [SUMMARY_FLAG]. Each call has to take its answer
 * with a move-result-object, and the Reels viewer's More menu (the class holding the
 * [REELS_MENU_MARKER] purge marker) has to be among the callers, or the patch fails.
 */
internal fun BytecodePatchContext.findAboutSummaryCalls(): AboutSummary {
    val factories = classesLoading(SUMMARY_FLAG).flatMap { classDef ->
        classDef.methods.filter { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == classDef.type &&
                method.parameterTypes.map(CharSequence::toString) == FACTORY_PARAMETERS &&
                method.code().any { it.wide() == SUMMARY_FLAG }
        }.map { classDef.type to it }
    }
    val (type, factory) = factories.singleOrNull() ?: refuseAbout("${factories.size} About this reel summary factories, not one")
    val reference = "$type->${factory.name}(${FACTORY_PARAMETERS.joinToString("")})$type"
    val reelsMenus = typesMarked(REELS_MENU_MARKER)
    var reelsMenuAsks = false
    val calls = classesCalling(type, factory.name).flatMap { classDef ->
        classDef.methods.flatMap { method ->
            val code = method.code()
            code.indices.filter { code[it].methodReference()?.toString() == reference }.map { index ->
                val where = "${classDef.type}->${method.name}"
                if (code[index].opcode != Opcode.INVOKE_STATIC && code[index].opcode != Opcode.INVOKE_STATIC_RANGE) {
                    refuseAbout("$where calls the summary factory with ${code[index].opcode}")
                }
                val result = code.getOrNull(index + 1)
                if (result?.opcode != Opcode.MOVE_RESULT_OBJECT) refuseAbout("$where drops the summary factory's answer")
                if (classDef.type in reelsMenus) reelsMenuAsks = true
                SummaryCall(
                    classDef.type, method.name, method.parameterTypes.map(CharSequence::toString), method.returnType,
                    index + 1, (result as OneRegisterInstruction).registerA,
                )
            }
        }
    }
    if (!reelsMenuAsks) refuseAbout("the Reels More menu never asks the summary factory")
    return AboutSummary(type, calls)
}

/**
 * Passes each call's answer through MetaAi.aboutThisReel, right after its move-result, and casts
 * it back to the factory's class. A method's calls are written last first, so none moves another.
 */
internal fun BytecodePatchContext.dropAboutSummary(summary: AboutSummary) {
    summary.calls.groupBy { listOf(it.type, it.name, it.parameters, it.returnType) }.values.forEach { calls ->
        val first = calls.first()
        val method = mutableClassDefBy(first.type).methods.single {
            it.name == first.name && it.returnType == first.returnType &&
                it.parameterTypes.map(CharSequence::toString) == first.parameters
        }
        calls.sortedByDescending { it.moveResult }.forEach { call ->
            method.addInstructions(
                call.moveResult + 1,
                """
                    invoke-static/range { v${call.register} .. v${call.register} }, $ABOUT_THIS_REEL
                    move-result-object v${call.register}
                    check-cast v${call.register}, ${summary.factory}
                """,
            )
        }
    }
}

/** Where the summary row adds its Ask Meta AI box: the method, and the index of its addView. */
internal class AskBoxSite(val type: String, val name: String, val parameters: List<String>, val returnType: String, val addView: Int)

/**
 * The summary row's binder (the one method holding [SUMMARY_ROW_STRINGS] that creates a
 * [COMPOSER_LAMBDA]) inflates the Ask Meta AI box, hands it to that lambda as its first View, and
 * adds it to the row with the method's one `ViewGroup.addView(View)`. The box the lambda gets and
 * the view that call adds have to be the same register, with nothing written to it between the
 * copy the lambda took and the add, or the patch fails.
 */
internal fun BytecodePatchContext.findAskMetaAiBox(): AskBoxSite {
    val binders = classesHolding(*SUMMARY_ROW_STRINGS.toTypedArray()).flatMap { classDef ->
        classDef.methods.filter { method ->
            val code = method.code()
            code.mapNotNull { it.string() }.containsAll(SUMMARY_ROW_STRINGS) &&
                code.any { it.opcode == Opcode.NEW_INSTANCE && COMPOSER_LAMBDA.matches(it.reference().orEmpty()) }
        }.map { classDef.type to it }
    }
    val (type, method) = binders.singleOrNull() ?: refuseAbout("${binders.size} About this reel summary rows, not one")
    val where = "$type->${method.name}"
    val code = method.code()
    val constructors = code.indices.filter { index ->
        val call = code[index].methodReference()
        call != null && call.name == "<init>" && COMPOSER_LAMBDA.matches(call.definingClass)
    }
    val constructor = constructors.singleOrNull() ?: refuseAbout("$where builds the composer lambda ${constructors.size} times, not once")
    val parameters = code[constructor].methodReference()!!.parameterTypes.map(CharSequence::toString)
    val viewAt = parameters.indexOf(VIEW)
    if (viewAt < 0) refuseAbout("$where hands the composer lambda no View")
    // The receiver takes the first register, and a long or double takes two.
    val slot = 1 + parameters.take(viewAt).sumOf { if (it == "J" || it == "D") 2 else 1 }
    val (box, copied) = sourceOf(code, constructor, code[constructor].arguments()[slot])
    val adds = code.indices.filter { code[it].methodReference()?.toString() == ADD_VIEW }
    val add = adds.singleOrNull() ?: refuseAbout("$where adds ${adds.size} views without layout params, not one")
    if (code[add].opcode != Opcode.INVOKE_VIRTUAL || code[add].arguments().getOrNull(1) != box) {
        refuseAbout("$where adds some other view than the Ask Meta AI box")
    }
    if ((minOf(copied, add) + 1 until maxOf(copied, add)).any { code[it].writes(box) }) {
        refuseAbout("$where puts something else in the Ask Meta AI box's register before adding it")
    }
    return AskBoxSite(type, method.name, method.parameterTypes.map(CharSequence::toString), method.returnType, add)
}

/**
 * The register [register] was copied from by [at], and the index of that copy: the last move-object
 * into it, followed back, or [register] itself at [at]. The scan goes by text order, not branches,
 * so the caller checks the register holds still from the copy to its use.
 */
private fun sourceOf(code: List<Instruction>, at: Int, register: Int): Pair<Int, Int> {
    val write = (at - 1 downTo 0).firstOrNull { code[it].writes(register) } ?: return register to at
    val instruction = code[write]
    return if (instruction.opcode in MOVES) sourceOf(code, write, (instruction as TwoRegisterInstruction).registerB) else register to at
}

/** Whether this instruction writes [register], a wide write's second half included. */
private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val written = (this as? OneRegisterInstruction)?.registerA ?: return false
    return written == register || (opcode.setsWideRegister() && written + 1 == register)
}

/**
 * Swaps the summary row's addView of the Ask Meta AI box for MetaAi.askMetaAiBox, which makes the
 * same call unless Hide Ask Meta AI is on. The swap keeps the instruction's place and any label on it.
 */
internal fun BytecodePatchContext.holdAskMetaAiBox(site: AskBoxSite) {
    val method = mutableClassDefBy(site.type).methods.single {
        it.name == site.name && it.returnType == site.returnType &&
            it.parameterTypes.map(CharSequence::toString) == site.parameters
    }
    val (row, box) = method.implementation!!.instructions.toList()[site.addView].arguments()
    method.replaceInstruction(site.addView, "invoke-static { v$row, v$box }, $ASK_META_AI_BOX")
}

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
private fun Instruction.wide(): Long? = (this as? WideLiteralInstruction)?.wideLiteral
private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference
private fun Instruction.reference(): String? = (this as? ReferenceInstruction)?.reference?.toString()
private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
private fun Instruction.arguments(): List<Int> = when (this) {
    is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

private fun refuseAbout(detail: String): Nothing = throw PatchException("Hide Meta AI: $detail")
