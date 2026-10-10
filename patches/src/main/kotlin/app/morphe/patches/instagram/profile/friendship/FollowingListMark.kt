/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.friendship

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val FOLLOWING_LIST = "$EXTENSION_PACKAGE/profile/FollowingList;"
internal const val FOLLOWING_ROW = "$FOLLOWING_LIST->row(Ljava/lang/Object;ILandroid/view/View;Ljava/lang/Object;)V"

/**
 * Kotlin's message for the follow list binder's cast of a row's state, which keeps the name
 * Instagram's source gives that class. Only the binder of a follow list's rows loads it.
 */
internal const val FOLLOW_ROW_STATE =
    "null cannot be cast to non-null type com.instagram.user.userlist.adapter.FollowRowState"

/** What a follow list is, a class Instagram keeps the name of: the kind of list, and whose it is. */
internal const val FOLLOW_LIST_DATA = "Lcom/instagram/follow/analytics/FollowListData;"

/** The name of Instagram's enum constant for a Following list, which its class initializer hands to Enum. */
internal const val FOLLOWING_KIND = "FOLLOWING"

/** The keys the user model's getters of a display name and a full name load. A row's name line shows one of them. */
internal val NAME_KEYS = setOf("extra_display_name", "full_name")

/** The signed-in account's ID, a field whose name Instagram keeps. Its check of whether a list is yours reads it. */
internal const val SESSION_USER_ID = "$USER_SESSION->userId:$STRING"

private const val BINDER_SHAPE = "(ILandroid/view/View;Ljava/lang/Object;Ljava/lang/Object;)V"

/**
 * The follow list's row binder and what the hook and its stubs need: where the hook goes, the row's
 * view holder and its name line, and the fields from the binder to the kind of list, its owner and
 * the account signed in.
 */
internal class FollowRow(
    val type: String,
    val name: String,
    val parameters: List<String>,
    /** The instruction right after the call that fills the row in, where the hook goes. */
    val at: Int,
    /** The row's view holder, which the binder takes from the row's tag, and its name line. */
    val holder: String,
    val subtitle: FieldReference,
    /** The binder's list config, the config's [FOLLOW_LIST_DATA], and that one's kind of list and owner ID. */
    val config: FieldReference,
    val data: FieldReference,
    val kind: FieldReference,
    val owner: FieldReference,
    /** The binder's signed-in account. */
    val session: FieldReference,
)

/**
 * Finds the one method outside the extension that loads [FOLLOW_ROW_STATE], the follow list's
 * `bindView(int, View, Object, Object)`, and in it:
 * - the row state's class, from the first cast after that string;
 * - the view holder's class, from the one cast of the row view's `getTag()`;
 * - the call that fills the row in, the first static call after the string taking a [USER], the row
 *   state and the holder. The hook goes right after it, where no jump lands;
 * - one cast of the third parameter to [USER], so the hook's account is a user.
 *
 * In the call it finds the name line: the one TextView field of the holder that's read and straight
 * away given the result of a [USER] getter loading one of [NAME_KEYS].
 *
 * The binder's one field whose class keeps one [FOLLOW_LIST_DATA] is the list config, and that
 * class's one enum field whose class initializer names [FOLLOWING_KIND] the kind of list. The
 * owner's ID is the one String of it the config hands, with the signed-in account, straight to a
 * static check that reads [SESSION_USER_ID]: Instagram's own test of whether the list is yours. The
 * binder's one [USER_SESSION] is the account signed in.
 *
 * Fails when any of them isn't there, or there's more than one, since that's an update this patch
 * hasn't seen, and when `this` or a parameter the hook hands on is written over before it.
 */
