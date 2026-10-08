/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.friendship

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.analytics.stringLoadedAt
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/** The profile screen's fragment. Instagram keeps its name. */
internal const val PROFILE_FRAGMENT = "Lcom/instagram/profile/fragment/UserDetailFragment;"

/** The call a profile screen's answer is asked for its value with, which Kotlin's flows keep the name of. */
internal const val GET_VALUE = "getValue"

/**
 * Where the profile screen keeps its own answer about the account, and how Instagram's options
 * sheet on that profile reads whether the account follows you from it to offer Remove follower.
 *
 * <p>The answer wraps the account's live tree. The sheet turns it into one of its fragments and
 * types that for a client, which reads the tree as it is now. The friendship status the patch
 * read before is a copy the account's model keeps from the first time it was asked, and on some
 * profiles it still says the account doesn't follow you after the screen has heard it does (#40).
 */
internal class ScreenAnswer(
    /** The view model's field keeping the screen's answer, and its call answering the value it has now. */
    val answer: FieldReference,
    val value: String,
    /** The class of that value keeping the answer's tree, and the field it keeps it in. */
    val holder: String,
    val tree: FieldReference,
    /** The tree's call making it the sheet's fragment, and the one typing that for the client. */
    val reinterpret: String,
    val retype: String,
    /** The tree's getters of a tree and of a Boolean under the Java hash of a field's name. */
    val subtree: String,
    val flag: String,
    /** The sheet's fragment type and the client it types it for, as Instagram hands them over. */
    val type: Int,
    val client: String,
)

/**
 * The profile screen's answer, or null with a warning in the patch log when this build's can't be
 * told. The label then goes by the status Instagram keeps on the account, as it did before.
 */
internal fun BytecodePatchContext.screenAnswerOrWarn(): ScreenAnswer? = try {
    findScreenAnswer()
} catch (moved: PatchException) {
    patchLog.warning("${moved.message}. The label goes by the follow status Instagram keeps on the account.")
    null
}

/**
 * Finds the one place reading the profile screen's answer as Instagram's options sheet does: a
 * method that asks one of [PROFILE_FRAGMENT]'s getters of the answer for it, reads its tree, makes
 * that a fragment by a type constant, types it for a client string, and reads the friendship
 * status ([FRIENDSHIP_STATUS_KEY]) and [FOLLOWED_BY] from trees by the Java hash of their names.
 * A getter of the answer takes nothing, reads its [VIEW_MODEL], one of the view model's fields,
 * asks that for its value and answers it cast to what the getter returns. Fails when any of that
 * isn't there, can't be reached from the extension, or there's more than one such place.
 */
internal fun BytecodePatchContext.findScreenAnswer(): ScreenAnswer {
    val fragment = classDefByOrNull(PROFILE_FRAGMENT) ?: refuse("$PROFILE_FRAGMENT isn't in this build")
    val getters = fragment.methods.mapNotNull { it.answerGetter() }
    if (getters.isEmpty()) refuse("no getter in $PROFILE_FRAGMENT answers a value its $VIEW_MODEL keeps")
    val sites = getters.flatMap { getter ->
        classesCalling(PROFILE_FRAGMENT, getter.name).flatMap { caller -> caller.methods.flatMap { sheetReads(it, getter) } }
    }
    val site = sites.singleOrNull()
        ?: refuse("expected one place reading the profile screen's friendship status as its options sheet does, found ${sites.size}")

    // The stub reaches all of these from the extension, outside Instagram's packages.
    fun fieldIsPublic(field: FieldReference) = classDefByOrNull(field.definingClass)?.fields?.any {
        it.name == field.name && it.type == field.type && AccessFlags.PUBLIC.isSet(it.accessFlags) && !AccessFlags.STATIC.isSet(it.accessFlags)
    } == true
    for (field in listOf(site.answer, site.tree)) {
        if (!fieldIsPublic(field)) refuse("$field isn't a public field, so the extension can't read it")
    }
    for (type in listOf(site.answer.type, site.holder, site.tree.type)) {
        val reachable = classDefByOrNull(type) ?: refuse("$type isn't in this build")
        if (!AccessFlags.PUBLIC.isSet(reachable.accessFlags)) refuse("$type isn't public, so the extension can't reach it")
    }
    // The stub asks for the value through the answer's own type, which every 450 build declares as an interface.
    if (!AccessFlags.INTERFACE.isSet(classDefByOrNull(site.answer.type)!!.accessFlags)) {
        refuse("${site.answer.type} isn't an interface, so the stub can't ask it for its value")
    }
    return site
}

