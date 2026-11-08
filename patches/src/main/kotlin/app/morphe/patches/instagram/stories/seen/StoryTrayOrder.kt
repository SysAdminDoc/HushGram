/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.seen

import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.cloneMutable
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCallingInto
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.loadsString
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

internal const val STORY_TRAY_ORDER = "$EXTENSION_PACKAGE/stories/StoryTrayOrder;"
internal const val TRAY_ORDER = "$STORY_TRAY_ORDER->order(Ljava/util/List;Ljava/lang/Object;)Ljava/util/List;"
internal const val TRAY_RESPONDED = "$STORY_TRAY_ORDER->responded(Ljava/lang/Object;)V"

/** What the method taking in a tray response logs (see [TrayResponse]). */
internal const val TRAY_RESPONSE_LOG = "ReelTrayManager.onReelTrayResponseReady - useCache: "

/** What a tray request's text starts with, the reason for the fetch right behind it. */
internal const val TRAY_REQUEST_TEXT = "Request{mReason="

private const val JAVA_LIST = "Ljava/util/List;"
private const val ARRAY_LIST = "Ljava/util/ArrayList;"
private const val OBJECT_TYPE = "Ljava/lang/Object;"
private const val TRAY_USER = "Lcom/instagram/user/model/User;"
private const val TRAY_STRING = "Ljava/lang/String;"
private val TRAY_KINDS = setOf("USER_REEL", "EMPTY_STORY_STATE_REEL", "ADS_REEL")

/** What the store's list for Home's top story row logs when the store has no reels. */
private const val MAIN_TRAY_EMPTY = "main feed tray reels is empty"

