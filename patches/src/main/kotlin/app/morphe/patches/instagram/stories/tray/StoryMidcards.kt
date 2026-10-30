/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.tray

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderInstruction
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference

private const val PATCH = "Hide suggested stories"

internal const val STORY_MIDCARD = "$EXTENSION_PACKAGE/stories/StoriesTray;->midcard(Ljava/lang/String;)Z"

/** The name Instagram gives the type of the full-screen Suggested for you page between stories. */
internal const val SUGGESTED_USERS_MIDCARD = "story_interstitial_suggested_users_midcard"

/** The repository the code adding that page asks first. Instagram keeps its name. */
internal const val SUGGESTED_USERS_REPOSITORY = "Lcom/instagram/reels/midcards/suggestedusers/data/StorySuggestedUsersRepository;"

private const val INTEGER = "Ljava/lang/Integer;"
private const val STRING = "Ljava/lang/String;"
private const val LIST = "Ljava/util/List;"
private val LISTS = setOf(LIST, "Ljava/util/ArrayList;")

/**
 * What [hideStoryMidcards] needs: the static `(Integer)String` naming a midcard type, the reel
 * constructor that takes the type and names the reel with it, the static helpers that build and add
 * such a reel for their caller, and the methods adding a midcard reel to the viewer's list, either
 * building it themselves or through one of those helpers.
 */
internal class StoryMidcards(
    val mapper: MethodReference,
    val reel: MethodReference,
    val helpers: List<MethodReference>,
    val adders: List<MethodSite>,
)

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Finds the code that adds a midcard between stories. On 451 each midcard reel is built with
 * `new Reel(null, type, list, list)`, the type an Integer that the reel's constructor turns into
 * its id through one static `(Integer)String`, the method holding [SUGGESTED_USERS_MIDCARD] among
 * the other types' names. The adders are the static methods making that call that take the
 * viewer's list and answer a boolean (whether they added a card) or an object they may answer
 * null for (no card). Some builds move one build into a static void helper taking the type as an
 * Integer and the list, which builds the reel with that type and adds it (the x86 453.0.0.57.79
 * build does it for two midcards), so the methods calling such a helper that answer the same way
 * are adders too. One of them has to ask [SUGGESTED_USERS_REPOSITORY]. Fails before anything
 * changes when any of it isn't so, since that's an update this patch hasn't seen.
 */
internal fun BytecodePatchContext.findStoryMidcards(): StoryMidcards {
    val mappers = classesLoadingString(SUGGESTED_USERS_MIDCARD).filterNot { it.type.startsWith(EXTENSION_ROOT) }.flatMap { classDef ->
        classDef.methods.filter {
            it.isStatic() && it.parameters() == listOf(INTEGER) && it.returnType == STRING && loadsString(it, SUGGESTED_USERS_MIDCARD)
        }
    }
    if (mappers.isEmpty()) refuse("expected a static (Integer)String holding \"$SUGGESTED_USERS_MIDCARD\", found none")
    val built = mappers.mapNotNull { mapper ->
        val reels = classesCalling(mapper.definingClass, mapper.name).flatMap { classDef ->
            classDef.methods.filter { method ->
                val parameters = method.parameters()
                method.name == "<init>" && parameters.size == 4 && parameters[0].startsWith("L") &&
                    parameters.drop(1) == listOf(INTEGER, LIST, LIST) && method.calls().any { it.matches(mapper) }
            }
        }
        reels.singleOrNull()?.let { mapper to it }
    }
    val (mapper, reel) = built.singleOrNull()
        ?: refuse("expected one static (Integer)String holding \"$SUGGESTED_USERS_MIDCARD\" with one reel constructor (Object, Integer, List, List) asking it, found ${built.size}")
    val reelAt = "${reel.definingClass}-><init>(${reel.parameters().joinToString("")})"

    val builders = classesCalling(reel.definingClass, "<init>").filterNot { it.type.startsWith(EXTENSION_ROOT) }
        .flatMap { classDef -> classDef.methods.filter { it.reelBuilds(reel).isNotEmpty() } }
    val helpers = builders.filter { it.addsFromItsType(reel) }
    val callers = helpers.flatMap { helper ->
        classesCalling(helper.definingClass, helper.name).filterNot { it.type.startsWith(EXTENSION_ROOT) }.flatMap { classDef ->
            classDef.methods.filter { method -> method.helperCalls(listOf(helper.reference())).isNotEmpty() }
        }
    }
    val adders = (builders + callers).filter { it.addsToAList() }.distinctBy { "${it.definingClass}->${it.name}${it.parameters()}" }
    if (adders.isEmpty()) refuse("nothing taking a list builds a midcard with $reelAt")
    if (adders.none { it.asks(SUGGESTED_USERS_REPOSITORY) }) {
        refuse("none of the ${adders.size} midcard adder(s) asks $SUGGESTED_USERS_REPOSITORY")
    }
    return StoryMidcards(
        mapper.reference(),
        reel.reference(),
        helpers.map { it.reference() },
        adders.map { MethodSite(it.definingClass, it.name, it.parameters()) },
    )
}

