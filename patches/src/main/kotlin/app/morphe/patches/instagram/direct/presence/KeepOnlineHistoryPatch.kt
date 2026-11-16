/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.presence

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.download.USER
import app.morphe.patches.instagram.download.usernameBridge
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.parameterRegister
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val ONLINE_HISTORY_PATCH = "Keep online history"
internal const val ONLINE_HISTORY = "$EXTENSION_PACKAGE/direct/OnlineHistory;"
internal const val ONLINE_HISTORY_RECEIVED = "$ONLINE_HISTORY->received(Ljava/lang/Object;Ljava/util/Map;)V"

/** The request Instagram's presence store polls with, a string no Redex rename touches. */
internal const val PRESENCE_POLL = "direct_v2/get_presence_active_now/"

/** Three of the keys Instagram's presence model writes when it describes itself as JSON. */
internal val PRESENCE_MODEL_KEYS = listOf("user_id", "is_active", "lastActivityAtMs")

internal const val ONLINE_SESSION = "Lcom/instagram/common/session/UserSession;"

/** Instagram's cache of the accounts it has seen, which keeps its name. */
internal const val USER_CACHE = "Lcom/instagram/user/model/UserCache;"

/**
 * Where the online history hook goes: the presence store's merge, which every update to the
 * people's active status goes through, the store's session field, and the presence model's
 * method describing itself as JSON.
 */
internal class OnlineHistorySite(
    val store: String,
    val merge: Method,
    val session: String,
    val model: String,
    val describe: Method,
)

/**
 * Keeps a list on the phone of when people in your messages came online and went offline (#590),
 * from the active status Instagram already receives for its Active now dots. In the default
 * selection with its switch off.
 *
 * Nothing is requested or sent: the hook reads each update as it goes into Instagram's presence
 * store, and the list stays in the app's own storage.
 */
@Suppress("unused")
val keepOnlineHistoryPatch = bytecodePatch(
    name = "Keep online history",
    description = "Keeps a list on your phone of when people in your messages came online and went offline, from " +
        "the active status Instagram already shows. Nothing extra is asked of Instagram. Starts off. Turn it on in " +
        "HushGram settings > Profiles.",
    default = true,
) {
    category("Downloads")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("onlineHistory")
        val site = findOnlineHistory()
        val writeUsername = usernameBridge(ONLINE_HISTORY_PATCH)
        keepOnlineHistory(site)
        writeUsername()
        enableStatus("onlineHistory")
    }
}

private fun refuse(why: String): Nothing = throw PatchException("$ONLINE_HISTORY_PATCH: $why")

/**
 * Finds the presence model by the keys its JSON method writes, then the store by the request it
 * polls with, and in it the one static merge taking the store and a map whose values it casts to
 * the model. Instagram's code describes nowhere else these keys together, and the poll request is
 * the store's alone.
 */
internal fun BytecodePatchContext.findOnlineHistory(): OnlineHistorySite {
    val describers = classesLoadingString(PRESENCE_MODEL_KEYS.last()).flatMap { type -> type.methods }.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.parameterTypes.isEmpty() &&
            method.returnType == "Ljava/lang/String;" && PRESENCE_MODEL_KEYS.all { key -> loadsString(method, key) }
    }
    val describe = describers.singleOrNull()
        ?: refuse("expected one presence model describing itself, found " + describers.joinToString { "${it.definingClass}->${it.name}" }.ifEmpty { "none" })
    val model = describe.definingClass

    val merges = classesLoadingString(PRESENCE_POLL).flatMap { store ->
        store.methods.filter { method ->
            AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
                method.parameterTypes.map(Any::toString).take(2) == listOf(store.type, "Ljava/util/Map;") &&
                method.implementation?.instructions?.any { instruction ->
                    instruction.opcode == Opcode.CHECK_CAST &&
                        ((instruction as ReferenceInstruction).reference as TypeReference).type == model
                } == true
        }.map { store to it }
    }
    val (store, merge) = merges.singleOrNull()
        ?: refuse("expected one presence merge casting to $model, found " + merges.joinToString { "${it.second.definingClass}->${it.second.name}" }.ifEmpty { "none" })

    val sessions = store.fields.filter { field -> !AccessFlags.STATIC.isSet(field.accessFlags) && field.type == ONLINE_SESSION }
    val session = sessions.singleOrNull()
        ?: refuse("expected one session field on ${store.type}, found ${sessions.size}")
    return OnlineHistorySite(store.type, merge, "${store.type}->${session.name}:$ONLINE_SESSION", model, describe)
}

/**
 * Hands the store's session and the map of updates to the extension at the top of the merge, then
 * writes the two bridges the extension reads them through. The hook borrows two locals, which hold
 * nothing before the merge's own first instruction.
 */
internal fun BytecodePatchContext.keepOnlineHistory(site: OnlineHistorySite) {
    val merge = mutableClassDefBy(site.store).methods.single {
        it.name == site.merge.name && it.parameterTypes.map(Any::toString) == site.merge.parameterTypes.map(Any::toString) &&
            it.returnType == site.merge.returnType
    }
    if (0 in merge.jumpTargets()) refuse("something in ${site.store}->${merge.name} jumps back to its start")
    if (merge.localRegisterCount() < 2) refuse("${site.store}->${merge.name} has fewer than two locals")

    val caches = classDefBy(USER_CACHE).methods
    val cacheFor = caches.singleOrNull { method ->
        AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == USER_CACHE &&
            method.parameterTypes.map(Any::toString) == listOf(ONLINE_SESSION)
    } ?: refuse("expected one $USER_CACHE for a session")
    val byId = caches.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == USER &&
            method.parameterTypes.map(Any::toString) == listOf("J")
    }.singleOrNull() ?: refuse("expected one account by id on $USER_CACHE")

    val stubs = mutableClassDefBy(ONLINE_HISTORY).methods
    val presence = stubs.singleOrNull {
        it.name == "presence" && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;")
    } ?: refuse("$ONLINE_HISTORY has no static presence(Object)")
    val user = stubs.singleOrNull {
        it.name == "user" && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;", "J")
    } ?: refuse("$ONLINE_HISTORY has no static user(Object, long)")

    merge.addInstructions(
        0,
        """
            move-object/from16 v0, ${merge.parameterRegister(0)}
            iget-object v0, v0, ${site.session}
            move-object/from16 v1, ${merge.parameterRegister(1)}
            invoke-static {v0, v1}, $ONLINE_HISTORY_RECEIVED
        """,
    )
    if (presence.implementation!!.instructions.first().opcode != Opcode.CHECK_CAST) {
        presence.addInstructions(
            0,
            """
                check-cast p0, ${site.model}
                invoke-virtual {p0}, ${site.model}->${site.describe.name}()Ljava/lang/String;
                move-result-object p0
                return-object p0
            """,
        )
    }
    if (user.implementation!!.instructions.first().opcode != Opcode.CHECK_CAST) {
        user.addInstructions(
            0,
            """
                check-cast p0, $ONLINE_SESSION
                invoke-static {p0}, $USER_CACHE->${cacheFor.name}($ONLINE_SESSION)$USER_CACHE
                move-result-object p0
                invoke-virtual {p0, p1, p2}, $USER_CACHE->${byId.name}(J)$USER
                move-result-object p0
                return-object p0
            """,
        )
    }
}