private fun refuseTray(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * One of the reel store's methods that hands out the tray's reels: [lister], taking [parameters]
 * and returning [JAVA_LIST]. It has one return, [at], of the list in register [result], after the
 * monitor-exit that lets go of the store (register [self] is the store).
 */
internal class TraySite(
    val lister: String,
    val parameters: List<String>,
    val at: Int,
    val result: Int,
    val self: Int,
    /** The monitor-exit before the return. Another patch's hook calls can sit between it and the return. */
    val exit: Int = at - 1,
)

/**
 * What ordering Home's story tray works on, found before anything changes.
 *
 * [sites] are the methods of the reel store [store] whose lists get ordered: the one Home's top
 * story row, the main feed and the story viewer read, then the one the stories unit inside the
 * feed reads. [seenCheck] is the reel's own check (on [reelClass]) that the account has seen it, the
 * one the store's sort uses, and [sessionField] is the store's field holding the account.
 * [isSeen] is the extension's stub that makes the check.
 */
internal class StoryTrayTargets(
    val store: String,
    val sites: List<TraySite>,
    val reelClass: String,
    val seenCheck: String,
    val sessionField: String,
    internal val isSeen: MutableMethod,
    val identity: TrayIdentity,
    /** Only for the tray's own hook. */
    val response: TrayResponse?,
    /** The extension class holding the hook and the [isSeen] stub, and the hook this patch calls. */
    val extension: String = STORY_TRAY_ORDER,
    val hook: String = TRAY_ORDER,
)

/**
 * Where a tray response goes into the store: in [method] of the tray manager [manager], taking
 * [parameters], the store's call at instruction [at], with register [request] holding the request it
 * was fetched for. The request's text says why it was fetched, which is what decides whether the row
 * starts over, since Instagram also fetches a tray when you come back from a longer watch (#377). The
 * hook goes before the call: the store hands Home the new tray on its way through, and told after,
 * Home drew a reloaded row from the old places (#241).
 */
internal class TrayResponse(
    val manager: String,
    val method: String,
    val parameters: List<String>,
    val at: Int,
    val request: Int,
)

/** The actual store Reel's kind and public owner/ID readers, independent of the response parser model. */
internal class TrayIdentity(
    val kind: FieldReference,
    val owner: MethodReference,
    val userId: MethodReference,
    val sessionId: MethodReference,
    val reelId: MethodReference,
)

private fun BytecodePatchContext.trayIdentity(reelType: String): TrayIdentity {
    val reel = classDefBy(reelType)
    fun Method.reader(result: String) = AccessFlags.PUBLIC.isSet(accessFlags) && !AccessFlags.STATIC.isSet(accessFlags) &&
        parameterTypes.isEmpty() && returnType == result && implementation != null
    val owner = reel.methods.singleOrNull { it.reader(TRAY_USER) }
        ?: refuseTray("expected one public owner getter on $reelType")
    val kind = reel.fields.filter { field ->
        if (AccessFlags.STATIC.isSet(field.accessFlags)) return@filter false
        val enum = classDefByOrNull(field.type) ?: return@filter false
        enum.superclass == "Ljava/lang/Enum;" && enum.methods.filter { it.name == "<clinit>" }.flatMap { method ->
            method.implementation?.instructions?.mapNotNull {
                ((it as? ReferenceInstruction)?.reference as? StringReference)?.string
            }.orEmpty()
        }.toSet().containsAll(TRAY_KINDS)
    }.singleOrNull() ?: refuseTray("expected one native reel-kind field on $reelType")
    if (!AccessFlags.PUBLIC.isSet(kind.accessFlags)) refuseTray("the native reel-kind field isn't public")
    val userId = userIdGetter(::refuseTray)
    val sessionId = classDefByOrNull(USER_SESSION)?.methods?.singleOrNull { it.name == "getUserId" && it.reader(TRAY_STRING) }
        ?: refuseTray("the session has no public getUserId")
    val reelId = reel.methods.singleOrNull { it.name == "getId" && it.reader(TRAY_STRING) }
        ?: refuseTray("the Reel has no public getId")
    return TrayIdentity(kind, owner, userId, sessionId, reelId)
}

/**
 * The User model's public id getter: the one String getter of its own its hashCode asks, since a
 * User hashes by its id. [refuse] says why when that isn't so.
 */
internal fun BytecodePatchContext.userIdGetter(refuse: (String) -> Nothing): Method {
    fun Method.reader(result: String) = AccessFlags.PUBLIC.isSet(accessFlags) && !AccessFlags.STATIC.isSet(accessFlags) &&
        parameterTypes.isEmpty() && returnType == result && implementation != null
    val user = classDefByOrNull(TRAY_USER) ?: refuse("the User model isn't present")
    val hash = user.methods.singleOrNull { it.name == "hashCode" && it.reader("I") }
        ?: refuse("the User model has no public hashCode")
    val id = hashedIdGetters(user.methods, hash).singleOrNull() ?: refuse("the User hash doesn't identify one ID getter")
    return user.methods.singleOrNull { it.name == id && it.reader(TRAY_STRING) }
        ?: refuse("the User ID getter isn't public")
}

/**
 * The names of the User model's String getters its [hash] reads the id through, out of [methods],
 * the model's own. That's the ones it calls, or when it calls none, since 454's Redex pass inlines
 * the getter into hashCode, the getter whose code is the reads hashCode runs before it hashes the
 * String, opcode for opcode and field for field.
 */
internal fun hashedIdGetters(methods: Iterable<Method>, hash: Method): List<String> {
    fun Instruction.reference() = (this as? ReferenceInstruction)?.reference
    val code = hash.implementation?.instructions?.filter { it.opcode != Opcode.NOP } ?: emptyList()
    val called = code.mapNotNull { it.reference() as? MethodReference }
        .filter { it.definingClass == TRAY_USER && it.parameterTypes.isEmpty() && it.returnType == TRAY_STRING }
        .map { it.name }.distinct()
    if (called.isNotEmpty()) return called
    val hashed = code.indexOfFirst {
        (it.reference() as? MethodReference)?.let { called -> called.definingClass == TRAY_STRING && called.name == "hashCode" } == true
    }
    if (hashed <= 0) return emptyList()
    val reads = code.subList(0, hashed).map { it.opcode to it.reference() }
    return methods.filter { method ->
        val body = method.implementation?.instructions?.filter { it.opcode != Opcode.NOP }
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.isEmpty() && method.returnType == TRAY_STRING &&
            body != null && body.lastOrNull()?.opcode == Opcode.RETURN_OBJECT && body.dropLast(1).map { it.opcode to it.reference() } == reads
    }.map { it.name }.distinct()
}

/**
 * Finds the reel store's list for Home's feed adapter.
 *
 * Instagram keeps the tray's reels in a store. It has a private sort that splits a list of reels
 * into the ones the account hasn't seen and the ones it has, each half in its order, and puts the
 * unseen ones first, asking each reel [reelClass] one question taking the account, "seen?". The
 * store's method that takes the tray's ids and returns a list of reels is the one the feed adapter
 * calls: it runs that sort only when a server flag says to, so with the flag off the list keeps the
 * server's order. That method is public and synchronized, takes a list and returns a list, calls the
 * sort and a static filter of `(account, list)` in its own class, and has one return, after the
 * monitor-exit. Other methods of the store that take no list, or sort alone, don't have the shape.
 *
 * That list only feeds the stories unit inside the feed and a short preview. Home's top story row
 * (StoriesTrayControllerImpl), the main feed and the story viewer read the store through another of
 * its methods, which re-sorts the store by the server's ranking and hands out a copy of it: public
 * and synchronized, two booleans in and a list out, logging [MAIN_TRAY_EMPTY] when the store is
 * empty, with one return after the monitor-exit. Ordering only the first left the top row as the
 * server ranked it, watched rings mixed in with new ones (#241), so both are hooked.
 */
internal fun BytecodePatchContext.findStoryTray(
    reelClass: String,
    extension: String = STORY_TRAY_ORDER,
    hook: String = TRAY_ORDER,
): StoryTrayTargets {
    val found = classesCallingInto(reelClass).flatMap { classDef ->
        classDef.methods.mapNotNull { method -> trayLister(classDef.type, classDef.methods, reelClass, method)?.let { classDef to it } }
    }
    val (storeDef, tray) = found.singleOrNull()
        ?: refuseTray("expected one reel store method handing the tray's reels to Home's feed adapter, found ${found.size}")
    val main = storeDef.methods.mapNotNull(::mainTraySite)
    val top = main.singleOrNull()
        ?: refuseTray("expected one method of ${tray.store} handing Home's top story row its reels, found ${main.size}")
    // Only the tray's own hook keeps places in the row, so only it needs to hear of a response.
    val response = if (hook == TRAY_ORDER) trayResponse(tray.store) else null
    return StoryTrayTargets(tray.store, listOf(top, tray.site), tray.reelClass, tray.seenCheck, tray.sessionField,
        trayStub(extension, hook), trayIdentity(tray.reelClass), response, extension, hook)
}

/**
 * The [TrayResponse] of the reel store [store]: the method logging [TRAY_RESPONSE_LOG], whose first
 * parameter is the request, and its one call of the store that takes that request. The request's
 * own text has to name the reason, or there'd be nothing to read from it.
 */
private fun BytecodePatchContext.trayResponse(store: String): TrayResponse {
    val methods = classesLoadingString(TRAY_RESPONSE_LOG).flatMap { classDef ->
        classDef.methods.filter { loadsString(it, TRAY_RESPONSE_LOG) }.map { classDef to it }
    }
    val (manager, method) = methods.singleOrNull()
        ?: refuseTray("expected one method taking in a tray response, found ${methods.size}")
    val where = "${manager.type}->${method.name}"
    val requestType = method.parameterTypes.firstOrNull()?.toString() ?: refuseTray("$where takes no tray request")
    if (classDefByOrNull(requestType)?.methods?.any { it.name == "toString" && loadsString(it, TRAY_REQUEST_TEXT) } != true) {
        refuseTray("the tray request $requestType doesn't name its reason in its text")
    }
    val code = method.implementation!!.instructions.toList()
    val calls = code.indices.filter { at ->
        val call = code[at].trayCall()
        call != null && call.definingClass == store && call.parameterTypes.any { it.toString() == requestType }
    }
    val at = calls.singleOrNull()
        ?: refuseTray("expected one call of $store taking the tray request in $where, found ${calls.size}")
    val request = code[at].argumentOfType(requestType) ?: refuseTray("can't read the tray request's register in $where")
    return TrayResponse(manager.type, method.name, method.parameterTypes.map(Any::toString), at, request)
}

/** The register this call hands its argument of [type] in, or null when it's no call of a kind that says. */
private fun Instruction.argumentOfType(type: String): Int? {
    val parameters = trayCall()?.parameterTypes?.map(Any::toString) ?: return null
    val receiver = if (opcode == Opcode.INVOKE_STATIC || opcode == Opcode.INVOKE_STATIC_RANGE) 0 else 1
    val slot = receiver + parameters.take(parameters.indexOf(type)).sumOf { if (it == "J" || it == "D") 2 else 1 as Int }
    return when (this) {
        is RegisterRangeInstruction -> startRegister + slot
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG)[slot]
        else -> null
    }
}

