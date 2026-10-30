/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.suggested

import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Hide suggested posts"

internal const val FEED_INTEREST = "$EXTENSION_PACKAGE/feed/FeedInterest;"
internal const val HIDE_INTEREST = "$FEED_INTEREST->hide()Z"

/** The modules of Home's feed and of the Older Posts page, which Instagram adds the question's row for. */
internal const val HOME_MODULE = "feed_timeline"
internal const val OLDER_MODULE = "feed_timeline_older"

/**
 * Where a row builder adds the "Are you interested in this post?" row, found: the builder (an
 * instance method of a feed adapter taking the row list, a post and its state), the index of the
 * instruction that starts the add, the register that instruction writes (dead until then), the
 * index a yes goes on to (the builder's return, or the instruction after the add), and the row
 * type's field. [write] puts a call to FeedInterest.hide() right before the add, and a yes skips
 * it, so the row is never added and none is left blank.
 */
internal class InterestQuestion(
    val type: String,
    val name: String,
    val parameters: List<String>,
    val rowAt: Int,
    val register: Int,
    val endAt: Int,
    val rowType: String,
) {
    fun write(context: BytecodePatchContext) {
        val method = context.mutableClassDefBy(type).methods.single {
            it.name == name && it.returnType == "V" && it.parameterTypes.map(CharSequence::toString) == parameters
        }
        // Taken before the insert, so the label lands on that instruction itself.
        val end = method.getInstruction(endAt)
        // At the add's own label, so every way to it, Home's and Older Posts', passes the hook.
        method.addInstructionsAtControlFlowLabel(
            rowAt,
            """
                invoke-static {}, $HIDE_INTEREST
                move-result v$register
                if-nez v$register, :end
            """,
            ExternalLabel("end", end),
        )
    }
}

/**
 * Finds where Instagram's Home feed adapter adds the question's row. That adapter's row builder is
 * the one instance method of the shape (adapter list, Object, Object)V holding both feed module
 * names, [HOME_MODULE] and [OLDER_MODULE]. The row goes in at the end of the builder: a post's
 * question object (a getter on the post answering a Redex class) not being null, the feed's module
 * being [HOME_MODULE] or [OLDER_MODULE], and then the row's type constant and a jump to the shared
 * add. The question's absence and a module that isn't Home's both go straight to the builder's
 * return. The hook goes where the two module checks meet, so it only runs, and only counts, for a
 * row that was about to be added. On 451's 385713073 that's LX/05sE->buildRowViewTypes, Media.A2I()
 * and the add at instruction 442. Anything else is an update this patch hasn't seen, and it stops.
 */
