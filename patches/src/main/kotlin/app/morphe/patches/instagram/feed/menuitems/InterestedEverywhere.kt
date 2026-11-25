/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.menuitems

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.download.reel.OPTION
import app.morphe.patches.instagram.misc.comment.code
import app.morphe.patches.instagram.misc.comment.discovering
import app.morphe.patches.instagram.misc.comment.field
import app.morphe.patches.instagram.misc.comment.one
import app.morphe.patches.instagram.misc.comment.reference
import app.morphe.patches.instagram.misc.comment.refuse
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.util.ControlFlow
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val INTERESTED_PATCH = "Three-dot menu items"

/** The SettingsStatus flag the Interested part turns on when both its hooks go in. */
internal const val INTERESTED_STATUS = "interestedEverywhere"

private const val INTERESTED_HELPER = "$EXTENSION_PACKAGE/feed/InterestedEverywhere;"

/**
 * Handed a reel menu's answer to whether you follow the reel's account, right before its test.
 * Answers a boolean, since the test reads it as one.
 */
internal const val REEL_FOLLOWING = "$INTERESTED_HELPER->reelFollowing(I)Z"

/**
 * Handed whether a post's menu offers Interested, right before the menu's flags are made. Answers a
 * boolean: the maker takes it as one, and the verifier refuses an int there.
 */
internal const val POST_OFFERED = "$INTERESTED_HELPER->postOffered(I)Z"

/** The name of the post menu's sheet, which the method opening it loads. Instagram keeps it. */
internal const val POST_SHEET = "SimplifiedMediaOverflowBottomSheet"

/** The key a post menu's Interested row goes by. Instagram keeps it: it's the server's name. */
internal const val INTERESTED_KEY = "interested"

internal const val INTERESTED = "$OPTION->INTERESTED:$OPTION"
internal const val NOT_INTERESTED = "$OPTION->NOT_INTERESTED:$OPTION"
private const val POST_VIEW = "Landroid/view/View;"
private const val POST_MEDIA = "Lcom/instagram/feed/media/Media;"

private fun Method.text() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"

private fun Method.takes() = parameterTypes.map(Any::toString)

private fun words(type: String) = if (type == "J" || type == "D") 2 else 1

/** One hook: a method, the instruction the call goes in front of, and the register it reads and writes back. Plain text and numbers only. */
internal class InterestedHook(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val returnType: String,
    val at: Int,
    val register: Int,
)

/** The reel menus' hooks, the post menu's, and the "type->name" of the maker the post hook goes in front of. */
internal class InterestedPlan(val reels: List<InterestedHook>, val post: InterestedHook, val maker: String)

/** Whether [method] reads [INTERESTED], the quick look before anything is worked out. */
private fun readsInterested(method: Method) =
    method.implementation?.instructions?.any { it.opcode == Opcode.SGET_OBJECT && it.reference()?.toString() == INTERESTED } == true

/**
 * Where [method] tests whether you follow a reel's account right before adding Interested (457's
 * three-dot list 0Ckr.A06 and the sheet 0Ckr.A0B, which takes the press's point): Object.equals, its
 * move-result, an if-nez on it landing on the read of Not interested, and the read of Interested
 * right after. Answers each such if-nez's index.
 */
internal fun followTestsIn(method: Method): List<Int> {
    val code = method.code()
    val candidates = (2 until code.size - 1).filter { at ->
        code[at].opcode == Opcode.IF_NEZ && code[at + 1].opcode == Opcode.SGET_OBJECT &&
            code[at + 1].reference()?.toString() == INTERESTED
    }
    if (candidates.isEmpty()) return emptyList()
    val flow = try {
        ControlFlow.of(method)
    } catch (unsupported: IllegalArgumentException) {
        return emptyList()
    } catch (unsupported: ClassCastException) {
        return emptyList()
    }
    return candidates.filter { at ->
        val register = (code[at] as OneRegisterInstruction).registerA
        val result = code[at - 1]
        val asked = code[at - 2].reference() as? MethodReference
        val lands = flow.normal[at].firstOrNull()?.let { code.getOrNull(it) }
        result.opcode == Opcode.MOVE_RESULT && (result as OneRegisterInstruction).registerA == register &&
            asked?.name == "equals" && asked.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;") &&
            lands?.opcode == Opcode.SGET_OBJECT && lands.reference()?.toString() == NOT_INTERESTED
    }
}

/**
 * The boolean a post menu reads and tests right before loading [INTERESTED_KEY] (457's 0Clj.A01
 * reading 0e6N.A03): the field of the menu's flags saying it offers Interested.
 */
internal fun BytecodePatchContext.interestedFlag(): FieldReference =
    classesLoadingString(INTERESTED_KEY).flatMap { it.methods }.filter { loadsString(it, INTERESTED_KEY) }
        .flatMap { method ->
            val code = method.code()
            code.indices.mapNotNull { at ->
                if (code[at].opcode != Opcode.IGET_BOOLEAN) return@mapNotNull null
                val register = (code[at] as TwoRegisterInstruction).registerA
                val window = (at + 1..minOf(at + 3, code.lastIndex)).map { code[it] }
                val tests = window.any { it.opcode == Opcode.IF_EQZ && (it as OneRegisterInstruction).registerA == register }
                val loads = window.any { it.reference()?.toString() == INTERESTED_KEY }
                code[at].field().takeIf { tests && loads }
            }
        }
        .distinctBy { it.toString() }
        .one("boolean a post menu reads right before adding \"$INTERESTED_KEY\"")

/** Which parameter of static [maker] [register] is, or null when it's a register of its own. */
private fun parameterIn(maker: Method, register: Int): Int? {
    var first = maker.implementation!!.registerCount - maker.takes().sumOf { words(it) }
    for ((index, type) in maker.takes().withIndex()) {
        if (first == register) return index
        first += words(type)
    }
    return null
}