/** What [trayLister] finds in a method, before the extension's stub is looked up. */
private class TrayShape(
    val store: String,
    val site: TraySite,
    val reelClass: String,
    val seenCheck: String,
    val sessionField: String,
)

private fun trayLister(store: String, methods: Iterable<Method>, reelClass: String, method: Method): TrayShape? {
    if (AccessFlags.STATIC.isSet(method.accessFlags) || !AccessFlags.PUBLIC.isSet(method.accessFlags)) return null
    if (method.returnType != JAVA_LIST || method.parameterTypes.map(Any::toString) != listOf(JAVA_LIST)) return null
    val code = method.implementation?.instructions?.toList() ?: return null
    if (code.none { it.opcode == Opcode.MONITOR_ENTER }) return null
    val calls = code.mapNotNull { it.trayCall() }
    val sort = methods.filter { candidate ->
        !AccessFlags.STATIC.isSet(candidate.accessFlags) && candidate.returnType == ARRAY_LIST &&
            candidate.parameterTypes.map(Any::toString) == listOf(JAVA_LIST) &&
            calls.any { it.definingClass == store && it.name == candidate.name && it.parameterTypes.map(Any::toString) == listOf(JAVA_LIST) }
    }.singleOrNull() ?: return null
    val filter = calls.any {
        it.definingClass == store && it.returnType == ARRAY_LIST && it.parameterTypes.map(Any::toString) == listOf(USER_SESSION, JAVA_LIST)
    }
    if (!filter) return null

    val sortCode = sort.implementation?.instructions?.toList() ?: return null
    val checks = sortCode.filter { it.opcode == Opcode.INVOKE_VIRTUAL || it.opcode == Opcode.INVOKE_VIRTUAL_RANGE }
        .mapNotNull { it.trayCall() }
        .filter { it.definingClass == reelClass && it.returnType == "Z" && it.parameterTypes.map(Any::toString) == listOf(USER_SESSION) }
        .map { it.name }.distinct()
    // Only a virtual call: the stub makes the same call with invoke-virtual.
    val seenCheck = checks.singleOrNull() ?: return null
    val session = sortCode.mapNotNull { (it as? ReferenceInstruction)?.reference as? FieldReference }
        .filter { it.definingClass == store && it.type == USER_SESSION }.map { it.name }.distinct().singleOrNull() ?: return null

    val site = returnSite(method, code) ?: return null
    return TrayShape(store = store, site = site, reelClass = reelClass, seenCheck = seenCheck, sessionField = session)
}

