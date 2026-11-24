/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.cleanup

import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.download.USER
import app.morphe.patches.instagram.download.pandoGetter
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.patches.instagram.reels.partnership.ReelsPageSites
import app.morphe.patches.instagram.reels.partnership.fillReelsItemMedia
import app.morphe.patches.instagram.reels.partnership.fillStub
import app.morphe.patches.instagram.reels.partnership.filterReelsPage
import app.morphe.patches.instagram.reels.partnership.isStaticStub
import app.morphe.patches.instagram.reels.live.ReelsItemInserts
import app.morphe.patches.instagram.reels.live.filterReelsItemInserts
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference

private const val PATCH = "Clean up Reels"

internal const val FRIEND_LANE_PICKS = "$EXTENSION_PACKAGE/reels/FriendLanePicks;"
internal const val FRIEND_PICKS_FILTER = "$FRIEND_LANE_PICKS->page(Ljava/util/List;Ljava/lang/String;)Ljava/util/List;"

/** Takes the reel a pick-less page kept back off the Friends tab once its viewer holds others (#503). */
internal const val FRIEND_PICKS_ADDED = "$FRIEND_LANE_PICKS->added(Ljava/util/List;Ljava/lang/String;I)Ljava/util/List;"

/** What a post says about friends and it, the public interface over Instagram's two models of it. */
internal const val SOCIAL_CONTEXT_INFO = "Lcom/instagram/api/schemas/SocialContextInfoIntf;"

/** The Media fields naming friends on a post, and the JSON key of a social context's faces. */
internal const val SOCIAL_CONTEXT_FIELD = "social_context"
internal const val TOP_LIKERS_FIELD = "top_likers"
internal const val FACEPILE_KEY = "social_context_facepile_users"

private const val OBJECT = "Ljava/lang/Object;"
private const val STRING = "Ljava/lang/String;"
private const val LIST = "Ljava/util/List;"

/** The bodies of the extension's stubs that read the friends named on a post. */
internal class FriendPicksReads(
    val socialContexts: String,
    val faces: String,
    val sourceUser: String,
    val topLikers: String,
    val username: String,
)

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Checks the extension has the page filter and its stubs, then finds how a post's friends are read:
 * Media's social context and top likers and User's username by the hash of their field, and on the
 * social context, whose faces are filled in ahead of time so its getter holds no hash, the source
 * user as the one getter answering a user and the faces as the list getter its writer calls right
 * before writing their key. Fails before anything changes when any of it can't be told.
 */
internal fun BytecodePatchContext.findFriendPicks(): FriendPicksReads {
    val extension = classDefByOrNull(FRIEND_LANE_PICKS) ?: refuse("the extension has no $FRIEND_LANE_PICKS")
    if (extension.methods.none { it.isStaticStub("page", listOf(LIST, STRING), LIST) }) {
        refuse("the extension has no static page(List, String)List")
    }
    listOf(
        "media" to OBJECT, "socialContexts" to LIST, "faces" to LIST, "sourceUser" to OBJECT,
        "topLikers" to LIST, "username" to STRING,
    )
        .filter { (stub, returns) -> extension.methods.none { it.isStaticStub(stub, listOf(OBJECT), returns) } }
        .forEach { (stub, returns) -> refuse("the extension has no static $stub(Object)$returns to fill") }

    val contexts = pandoGetter(PATCH, MEDIA, SOCIAL_CONTEXT_FIELD, LIST)
    val likers = pandoGetter(PATCH, MEDIA, TOP_LIKERS_FIELD, LIST)
    val username = pandoGetter(PATCH, USER, "username", STRING)
    listOf(contexts, likers, username).forEach { getter ->
        if (!AccessFlags.PUBLIC.isSet(getter.accessFlags)) refuse("${getter.definingClass}->${getter.name} isn't public")
    }
    for (type in listOf(MEDIA, USER)) {
        if (!AccessFlags.PUBLIC.isSet((classDefByOrNull(type) ?: refuse("$type isn't in this build")).accessFlags)) {
            refuse("$type isn't public, so the extension can't reach it")
        }
    }

    val info = classDefByOrNull(SOCIAL_CONTEXT_INFO) ?: refuse("$SOCIAL_CONTEXT_INFO isn't in this build")
    if (!AccessFlags.INTERFACE.isSet(info.accessFlags) || !AccessFlags.PUBLIC.isSet(info.accessFlags)) {
        refuse("$SOCIAL_CONTEXT_INFO is no longer a public interface")
    }
    val sources = info.methods.filter { it.parameterTypes.isEmpty() && it.returnType == USER }
    val source = sources.singleOrNull()
        ?: refuse("expected one getter on $SOCIAL_CONTEXT_INFO answering a user, found ${sources.size}")
    val faces = facesGetter()

    return FriendPicksReads(
        socialContexts = virtualBody(MEDIA, contexts.name, LIST),
        faces = interfaceBody(faces, LIST),
        sourceUser = interfaceBody(source.name, USER),
        topLikers = virtualBody(MEDIA, likers.name, LIST),
        username = virtualBody(USER, username.name, STRING),
    )
}

