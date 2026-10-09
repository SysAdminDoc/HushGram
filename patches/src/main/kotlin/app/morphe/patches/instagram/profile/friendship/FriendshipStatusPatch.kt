/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.friendship

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableClass
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.liveAcrossInjection
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation

internal const val PATCH = "Show if a profile follows you"
internal const val FRIENDSHIP_STATUS = "$EXTENSION_PACKAGE/profile/FriendshipStatus;"
internal const val BESIDE_PRONOUNS = "$FRIENDSHIP_STATUS->besidePronouns(Ljava/lang/Object;Ljava/lang/Object;)V"
internal const val IN_PLACE_OF_PRONOUNS = "$FRIENDSHIP_STATUS->inPlaceOfPronouns(Ljava/lang/Object;Ljava/lang/Object;)V"

/** The trace name of the part of the profile header's binder that fills in the name and pronouns. */
internal const val BIND_FULL_NAME = "bindFullName"

/** The profile screen's view model, which keeps the profile's user. Instagram keeps its name. */
internal const val VIEW_MODEL = "Lcom/instagram/profile/fragment/UserDetailViewModel;"
internal const val USER = "Lcom/instagram/user/model/User;"

/** The signed-in account, whose `getUserId()` keeps its name, so your own profile gets no label. */
internal const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"
internal const val GET_USER_ID = "getUserId"

/**
 * The friendship status Instagram keeps on a user, from friendships/show, and the key its getter on
 * the user falls back on. Instagram keeps the interface's name.
 */
internal const val RELATIONSHIP = "Lcom/instagram/api/schemas/RelationshipInfoDict;"
internal const val FRIENDSHIP_STATUS_KEY = "friendship_status"

/**
 * The key of whether the account follows you: the friendship status's dump loads it right before
 * asking the status, and the user model's own getter falls back on it.
 */
internal const val FOLLOWED_BY = "followed_by"

/**
 * The key of whether you follow the account, which the friendship status's dump loads the same way.
 * Show it as a chip reads it to say Following each other.
 */
internal const val FOLLOWING = "following"

internal const val OBJECT = "Ljava/lang/Object;"
internal const val STRING = "Ljava/lang/String;"
internal const val VIEW = "Landroid/view/View;"
internal const val TEXT_VIEW = "Landroid/widget/TextView;"
private const val BOOLEAN = "Ljava/lang/Boolean;"

/**
 * Adds Follows you or Doesn't follow you beside the name on someone's profile, in the slot Instagram
 * keeps there for pronouns, or with Show it as a chip as a chip under the profile's counts, and with
 * a second switch marks the accounts on your own Following list that don't follow you back
 * ([findFollowRow]). Asked for in #1 and, for the chip, #24.
 */
@Suppress("unused")
val friendshipStatusPatch = bytecodePatch(
    name = "Show if a profile follows you",
    description = "Adds Follows you or Doesn't follow you beside the name on someone's profile, after their pronouns " +
        "if they've set any, or as a chip under their counts that also says Following each other. On to start. The " +
        "chip, and a switch that marks the accounts on your own Following list that don't follow you back, are off " +
        "until you turn them on. Switches: HushGram settings > Profiles.",
) {
    category("Interface")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("friendshipStatus")
        requireStatusMethod(FOLLOWING_LIST_STATUS)
        // Everything is found before anything changes, so a build missing any part is left untouched.
        val found = findProfileName()
        val screen = screenAnswerOrWarn()
        val mark = followRowOrWarn()
        val stubs = friendshipStubs()
        val rowStubs = followingStubs()
        labelProfileName(found)
        stubs.fill(found)
        if (screen != null) stubs.fillScreen(found, screen)
        if (mark != null) {
            markFollowRow(mark.row)
            askFollowAnswers(mark.answers)
            rowStubs.fill(mark.row, mark.answers)
            enableStatus(FOLLOWING_LIST_STATUS)
        }
        enableStatus("friendshipStatus")
    }
}