/**
 * Right after each midcard build in each adder, or right before each call to a helper that builds
 * one: hands the type the reel is built with to [StoryMidcards.mapper], the same name the reel takes
 * as its id, and asks [STORY_MIDCARD]. A yes answers the adder false or null, what it answers when
 * it has no card, before the reel reaches the list. A no runs on into Instagram's own code. The one
 * local it borrows is one nothing reads afterwards; the type's own register, when that's free after
 * a build, does fine.
 */
internal fun BytecodePatchContext.hideStoryMidcards(midcards: StoryMidcards) {
    // Where each check goes, the register holding the type there, and the instruction Instagram goes
    // on with, for every adder before any is written.
    val plans = midcards.adders.map { site ->
        val method = mutableClassDefBy(site.type).methods.single {
            it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
        }
        val where = "${site.type}->${site.name}"
        val builds = method.reelBuilds(midcards.reel)
        val helped = method.helperCalls(midcards.helpers)
        if (builds.isEmpty() && helped.isEmpty()) refuse("$where no longer builds a midcard")
        method to builds.map { at -> Triple(at + 1, method.typeRegister(method.getInstruction(at)), method.getInstruction(at + 1)) } +
            helped.map { at ->
                if ((method.implementation!!.instructions[at] as BuilderInstruction).location.labels.isNotEmpty()) {
                    refuse("$where jumps straight to its midcard helper call at $at, so a check before it could be skipped")
                }
                Triple(at, method.helperTypeRegister(method.getInstruction(at)), method.getInstruction(at))
            }
    }
    plans.forEach { (method, checks) ->
        val answer = if (method.returnType == "Z") "return" else "return-object"
        // Placed from the end so the indexes before each one still hold.
        checks.sortedByDescending { it.first }.forEach { (at, type, instagram) ->
            val free = method.freeLocalsAt(PATCH, at, 1, highest = 255).single()
            method.addInstructionsWithLabels(
                at,
                """
                    invoke-static/range { v$type .. v$type }, ${midcards.mapper}
                    move-result-object v$free
                    invoke-static/range { v$free .. v$free }, $STORY_MIDCARD
                    move-result v$free
                    if-eqz v$free, :instagram
                    const/16 v$free, 0x0
                    $answer v$free
                """,
                ExternalLabel("instagram", instagram),
            )
        }
    }
}

/** The indexes of the calls to [reel], the midcard reel's constructor. */
private fun Method.reelBuilds(reel: MethodReference): List<Int> =
    implementation?.instructions?.toList().orEmpty().withIndex().filter { (_, instruction) ->
        instruction.opcode.let { it == Opcode.INVOKE_DIRECT || it == Opcode.INVOKE_DIRECT_RANGE } &&
            ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.matches(reel) == true
    }.map { it.index }