/** The parameter [maker] writes [flag] from, when it writes it once and from a parameter. */
private fun writtenParameter(maker: Method, flag: FieldReference): Int? {
    if (!AccessFlags.STATIC.isSet(maker.accessFlags) || maker.returnType != flag.definingClass) return null
    val writes = maker.code().filter { it.opcode == Opcode.IPUT_BOOLEAN && it.field()?.toString() == flag.toString() }
    val write = writes.singleOrNull() as? TwoRegisterInstruction ?: return null
    return parameterIn(maker, write.registerA)
}

private fun Method.branchTargets(): Set<Int> = try {
    jumpTargets()
} catch (unsupported: IllegalArgumentException) {
    refuse("unsupported control flow in ${text()}: ${unsupported.message}")
} catch (unsupported: ClassCastException) {
    refuse("unsupported control flow in ${text()}: ${unsupported.message}")
}

/**
 * Finds every hook before anything changes. The reel menus are the methods testing whether you
 * follow the account right before adding Interested ([followTestsIn]), once each; 457 has two, the
 * three-dot list and the sheet a press on a reel opens, and each gets its own hook. The post menu's flags are
 * made by the one static method answering the class of [interestedFlag] that writes a parameter of
 * its own to it, and the one method loading [POST_SHEET] for a view and a post calls that maker once;
 * the hook goes in front of that call, on that parameter's register.
 */
internal fun BytecodePatchContext.findInterested(): InterestedPlan = discovering(INTERESTED_PATCH) {
    val flag = interestedFlag()
    val reels = mutableListOf<Method>()
    val makers = mutableListOf<Pair<Method, Int>>()
    classDefForEach { classDef ->
        for (method in classDef.methods) {
            if (method.implementation == null) continue
            if (readsInterested(method) && followTestsIn(method).size == 1) reels += method
            writtenParameter(method, flag)?.let { makers += method to it }
        }
    }
    if (reels.isEmpty()) refuse("expected a reel menu testing whether you follow the account right before adding Interested, found 0")
    val reelHooks = reels.map { reelMenu ->
        val test = followTestsIn(reelMenu).single()
        if (test in reelMenu.branchTargets()) refuse("${reelMenu.text()} jumps onto its follow test")
        InterestedHook(
            reelMenu.definingClass, reelMenu.name, reelMenu.takes(), reelMenu.returnType, test,
            (reelMenu.code()[test] as OneRegisterInstruction).registerA,
        )
    }

    val (maker, index) = makers.one("static method making ${flag.definingClass}, a post menu's flags, from its parameters")
    val opener = classesLoadingString(POST_SHEET).flatMap { it.methods }
        .filter { loadsString(it, POST_SHEET) && it.takes().take(2) == listOf(POST_VIEW, POST_MEDIA) }
        .one("method opening $POST_SHEET for a view and a post")
    val code = opener.code()
    val calls = code.indices.filter { at ->
        (code[at].reference() as? MethodReference)?.let {
            it.definingClass == maker.definingClass && it.name == maker.name && it.parameterTypes.map(Any::toString) == maker.takes()
        } == true
    }
    val call = calls.singleOrNull() ?: refuse("${opener.text()} calls ${maker.text()} ${calls.size} times, not once")
    val range = code[call] as? RegisterRangeInstruction ?: refuse("${opener.text()} calls ${maker.text()} without a register range")
    if (call in opener.branchTargets()) refuse("${opener.text()} jumps onto its call of ${maker.text()}")
    val register = range.startRegister + maker.takes().take(index).sumOf { words(it) }
    InterestedPlan(
        reelHooks,
        InterestedHook(opener.definingClass, opener.name, opener.takes(), opener.returnType, call, register),
        "${maker.definingClass}->${maker.name}",
    )
}

/** [findInterested], or null with a warning, so Three-dot menu items goes in without its Interested switch. */
internal fun BytecodePatchContext.findInterestedOrWarn(): InterestedPlan? = try {
    findInterested()
} catch (refusal: PatchException) {
    patchLog.warning("${refusal.message}. $INTERESTED_PATCH goes in without offering Interested everywhere.")
    null
}

/**
 * Hands each reel menu's follow answer to [REEL_FOLLOWING] right before its test, and the post
 * menu's Interested flag to [POST_OFFERED] right before the flags are made, each answer back in the
 * same register. Nothing jumps onto any of them (the find makes sure), and each instruction is
 * checked again before anything changes.
 */
internal fun BytecodePatchContext.offerInterested(plan: InterestedPlan) = discovering(INTERESTED_PATCH) {
    val hooks = (plan.reels.map { it to REEL_FOLLOWING } + (plan.post to POST_OFFERED)).map { (hook, call) ->
        val method = mutableClassDefBy(hook.type).methods.single {
            it.name == hook.name && it.returnType == hook.returnType && it.parameterTypes.map(Any::toString) == hook.parameters
        }
        val there = method.getInstruction(hook.at)
        val still = if (hook !== plan.post) {
            there.opcode == Opcode.IF_NEZ && (there as OneRegisterInstruction).registerA == hook.register
        } else {
            (there.reference() as? MethodReference)?.let { "${it.definingClass}->${it.name}" } == plan.maker
        }
        if (!still) refuse("${method.text()} changed before Interested could be offered there")
        Triple(method, hook, call)
    }
    for ((method, hook, call) in hooks) {
        method.addInstructions(
            hook.at,
            """
                invoke-static/range { v${hook.register} .. v${hook.register} }, $call
                move-result v${hook.register}
            """,
        )
    }
}