/** The status of the second switch, which a build can lack while the profile label goes in. */
internal const val FOLLOWING_LIST_STATUS = "followingListMark"

/** The Following list mark's parts: the row binder, and where Instagram asks the server and hears back. */
internal class FollowMark(val row: FollowRow, val answers: FollowAnswers)

/**
 * The follow list's row binder and the places its answers come from, or null with a warning in the
 * patch log when this build's list doesn't match. A row is only marked on the server's answer, so
 * the binder without them would mark nothing. Marking the list is a second switch, off to start, so
 * a list that moved leaves it out rather than taking the profile label down too; settings then
 * don't offer the switch.
 */
internal fun BytecodePatchContext.followRowOrWarn(): FollowMark? = try {
    FollowMark(findFollowRow(), findFollowAnswers())
} catch (moved: PatchException) {
    patchLog.warning("${moved.message}. The profile label goes in without Mark who doesn't follow you back.")
    null
}

internal fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * The profile header's binder and what the patch needs from it: the two calls that show and hide the
 * pronouns slot, each with the register holding the slot, the parameter holding the profile screen's
 * header, and the way from that header to whether the profile follows you.
 */
internal class ProfileName(
    val type: String,
    val name: String,
    val parameters: List<String>,
    /** The slot's interface, with its kept getView() and setVisibility(int). */
    val slot: String,
    /** The call that shows the slot once the pronouns are in it, and the slot's register there. */
    val shown: Int,
    val shownSlot: Int,
    /** The call that hides the slot for an account with no pronouns, and the slot's register there. */
    val hidden: Int,
    val hiddenSlot: Int,
    /** The binder's parameter holding the header, its type and the header's field of the view model. */
    val header: Int,
    val headerType: String,
    val viewModel: FieldReference,
    /** The view model's field of the profile's user. */
    val user: FieldReference,
    /** The user model's getter of its friendship status, and the status's getter of whether the account follows you. */
    val friendship: String,
    val relationshipFollowedBy: String,
    /** The status's getter of whether you follow the account, or null when this build's can't be told. */
    val relationshipFollowing: String?,
    /** The user model's own getter of whether the account follows you. */
    val userFollowedBy: String,
    /** The user model's getter of its ID, and the header's field of the signed-in account. */
    val userId: String,
    val session: FieldReference,
)

/**
 * Finds the one method outside the extension that loads [BIND_FULL_NAME], and in the trace section
 * that string names, the pronouns slot: a field read into a register that's asked for its view,
 * which is cast to a TextView, given its text and shown, and read once more into a register that's
 * hidden straight away. The binder's one parameter whose class has a [VIEW_MODEL] field is the
 * header and the view model's one [USER] field the profile's user. Whether the account follows you
 * comes from the user model's one getter answering a [RELATIONSHIP] and loading
 * [FRIENDSHIP_STATUS_KEY], and that status's getter the status's dump asks right after loading
 * [FOLLOWED_BY], with the user model's one getter answering a Boolean and loading [FOLLOWED_BY] to
 * fall back on. The getter the dump asks right after loading [FOLLOWING] is whether you follow the
 * account; a build where that isn't one getter goes in with a warning, and its chip never says
 * Following each other. Your own profile is told apart by the user's ID, from the one getter its
 * hashCode() asks, against the header's [USER_SESSION]. Fails when any of them isn't there, or
 * there's more than one, since that's an update this patch hasn't seen.
 */