/** The store's list for Home's top story row, the one that logs [MAIN_TRAY_EMPTY] (see [findStoryTray]). */
private fun mainTraySite(method: Method): TraySite? {
    if (AccessFlags.STATIC.isSet(method.accessFlags) || !AccessFlags.PUBLIC.isSet(method.accessFlags)) return null
    if (!AccessFlags.DECLARED_SYNCHRONIZED.isSet(method.accessFlags)) return null
    if (method.returnType != JAVA_LIST || method.parameterTypes.map(Any::toString) != listOf("Z", "Z")) return null
    val code = method.implementation?.instructions?.toList() ?: return null
    if (code.none { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == MAIN_TRAY_EMPTY }) return null
    return returnSite(method, code)
}

/**
 * [method]'s one return of a list, right after the monitor-exit that lets go of the store, or null.
 * Calls of the extension's story hooks (an invoke-static and its move-result-object, as the tray
 * patches put them) may sit between the two, so the patches find the site in either order.
 */
private fun returnSite(method: Method, code: List<Instruction>): TraySite? {
    val at = code.indices.filter { code[it].opcode == Opcode.RETURN_OBJECT }.singleOrNull() ?: return null
    var exit = at - 1
    while (exit >= 2 && code[exit].opcode == Opcode.MOVE_RESULT_OBJECT && code[exit - 1].isStoryHook()) exit -= 2
    if (exit < 0 || code[exit].opcode != Opcode.MONITOR_EXIT) return null
    val result = (code[at] as OneRegisterInstruction).registerA
    val self = method.implementation!!.registerCount - 1 - method.parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
    // The monitor-exit lets go of the store itself, so its register still holds the store at the return.
    if ((code[exit] as OneRegisterInstruction).registerA != self) return null
    if (result > 15 || self > 15 || result == self) return null
    return TraySite(method.name, method.parameterTypes.map(Any::toString), at, result, self, exit)
}

/** Whether this is a static call into one of the extension's stories classes. */
private fun Instruction.isStoryHook(): Boolean {
    val call = trayCall() ?: return false
    return opcode == Opcode.INVOKE_STATIC && call.definingClass.startsWith("$EXTENSION_PACKAGE/stories/")
}