/** One of the fragment's getters of the screen's answer: its name, what it answers, and where it reads it. */
private class AnswerGetter(val name: String, val returns: String, val answer: FieldReference, val value: MethodReference)

private fun Method.answerGetter(): AnswerGetter? {
    if (AccessFlags.STATIC.isSet(accessFlags) || parameterTypes.isNotEmpty() || !returnType.startsWith("L")) return null
    val code = implementation?.instructions?.toList() ?: return null
    if (code.firstOrNull()?.opcode != Opcode.IGET_OBJECT || code[0].fieldReference()?.type != VIEW_MODEL) return null
    for (at in 1 until code.size - 4) {
        val answer = code[at].fieldReference()
        if (code[at].opcode != Opcode.IGET_OBJECT || answer == null || answer.definingClass != VIEW_MODEL) continue
        val value = code[at + 1].methodReference() ?: continue
        if (code[at + 1].opcode != Opcode.INVOKE_INTERFACE || value.name != GET_VALUE || value.parameterTypes.isNotEmpty() ||
            value.returnType != OBJECT || code[at + 1].namedRegisters() != listOf((code[at] as TwoRegisterInstruction).registerA)
        ) {
            continue
        }
        val cast = code[at + 3]
        if (code[at + 2].opcode != Opcode.MOVE_RESULT_OBJECT || cast.opcode != Opcode.CHECK_CAST ||
            (cast as ReferenceInstruction).reference.toString() != returnType || code[at + 4].opcode != Opcode.RETURN_OBJECT
        ) {
            continue
        }
        return AnswerGetter(name, returnType, answer, value)
    }
    return null
}

/**
 * The places in [method] that ask [getter] for the screen's answer and read it as the options
 * sheet does: the answer (null-checked or not) has its tree read, the tree is made a fragment by
 * a constant and that typed for a client string with the same constant, and the method also reads
 * a tree under the friendship status's key and a Boolean under [FOLLOWED_BY]'s, each with one
 * getter of the tree's interface.
 */
private fun BytecodePatchContext.sheetReads(method: Method, getter: AnswerGetter): List<ScreenAnswer> {
    val code = method.implementation?.instructions?.toList() ?: return emptyList()
    return code.indices.mapNotNull { at ->
        val called = code[at].methodReference()
        if (code[at].opcode != Opcode.INVOKE_VIRTUAL || called?.definingClass != PROFILE_FRAGMENT || called.name != getter.name ||
            called.parameterTypes.isNotEmpty() || called.returnType != getter.returns
        ) {
            return@mapNotNull null
        }
        val result = code.getOrNull(at + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } ?: return@mapNotNull null
        val answer = (result as OneRegisterInstruction).registerA
        var read = at + 2
        if (code.getOrNull(read)?.let { it.opcode == Opcode.IF_EQZ && (it as OneRegisterInstruction).registerA == answer } == true) read++
        if (read + 4 >= code.size) return@mapNotNull null
        val tree = code[read].fieldReference()
        if (code[read].opcode != Opcode.IGET_OBJECT || tree == null || (code[read] as TwoRegisterInstruction).registerB != answer ||
            !keeps(getter.returns, tree.definingClass) || classDefByOrNull(tree.type)?.let { AccessFlags.INTERFACE.isSet(it.accessFlags) } != true
        ) {
            return@mapNotNull null
        }
        val treeRegister = (code[read] as TwoRegisterInstruction).registerA
        val type = code[read + 1]
        if (type !is NarrowLiteralInstruction || type.opcode !in CONSTANTS) return@mapNotNull null
        val typeRegister = (type as OneRegisterInstruction).registerA
        val reinterpret = code[read + 2].methodReference()
        if (code[read + 2].opcode != Opcode.INVOKE_INTERFACE || reinterpret == null || !reinterpret.isTreeCall(tree.type, "I") ||
            code[read + 2].namedRegisters() != listOf(treeRegister, typeRegister) || code[read + 3].opcode != Opcode.MOVE_RESULT_OBJECT
        ) {
            return@mapNotNull null
        }
        val fragment = (code[read + 3] as OneRegisterInstruction).registerA
        val retype = code[read + 4].methodReference()
        val retyped = code[read + 4].namedRegisters()
        if (code[read + 4].opcode != Opcode.INVOKE_INTERFACE || retype == null || !retype.isTreeCall(tree.type, STRING, "I") ||
            retyped.size != 3 || retyped[0] != fragment || retyped[2] != typeRegister
        ) {
            return@mapNotNull null
        }
        val client = clientIn(code, at, retyped[1]) ?: return@mapNotNull null
        val subtree = keyedCalls(code, FRIENDSHIP_STATUS_KEY, tree.type, tree.type).singleOrNull() ?: return@mapNotNull null
        val flag = keyedCalls(code, FOLLOWED_BY, tree.type, BOOLEAN_OBJECT).singleOrNull() ?: return@mapNotNull null
        ScreenAnswer(
            getter.answer, "${getter.answer.type}->${getter.value.name}()$OBJECT", tree.definingClass, tree,
            reinterpret.toString(), retype.toString(), subtree, flag, type.narrowLiteral, client,
        )
    }
}