internal fun BytecodePatchContext.findProfileName(): ProfileName {
    val binders = mutableListOf<Pair<String, Method>>()
    val relationshipGetters = sortedSetOf<String>()
    val followingGetters = sortedSetOf<String>()
    val naming = classesHolding(BIND_FULL_NAME).mapTo(HashSet()) { it.type }
    classDefForEach { classDef ->
        if (classDef.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        if (classDef.type in naming) classDef.methods.filter { it.holdsString(BIND_FULL_NAME) }.forEach { binders += classDef.type to it }
        classDef.methods.filter { method -> method.parameterTypes.any { it.toString() == RELATIONSHIP } }.forEach {
            relationshipGetters += it.relationshipGetters(FOLLOWED_BY)
            followingGetters += it.relationshipGetters(FOLLOWING)
        }
    }
    val (type, binder) = binders.singleOrNull()
        ?: refuse("expected one method loading $BIND_FULL_NAME, found ${binders.size}")
    val where = "$type->${binder.name}"
    val code = binder.implementation!!.instructions.toList()

    // The section starts with a static call handed the name, and ends where that call starts the next.
    val named = code.indexOfFirst { it.loadsString(BIND_FULL_NAME) }
    val begin = (named + 1 until code.size).firstOrNull { code[it].opcode == Opcode.INVOKE_STATIC }
        ?: refuse("$where doesn't start a trace section with $BIND_FULL_NAME")
    val start = code[begin].methodReference()!!
    if (start.parameterTypes.firstOrNull()?.toString() != STRING ||
        code[begin].namedRegisters().firstOrNull() != (code[named] as OneRegisterInstruction).registerA
    ) {
        refuse("$where doesn't hand $BIND_FULL_NAME to the call after it")
    }
    val end = (begin + 1 until code.size).firstOrNull { code[it].methodReference()?.toString() == start.toString() } ?: code.size
    val section = begin + 1 until end

    val shownSites = section.mapNotNull { at -> shownSlotAt(code, at) }
    val (shown, field) = shownSites.singleOrNull()
        ?: refuse("expected one slot in $where's $BIND_FULL_NAME section given its text and shown, found ${shownSites.size}")
    val slot = field.type
    // 450 moves the visibility into place between the read and the call.
    val moves = setOf(Opcode.MOVE, Opcode.MOVE_FROM16, Opcode.MOVE_16)
    val hiddenSites = section.mapNotNull { at ->
        if (code[at].opcode != Opcode.IGET_OBJECT || code[at].fieldReference()?.toString() != field.toString()) return@mapNotNull null
        val register = (code[at] as OneRegisterInstruction).registerA
        (at + 1..minOf(at + 3, code.size - 1)).firstOrNull { code[it].isSetVisibility(slot, register) }?.takeIf { call ->
            (at + 1 until call).all { code[it].opcode in moves && (code[it] as OneRegisterInstruction).registerA != register }
        }
    }
    val hidden = hiddenSites.singleOrNull()
        ?: refuse("expected one place in $where's $BIND_FULL_NAME section hiding the slot, found ${hiddenSites.size}")

    val headers = binder.parameterTypes.withIndex().mapNotNull { (index, parameter) ->
        val held = classDefByOrNull(parameter.toString())?.instanceFields(VIEW_MODEL).orEmpty()
        held.singleOrNull()?.let { Triple(index, parameter.toString(), it) }
    }
    val (header, headerType, viewModel) = headers.singleOrNull()
        ?: refuse("expected one parameter of $where holding a $VIEW_MODEL, found ${headers.size}")
    val viewModelClass = classDefByOrNull(VIEW_MODEL) ?: refuse("$VIEW_MODEL isn't in this build")
    val user = viewModelClass.instanceFields(USER).singleOrNull()
        ?: refuse("expected $VIEW_MODEL to keep one $USER")
    val userClass = classDefByOrNull(USER) ?: refuse("$USER isn't in this build")
    val getters = userClass.methods.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.isEmpty() && it.returnType == BOOLEAN &&
            it.holdsString(FOLLOWED_BY)
    }
    val followedBy = getters.singleOrNull()
        ?: refuse("expected one getter in $USER answering a Boolean and loading $FOLLOWED_BY, found ${getters.size}")
    val friendships = userClass.methods.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.isEmpty() && it.returnType == RELATIONSHIP &&
            it.holdsString(FRIENDSHIP_STATUS_KEY)
    }
    val friendship = friendships.singleOrNull()
        ?: refuse("expected one getter in $USER answering a $RELATIONSHIP and loading $FRIENDSHIP_STATUS_KEY, found ${friendships.size}")
    val relationshipFollowedBy = relationshipGetters.singleOrNull()
        ?: refuse("expected one $RELATIONSHIP getter asked right after $FOLLOWED_BY is loaded, found $relationshipGetters")
    val relationshipFollowing = followingGetters.singleOrNull()
    if (relationshipFollowing == null) {
        patchLog.warning(
            "$PATCH: expected one $RELATIONSHIP getter asked right after $FOLLOWING is loaded, found $followingGetters. " +
                "The chip says Follows you, never Following each other.",
        )
    }

    // The user model hashes its ID, so hashCode() asks its one getter of it.
    val hashCode = userClass.methods.singleOrNull {
        it.name == "hashCode" && it.parameterTypes.isEmpty() && it.returnType == "I" && !AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuse("$USER has no hashCode()")
    val idGetters = hashCode.implementation!!.instructions.mapNotNull { it.methodReference() }.filter {
        it.definingClass == USER && it.parameterTypes.isEmpty() && it.returnType == STRING
    }.map { it.name }.distinct()
    val userId = idGetters.singleOrNull()
        ?: refuse("expected $USER's hashCode() to ask one getter of its ID, found $idGetters")
    val session = classDefByOrNull(headerType)!!.instanceFields(USER_SESSION).singleOrNull()
        ?: refuse("expected $headerType to keep one $USER_SESSION")
    val sessionClass = classDefByOrNull(USER_SESSION) ?: refuse("$USER_SESSION isn't in this build")
    sessionClass.methods.singleOrNull {
        it.name == GET_USER_ID && it.parameterTypes.isEmpty() && it.returnType == STRING && AccessFlags.PUBLIC.isSet(it.accessFlags) &&
            !AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuse("$USER_SESSION has no public $GET_USER_ID()")

    // The stubs reach these from the extension, outside Instagram's packages.
    val headerClass = classDefByOrNull(headerType)!!
    val slotClass = classDefByOrNull(slot) ?: refuse("$slot, the pronouns slot, isn't in this build")
    val relationshipClass = classDefByOrNull(RELATIONSHIP) ?: refuse("$RELATIONSHIP isn't in this build")
    listOf(headerClass, viewModelClass, userClass, slotClass, relationshipClass, sessionClass).forEach { reachable ->
        if (!AccessFlags.PUBLIC.isSet(reachable.accessFlags)) refuse("${reachable.type} isn't public, so the extension can't reach it")
    }
    val idGetter = userClass.methods.single { it.name == userId && it.parameterTypes.isEmpty() && it.returnType == STRING }
    listOf(followedBy, friendship, idGetter).forEach { getter ->
        if (!AccessFlags.PUBLIC.isSet(getter.accessFlags)) refuse("$USER->${getter.name} isn't public")
    }

    return ProfileName(
        type, binder.name, binder.parameterTypes.map(CharSequence::toString), slot,
        shown, code[shown].namedRegisters().first(), hidden, code[hidden].namedRegisters().first(),
        header, headerType, viewModel, user, friendship.name, relationshipFollowedBy, relationshipFollowing, followedBy.name, userId,
        session,
    )
}

/**
 * The names of the [RELATIONSHIP] getters [this] asks, with nothing, right after loading [key]: the
 * friendship status's dump writes each key and then asks the status for it.
 */
private fun Method.relationshipGetters(key: String): List<String> {
    val code = implementation?.instructions?.toList() ?: return emptyList()
    return code.withIndex().mapNotNull { (at, instruction) ->
        if (!instruction.loadsString(key)) return@mapNotNull null
        val next = code.getOrNull(at + 1) ?: return@mapNotNull null
        val asked = next.methodReference() ?: return@mapNotNull null
        val isGetter = (next.opcode == Opcode.INVOKE_INTERFACE || next.opcode == Opcode.INVOKE_INTERFACE_RANGE) &&
            asked.definingClass == RELATIONSHIP && asked.parameterTypes.isEmpty() && asked.returnType == BOOLEAN
        if (isGetter) asked.name else null
    }
}

/**
 * When [at] reads a field into a register and the next instructions ask that register for its view,
 * cast it to a TextView, give it its text and show the slot, the index of that last call and the
 * field.
 */
private fun shownSlotAt(code: List<Instruction>, at: Int): Pair<Int, FieldReference>? {
    val read = code[at]
    if (read.opcode != Opcode.IGET_OBJECT) return null
    val field = read.fieldReference() ?: return null
    val slot = (read as OneRegisterInstruction).registerA
    val asked = code.getOrNull(at + 1)?.methodReference() ?: return null
    if (code[at + 1].opcode != Opcode.INVOKE_INTERFACE || asked.definingClass != field.type || asked.name != "getView" ||
        asked.parameterTypes.isNotEmpty() || asked.returnType != VIEW || code[at + 1].namedRegisters() != listOf(slot)
    ) {
        return null
    }
    val view = (code.getOrNull(at + 2) as? OneRegisterInstruction)?.takeIf { code[at + 2].opcode == Opcode.MOVE_RESULT_OBJECT }?.registerA
        ?: return null
    val cast = code.getOrNull(at + 3) ?: return null
    if (cast.opcode != Opcode.CHECK_CAST || (cast as OneRegisterInstruction).registerA != view ||
        (cast as ReferenceInstruction).reference.toString() != TEXT_VIEW
    ) {
        return null
    }
    val text = code.getOrNull(at + 4)?.methodReference() ?: return null
    if (text.name != "setText" || text.parameterTypes.map(CharSequence::toString) != listOf("Ljava/lang/CharSequence;") ||
        code[at + 4].namedRegisters().firstOrNull() != view
    ) {
        return null
    }
    return if (code.getOrNull(at + 5)?.isSetVisibility(field.type, slot) == true) (at + 5) to field else null
}

private fun Instruction.isSetVisibility(slot: String, register: Int): Boolean {
    val called = methodReference() ?: return false
    return (opcode == Opcode.INVOKE_INTERFACE || opcode == Opcode.INVOKE_INTERFACE_RANGE) && called.definingClass == slot &&
        called.name == "setVisibility" && called.parameterTypes.map(CharSequence::toString) == listOf("I") &&
        namedRegisters().firstOrNull() == register
}

internal fun ClassDef.instanceFields(type: String) = fields.filter {
    it.type == type && !AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
}

internal fun Method.holdsString(value: String) = implementation?.instructions?.any { it.loadsString(value) } == true

internal fun Instruction.loadsString(value: String) =
    (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) &&
        ((this as ReferenceInstruction).reference as StringReference).string == value

internal fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

internal fun Instruction.fieldReference(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

/**
 * Right after the binder shows the pronouns slot, hands the slot and the header to [BESIDE_PRONOUNS],
 * and right after it hides the slot, to [IN_PLACE_OF_PRONOUNS]. Both are range calls on two locals
 * side by side that nothing reads afterwards, so the slot and the header may be in any register.
 */
internal fun BytecodePatchContext.labelProfileName(found: ProfileName) {
    val method = mutableClassDefBy(found.type).methods.single {
        it.name == found.name && it.parameterTypes.map(CharSequence::toString) == found.parameters
    }
    method.requireParameterIntact(PATCH, found.header, listOf(found.shown + 1, found.hidden + 1))
    val header = method.parameterRegisterNumber(found.header)
    // Worked out before anything goes in, and put in from the bottom up so the indices above stay.
    val hooks = listOf(
        Triple(found.shown, found.shownSlot, BESIDE_PRONOUNS),
        Triple(found.hidden, found.hiddenSlot, IN_PLACE_OF_PRONOUNS),
    ).map { (at, slot, hook) -> HookSite(at, slot, hook, method.freePairAt(at + 1)) }
    for (site in hooks.sortedByDescending { it.at }) {
        method.addInstructions(
            site.at + 1,
            """
                move-object/from16 v${site.first}, v${site.slot}
                move-object/from16 v${site.first + 1}, v$header
                invoke-static/range { v${site.first} .. v${site.first + 1} }, ${site.hook}
            """,
        )
    }
}

private class HookSite(val at: Int, val slot: Int, val hook: String, val first: Int)

/** The lower of two locals side by side that code put in front of instruction [index] may write. */
private fun Method.freePairAt(index: Int): Int {
    val live = liveAcrossInjection(index)
    val highest = minOf(localRegisterCount() - 1, 255)
    return (0 until highest).firstOrNull { it !in live && it + 1 !in live }
        ?: refuse("$definingClass->$name has no two locals side by side free after instruction $index")
}

/** The extension's stubs, found before anything changes, and the steps that fill them. */
internal class FriendshipStubs(
    private val extension: MutableClass,
    private val screenFriendship: MutableMethod,
    private val statusFlag: MutableMethod,
    private val profileUser: MutableMethod,
    private val friendshipFollowedBy: MutableMethod,
    private val friendshipFollowing: MutableMethod,
    private val followedBy: MutableMethod,
    private val userId: MutableMethod,
    private val viewerId: MutableMethod,
    private val slotView: MutableMethod,
    private val setSlotVisibility: MutableMethod,
) {
    fun fill(found: ProfileName) {
        profileUser.addInstructionsWithLabels(
            0,
            """
                check-cast p0, ${found.headerType}
                iget-object p0, p0, ${found.viewModel}
                if-eqz p0, :none
                iget-object p0, p0, ${found.user}
                :none
                return-object p0
            """,
        )
        // Each way out returns on its own: joined at one return, a status and a Boolean in p0 merge
        // to Object, and ART rejects the whole class for answering that where a Boolean is declared.
        friendshipFollowedBy.addInstructionsWithLabels(
            0,
            """
                check-cast p0, $USER
                invoke-virtual { p0 }, $USER->${found.friendship}()$RELATIONSHIP
                move-result-object p0
                if-nez p0, :known
                const/4 p0, 0x0
                return-object p0
                :known
                invoke-interface { p0 }, $RELATIONSHIP->${found.relationshipFollowedBy}()$BOOLEAN
                move-result-object p0
                return-object p0
            """,
        )
        // Left answering null when the build's getter can't be told, so the chip never says you
        // follow each other on a guess.
        found.relationshipFollowing?.let { following ->
            friendshipFollowing.addInstructionsWithLabels(
                0,
                """
                    check-cast p0, $USER
                    invoke-virtual { p0 }, $USER->${found.friendship}()$RELATIONSHIP
                    move-result-object p0
                    if-nez p0, :known
                    const/4 p0, 0x0
                    return-object p0
                    :known
                    invoke-interface { p0 }, $RELATIONSHIP->$following()$BOOLEAN
                    move-result-object p0
                    return-object p0
                """,
            )
        }
        followedBy.addInstructionsWithLabels(
            0,
            """
                check-cast p0, $USER
                invoke-virtual { p0 }, $USER->${found.userFollowedBy}()$BOOLEAN
                move-result-object p0
                return-object p0
            """,
        )
        userId.addInstructionsWithLabels(
            0,
            """
                check-cast p0, $USER
                invoke-virtual { p0 }, $USER->${found.userId}()$STRING
                move-result-object p0
                return-object p0
            """,
        )
        viewerId.addInstructionsWithLabels(
            0,
            """
                check-cast p0, ${found.headerType}
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
        slotView.addInstructionsWithLabels(
            0,
            """
                check-cast p0, ${found.slot}
                invoke-interface { p0 }, ${found.slot}->getView()$VIEW
                move-result-object p0
                return-object p0
            """,
        )
        // The stub's own registers are its two parameters, so the plain invoke names them.
        setSlotVisibility.addInstructionsWithLabels(
            0,
            """
                check-cast p0, ${found.slot}
                invoke-interface { p0, p1 }, ${found.slot}->setVisibility(I)V
                const/4 p0, 0x1
                return p0
            """,
        )
    }

    /**
     * Fills the two stubs reading the profile screen's own answer as Instagram's options sheet does
     * (#40): from the header's view model to the answer's tree, made the sheet's fragment and typed
     * for its client, then the friendship status in it, and a Boolean in that by its key. The first
     * needs two registers of its own for the type and the client, which the stub as compiled may not
     * have, so it's written anew with them ahead of its parameter.
     */
    fun fillScreen(found: ProfileName, screen: ScreenAnswer) {
        val body = """
            check-cast p0, ${found.headerType}
            iget-object p0, p0, ${found.viewModel}
            if-eqz p0, :unknown
            iget-object p0, p0, ${screen.answer}
            if-eqz p0, :unknown
            invoke-interface { p0 }, ${screen.value}
            move-result-object p0
            instance-of v0, p0, ${screen.holder}
            if-eqz v0, :unknown
            check-cast p0, ${screen.holder}
            iget-object p0, p0, ${screen.tree}
            if-eqz p0, :unknown
            const v0, ${screen.type}
            invoke-interface { p0, v0 }, ${screen.reinterpret}
            move-result-object p0
            if-eqz p0, :unknown
            const-string v1, "${screen.client}"
            invoke-interface { p0, v1, v0 }, ${screen.retype}
            move-result-object p0
            if-eqz p0, :unknown
            const v0, ${FRIENDSHIP_STATUS_KEY.hashCode()}
            invoke-interface { p0, v0 }, ${screen.subtree}
            move-result-object p0
            return-object p0
            :unknown
            const/4 p0, 0x0
            return-object p0
        """
        val written = ImmutableMethod(
            screenFriendship.definingClass, screenFriendship.name, screenFriendship.parameters, screenFriendship.returnType,
            screenFriendship.accessFlags, screenFriendship.annotations, screenFriendship.hiddenApiRestrictions,
            ImmutableMethodImplementation(SCREEN_LOCALS + 1, emptyList(), null, null),
        ).toMutable().apply { addInstructionsWithLabels(0, body.trimIndent()) }
        extension.methods.remove(screenFriendship)
        extension.methods.add(written)
        // The stub's own registers are its two parameters, so the plain invoke names them.
        statusFlag.addInstructionsWithLabels(
            0,
            """
                check-cast p0, ${screen.tree.type}
                invoke-interface { p0, p1 }, ${screen.flag}
                move-result-object p0
                return-object p0
            """,
        )
    }
}

/** The registers the screen's answer stub has of its own: the type and the client. */
private const val SCREEN_LOCALS = 2

internal fun BytecodePatchContext.friendshipStubs(): FriendshipStubs {
    val extension = mutableClassDefBy(FRIENDSHIP_STATUS)
    fun stub(name: String, parameters: List<String>, returns: String): MutableMethod = extension.methods.singleOrNull {
        it.name == name && it.returnType == returns && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(Any::toString) == parameters
    } ?: refuse("$FRIENDSHIP_STATUS has no static $returns $name(${parameters.joinToString("")})")

    return FriendshipStubs(
        extension = extension,
        screenFriendship = stub("screenFriendship", listOf(OBJECT), OBJECT),
        statusFlag = stub("statusFlag", listOf(OBJECT, "I"), BOOLEAN),
        profileUser = stub("profileUser", listOf(OBJECT), OBJECT),
        friendshipFollowedBy = stub("friendshipFollowedBy", listOf(OBJECT), BOOLEAN),
        friendshipFollowing = stub("friendshipFollowing", listOf(OBJECT), BOOLEAN),
        followedBy = stub("followedBy", listOf(OBJECT), BOOLEAN),
        userId = stub("userId", listOf(OBJECT), STRING),
        viewerId = stub("viewerId", listOf(OBJECT), STRING),
        slotView = stub("slotView", listOf(OBJECT), VIEW),
        setSlotVisibility = stub("setSlotVisibility", listOf(OBJECT, "I"), "Z"),
    )
}