internal fun BytecodePatchContext.findFollowRow(): FollowRow {
    val binders = mutableListOf<Pair<ClassDef, Method>>()
    val holders = classesHolding(FOLLOW_ROW_STATE).mapTo(HashSet()) { it.type }
    classDefForEach { classDef ->
        if (classDef.type !in holders || classDef.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        classDef.methods.filter { it.holdsString(FOLLOW_ROW_STATE) }.forEach { binders += classDef to it }
    }
    val (binderClass, binder) = binders.singleOrNull()
        ?: refuse("expected one method loading \"$FOLLOW_ROW_STATE\", found ${binders.size}")
    val where = "${binderClass.type}->${binder.name}"
    val shape = binder.parameterTypes.joinToString("", "(", ")") + binder.returnType
    if (AccessFlags.STATIC.isSet(binder.accessFlags) || shape != BINDER_SHAPE) {
        refuse("$where isn't an instance method $BINDER_SHAPE, the follow list's row binder")
    }
    val code = binder.implementation!!.instructions.toList()
    val view = binder.parameterRegisterNumber(1)
    val item = binder.parameterRegisterNumber(2)

    val named = code.indexOfFirst { it.loadsString(FOLLOW_ROW_STATE) }
    val rowState = (named + 1 until code.size).firstOrNull { code[it].opcode == Opcode.CHECK_CAST }
        ?.let { code[it].castType() }
        ?: refuse("$where doesn't cast the row state after loading its name")

    val tags = code.indices.filter { at ->
        val called = code[at].methodReference()
        code[at].opcode == Opcode.INVOKE_VIRTUAL && called?.name == "getTag" && called.parameterTypes.isEmpty() &&
            called.returnType == OBJECT && code[at].namedRegisters().singleOrNull()?.let { holdsParameter(code, at, it, view) } == true
    }
    val tag = tags.singleOrNull() ?: refuse("expected $where to ask the row's view for its tag once, found ${tags.size}")
    val tagged = (code.getOrNull(tag + 1) as? OneRegisterInstruction)?.takeIf { code[tag + 1].opcode == Opcode.MOVE_RESULT_OBJECT }
        ?.registerA ?: refuse("$where drops the row's tag")
    val holder = (tag + 2 until code.size).firstOrNull {
        code[it].opcode == Opcode.CHECK_CAST && (code[it] as OneRegisterInstruction).registerA == tagged
    }?.let { code[it].castType() } ?: refuse("$where doesn't cast the row's tag to its view holder")

    val call = (named + 1 until code.size).firstOrNull { at ->
        val called = code[at].methodReference()
        (code[at].opcode == Opcode.INVOKE_STATIC || code[at].opcode == Opcode.INVOKE_STATIC_RANGE) && called != null &&
            called.parameterTypes.map(CharSequence::toString).containsAll(listOf(USER, rowState, holder))
    } ?: refuse("$where doesn't hand a $USER, its $rowState and its $holder to a static call")
    // A call answering something is followed by its move-result, which the hook mustn't split off.
    val fills = code[call].methodReference()!!.returnType
    if (fills != "V") refuse("$where fills the row in with a call answering $fills, not void")
    val at = call + 1
    if (at >= code.size || at in binder.jumpTargets()) {
        refuse("$where has no place right after the call filling the row in that only that call leads to")
    }

    val users = code.indices.filter { code[it].opcode == Opcode.CHECK_CAST && code[it].castType() == USER }
    val user = users.singleOrNull() ?: refuse("expected $where to cast one value to $USER, found ${users.size}")
    if (user > call || !holdsParameter(code, user, (code[user] as OneRegisterInstruction).registerA, item)) {
        refuse("$where doesn't cast the row's item to $USER before filling the row in")
    }

    val subtitle = nameLine(code[call].methodReference()!!, holder)

    val configs = binderClass.publicInstanceFields().mapNotNull { field ->
        classDefByOrNull(field.type)?.instanceFields(FOLLOW_LIST_DATA)?.singleOrNull()?.let { field to it }
    }
    val (config, data) = configs.singleOrNull()
        ?: refuse("expected ${binderClass.type} to keep one config holding a $FOLLOW_LIST_DATA, found ${configs.size}")
    val dataClass = classDefByOrNull(FOLLOW_LIST_DATA) ?: refuse("$FOLLOW_LIST_DATA isn't in this build")
    val kinds = dataClass.publicInstanceFields().filter { field ->
        val kindClass = classDefByOrNull(field.type)
        kindClass != null && AccessFlags.ENUM.isSet(kindClass.accessFlags) &&
            kindClass.methods.any { it.name == "<clinit>" && it.holdsString(FOLLOWING_KIND) }
    }
    val kind = kinds.singleOrNull()
        ?: refuse("expected $FOLLOW_LIST_DATA to keep one list kind naming $FOLLOWING_KIND, found ${kinds.size}")

    val configClass = classDefByOrNull(config.type)!!
    val owners = configClass.methods.flatMap { method ->
        val body = method.implementation?.instructions?.toList() ?: return@flatMap emptyList()
        body.indices.mapNotNull { index -> ownerCheckedAt(body, index) }
    }.distinct()
    val owner = owners.singleOrNull()
        ?: refuse("expected ${config.type} to check one $FOLLOW_LIST_DATA ID against $SESSION_USER_ID, found $owners")

    val session = binderClass.instanceFields(USER_SESSION).singleOrNull()
        ?: refuse("expected ${binderClass.type} to keep one $USER_SESSION")
    val sessionClass = classDefByOrNull(USER_SESSION) ?: refuse("$USER_SESSION isn't in this build")
    sessionClass.methods.singleOrNull {
        it.name == GET_USER_ID && it.parameterTypes.isEmpty() && it.returnType == STRING && AccessFlags.PUBLIC.isSet(it.accessFlags) &&
            !AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuse("$USER_SESSION has no public $GET_USER_ID()")

    // The stubs reach these from the extension, outside Instagram's packages.
    val holderClass = classDefByOrNull(holder) ?: refuse("$holder, the row's view holder, isn't in this build")
    if (holderClass.instanceFields(TEXT_VIEW).none { it.name == subtitle.name }) refuse("$holder->${subtitle.name} isn't public")
    listOf(binderClass, configClass, dataClass, holderClass, classDefByOrNull(kind.type)!!, sessionClass).forEach { reachable ->
        if (!AccessFlags.PUBLIC.isSet(reachable.accessFlags)) refuse("${reachable.type} isn't public, so the extension can't reach it")
    }

    // The hook reads this, the position, the row's view and its item where it goes.
    binder.requireThisIntact(PATCH, listOf(at))
    (0..2).forEach { binder.requireParameterIntact(PATCH, it, listOf(at)) }

    return FollowRow(
        binderClass.type, binder.name, binder.parameterTypes.map(CharSequence::toString), at, holder, subtitle,
        config, data, kind, owner, session,
    )
}

/**
 * The holder's name line in [called], the static call filling a row in: the one TextView field of
 * [holder] it reads and straight away gives the text a [USER] getter loading one of [NAME_KEYS]
 * answered last.
 */
private fun BytecodePatchContext.nameLine(called: MethodReference, holder: String): FieldReference {
    val where = "${called.definingClass}->${called.name}"
    val method = classDefByOrNull(called.definingClass)?.methods?.singleOrNull {
        it.name == called.name && it.parameterTypes.map(CharSequence::toString) == called.parameterTypes.map(CharSequence::toString) &&
            it.returnType == called.returnType
    } ?: refuse("$where, which fills a follow list's row in, isn't in this build")
    val userClass = classDefByOrNull(USER) ?: refuse("$USER isn't in this build")
    val nameGetters = userClass.methods.filter { getter ->
        getter.parameterTypes.isEmpty() && getter.returnType == STRING && NAME_KEYS.any { getter.holdsString(it) }
    }.map { it.name }.toSet()
    val code = method.implementation?.instructions?.toList() ?: refuse("$where has no body")
    val fields = code.indices.mapNotNull { at ->
        val read = code[at].fieldReference()
        if (code[at].opcode != Opcode.IGET_OBJECT || read?.definingClass != holder || read.type != TEXT_VIEW) return@mapNotNull null
        val line = (code[at] as TwoRegisterInstruction).registerA
        val set = code.getOrNull(at + 1)?.methodReference() ?: return@mapNotNull null
        if (set.name != "setText" || set.parameterTypes.map(CharSequence::toString) != listOf("Ljava/lang/CharSequence;")) return@mapNotNull null
        val (target, text) = code[at + 1].namedRegisters().takeIf { it.size == 2 } ?: return@mapNotNull null
        if (target != line) return@mapNotNull null
        val written = (at - 1 downTo 0).firstOrNull { code[it].writes(text) } ?: return@mapNotNull null
        if (code[written].opcode != Opcode.MOVE_RESULT_OBJECT) return@mapNotNull null
        val got = code.getOrNull(written - 1)?.methodReference() ?: return@mapNotNull null
        if (got.definingClass == USER && got.parameterTypes.isEmpty() && got.returnType == STRING && got.name in nameGetters) read else null
    }.distinctBy { it.toString() }
    return fields.singleOrNull()
        ?: refuse("expected $where to give one $holder TextView a user's name, found ${fields.size}")
}

/**
 * The [FOLLOW_LIST_DATA] String field read at [index] when the next instruction hands it, with the
 * signed-in account, to a static `(UserSession, String)Z` reading [SESSION_USER_ID], or null.
 */
private fun BytecodePatchContext.ownerCheckedAt(code: List<Instruction>, index: Int): FieldReference? {
    val read = code[index].fieldReference() ?: return null
    if (code[index].opcode != Opcode.IGET_OBJECT || read.definingClass != FOLLOW_LIST_DATA || read.type != STRING) return null
    val check = code.getOrNull(index + 1) ?: return null
    val called = check.methodReference() ?: return null
    if (check.opcode != Opcode.INVOKE_STATIC || called.returnType != "Z" ||
        called.parameterTypes.map(CharSequence::toString) != listOf(USER_SESSION, STRING) ||
        check.namedRegisters().getOrNull(1) != (code[index] as TwoRegisterInstruction).registerA
    ) {
        return null
    }
    val body = classDefByOrNull(called.definingClass)?.methods?.singleOrNull {
        it.name == called.name && it.parameterTypes.map(CharSequence::toString) == listOf(USER_SESSION, STRING) && it.returnType == "Z"
    }?.implementation?.instructions ?: return null
    return if (body.any { it.fieldReference()?.toString() == SESSION_USER_ID }) read else null
}

/**
 * Whether [register] holds the parameter in register [parameter] at [at]: it's that register, or
 * the last instruction before [at] writing it moved the parameter there.
 */
private fun holdsParameter(code: List<Instruction>, at: Int, register: Int, parameter: Int): Boolean {
    if (register == parameter) return true
    val written = (at - 1 downTo 0).firstOrNull { code[it].writes(register) } ?: return false
    val move = code[written]
    return move.opcode in MOVES && (move as TwoRegisterInstruction).registerB == parameter
}

private val MOVES = setOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16)

internal fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val destination = (this as? OneRegisterInstruction)?.registerA ?: return false
    return destination == register || (opcode.setsWideRegister() && destination + 1 == register)
}