/** The social context's getter for its faces: the last list getter its writer calls before their key. */
private fun BytecodePatchContext.facesGetter(): String {
    val writers = classesLoadingString(FACEPILE_KEY).flatMap { it.methods }.filter { method ->
        method.parameterTypes.any { it.toString() == SOCIAL_CONTEXT_INFO } && loadsString(method, FACEPILE_KEY)
    }
    val writer = writers.singleOrNull()
        ?: refuse("expected one method writing a $SOCIAL_CONTEXT_INFO with $FACEPILE_KEY, found ${writers.size}")
    return facesCalledIn(writer)
}

private fun BytecodePatchContext.facesCalledIn(writer: Method): String {
    val code = writer.implementation?.instructions?.toList().orEmpty()
    val key = code.indices.firstOrNull { stringLoadedAt(code, it) == FACEPILE_KEY }
        ?: refuse("${writer.definingClass}->${writer.name} doesn't load $FACEPILE_KEY where it can be read")
    val call = (key - 1 downTo 0).asSequence().mapNotNull { at ->
        val instruction = code[at]
        val called = (instruction as? ReferenceInstruction)?.reference as? MethodReference
        called?.takeIf {
            instruction.opcode == Opcode.INVOKE_INTERFACE && it.definingClass == SOCIAL_CONTEXT_INFO &&
                it.parameterTypes.isEmpty() && it.returnType == LIST
        }
    }.firstOrNull() ?: refuse("${writer.definingClass}->${writer.name} calls no list getter before $FACEPILE_KEY")
    return call.name
}

/** A stub's body that hands its argument, a [type], to its getter [name] and answers what it reads. */
private fun virtualBody(type: String, name: String, returns: String) = """
    check-cast p0, $type
    invoke-virtual { p0 }, $type->$name()$returns
    move-result-object p0
    return-object p0
"""

/** The same through [SOCIAL_CONTEXT_INFO]. */
private fun interfaceBody(name: String, returns: String) = """
    check-cast p0, $SOCIAL_CONTEXT_INFO
    invoke-interface { p0 }, $SOCIAL_CONTEXT_INFO->$name()$returns
    move-result-object p0
    return-object p0
"""

/** Fills the stubs that read the friends named on a post. */
internal fun BytecodePatchContext.fillFriendPicks(page: ReelsPageSites, reads: FriendPicksReads) {
    fillReelsItemMedia(FRIEND_LANE_PICKS, page)
    fillStub(FRIEND_LANE_PICKS, "socialContexts", LIST, 1, reads.socialContexts)
    fillStub(FRIEND_LANE_PICKS, "faces", LIST, 1, reads.faces)
    fillStub(FRIEND_LANE_PICKS, "sourceUser", OBJECT, 1, reads.sourceUser)
    fillStub(FRIEND_LANE_PICKS, "topLikers", LIST, 1, reads.topLikers)
    fillStub(FRIEND_LANE_PICKS, "username", STRING, 1, reads.username)
}

