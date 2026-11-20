/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.follow

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.download.accountBridges
import app.morphe.patches.instagram.feed.confirm.publicStatic
import app.morphe.patches.instagram.feed.confirm.staticStub
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.getFreeRegisterProvider
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val SENT_PATCH = "Sent follow requests"

internal const val SENT_REQUESTS = "$EXTENSION_PACKAGE/profile/SentRequests;"
internal const val SENT_SEEN = "$SENT_REQUESTS->seen(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;)V"

/** The extension's stubs the patch fills: the account's field tree, one of its string fields, and the session's account. */
internal const val TREE_OF_STUB = "treeOf"
internal const val FIELD_OF_STUB = "fieldOf"
internal const val OWNER_OF_STUB = "ownerOf"

private const val SENT_SESSION = "Lcom/instagram/common/session/UserSession;"
private const val SENT_STRING = "Ljava/lang/String;"
private const val SENT_OBJECT = "Ljava/lang/Object;"

/** The signed-in account's ID, a field UserSession keeps by name. */
internal const val SESSION_USER_ID = "userId"

/**
 * Keeps the follow requests a follow button shows as Requested, so they can be found and cancelled
 * later. Included in the default selection with its switch off, so keeping the list is the user's pick.
 */
@Suppress("unused")
val sentFollowRequestsPatch = bytecodePatch(
    name = "Sent follow requests",
    description = "Keeps a list of the accounts you've asked to follow that haven't let you in yet, with their " +
        "pictures and names. A tap opens the profile, where Instagram's own Requested button cancels the " +
        "request. It lists the requests a follow button shows while it's on. Starts off. Turn it on in " +
        "HushGram settings > Profiles.",
    default = true,
) {
    category("Profiles")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("sentRequests")
        sentRequests(findSentRequests())
        enableStatus("sentRequests")
    }
}

private fun refuseSent(why: String): Nothing = throw PatchException("$SENT_PATCH: $why")

/**
 * The follow button helper's status read with where it returns, the account model's field tree and
 * string getter, the extension's stubs, and the writer for the picture bridges.
 */
internal class SentRequestTargets(
    val status: MutableMethod,
    val user: String,
    val returns: List<Int>,
    val header: HeaderStatus,
    val shown: ShownStatus,
    val tree: Field,
    val stringGetter: MethodReference,
    val treeOf: MutableMethod,
    val fieldOf: MutableMethod,
    val ownerOf: MutableMethod,
    val bridges: () -> Unit,
)

/** A profile header's status method, where its hook goes, and the session's, the account's and the answer's registers there. */
internal class HeaderStatus(val method: MutableMethod, val at: Int, val session: Int, val model: Int, val status: Int)

/** The helper's method that shows a button its status, and the hook at each place it does. */
internal class ShownStatus(val method: MutableMethod, val hooks: List<ShownHook>)

/**
 * One place a button is shown its status: the call's index, the session's and the account's
 * parameter registers, the status register the call reads, and the two free registers below v16
 * the session and the account are copied into for the hook.
 */
internal class ShownHook(val at: Int, val session: Int, val model: Int, val status: Int, val sessionCopy: Int, val modelCopy: Int)

private fun publicFlag(flags: Int) = AccessFlags.PUBLIC.isSet(flags)
private fun staticFlag(flags: Int) = AccessFlags.STATIC.isSet(flags)

/**
 * Finds what the hook needs and checks it before anything changes. FollowButtonBase.getHelper()
 * names the helper every follow button draws itself through. Its one instance (session, account)
 * method answering the enum whose setup loads [NOT_FOLLOWING] is what a button asks each time it
 * shows an account's status: Requested while a request waits, Following once it's taken, Follow
 * again once it's cancelled. Each of its object returns takes the hook, with the session and the
 * account still in their parameter registers there. The account's ID, username and name come from
 * its model's field tree, through the (int) string getter the helper calls right after loading
 * "username".hashCode(). The signed-in account is the session's kept userId field. A profile
 * header's button skips the status read and asks its resolver itself ([findHeaderStatus]), and on
 * 456 it skips both, so the status is handed over where every button is shown it ([findShownStatus]).
 */