private fun Instruction.castType(): String? = ((this as? ReferenceInstruction)?.reference as? TypeReference)?.type

private fun ClassDef.publicInstanceFields() = fields.filter {
    !AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
}

/**
 * Right after the row binder fills a row in, hands the binder, the row's position, its view and its
 * item to [FOLLOWING_ROW]: a range call on `this` and the first three parameters, which sit side by
 * side in their own registers, so no local is borrowed.
 */
internal fun BytecodePatchContext.markFollowRow(found: FollowRow) {
    val method = mutableClassDefBy(found.type).methods.single {
        it.name == found.name && it.parameterTypes.map(CharSequence::toString) == found.parameters
    }
    method.addInstructions(found.at, "invoke-static/range { p0 .. p3 }, $FOLLOWING_ROW")
}

/** The Following list's stubs, found before anything changes, and the step that fills them. */
internal class FollowingStubs(
    private val listKind: MutableMethod,
    private val listOwnerId: MutableMethod,
    private val viewerId: MutableMethod,
    private val subtitle: MutableMethod,
    private val fetchKind: MutableMethod,
    private val fetchOwnerId: MutableMethod,
    private val fetchViewerId: MutableMethod,
    private val statusFollowedBy: MutableMethod,
    private val sessionUserId: MutableMethod,
    internal val orderBinder: MutableMethod,
    internal val rebuildRows: MutableMethod,
) {
    fun fill(found: FollowRow, answers: FollowAnswers) {
        // Answers an Object, so its ways out may meet at one return.
        listKind.addInstructionsWithLabels(
            0,
            """
                check-cast p0, ${found.type}
                iget-object p0, p0, ${found.config}
                if-eqz p0, :none
                iget-object p0, p0, ${found.data}
                if-eqz p0, :none
                iget-object p0, p0, ${found.kind}
                :none
                return-object p0
            """,
        )
        // Each way out of a stub answering a String returns on its own (see FriendshipStubs).
        listOwnerId.addInstructionsWithLabels(
            0,
            """
                check-cast p0, ${found.type}
                iget-object p0, p0, ${found.config}
                if-nez p0, :config
                const/4 p0, 0x0
                return-object p0
                :config
                iget-object p0, p0, ${found.data}
                if-nez p0, :data
                const/4 p0, 0x0
                return-object p0
                :data
                iget-object p0, p0, ${found.owner}
                return-object p0
            """,
        )
        viewerId.addInstructionsWithLabels(
            0,
            """
                check-cast p0, ${found.type}
                iget-object p0, p0, ${found.session}
                if-nez p0, :signed_in
                const/4 p0, 0x0
                return-object p0
                :signed_in
                invoke-virtual { p0 }, $USER_SESSION->$GET_USER_ID()$STRING
                move-result-object p0
                return-object p0
            """,
        )
        subtitle.addInstructionsWithLabels(
            0,
            """
                check-cast p0, ${found.holder}
                iget-object p0, p0, ${found.subtitle}
                return-object p0
            """,
        )
        // The page fetch's list keeps the same data as the binder's config, so the kind and owner are
        // the same fields. These three answer an Object, so their ways out may meet at one return.
        for ((stub, field) in listOf(fetchKind to found.kind, fetchOwnerId to found.owner)) {
            stub.addInstructionsWithLabels(
                0,
                """
                    check-cast p0, ${answers.listType}
                    iget-object p0, p0, ${answers.listData}
                    if-eqz p0, :none
                    iget-object p0, p0, $field
                    :none
                    return-object p0
                """,
            )
        }
        // Answers an Object too, so its ways out may meet at one return.
        fetchViewerId.addInstructionsWithLabels(
            0,
            """
                check-cast p0, ${answers.listType}
                iget-object p0, p0, ${answers.listSession}
                if-eqz p0, :none
                invoke-virtual { p0 }, $USER_SESSION->$GET_USER_ID()$STRING
                move-result-object p0
                :none
                return-object p0
            """,
        )
        statusFollowedBy.addInstructionsWithLabels(
            0,
            """
                check-cast p0, ${answers.followedBy.definingClass}
                iget-object p0, p0, ${answers.followedBy}
                return-object p0
            """,
        )
        sessionUserId.addInstructionsWithLabels(
            0,
            """
                check-cast p0, $USER_SESSION
                invoke-virtual { p0 }, $USER_SESSION->$GET_USER_ID()$STRING
                move-result-object p0
                return-object p0
            """,
        )
    }
}