internal fun BytecodePatchContext.findInterestQuestion(): InterestQuestion {
    val found = mutableListOf<Pair<Method, List<Instruction>>>()
    classesHolding(HOME_MODULE, OLDER_MODULE).forEach { classDef ->
        classDef.methods.forEach { method ->
            val parameters = method.parameterTypes.map(CharSequence::toString)
            if (AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "V" || parameters.size != 3 ||
                parameters[1] != OBJECT || parameters[2] != OBJECT
            ) {
                return@forEach
            }
            val code = method.implementation?.instructions?.toList() ?: return@forEach
            val names = code.indices.mapNotNull { stringLoadedAt(code, it) }.toSet()
            if (HOME_MODULE in names && OLDER_MODULE in names) found += method to code
        }
    }
    // 453 has another adapter that checks the same two module names for a different row; the
    // question's builder is the one that reads a post's question right before the name.
    val candidates = found.filter { (_, code) ->
        code.indices.any { at ->
            stringLoadedAt(code, at) == HOME_MODULE && at >= 5 &&
                (code[at - 5] as? ReferenceInstruction)?.reference.let {
                    it is MethodReference && it.definingClass == MEDIA && it.parameterTypes.isEmpty() &&
                        it.returnType.startsWith("L")
                } &&
                code[at - 4].opcode == Opcode.MOVE_RESULT_OBJECT && code[at - 3].opcode == Opcode.IF_EQZ &&
                code[at - 2].opcode == Opcode.INVOKE_INTERFACE
        }
    }
    // A lone builder goes on to the checks below, which say what about it is off.
    val (method, code) = found.singleOrNull() ?: candidates.singleOrNull()
        ?: refuse("expected one row builder holding $HOME_MODULE and $OLDER_MODULE, found ${candidates.size} of ${found.size}")
    val where = "${method.definingClass}->${method.name}"
    val addresses = code.runningFold(0) { at, instruction -> at + instruction.codeUnits }
    fun targetOf(at: Int): Int {
        val target = addresses[at] + (code[at] as OffsetInstruction).codeOffset
        return addresses.indexOf(target)
    }

    val named = code.indices.filter { stringLoadedAt(code, it) == HOME_MODULE }
    val nameAt = named.singleOrNull() ?: refuse("$where loads $HOME_MODULE ${named.size} times, expected once")
    if (nameAt < 5) refuse("$where reads $HOME_MODULE before it could check a post's question")
    // post.<question>() -> move-result -> if-eqz (no question) -> module.getModuleName() -> move-result -> "feed_timeline"
    val asked = (code.getOrNull(nameAt - 5) as? ReferenceInstruction)?.reference as? MethodReference
    val module = (code.getOrNull(nameAt - 2) as? ReferenceInstruction)?.reference as? MethodReference
    val questionIn = code.getOrNull(nameAt - 4) as? OneRegisterInstruction
    val checkOn = code.getOrNull(nameAt - 3) as? OneRegisterInstruction
    if (code[nameAt - 5].opcode != Opcode.INVOKE_VIRTUAL || asked?.definingClass != MEDIA || asked.parameterTypes.isNotEmpty() ||
        !asked.returnType.startsWith("L") || code[nameAt - 4].opcode != Opcode.MOVE_RESULT_OBJECT ||
        code[nameAt - 3].opcode != Opcode.IF_EQZ || questionIn == null || checkOn == null ||
        questionIn.registerA != checkOn.registerA
    ) {
        refuse("$where doesn't check a post's question before it reads the feed's module")
    }
    if (code[nameAt - 2].opcode != Opcode.INVOKE_INTERFACE || module?.returnType != STRING || module.parameterTypes.isNotEmpty() ||
        code[nameAt - 1].opcode != Opcode.MOVE_RESULT_OBJECT
    ) {
        refuse("$where doesn't read the feed's module name before comparing it")
    }
    val endAt = targetOf(nameAt - 3)
    if (endAt < 0 || code[endAt].opcode != Opcode.RETURN_VOID) refuse("$where doesn't return when a post has no question")

    // equals -> move-result -> if-nez (Home) -> "feed_timeline_older" -> equals -> move-result -> if-eqz (neither) -> add
    val register = (code[nameAt] as OneRegisterInstruction).registerA
    val equalsHome = code.getOrNull(nameAt + 1)
    val homeAt = nameAt + 3
    val olderAt = nameAt + 4
    val addAt = nameAt + 8
    if (!equalsHome.callsEquals() || code.getOrNull(nameAt + 2)?.opcode != Opcode.MOVE_RESULT ||
        code.getOrNull(homeAt)?.opcode != Opcode.IF_NEZ || stringLoadedAt(code, olderAt) != OLDER_MODULE ||
        !code.getOrNull(nameAt + 5).callsEquals() || code.getOrNull(nameAt + 6)?.opcode != Opcode.MOVE_RESULT ||
        code.getOrNull(nameAt + 7)?.opcode != Opcode.IF_EQZ
    ) {
        refuse("$where doesn't compare the module with $HOME_MODULE and $OLDER_MODULE one after the other")
    }
    if (targetOf(homeAt) != addAt) refuse("$where doesn't add its row when the module is $HOME_MODULE")
    if (targetOf(nameAt + 7) != endAt) refuse("$where doesn't return when the module is neither")

    // The add: the row type constant into the register the comparison used, then a jump to the shared add.
    val add = code.getOrNull(addAt)
    val jump = code.getOrNull(addAt + 1)
    if (add?.opcode != Opcode.SGET_OBJECT || (add as OneRegisterInstruction).registerA != register) {
        refuse("$where doesn't load its row type into v$register where it adds the row")
    }
    if (jump?.opcode != Opcode.GOTO && jump?.opcode != Opcode.GOTO_16 && jump?.opcode != Opcode.GOTO_32) {
        refuse("$where doesn't jump to a shared add after loading its row type")
    }
    val shared = targetOf(addAt + 1)
    val ordinal = (code.getOrNull(shared) as? ReferenceInstruction)?.reference as? MethodReference
    if (shared < 0 || code[shared].opcode != Opcode.INVOKE_VIRTUAL || ordinal?.name != "ordinal" || ordinal.returnType != "I") {
        refuse("$where doesn't go on to take the row type's ordinal")
    }
    if (register > 255) refuse("$where keeps the module in v$register, past v255")
    // Nothing between the hook's write and the add may read the register: the add starts with its overwrite.
    return InterestQuestion(
        method.definingClass, method.name, method.parameterTypes.map(CharSequence::toString), addAt, register, endAt,
        ((add as ReferenceInstruction).reference as FieldReference).toString(),
    )
}

/**
 * Finds the other place the question's row goes in (#131): the feed item binder group, which Home
 * can be built with too. It reads the same question from the post's data and adds the row when the
 * server styles it UNIFIED_NO_SUBTEXT (the question without a subtitle, `LX/06An.A0F` on 385713073), again
 * only for [HOME_MODULE] and [OLDER_MODULE]. It's the one other method loading both names, held or
 * asked of a string pool, that loads [home]'s row type, and it loads it once: the load, its ordinal
 * and the add, right after a Home check and a style check that both skip the add. The hook goes on
 * the load, and a yes goes on to the instruction after the add. On 451's 385713073 that's
 * LX/05rL->A06 and the load at instruction 779. Anything else stops the patch.
 */