internal fun BytecodePatchContext.findSentRequests(): SentRequestTargets {
    val base = classDefByOrNull(FOLLOW_BUTTON_BASE) ?: refuseSent("the app has no $FOLLOW_BUTTON_BASE")
    val getHelper = base.methods.singleOrNull { it.name == "getHelper" && it.parameterTypes.isEmpty() }
        ?: refuseSent("$FOLLOW_BUTTON_BASE has no getHelper()")
    val helperType = getHelper.returnType
    val helper = classDefByOrNull(helperType) ?: refuseSent("getHelper() answers $helperType, which isn't in the app")

    val statuses = helper.methods.filter { method ->
        val types = method.parameterTypes.map(Any::toString)
        !staticFlag(method.accessFlags) && types.size == 2 && types[0] == SENT_SESSION && types[1].startsWith("L") &&
            isFollowStatus(method.returnType)
    }
    val status = statuses.singleOrNull()
        ?: refuseSent("expected one (session, account) status read in $helperType answering the follow status, found ${statuses.size}")
    val user = status.parameterTypes[1].toString()
    val userClass = classDefByOrNull(user) ?: refuseSent("the status read takes $user, which isn't in the app")
    if (!publicFlag(userClass.accessFlags)) refuseSent("the account model $user isn't public")

    val trees = userFields(user).filter { field ->
        !staticFlag(field.accessFlags) && classDefByOrNull(field.type)?.let { AccessFlags.INTERFACE.isSet(it.accessFlags) } == true
    }
    val getters = helper.methods.flatMap { usernameReads(it) }
        .filter { read -> trees.any { it.type == read.definingClass } }
        .distinctBy { "${it.definingClass}->${it.name}" }
    val getter = getters.singleOrNull()
        ?: refuseSent("expected one username read on the account's field tree in $helperType, found ${getters.size}")
    val tree = trees.filter { it.type == getter.definingClass }.singleOrNull()
        ?: refuseSent("$user keeps more than one ${getter.definingClass}")
    if (!publicFlag(tree.accessFlags)) refuseSent("${tree.definingClass}->${tree.name} isn't public")
    if (classDefByOrNull(tree.definingClass)?.let { publicFlag(it.accessFlags) } != true) refuseSent("${tree.definingClass} isn't public")
    if (classDefByOrNull(getter.definingClass)?.let { publicFlag(it.accessFlags) } != true) refuseSent("${getter.definingClass} isn't public")

    val session = classDefByOrNull(SENT_SESSION) ?: refuseSent("the app has no $SENT_SESSION")
    if (!publicFlag(session.accessFlags)) refuseSent("$SENT_SESSION isn't public")
    val owner = session.fields.singleOrNull { it.name == SESSION_USER_ID && it.type == SENT_STRING }
        ?: refuseSent("$SENT_SESSION has no $SESSION_USER_ID:$SENT_STRING")
    if (!publicFlag(owner.accessFlags) || staticFlag(owner.accessFlags)) {
        refuseSent("$SENT_SESSION->$SESSION_USER_ID isn't a public instance field")
    }

    val parameters = listOf(SENT_SESSION, user)
    val mutable = mutableClassDefBy(helperType).methods.single {
        it.name == status.name && it.parameterTypes.map(Any::toString) == parameters && it.returnType == status.returnType
    }
    val where = "$helperType->${status.name}"
    val implementation = mutable.implementation ?: refuseSent("$where has no body")
    val code = implementation.instructions.toList()
    val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_OBJECT }
    if (returns.isEmpty()) refuseSent("$where returns no object")
    val covered = implementation.tryBlocks.flatMapTo(HashSet()) { block -> block.start.location.index until block.end.location.index }
    if (returns.any { it in covered }) refuseSent("a return of $where sits in a try block, where the hook could throw into its handler")
    val registers = returns.map { (code[it] as OneRegisterInstruction).registerA } +
        mutable.parameterRegisterNumber(0) + mutable.parameterRegisterNumber(1)
    if (registers.any { it > 15 }) refuseSent("$where keeps the session, the account or its answer past v15")
    mutable.requireParameterIntact(SENT_PATCH, 0, returns)
    mutable.requireParameterIntact(SENT_PATCH, 1, returns)
    val header = findHeaderStatus(helperType, user, status.returnType, code, returns)
    val shown = findShownStatus(helperType, user, status.returnType)

    val extension = classDefByOrNull(SENT_REQUESTS) ?: refuseSent("the extension has no $SENT_REQUESTS")
    extension.publicStatic(SENT_SEEN) ?: refuseSent("the extension has no public static $SENT_SEEN")
    val stubs = mutableClassDefBy(SENT_REQUESTS)
    val treeOf = stubs.staticStub(TREE_OF_STUB, listOf(SENT_OBJECT), SENT_OBJECT, 1)
        ?: refuseSent("$SENT_REQUESTS has no static Object $TREE_OF_STUB(Object)")
    val fieldOf = stubs.staticStub(FIELD_OF_STUB, listOf(SENT_OBJECT, "I"), SENT_STRING, 2)
        ?: refuseSent("$SENT_REQUESTS has no static String $FIELD_OF_STUB(Object, int)")
    val ownerOf = stubs.staticStub(OWNER_OF_STUB, listOf(SENT_OBJECT), SENT_STRING, 1)
        ?: refuseSent("$SENT_REQUESTS has no static String $OWNER_OF_STUB(Object)")
    // Pictures come through the account bridges, checked here and written with the rest.
    val bridges = accountBridges(SENT_PATCH)
    return SentRequestTargets(mutable, user, returns, header, shown, tree, getter, treeOf, fieldOf, ownerOf, bridges)
}