/**
 * A static method taking a list that answers whether it added something, or an object it answers
 * null for somewhere, as a midcard adder does. Anything else building a midcard reel (rebuilding the
 * viewer's reels from their ids, say) isn't one.
 */
private fun Method.addsToAList(): Boolean {
    if (!isStatic() || LIST !in parameters()) return false
    if (returnType == "Z") return true
    if (!returnType.startsWith("L")) return false
    val code = implementation?.instructions?.toList().orEmpty()
    return code.zipWithNext().any { (load, answer) ->
        load.opcode == Opcode.CONST_4 && (load as NarrowLiteralInstruction).narrowLiteral == 0 &&
            answer.opcode == Opcode.RETURN_OBJECT &&
            (answer as OneRegisterInstruction).registerA == (load as OneRegisterInstruction).registerA
    }
}

/**
 * A static void method taking one Integer and a list that builds the midcard reel with that Integer
 * as the type every time and adds to a list, as the helper some builds outline a midcard build into.
 */
private fun Method.addsFromItsType(reel: MethodReference): Boolean {
    val parameters = parameters()
    if (!isStatic() || returnType != "V" || LIST !in parameters || parameters.count { it == INTEGER } != 1) return false
    if (calls().none { it.name == "add" && it.definingClass in LISTS }) return false
    val code = implementation?.instructions?.toList() ?: return false
    val type = implementation!!.registerCount - parameters.sumOf { it.width() } +
        parameters.takeWhile { it != INTEGER }.sumOf { it.width() }
    return reelBuilds(reel).all { typeRegister(code[it]) == type }
}

/** The indexes of the calls to any of [helpers], the static methods building and adding a midcard. */
private fun Method.helperCalls(helpers: List<MethodReference>): List<Int> =
    implementation?.instructions?.toList().orEmpty().withIndex().filter { (_, instruction) ->
        instruction.opcode.let { it == Opcode.INVOKE_STATIC || it == Opcode.INVOKE_STATIC_RANGE } &&
            ((instruction as? ReferenceInstruction)?.reference as? MethodReference)?.let { called -> helpers.any { called.matches(it) } } == true
    }.map { it.index }

/** The register holding the type the reel constructor's call [build] passes, its third operand. */
private fun Method.typeRegister(build: Instruction): Int = when (build) {
    is FiveRegisterInstruction -> build.registerE
    is RegisterRangeInstruction -> build.startRegister + 2
    else -> refuse("$definingClass->$name builds a midcard with a ${build.opcode}")
}

/** The register holding the type a helper call [call] passes, the operand of its Integer parameter. */
private fun Method.helperTypeRegister(call: Instruction): Int {
    val parameters = ((call as ReferenceInstruction).reference as MethodReference).parameterTypes.map(CharSequence::toString)
    val operand = parameters.takeWhile { it != INTEGER }.sumOf { it.width() }
    return when (call) {
        is FiveRegisterInstruction -> listOf(call.registerC, call.registerD, call.registerE, call.registerF, call.registerG)[operand]
        is RegisterRangeInstruction -> call.startRegister + operand
        else -> refuse("$definingClass->$name calls a midcard helper with a ${call.opcode}")
    }
}

private fun String.width() = if (this == "J" || this == "D") 2 else 1

private fun Method.asks(type: String): Boolean = implementation?.instructions?.any { instruction ->
    (instruction as? ReferenceInstruction)?.reference?.toString()?.contains(type) == true
} == true

private fun MethodReference.matches(other: MethodReference): Boolean =
    definingClass == other.definingClass && name == other.name && returnType == other.returnType &&
        parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

private fun Method.reference(): MethodReference =
    ImmutableMethodReference(definingClass, name, parameterTypes.map(CharSequence::toString), returnType)

private fun Method.calls(): List<MethodReference> = implementation?.instructions
    ?.mapNotNull { (it as? ReferenceInstruction)?.reference as? MethodReference }.orEmpty()

private fun Method.parameters(): List<String> = parameterTypes.map(CharSequence::toString)

private fun Method.isStatic() = AccessFlags.STATIC.isSet(accessFlags)