private fun Instruction.trayCall(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun BytecodePatchContext.trayStub(extension: String, hook: String): MutableMethod {
    val order = classDefByOrNull(extension)?.let { mutableClassDefBy(extension) }
        ?: refuseTray("$extension isn't in the extension")
    order.methods.singleOrNull {
        "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == hook.substringAfter("->") &&
            AccessFlags.STATIC.isSet(it.accessFlags) && AccessFlags.PUBLIC.isSet(it.accessFlags)
    } ?: refuseTray("$extension has no public static hook ${hook.substringAfter("->")}")
    return order.methods.singleOrNull {
        it.name == "seen" && it.returnType == "I" && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(Any::toString) == listOf(OBJECT_TYPE, OBJECT_TYPE)
    } ?: refuseTray("$extension has no static I seen(${OBJECT_TYPE}${OBJECT_TYPE})")
}

/**
 * Hands each list the reel store gives out for the tray, just before it returns, to the extension,
 * which moves the reels the account has watched behind the ones it hasn't while views are held back
 * and watched stories are grayed (#241). The stub asks Instagram's own check.
 */
internal fun BytecodePatchContext.orderStoryTray(found: StoryTrayTargets) {
    val store = mutableClassDefBy(found.store)
    val listers = found.sites.map { site ->
        site to store.methods.single {
            it.name == site.lister && it.parameterTypes.map(Any::toString) == site.parameters && it.returnType == JAVA_LIST
        }
    }
    // Every site is checked before any changes, so a moved one leaves the store as it was.
    for ((site, lister) in listers) {
        val code = lister.implementation!!.instructions.toList()
        if (code.getOrNull(site.at)?.opcode != Opcode.RETURN_OBJECT || code.getOrNull(site.exit)?.opcode != Opcode.MONITOR_EXIT) {
            throw PatchException("$PATCH: ${found.store}->${site.lister} changed before its list was hooked")
        }
    }
    for ((site, lister) in listers) {
        // The sort goes right after the monitor-exit and any other hook right before the return, so
        // the sort runs first whichever patch is applied first.
        lister.addInstructionsAtControlFlowLabel(
            if (found.hook == TRAY_ORDER) site.exit + 1 else site.at,
            """
                invoke-static { v${site.result}, v${site.self} }, ${found.hook}
                move-result-object v${site.result}
            """,
        )
    }
    // A tray response is going into the store: what it was fetched for may start the row over (#377),
    // and it has to before the store hands Home the new tray (#241).
    found.response?.let { response ->
        mutableClassDefBy(response.manager).methods.single {
            it.name == response.method && it.parameterTypes.map(Any::toString) == response.parameters
        }.addInstructionsAtControlFlowLabel(
            response.at,
            "invoke-static/range { v${response.request} .. v${response.request} }, $TRAY_RESPONDED",
        )
    }
    // Three locals keep the owner/viewer IDs apart. Every path returns before the original stub.
    val state = found.isSeen.cloneMutable(additionalRegisters = (5 - found.isSeen.implementation!!.registerCount).coerceAtLeast(0))
    mutableClassDefBy(found.extension).methods.apply { remove(found.isSeen); add(state) }
    state.addInstructionsWithLabels(
        0,
        """
            instance-of v0, p0, ${found.store}
            if-eqz v0, :pinned
            instance-of v0, p1, ${found.reelClass}
            if-eqz v0, :pinned
            check-cast p0, ${found.store}
            iget-object p0, p0, ${found.store}->${found.sessionField}:$USER_SESSION
            if-eqz p0, :pinned
            check-cast p1, ${found.reelClass}
            iget-object v0, p1, ${found.identity.kind}
            if-eqz v0, :pinned
            invoke-virtual { v0 }, Ljava/lang/Enum;->name()Ljava/lang/String;
            move-result-object v0
            const-string v1, "USER_REEL"
            invoke-virtual { v1, v0 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
            move-result v0
            if-eqz v0, :pinned
            invoke-virtual { p1 }, ${found.identity.reelId}
            move-result-object v0
            if-eqz v0, :pinned
            invoke-virtual { v0 }, Ljava/lang/String;->isEmpty()Z
            move-result v1
            if-nez v1, :pinned
            const-string v1, "add_to_story"
            invoke-virtual { v1, v0 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
            move-result v0
            if-nez v0, :pinned
            invoke-virtual { p1 }, ${found.identity.owner}
            move-result-object v0
            if-eqz v0, :pinned
            invoke-virtual { v0 }, ${found.identity.userId}
            move-result-object v1
            if-eqz v1, :pinned
            invoke-virtual { v1 }, Ljava/lang/String;->isEmpty()Z
            move-result v0
            if-nez v0, :pinned
            invoke-virtual { p0 }, ${found.identity.sessionId}
            move-result-object v2
            if-eqz v2, :pinned
            invoke-virtual { v2 }, Ljava/lang/String;->isEmpty()Z
            move-result v0
            if-nez v0, :pinned
            invoke-virtual { v1, v2 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
            move-result v0
            if-nez v0, :own
            invoke-virtual { p1, p0 }, ${found.reelClass}->${found.seenCheck}(${USER_SESSION})Z
            move-result v0
            return v0
            :own
            const/4 v0, -0x2
            return v0
            :pinned
            const/4 v0, -0x1
            return v0
        """,
    )
}