/**
 * Where a follow button is shown the status it draws: the helper's one instance method taking the
 * status first and the session and the account once each that hands a status to the button's own
 * (status)V method, 04Hj.A06 on 456. A profile header calls it through A07 with the status its
 * profile fetch read, so neither the status read nor [findHeaderStatus] sees that button (#690).
 * The hook goes right before each of those calls with the status the button is about to show,
 * after the app has squared it with what the account did since. The session and the account sit
 * past v15 there, so they're copied into two registers below v16 that nothing reads again before
 * writing. Nothing there may sit in a try block, where a handler could read what the copies took.
 */
private fun BytecodePatchContext.findShownStatus(helperType: String, user: String, statusType: String): ShownStatus {
    fun Instruction.shows(): Boolean {
        val call = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
        return opcode == Opcode.INVOKE_INTERFACE && call.returnType == "V" && call.parameterTypes.map(Any::toString) == listOf(statusType)
    }
    val methods = mutableClassDefBy(helperType).methods.filter { method ->
        val types = method.parameterTypes.map(Any::toString)
        !staticFlag(method.accessFlags) && method.returnType == "V" && types.firstOrNull() == statusType &&
            types.count { it == SENT_SESSION } == 1 && types.count { it == user } == 1 &&
            method.implementation?.instructions?.any { it.shows() } == true
    }
    val method = methods.singleOrNull()
        ?: refuseSent("expected one instance (status, session, account) method in $helperType showing a button its status, found ${methods.size}")
    val where = "$helperType->${method.name}"
    val implementation = method.implementation!!
    if (implementation.tryBlocks.isNotEmpty()) refuseSent("$where has a try block, where a handler could read what the hook's copies took")
    val code = implementation.instructions.toList()
    val calls = code.indices.filter { code[it].shows() }
    val types = method.parameterTypes.map(Any::toString)
    val sessionAt = types.indexOf(SENT_SESSION)
    val modelAt = types.indexOf(user)
    method.requireParameterIntact(SENT_PATCH, sessionAt, calls)
    method.requireParameterIntact(SENT_PATCH, modelAt, calls)
    val session = method.parameterRegisterNumber(sessionAt)
    val model = method.parameterRegisterNumber(modelAt)
    val hooks = calls.map { at ->
        val call = code[at] as? FiveRegisterInstruction
        if (call == null || call.registerCount != 2) refuseSent("$where shows a button its status at $at in a form the hook can't read")
        val copies = runCatching {
            val free = method.getFreeRegisterProvider(at, 2, call.registerC, call.registerD, session, model)
            listOf(free.getFreeRegister4Bit(), free.getFreeRegister4Bit())
        }.getOrElse { refuseSent("$where has no two free registers below v16 where it shows a button its status at $at") }
        ShownHook(at, session, model, call.registerD, copies[0], copies[1])
    }
    return ShownStatus(method, hooks)
}