internal fun BytecodePatchContext.findGroupedInterestQuestion(home: InterestQuestion): InterestQuestion {
    val found = mutableListOf<Triple<Method, List<Instruction>, Int>>()
    val older = classesLoadingString(OLDER_MODULE).mapTo(HashSet()) { it.type }
    classesLoadingString(HOME_MODULE).filter { it.type in older }.forEach { classDef ->
        classDef.methods.forEach { method ->
            val parameters = method.parameterTypes.map(CharSequence::toString)
            if (AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "V" ||
                method.definingClass == home.type && method.name == home.name && parameters == home.parameters
            ) {
                return@forEach
            }
            val code = method.implementation?.instructions?.toList() ?: return@forEach
            val loads = code.indices.filter { code[it].opcode == Opcode.SGET_OBJECT && code[it].fieldReference() == home.rowType }
            if (loads.isEmpty()) return@forEach
            val names = code.indices.mapNotNull { stringLoadedAt(code, it) }.toSet()
            if (HOME_MODULE in names && OLDER_MODULE in names) loads.forEach { found += Triple(method, code, it) }
        }
    }
    val (method, code, loadAt) = found.singleOrNull()
        ?: refuse("expected one other load of the row type next to $HOME_MODULE and $OLDER_MODULE, found ${found.size}")
    val where = "${method.definingClass}->${method.name}"
    val addresses = code.runningFold(0) { at, instruction -> at + instruction.codeUnits }
    fun targetOf(at: Int): Int {
        val target = addresses[at] + (code[at] as OffsetInstruction).codeOffset
        return addresses.indexOf(target)
    }

    // sget-object (row type) -> ordinal -> move-result -> the add, taking the ordinal last
    val register = (code[loadAt] as OneRegisterInstruction).registerA
    val ordinal = code.getOrNull(loadAt + 1)
    val called = (ordinal as? ReferenceInstruction)?.reference as? MethodReference
    if (ordinal?.opcode != Opcode.INVOKE_VIRTUAL || called?.name != "ordinal" || called.returnType != "I" ||
        (ordinal as FiveRegisterInstruction).registerC != register
    ) {
        refuse("$where doesn't take the row type's ordinal right after loading it")
    }
    val result = code.getOrNull(loadAt + 2)
    if (result?.opcode != Opcode.MOVE_RESULT) refuse("$where doesn't keep the row type's ordinal")
    val ordinalIn = (result as OneRegisterInstruction).registerA
    val add = code.getOrNull(loadAt + 3)
    val adds = (add as? ReferenceInstruction)?.reference as? MethodReference
    if (add?.opcode != Opcode.INVOKE_VIRTUAL || adds?.returnType != "V" || adds.parameterTypes.lastOrNull()?.toString() != "I" ||
        (add as FiveRegisterInstruction).lastRegister() != ordinalIn
    ) {
        refuse("$where doesn't add the row with the row type's ordinal")
    }
    val skipAt = loadAt + 4
    if (skipAt >= code.size) refuse("$where ends at its add")

    // The style check falls into the load and skips the add otherwise.
    val style = code.getOrNull(loadAt - 1)
    // Opcode.name is dexlib2's smali spelling ("if-ne"), not the enum constant's.
    if (style !is OffsetInstruction || !style.opcode.name.startsWith("if-") || targetOf(loadAt - 1) != skipAt) {
        refuse("$where doesn't check the question's style right before the add")
    }
    // The last Older Posts check before it: a feed that's neither Home's nor Older Posts' skips the add too.
    val olderAt = (0 until loadAt).lastOrNull { stringLoadedAt(code, it) == OLDER_MODULE }
        ?: refuse("$where doesn't compare the module with $OLDER_MODULE before the add")
    val neither = (olderAt + 1 until loadAt).firstOrNull { code[it].opcode == Opcode.IF_EQZ }
    if (neither == null || targetOf(neither) != skipAt) refuse("$where doesn't skip the add when the module is neither")
    return InterestQuestion(
        method.definingClass, method.name, method.parameterTypes.map(CharSequence::toString), loadAt, register, skipAt,
        home.rowType,
    )
}

private fun Instruction.fieldReference(): String? = ((this as? ReferenceInstruction)?.reference as? FieldReference)?.toString()

private fun FiveRegisterInstruction.lastRegister(): Int = when (registerCount) {
    1 -> registerC
    2 -> registerD
    3 -> registerE
    4 -> registerF
    5 -> registerG
    else -> -1
}

private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"

private fun Instruction?.callsEquals(): Boolean {
    val called = (this as? ReferenceInstruction)?.reference as? MethodReference
    return this?.opcode == Opcode.INVOKE_VIRTUAL && called?.definingClass == STRING && called.name == "equals" &&
        called.returnType == "Z" && called.parameterTypes.map(CharSequence::toString) == listOf(OBJECT)
}

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: the interest question: $detail")