/**
 * Fills the stubs and hands each Reels page to [FRIEND_PICKS_FILTER] where the page and its fallback
 * meet, and each list the viewer's data source takes in to [FRIEND_PICKS_ADDED] first thing. With
 * [pages], the stubs that ask the Friends tab for its next page are filled too.
 */
internal fun BytecodePatchContext.pickFriends(
    page: ReelsPageSites,
    reads: FriendPicksReads,
    inserts: ReelsItemInserts,
    pages: FriendLanePages? = null,
) {
    fillFriendPicks(page, reads)
    pages?.let { fillFriendLanePages(it, inserts) }
    filterReelsPage(page, FRIEND_PICKS_FILTER)
    filterReelsItemInserts(inserts, FRIEND_PICKS_ADDED)
}

/** What a Reels viewer's context prints first, the class [findFriendLanePages] starts from. */
internal const val VIEWER_CONTEXT_LABEL = "ClipsViewerContext(viewerSessionId="

/** The labels the context's toString prints its data source and its load of the next page after. */
internal const val VIEWER_ITEMS_LABEL = ", itemsProvider="
internal const val VIEWER_LOAD_LABEL = ", loadNextPage="

private const val FUNCTION0 = "Lkotlin/jvm/functions/Function0;"
private const val MAP = "Ljava/util/Map;"

/** The extension's stubs that ask the Friends tab for its next page, with their parameters and answer. */
internal val FRIEND_LANE_PAGE_STUBS = mapOf(
    "viewerRegistries" to (emptyList<String>() to "Ljava/lang/Object;"),
    "viewerRegistriesLock" to (emptyList<String>() to "Ljava/lang/Object;"),
    "viewerContext" to (listOf("Ljava/lang/Object;") to "Ljava/lang/Object;"),
    "viewerModule" to (listOf("Ljava/lang/Object;") to "Ljava/lang/String;"),
    "loadNextPage" to (listOf("Ljava/lang/Object;") to "Z"),
)
private const val STRING_BUILDER = "Ljava/lang/StringBuilder;"
private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"

/**
 * How the Friends tab's viewer is asked for its next page (#503). Instagram keeps a registry per
 * account, [registry], in the static map [registries] under [lock], and each holds the viewer on
 * screen in [current], a [context] whose [items] is the data source the viewer shows and whose [load]
 * is the viewer's own load of its next page.
 */
internal class FriendLanePages(
    val context: String,
    val items: FieldReference,
    val load: FieldReference,
    val registry: String,
    val registries: FieldReference,
    val lock: FieldReference,
    val current: FieldReference,
)

/**
 * Finds [FriendLanePages]: the viewer context is the class whose toString prints [VIEWER_CONTEXT_LABEL],
 * and that toString names its data source and next page load, read as the fields it appends after
 * their labels. The data source has to be the Reels viewer's that [inserts] found, and the load a
 * Function0. The registry is the one class holding a context of its own, with one static Map, one
 * static Object and a static getter taking a UserSession. Everything the extension reads is public.
 */