private val CONSTANTS = setOf(Opcode.CONST, Opcode.CONST_16, Opcode.CONST_HIGH16, Opcode.CONST_4)
private const val BOOLEAN_OBJECT = "Ljava/lang/Boolean;"

/** Whether [type], or a class it extends, is [holder]. */
private fun BytecodePatchContext.keeps(type: String, holder: String): Boolean =
    generateSequence(type) { classDefByOrNull(it)?.superclass }.take(16).any { it == holder }

/** A call on the tree's interface [tree], taking [parameters] and answering a tree. */
private fun MethodReference.isTreeCall(tree: String, vararg parameters: String) =
    definingClass == tree && returnType == tree && parameterTypes.map(CharSequence::toString) == parameters.toList()

/**
 * The client string the sheet hands the typing call in [register]: what the last instruction ahead
 * of the getter's call that writes that register loads, a const-string or a static string pool's
 * answer, as 450's Redex has it on some builds. Null for anything else.
 */
private fun BytecodePatchContext.clientIn(code: List<Instruction>, before: Int, register: Int): String? {
    val writer = (before - 1 downTo 0).firstOrNull { at ->
        code[at].opcode.setsRegister() && (code[at] as? OneRegisterInstruction)?.registerA == register
    } ?: return null
    val client = if (code[writer].opcode == Opcode.MOVE_RESULT_OBJECT) {
        if (writer == 0) null else stringLoadedAt(code, writer - 1)
    } else {
        stringLoadedAt(code, writer)
    }
    // Written into the stub as a smali string, so only plain names.
    return client?.takeIf { it.isNotEmpty() && it.all { char -> char.isLetterOrDigit() || char in "-_." } }
}

/**
 * The distinct calls on [tree] answering [returns] that [code] makes right after loading the Java
 * hash of [field] into the register the call is handed.
 */
private fun keyedCalls(code: List<Instruction>, field: String, tree: String, returns: String): Set<String> {
    val key = field.hashCode()
    return code.indices.mapNotNullTo(LinkedHashSet()) { at ->
        val load = code[at]
        if (load !is NarrowLiteralInstruction || load.opcode !in CONSTANTS || load.narrowLiteral != key) return@mapNotNullTo null
        val call = code.getOrNull(at + 1) ?: return@mapNotNullTo null
        val called = call.methodReference() ?: return@mapNotNullTo null
        val handed = call.namedRegisters()
        val matches = call.opcode == Opcode.INVOKE_INTERFACE && called.definingClass == tree && called.returnType == returns &&
            called.parameterTypes.map(CharSequence::toString) == listOf("I") && handed.size == 2 &&
            handed[1] == (load as OneRegisterInstruction).registerA
        if (matches) called.toString() else null
    }
}