internal fun BytecodePatchContext.followingStubs(): FollowingStubs {
    val extension = mutableClassDefBy(FOLLOWING_LIST)
    fun stub(name: String, returns: String): MutableMethod = extension.methods.singleOrNull {
        it.name == name && it.returnType == returns && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(Any::toString) == listOf(OBJECT)
    } ?: refuse("$FOLLOWING_LIST has no static $returns $name($OBJECT)")
    for (hook in listOf(FOLLOWING_ROW, KNOWN, ANSWERED, ORDERED)) {
        extension.methods.singleOrNull {
            "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == hook.substringAfter("->") &&
                AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
        } ?: refuse("$FOLLOWING_LIST has no public static hook ${hook.substringAfter("->")}")
    }

    return FollowingStubs(
        listKind = stub("listKind", OBJECT),
        listOwnerId = stub("listOwnerId", STRING),
        viewerId = stub("viewerId", STRING),
        subtitle = stub("subtitle", TEXT_VIEW),
        fetchKind = stub("fetchKind", OBJECT),
        fetchOwnerId = stub("fetchOwnerId", OBJECT),
        fetchViewerId = stub("fetchViewerId", OBJECT),
        statusFollowedBy = stub("statusFollowedBy", "Ljava/lang/Boolean;"),
        sessionUserId = stub("sessionUserId", STRING),
        orderBinder = stub("orderBinder", OBJECT),
        rebuildRows = extension.methods.singleOrNull {
            it.name == "rebuildRows" && it.returnType == "V" && AccessFlags.STATIC.isSet(it.accessFlags) &&
                it.parameterTypes.map(Any::toString) == listOf(OBJECT, "Z", "Z")
        } ?: refuse("$FOLLOWING_LIST has no static V rebuildRows($OBJECT" + "ZZ)"),
    )
}