internal fun BytecodePatchContext.findFriendLanePages(inserts: ReelsItemInserts): FriendLanePages {
    val extension = classDefByOrNull(FRIEND_LANE_PICKS) ?: refuse("the extension has no $FRIEND_LANE_PICKS")
    for ((stub, shape) in FRIEND_LANE_PAGE_STUBS) {
        val (parameters, returns) = shape
        if (extension.methods.none { it.isStaticStub(stub, parameters, returns) }) {
            refuse("the extension has no static $stub(${parameters.joinToString("")})$returns to fill")
        }
    }
    val printers = classesLoadingString(VIEWER_CONTEXT_LABEL).flatMap { it.methods }.filter {
        it.name == "toString" && it.parameterTypes.isEmpty() && loadsString(it, VIEWER_CONTEXT_LABEL)
    }
    val printer = printers.singleOrNull()
        ?: refuse("expected one toString printing $VIEWER_CONTEXT_LABEL, found ${printers.size}")
    val context = printer.definingClass
    val items = appendedAfter(printer, VIEWER_ITEMS_LABEL)
    val load = appendedAfter(printer, VIEWER_LOAD_LABEL)
    if (load.type != FUNCTION0) refuse("$context's ${load.name} after $VIEWER_LOAD_LABEL is a ${load.type}, not a $FUNCTION0")
    if (!isA(inserts.dataSource, items.type)) {
        refuse("the Reels viewer's data source ${inserts.dataSource} isn't the $context's ${items.type}")
    }

    val holders = mutableListOf<ClassDef>()
    classDefForEach { classDef ->
        if (classDef.type == context) return@classDefForEach
        val own = classDef.fields.count { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == context }
        val maps = classDef.fields.count { AccessFlags.STATIC.isSet(it.accessFlags) && it.type == MAP }
        val locks = classDef.fields.count { AccessFlags.STATIC.isSet(it.accessFlags) && it.type == OBJECT }
        val getter = classDef.methods.any {
            AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.map(Any::toString) == listOf(USER_SESSION) &&
                it.returnType == classDef.type
        }
        if (own == 1 && maps == 1 && locks == 1 && getter) holders += classDef
    }
    val registry = holders.singleOrNull()
        ?: refuse("expected one registry holding a $context with a static map, lock and getter, found ${holders.map { it.type }}")
    fun field(wanted: (Field) -> Boolean): FieldReference {
        val field = registry.fields.single(wanted)
        return ImmutableFieldReference(registry.type, field.name, field.type)
    }
    val registries = field { AccessFlags.STATIC.isSet(it.accessFlags) && it.type == MAP }
    val lock = field { AccessFlags.STATIC.isSet(it.accessFlags) && it.type == OBJECT }
    val current = field { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == context }

    for (type in listOf(context, registry.type, inserts.dataSource)) {
        if (!AccessFlags.PUBLIC.isSet((classDefByOrNull(type) ?: refuse("$type isn't in this build")).accessFlags)) {
            refuse("$type isn't public, so the extension can't reach it")
        }
    }
    for (read in listOf(items, load, registries, lock, current, inserts.moduleField)) {
        val owner = classDefBy(read.definingClass)
        val declared = owner.fields.single { it.name == read.name && it.type == read.type }
        if (!AccessFlags.PUBLIC.isSet(declared.accessFlags)) refuse("$read isn't public, so the extension can't reach it")
    }
    return FriendLanePages(context, items, load, registry.type, registries, lock, current)
}

/** [findFriendLanePages], or null with a warning, leaving the picks to keep a reel where a list leaves none. */
internal fun BytecodePatchContext.findFriendLanePagesOrWarn(inserts: ReelsItemInserts): FriendLanePages? = try {
    findFriendLanePages(inserts)
} catch (refusal: PatchException) {
    patchLog.warning("${refusal.message}. $PATCH goes in, and a Friends tab list with none of the picks keeps a reel.")
    null
}

/**
 * The field [printer] appends right after the label [label]: the label is loaded once, appended on
 * its own, and the next append takes one register, which the printer read off itself from a field
 * of its own class.
 */