/**
 * Where a profile header's follow button gets its status: the helper's one static (status, session,
 * helper, account) method that asks the resolver the status read returns the answer of, 04gP.A01 on
 * 454. It hands that answer to the button without the status read, so the hook goes right after the
 * answer comes back, where the session and the account are still in their parameter registers. On
 * 456 the resolver is the helper's static (status, session, account) method answering a pair of
 * statuses (04Hj.A00 and 0GL0 on 386214574), and the answer is the follow status read off it.
 */
private fun BytecodePatchContext.findHeaderStatus(
    helperType: String,
    user: String,
    statusType: String,
    read: List<Instruction>,
    returns: List<Int>,
): HeaderStatus {
    val answers = returns.mapNotNull { at -> statusAnswerAt(read, at, statusType) }.distinct()
    val answer = answers.singleOrNull()
        ?: refuseSent("expected the status read to return what one resolver answers, found ${answers.size}")
    val resolver = answer.call
    fun Instruction.asks() = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == resolver
    val shape = listOf(statusType, SENT_SESSION, helperType, user)
    val headers = mutableClassDefBy(helperType).methods.filter { method ->
        val types = method.parameterTypes.map(Any::toString)
        staticFlag(method.accessFlags) && method.returnType == "V" && types.firstOrNull() == statusType &&
            shape.all { type -> types.count { it == type } == 1 } &&
            method.implementation?.instructions?.any { it.asks() } == true
    }
    val header = headers.singleOrNull()
        ?: refuseSent("expected one static (status, session, helper, account) method in $helperType asking $resolver, found ${headers.size}")
    val where = "$helperType->${header.name}"
    val implementation = header.implementation!!
    val code = implementation.instructions.toList()
    val calls = code.indices.filter { code[it].asks() }
    val call = calls.singleOrNull() ?: refuseSent("$where asks $resolver ${calls.size} times, not once")
    if (code.getOrNull(call + 1)?.opcode != Opcode.MOVE_RESULT_OBJECT) refuseSent("$where doesn't keep what $resolver answers")
    val status = if (answer.field == null) call + 1 else {
        val kept = (code[call + 1] as OneRegisterInstruction).registerA
        val pick = code.getOrNull(call + 2)
        if (pick?.opcode != Opcode.IGET_OBJECT || (pick as ReferenceInstruction).reference.toString() != answer.field ||
            (pick as TwoRegisterInstruction).registerB != kept
        ) {
            refuseSent("$where doesn't read ${answer.field} off what $resolver answers")
        }
        call + 2
    }
    val at = status + 1
    if (at >= code.size) refuseSent("$where ends with what $resolver answers")
    if (at in header.jumpTargets()) refuseSent("a jump or exception handler enters $where right after $resolver answers")
    val covered = implementation.tryBlocks.flatMapTo(HashSet()) { block -> block.start.location.index until block.end.location.index }
    if (at in covered) refuseSent("what $resolver answers in $where comes back in a try block, where the hook could throw into its handler")
    val types = header.parameterTypes.map(Any::toString)
    val sessionAt = types.indexOf(SENT_SESSION)
    val modelAt = types.indexOf(user)
    val registers = listOf(header.parameterRegisterNumber(sessionAt), header.parameterRegisterNumber(modelAt),
        (code[status] as OneRegisterInstruction).registerA)
    if (registers.any { it > 15 }) refuseSent("$where keeps the session, the account or the answer past v15")
    header.requireParameterIntact(SENT_PATCH, sessionAt, listOf(at))
    header.requireParameterIntact(SENT_PATCH, modelAt, listOf(at))
    return HeaderStatus(header, at, registers[0], registers[1], registers[2])
}