private fun BytecodePatchContext.appendedAfter(printer: Method, label: String): FieldReference {
    val owner = printer.definingClass
    val code = printer.implementation?.instructions?.toList().orEmpty()
    val loads = code.indices.filter { stringLoadedAt(code, it) == label }
    val at = loads.singleOrNull() ?: refuse("expected $owner's toString to print $label once, found ${loads.size}")
    val (labelRegister, from) = when (code[at].opcode) {
        Opcode.CONST_STRING, Opcode.CONST_STRING_JUMBO -> (code[at] as OneRegisterInstruction).registerA to at + 1
        else -> {
            val result = code.getOrNull(at + 1)?.takeIf { it.opcode == Opcode.MOVE_RESULT_OBJECT } as? OneRegisterInstruction
                ?: refuse("$owner's toString doesn't keep $label where it can be read")
            result.registerA to at + 2
        }
    }
    val appends = (from..code.lastIndex).filter { index ->
        val called = (code[index] as? ReferenceInstruction)?.reference as? MethodReference
        code[index].opcode == Opcode.INVOKE_VIRTUAL && called?.definingClass == STRING_BUILDER && called.name == "append"
    }.take(2)
    val labelAppend = appends.getOrNull(0)?.let { code[it] as FiveRegisterInstruction }
    val valueAppend = appends.getOrNull(1)?.let { code[it] as FiveRegisterInstruction }
    if (labelAppend == null || valueAppend == null || labelAppend.registerCount != 2 || labelAppend.registerD != labelRegister ||
        valueAppend.registerCount != 2 || valueAppend.registerC != labelAppend.registerC
    ) {
        refuse("$owner's toString doesn't append one value after $label")
    }
    val value = valueAppend.registerD
    val self = printer.localRegisterCount()
    val write = (appends[1] - 1 downTo 0).firstOrNull { index ->
        code[index].opcode.setsRegister() && (code[index] as? OneRegisterInstruction)?.registerA == value
    } ?: refuse("$owner's toString appends a register after $label that nothing wrote")
    val read = code[write]
    val field = (read as? ReferenceInstruction)?.reference as? FieldReference
    if (read.opcode != Opcode.IGET_OBJECT || (read as TwoRegisterInstruction).registerB != self || field?.definingClass != owner) {
        refuse("$owner's toString doesn't print one of its own fields after $label")
    }
    return field
}

/** Whether [type] is [wanted], or extends or implements it, as far as this build's classes say. */
private fun BytecodePatchContext.isA(type: String, wanted: String): Boolean {
    var seen = setOf<String>()
    var next = listOf(type)
    while (next.isNotEmpty()) {
        if (wanted in next) return true
        seen = seen + next
        next = next.flatMap { name ->
            val classDef = classDefByOrNull(name) ?: return@flatMap emptyList()
            listOfNotNull(classDef.superclass) + classDef.interfaces
        }.filter { it !in seen }
    }
    return false
}

/**
 * Fills the stubs that ask the Friends tab's viewer for its next page: the registries map and its
 * lock, a registry's viewer on screen, the module name of the data source behind a viewer, read
 * through [inserts]' module field when it's the Reels viewer's, and a viewer's load, run when it has
 * one.
 */
internal fun BytecodePatchContext.fillFriendLanePages(pages: FriendLanePages, inserts: ReelsItemInserts) {
    fillStub(
        FRIEND_LANE_PICKS, "viewerRegistries", OBJECT, 1,
        """
            sget-object v0, ${pages.registries}
            return-object v0
        """,
        emptyList(),
    )
    fillStub(
        FRIEND_LANE_PICKS, "viewerRegistriesLock", OBJECT, 1,
        """
            sget-object v0, ${pages.lock}
            return-object v0
        """,
        emptyList(),
    )
    fillStub(
        FRIEND_LANE_PICKS, "viewerContext", OBJECT, 2,
        """
            instance-of v0, p0, ${pages.registry}
            if-eqz v0, :none
            check-cast p0, ${pages.registry}
            iget-object v0, p0, ${pages.current}
            return-object v0
            :none
            const/4 v0, 0x0
            return-object v0
        """,
    )
    fillStub(
        FRIEND_LANE_PICKS, "viewerModule", STRING, 2,
        """
            instance-of v0, p0, ${pages.context}
            if-eqz v0, :none
            check-cast p0, ${pages.context}
            iget-object p0, p0, ${pages.items}
            instance-of v0, p0, ${inserts.dataSource}
            if-eqz v0, :none
            check-cast p0, ${inserts.dataSource}
            iget-object v0, p0, ${inserts.moduleField}
            return-object v0
            :none
            const/4 v0, 0x0
            return-object v0
        """,
    )
    fillStub(
        FRIEND_LANE_PICKS, "loadNextPage", "Z", 2,
        """
            instance-of v0, p0, ${pages.context}
            if-eqz v0, :none
            check-cast p0, ${pages.context}
            iget-object p0, p0, ${pages.load}
            if-eqz p0, :none
            invoke-interface { p0 }, $FUNCTION0->invoke()$OBJECT
            const/4 v0, 0x1
            return v0
            :none
            const/4 v0, 0x0
            return v0
        """,
    )
}