/** What a return of the status read hands back: what [call] answers, or its [field] holding the status. */
private data class StatusAnswer(val call: String, val field: String?)

/**
 * What the status read's return at [at] hands back: the status a call answers right before it, or,
 * on 456, the status a call's answer holds in a field read right before it.
 */
private fun statusAnswerAt(read: List<Instruction>, at: Int, statusType: String): StatusAnswer? {
    val returned = (read[at] as OneRegisterInstruction).registerA
    val last = read.getOrNull(at - 1) ?: return null
    if (last.opcode == Opcode.MOVE_RESULT_OBJECT) {
        val call = (read.getOrNull(at - 2) as? ReferenceInstruction)?.reference as? MethodReference ?: return null
        if ((last as OneRegisterInstruction).registerA != returned || call.returnType != statusType) return null
        return StatusAnswer(call.toString(), null)
    }
    if (last.opcode != Opcode.IGET_OBJECT) return null
    val field = (last as ReferenceInstruction).reference as? FieldReference ?: return null
    val moved = read.getOrNull(at - 2) ?: return null
    val call = (read.getOrNull(at - 3) as? ReferenceInstruction)?.reference as? MethodReference ?: return null
    if ((last as TwoRegisterInstruction).registerA != returned || field.type != statusType || moved.opcode != Opcode.MOVE_RESULT_OBJECT ||
        (moved as OneRegisterInstruction).registerA != last.registerB || call.returnType != field.definingClass
    ) {
        return null
    }
    return StatusAnswer(call.toString(), field.toString())
}

/**
 * Fills the stubs, writes the picture bridges, then hands the session, the account and the status to
 * the extension at each return of the status read, the return's own label taking the call so a
 * branch to it passes through too, and the same three in the profile header's status method right
 * after its resolver answers, and once more right before the helper shows a button its status,
 * through copies of the session and the account. Each stub works in its parameters' registers alone.
 */
internal fun sentRequests(found: SentRequestTargets) {
    val user = found.user
    val tree = "${found.tree.definingClass}->${found.tree.name}:${found.tree.type}"
    found.treeOf.addInstructions(
        0,
        """
            check-cast p0, $user
            iget-object p0, p0, $tree
            return-object p0
        """,
    )
    val getter = found.stringGetter
    found.fieldOf.addInstructions(
        0,
        """
            check-cast p0, $user
            iget-object p0, p0, $tree
            if-eqz p0, :none
            invoke-interface { p0, p1 }, ${getter.definingClass}->${getter.name}(I)$SENT_STRING
            move-result-object p0
            return-object p0
            :none
            const/4 p0, 0x0
            return-object p0
        """,
    )
    found.ownerOf.addInstructions(
        0,
        """
            check-cast p0, $SENT_SESSION
            iget-object p0, p0, $SENT_SESSION->$SESSION_USER_ID:$SENT_STRING
            return-object p0
        """,
    )
    found.bridges()
    val status = found.status
    val session = status.parameterRegisterNumber(0)
    val model = status.parameterRegisterNumber(1)
    val code = status.implementation!!.instructions
    found.returns.sortedDescending().forEach { index ->
        val result = (code[index] as OneRegisterInstruction).registerA
        status.addInstructionsAtControlFlowLabel(index, "invoke-static { v$session, v$model, v$result }, $SENT_SEEN")
    }
    val header = found.header
    header.method.addInstructions(header.at, "invoke-static { v${header.session}, v${header.model}, v${header.status} }, $SENT_SEEN")
    val shown = found.shown
    shown.hooks.sortedByDescending { it.at }.forEach { hook ->
        shown.method.addInstructionsAtControlFlowLabel(
            hook.at,
            """
                move-object/from16 v${hook.sessionCopy}, v${hook.session}
                move-object/from16 v${hook.modelCopy}, v${hook.model}
                invoke-static { v${hook.sessionCopy}, v${hook.modelCopy}, v${hook.status} }, $SENT_SEEN
            """,
        )
    }
}
