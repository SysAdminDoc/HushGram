/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.lock

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesAccessing
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.liveAcrossInjection
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.util.addInstructionsAtControlFlowLabel
import app.morphe.util.reachingWrites
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference

/** Posts saved into a locked collection stay out of Saved's other grids while the lock is shut (#678). */
internal const val LOCKED_ITEMS = "$EXTENSION_PACKAGE/direct/LockedCollectionItems;"
/** Each collection Instagram reads, so its id is known by its name. */
internal const val READ_COLLECTION = "$LOCKED_ITEMS->read(Ljava/lang/Object;)V"
/** Asked for each post Saved's grid makes a row for, with the collection the grid is of. */
internal const val LEAVE_OUT_POST = "$LOCKED_ITEMS->leaveOut(Ljava/lang/Object;Ljava/lang/Object;)I"

internal const val SAVED_COLLECTION_TYPE = "Lcom/instagram/save/model/SavedCollection;"
internal const val SAVED_GRID_MEDIA = "Lcom/instagram/feed/media/Media;"

/** The keys of a collection's id and name in the JSON Instagram reads collections from. */
internal const val COLLECTION_ID_KEY = "collection_id"
internal const val COLLECTION_NAME_KEY = "collection_name"
/** The key of a post's collections, which the post's getter of them loads. */
internal const val SAVED_COLLECTION_IDS_KEY = "saved_collection_ids"
/** The endpoint of Saved's posts, which the grid screen of All and Posts loads. */
internal const val SAVED_POSTS_FEED = "feed/saved/posts/"

/** Handed each batch of reels Saved's Reels screen gets, with the screen; answers the batch to show. */
internal const val LEAVE_OUT_REELS = "$LOCKED_ITEMS->leaveOutReels(Ljava/util/List;Ljava/lang/Object;)Ljava/util/List;"
/** The name Saved's Reels screen gives its fetcher, loaded by the screen and where its new reels come in. */
internal const val SAVED_REELS_FETCHER = "clipsSavedTabFetcher"

/** Handed what Saved's search found, with the search screen; answers what to show. */
internal const val LEAVE_OUT_FOUND = "$LOCKED_ITEMS->leaveOutFound(Ljava/util/List;Ljava/lang/Object;)Ljava/util/List;"
/** The module name Saved's search screen gives, from its getModuleName. */
internal const val SAVED_SEARCH_MODULE = "saved_search"
/** Asked with what Saved's search just took: non-zero when the lock left none of what it found. */
internal const val LEFT_NOTHING = "$LOCKED_ITEMS->leftNothing(Ljava/lang/Object;)I"
/** Instagram's page for a search that found nothing, which Saved's search screen keeps. */
internal const val EMPTY_STATE_VIEW = "Lcom/instagram/igds/components/emptystate/IgdsEmptyState;"

/** The method reading a collection from JSON, each return of the collection it made, and its id and name fields. */
internal class CollectionReader(
    val method: MutableMethod,
    val returns: List<Int>,
    val collection: Int,
    val id: FieldReference,
    val name: FieldReference,
)

/**
 * Where Saved's grid has a post in hand as it makes rows: [at], just after the post's getter, with
 * the post in [media], the screen in [screen] and [collection], its field holding the collection the
 * grid is of. [scratch] is a register nothing reads after [at], and [next] the loop's next turn.
 */
internal class SavedGridRows(
    val method: MutableMethod,
    val at: Int,
    val media: Int,
    val screen: Int,
    val scratch: Int,
    val next: Int,
    val collection: FieldReference,
)

/**
 * Where Saved's Reels screen has a new batch of reels in hand: [at], just after the code handling it
 * casts the screen into [screen], with the batch in [reels]. [collection] is the screen's field
 * holding the collection it's of, and [media] a reel's field holding its post.
 */
internal class SavedReelsBatch(
    val method: MutableMethod,
    val at: Int,
    val reels: Int,
    val screen: Int,
    val collection: FieldReference,
    val media: FieldReference,
)

/**
 * Where what Saved's search found goes into the search screen: [at], the screen's own List field
 * taking the posts in [posts], the screen being in [screen]. The posts came out of the search's state
 * in [state], and [shown] holds whether that's the state with results, which shows the grid. [empty]
 * is the one instance of the state for finding nothing, which shows Instagram's empty page, and
 * [scratch] a register nothing reads after the write.
 */
internal class SavedSearchResults(
    val method: MutableMethod,
    val at: Int,
    val posts: Int,
    val screen: Int,
    val state: Int,
    val shown: Int,
    val empty: FieldReference,
    val scratch: Int,
)

internal class LockedGridTargets(
    val reader: CollectionReader,
    val rows: List<SavedGridRows>,
    val savedIn: MethodReference,
    val bridges: Map<String, MutableMethod>,
    /** Null when this build's Reels screen moved: its reels show as before, and the rest goes on. */
    val reels: SavedReelsBatch?,
    /** Null when this build's Saved search moved: it finds everything as before, and the rest goes on. */
    val search: SavedSearchResults?,
)

/**
 * [findLockedGrid], or null with a warning in the patch log when this build's Saved has moved: the
 * collection stays locked on its own page, and only its posts show in All and Posts as before.
 */
internal fun BytecodePatchContext.findLockedGridOrSkip(): LockedGridTargets? = try {
    findLockedGrid()
} catch (e: PatchException) {
    patchLog.warning("${e.message}. Posts saved into a locked collection still show in Saved's All and Posts, and the rest of the lock goes on.")
    null
}

/** Everything the grid needs, proved before anything changes. */
internal fun BytecodePatchContext.findLockedGrid(): LockedGridTargets {
    val reader = findCollectionReader()
    val savedIn = findSavedIn()
    val rows = findSavedGridRows()
    val stubs = mutableClassDefBy(LOCKED_ITEMS).methods
    fun bridge(name: String, returns: String) = stubs.singleOrNull {
        it.name == name && it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;") && it.returnType == returns &&
            AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags)
    } ?: refuseGrid("the extension has no public static $name bridge")
    val bridges = mapOf(
        "savedIn" to bridge("savedIn", "Ljava/util/List;"),
        "idOf" to bridge("idOf", "Ljava/lang/String;"),
        "nameOf" to bridge("nameOf", "Ljava/lang/String;"),
        "mediaOf" to bridge("mediaOf", "Ljava/lang/Object;"),
        "collectionOf" to bridge("collectionOf", "Ljava/lang/Object;"),
    )
    val reels = try {
        findSavedReelsBatch()
    } catch (e: PatchException) {
        patchLog.warning("${e.message}. Reels saved into a locked collection still show in Saved's Reels, and the rest of the lock goes on.")
        null
    }
    val search = try {
        findSavedSearchResults()
    } catch (e: PatchException) {
        patchLog.warning("${e.message}. Saved's search still finds posts saved into a locked collection, and the rest of the lock goes on.")
        null
    }
    return LockedGridTargets(reader, rows, savedIn, bridges, reels, search)
}

/**
 * Where Saved's search screen gets what it found: the screen is the one class whose getModuleName
 * loads [SAVED_SEARCH_MODULE], and the place is the one write of a List field of it, outside the
 * screen, that goes straight on to make a row of each post in that List. The hook goes on the write
 * itself, under any label it has, so every way there passes through it.
 *
 * The same code then shows the grid when the state the posts came from is the one with results, and
 * Instagram's empty page when it's the one for finding nothing. Right after the write, a search the
 * lock left nothing of takes that state instead ([LEFT_NOTHING]), so it says it found nothing rather
 * than showing an empty grid. Nothing jumps there, and nothing changes either register before the
 * empty page is decided on.
 */
internal fun BytecodePatchContext.findSavedSearchResults(): SavedSearchResults {
    val screens = classesLoadingString(SAVED_SEARCH_MODULE).filter { owner ->
        owner.methods.any { it.name == "getModuleName" && it.parameterTypes.isEmpty() && loadsString(it, SAVED_SEARCH_MODULE) }
    }
    val screen = screens.singleOrNull() ?: refuseGrid("expected one Saved search screen, found ${screens.size}")
    val lists = screen.fields.filter { it.type == "Ljava/util/List;" && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val found = lists.flatMap { field ->
        classesAccessing(screen.type, field.name, Opcode.IPUT_OBJECT).filter { it.type != screen.type }.flatMap { owner ->
            owner.methods.flatMap { method ->
                val code = method.implementation?.instructions?.toList().orEmpty()
                code.indices.filter { code.storesFoundPosts(it, screen.type, field.name) }.map { method to it }
            }
        }
    }
    val (stock, at) = found.singleOrNull()
        ?: refuseGrid("expected one place Saved's search gets what it found, found ${found.size}")
    val method = mutableClassDefBy(stock.definingClass).methods.single { it.sameAs(stock) }
    val code = method.implementation!!.instructions.toList()
    val write = code[at] as TwoRegisterInstruction
    if (write.registerA > 15 || write.registerB > 15) {
        refuseGrid("Saved's search keeps what it found in v${write.registerA} and its screen in v${write.registerB}, past what an invoke names")
    }
    if (at + 1 in method.jumpTargets()) refuseGrid("something jumps to just after Saved's search takes what it found")
    val (state, shown) = searchResultsCheck(method, code, at, write.registerA)
    val (decided, empty) = searchEmptyCheck(code, at, state, screen.type)
    for (register in listOf(state, shown)) {
        val writes = reachingWrites(method, code, register, decided)
        if (register > 255 || writes.isEmpty() || writes.any { it >= at }) {
            refuseGrid("Saved's search changes v$register before it decides on its empty page")
        }
    }
    val scratch = method.freeLocalsAt(LOCK_PATCH, at + 1, 1, except = listOf(state, shown, write.registerA, write.registerB)).first()
    return SavedSearchResults(method, at, write.registerA, write.registerB, state, shown, empty, scratch)
}

/**
 * The registers of Saved's search's state and of whether it's the state with results: the posts
 * written at [at] in [posts] come out of a List field of one class, the state with results, and the
 * last instance-of that class before [at] asks it of the state.
 */
private fun searchResultsCheck(method: Method, code: List<Instruction>, at: Int, posts: Int): Pair<Int, Int> {
    val kinds = reachingWrites(method, code, posts, at).filter { it >= 0 }.mapNotNull { i ->
        val field = (code[i] as? ReferenceInstruction)?.reference as? FieldReference
        field?.takeIf { code[i].opcode == Opcode.IGET_OBJECT && it.type == "Ljava/util/List;" }?.definingClass
    }.distinct()
    val results = kinds.singleOrNull()
        ?: refuseGrid("Saved's search takes what it found out of ${kinds.size} kinds of state, not one")
    val check = (at - 1 downTo 0).firstOrNull { i ->
        code[i].opcode == Opcode.INSTANCE_OF && ((code[i] as ReferenceInstruction).reference as TypeReference).type == results
    } ?: refuseGrid("Saved's search never asks whether its state has results")
    val asked = code[check] as TwoRegisterInstruction
    return asked.registerB to asked.registerA
}

/**
 * Where Saved's search asks whether its state in [state] is the one for finding nothing: the first
 * instance-of of it after the code past [at] fetches [screen]'s empty page. Answers that index and
 * the one instance of that state, its own static field.
 */
private fun BytecodePatchContext.searchEmptyCheck(code: List<Instruction>, at: Int, state: Int, screen: String): Pair<Int, FieldReference> {
    val page = (at + 1 until minOf(code.size, at + 80)).firstOrNull { i ->
        val field = (code[i] as? ReferenceInstruction)?.reference as? FieldReference
        code[i].opcode == Opcode.IGET_OBJECT && field?.definingClass == screen && field.type == EMPTY_STATE_VIEW
    } ?: refuseGrid("Saved's search doesn't fetch its empty page after taking what it found")
    val check = (page + 1 until minOf(code.size, page + 8)).firstOrNull { i ->
        code[i].opcode == Opcode.INSTANCE_OF && (code[i] as TwoRegisterInstruction).registerB == state
    } ?: refuseGrid("Saved's search shows its empty page without asking its state")
    val type = ((code[check] as ReferenceInstruction).reference as TypeReference).type
    val instances = classDefByOrNull(type)?.fields?.toList().orEmpty().filter {
        AccessFlags.STATIC.isSet(it.accessFlags) && it.type == type
    }
    val instance = instances.singleOrNull()
        ?: refuseGrid("Saved's search's state for finding nothing has ${instances.size} instances, not one")
    return check to ImmutableFieldReference(type, instance.name, type)
}

/**
 * Whether the instruction at [at] puts a List into the field [name] of [screen] and the code right
 * after goes over that List, casting what it gets to a post.
 */
private fun List<Instruction>.storesFoundPosts(at: Int, screen: String, name: String): Boolean {
    val write = this[at]
    if (write.opcode != Opcode.IPUT_OBJECT) return false
    val field = (write as ReferenceInstruction).reference as FieldReference
    if (field.definingClass != screen || field.name != name) return false
    val posts = (write as TwoRegisterInstruction).registerA
    val after = drop(at + 1).take(40)
    val walk = after.indexOfFirst {
        val call = (it as? ReferenceInstruction)?.reference as? MethodReference
        it.opcode == Opcode.INVOKE_INTERFACE && call?.name == "iterator" && (it as FiveRegisterInstruction).registerC == posts
    }
    return walk >= 0 && after.drop(walk).any {
        it.opcode == Opcode.CHECK_CAST && ((it as ReferenceInstruction).reference as TypeReference).type == SAVED_GRID_MEDIA
    }
}

/**
 * The one place Saved's Reels screen gets a new batch of reels: in a method loading
 * [SAVED_REELS_FETCHER] and taking one List, where it casts the screen, a class loading the same
 * string with one field holding a SavedCollection, and goes on to hand a List to the grid adapter the
 * screen keeps. On Redex builds that's one arm of a merged listener, so the hook goes on the arm's
 * own cast, where nothing jumps. The batch is the List the method took, still in a register of
 * v0 to v15 there.
 */
internal fun BytecodePatchContext.findSavedReelsBatch(): SavedReelsBatch {
    val owners = classesLoadingString(SAVED_REELS_FETCHER)
    val screens = owners.filter { owner ->
        owner.fields.count { it.type == SAVED_COLLECTION_TYPE && !AccessFlags.STATIC.isSet(it.accessFlags) } == 1
    }.associateBy { it.type }
    val found = owners.flatMap { owner ->
        owner.methods.filter { it.listParameter() != null && loadsString(it, SAVED_REELS_FETCHER) }.flatMap { method ->
            val code = method.implementation!!.instructions.toList()
            code.indices.filter { at ->
                val screen = code[at].castType()?.let(screens::get)
                screen != null && code.drop(at + 1).any { it.handsListToGridOf(screen) }
            }.map { method to it }
        }
    }
    val (stock, cast) = found.singleOrNull()
        ?: refuseGrid("expected one place Saved's Reels screen gets new reels, found ${found.size}")
    val method = mutableClassDefBy(stock.definingClass).methods.single { it.sameAs(stock) }
    val code = method.implementation!!.instructions.toList()
    val where = "${method.definingClass}->${method.name}"
    val screenClass = screens.getValue(code[cast].castType()!!)
    val screen = (code[cast] as OneRegisterInstruction).registerA
    val at = cast + 1
    if (at in method.jumpTargets()) refuseGrid("something in $where jumps to just after it casts Saved's Reels screen")
    if (screen > 15) refuseGrid("expected $where to cast Saved's Reels screen into v0 to v15")
    val reels = reelsRegister(method, code, method.listParameter()!!, at)
        ?: refuseGrid("expected $where to still hold the reels it got in v0 to v15 where it casts Saved's Reels screen")
    val collection = screenClass.fields.single { it.type == SAVED_COLLECTION_TYPE && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val media = screenClass.reelMediaField()
        ?: refuseGrid("expected Saved's Reels screen ${screenClass.type} to read one kind of reel's post")
    return SavedReelsBatch(method, at, reels, screen, collection, media)
}

/** The index of a method's one List parameter, or null when it has none or more. */
private fun Method.listParameter(): Int? =
    parameterTypes.indices.filter { parameterTypes[it].toString() == "Ljava/util/List;" }.singleOrNull()

private fun Instruction.castType(): String? =
    if (opcode == Opcode.CHECK_CAST) ((this as ReferenceInstruction).reference as TypeReference).type else null

/** A call handing a List and a flag to the grid adapter kept in one of [screen]'s fields. */
private fun Instruction.handsListToGridOf(screen: ClassDef): Boolean {
    if (opcode != Opcode.INVOKE_VIRTUAL) return false
    val call = (this as ReferenceInstruction).reference as MethodReference
    return call.returnType == "V" && call.parameterTypes.map(Any::toString) == listOf("Ljava/util/List;", "Z") &&
        screen.fields.any { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == call.definingClass }
}

/**
 * The register of v0 to v15 holding parameter [list] at [at]: the parameter's own, or the one a move
 * copied it to, when that copy is the only write of it reaching [at] and the parameter was intact then.
 */
private fun reelsRegister(method: MutableMethod, code: List<Instruction>, list: Int, at: Int): Int? {
    val param = method.parameterRegisterNumber(list)
    fun intactAt(index: Int) = runCatching { method.requireParameterIntact("reels", list, listOf(index)) }.isSuccess
    if (param <= 15 && intactAt(at)) return param
    return code.indices.firstNotNullOfOrNull { move ->
        val copy = code[move] as? TwoRegisterInstruction
        val copied = (code[move].opcode == Opcode.MOVE_OBJECT || code[move].opcode == Opcode.MOVE_OBJECT_FROM16) &&
            copy!!.registerB == param && copy.registerA <= 15
        copy?.registerA?.takeIf { copied && reachingWrites(method, code, it, at) == setOf(move) && intactAt(move) }
    }
}

/** The one field of a class other than this one and Media holding a post, read in this class's methods: a reel's post. */
private fun ClassDef.reelMediaField(): FieldReference? = methods
    .flatMap { it.implementation?.instructions?.toList() ?: emptyList() }
    .filter { it.opcode == Opcode.IGET_OBJECT }
    .map { (it as ReferenceInstruction).reference as FieldReference }
    .filter { it.type == SAVED_GRID_MEDIA && it.definingClass != type && it.definingClass != SAVED_GRID_MEDIA }
    .distinctBy { it.toString() }
    .singleOrNull()

/**
 * The single unsafeParseFromJson making a SavedCollection and loading both [COLLECTION_ID_KEY] and
 * [COLLECTION_NAME_KEY], with the String fields of the collection each key's value goes to, the
 * first written after the key loads. Every return of the collection it made is outside any try block.
 */
internal fun BytecodePatchContext.findCollectionReader(): CollectionReader {
    val readers = classesLoadingString(COLLECTION_NAME_KEY).flatMap { owner ->
        owner.methods.filter { method ->
            method.name == "unsafeParseFromJson" && method.returnType == "Ljava/lang/Object;" &&
                method.makesCollection() != null && loadsString(method, COLLECTION_ID_KEY) && loadsString(method, COLLECTION_NAME_KEY)
        }
    }
    val found = readers.singleOrNull()
        ?: refuseGrid("expected one reader of Saved collections, found ${readers.size}")
    val method = mutableClassDefBy(found.definingClass).methods.single { it.sameAs(found) }
    val code = method.implementation!!.instructions.toList()
    val where = "${method.definingClass}->${method.name}"
    val collection = method.makesCollection()!!
    fun fieldAfter(key: String): FieldReference {
        val loads = code.indices.filter { stringLoadedAt(code, it) == key }
        val load = loads.singleOrNull() ?: refuseGrid("expected $where to load \"$key\" once, found ${loads.size}")
        return code.drop(load + 1).take(16).firstNotNullOfOrNull { it.collectionStringWrite(collection) }
            ?: refuseGrid("expected $where to keep \"$key\" in a String field of the collection")
    }
    val id = fieldAfter(COLLECTION_ID_KEY)
    val name = fieldAfter(COLLECTION_NAME_KEY)
    if (id == name) refuseGrid("expected $where to keep a collection's id and name apart")
    val returns = code.indices.filter {
        code[it].opcode == Opcode.RETURN_OBJECT && (code[it] as OneRegisterInstruction).registerA == collection
    }
    if (returns.isEmpty()) refuseGrid("expected $where to return the collection it made")
    if (!method.implementation!!.tryBlocks.isEmpty()) refuseGrid("expected $where to read outside any try block")
    return CollectionReader(method, returns, collection, id, name)
}

/** The register a method's one new SavedCollection goes to, or null when it makes none or more. */
private fun Method.makesCollection(): Int? {
    val made = implementation?.instructions?.filter {
        it.opcode == Opcode.NEW_INSTANCE && ((it as ReferenceInstruction).reference as TypeReference).type == SAVED_COLLECTION_TYPE
    }.orEmpty()
    return (made.singleOrNull() as? OneRegisterInstruction)?.registerA
}

private fun Instruction.collectionStringWrite(collection: Int): FieldReference? {
    if (opcode != Opcode.IPUT_OBJECT) return null
    val field = (this as ReferenceInstruction).reference as FieldReference
    if ((this as TwoRegisterInstruction).registerB != collection) return null
    return field.takeIf { it.definingClass == SAVED_COLLECTION_TYPE && it.type == "Ljava/lang/String;" }
}

/** A post's getter of its collections: the one no-argument method of Media answering a List that loads [SAVED_COLLECTION_IDS_KEY]. */
internal fun BytecodePatchContext.findSavedIn(): MethodReference {
    val media = classDefByOrNull(SAVED_GRID_MEDIA) ?: refuseGrid("found no $SAVED_GRID_MEDIA")
    val getters = media.methods.filter {
        it.parameterTypes.isEmpty() && it.returnType == "Ljava/util/List;" && !AccessFlags.STATIC.isSet(it.accessFlags) &&
            loadsString(it, SAVED_COLLECTION_IDS_KEY)
    }
    return getters.singleOrNull() ?: refuseGrid("expected one getter of a post's collections, found ${getters.size}")
}

/**
 * In each class loading [SAVED_POSTS_FEED] with one field holding a SavedCollection, the instance
 * method taking and answering a List that makes a row for each post: it gets the post from each item,
 * then hands it to its own row maker. The hook goes between the two, where nothing jumps; the list
 * it took is read by nothing after that, so the hook borrows it.
 */
internal fun BytecodePatchContext.findSavedGridRows(): List<SavedGridRows> {
    val sites = classesLoadingString(SAVED_POSTS_FEED).flatMap { owner ->
        val fields = owner.fields.filter { it.type == SAVED_COLLECTION_TYPE && !AccessFlags.STATIC.isSet(it.accessFlags) }
        val field = fields.singleOrNull() ?: return@flatMap emptyList()
        owner.methods.filter { it.makesRows() != null }.map { found ->
            val method = mutableClassDefBy(found.definingClass).methods.single { it.sameAs(found) }
            gridSite(method, field)
        }
    }
    if (sites.isEmpty()) refuseGrid("found no Saved grid making a row for each post")
    return sites
}

/**
 * Where [method] gets a post and makes its row: the index of the read, or null when it doesn't look like
 * that. The post comes from the item's getter and its move-result, or, where Redex inlined the getter
 * (456.1's arm64-v8a and x86_64 builds), from the item's field and a null check of what it read. Either
 * way the instruction after the read holds the post's register, and the row maker comes next.
 */
private fun Method.makesRows(): Int? {
    if (AccessFlags.STATIC.isSet(accessFlags) || returnType != "Ljava/util/List;" ||
        parameterTypes.map(Any::toString) != listOf("Ljava/util/List;")
    ) {
        return null
    }
    val code = implementation?.instructions?.toList() ?: return null
    val gets = code.indices.filter { at ->
        val media = code[at].readsPost(code.getOrNull(at + 1)) ?: return@filter false
        code.getOrNull(at + 2)?.makesRowOf(definingClass, media) == true
    }
    return gets.singleOrNull()
}

/** The register a post read at this instruction ends up in, when [next] completes it; null for anything else. */
private fun Instruction.readsPost(next: Instruction?): Int? {
    val reference = (this as? ReferenceInstruction)?.reference
    return when {
        opcode == Opcode.INVOKE_VIRTUAL && reference is MethodReference && reference.returnType == SAVED_GRID_MEDIA &&
            reference.parameterTypes.isEmpty() && next?.opcode == Opcode.MOVE_RESULT_OBJECT ->
            (next as OneRegisterInstruction).registerA
        opcode == Opcode.IGET_OBJECT && reference is FieldReference && reference.type == SAVED_GRID_MEDIA &&
            next?.opcode == Opcode.IF_EQZ && (next as OneRegisterInstruction).registerA == (this as TwoRegisterInstruction).registerA ->
            (this as TwoRegisterInstruction).registerA
        else -> null
    }
}

private fun Instruction.makesRowOf(owner: String, media: Int): Boolean {
    val call = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
    val registers = this as? FiveRegisterInstruction ?: return false
    return opcode == Opcode.INVOKE_DIRECT && call.definingClass == owner &&
        call.parameterTypes.map(Any::toString) == listOf(SAVED_GRID_MEDIA) &&
        registers.registerCount == 2 && registers.registerD == media
}

private fun gridSite(method: MutableMethod, field: Field): SavedGridRows {
    val where = "${method.definingClass}->${method.name}"
    val code = method.implementation!!.instructions.toList()
    val get = method.makesRows()!!
    val at = get + 2
    val media = (code[get + 1] as OneRegisterInstruction).registerA
    val screen = (code[at] as FiveRegisterInstruction).registerC
    val registers = method.implementation!!.registerCount
    if (screen != registers - 2) refuseGrid("expected $where to make each row with its own screen")
    val scratch = registers - 1
    if (!method.implementation!!.tryBlocks.isEmpty()) refuseGrid("expected $where to make rows outside any try block")
    if (at in method.jumpTargets()) refuseGrid("something in $where jumps between a post and its row")
    // The loop's next turn: the goto after the row goes back to the iterator's hasNext.
    val loop = (at until code.size).firstOrNull { code[it].opcode == Opcode.GOTO || code[it].opcode == Opcode.GOTO_16 }
        ?: refuseGrid("expected $where to make rows in a loop")
    val next = method.targetOf(loop)
    val hasNext = (code.getOrNull(next) as? ReferenceInstruction)?.reference as? MethodReference
    if (next >= at || hasNext?.name != "hasNext" || hasNext.definingClass != "Ljava/util/Iterator;") {
        refuseGrid("expected $where to go back to its iterator's hasNext after each row")
    }
    if (scratch in method.liveAcrossInjection(at, listOf(next))) refuseGrid("expected $where to be done with the list it took")
    if (media > 15 || screen > 15 || scratch > 15) refuseGrid("expected $where to keep the post and its screen in v0 to v15")
    return SavedGridRows(method, at, media, screen, scratch, next, field)
}

/** The index the branch or goto at [at] goes to. */
private fun Method.targetOf(at: Int): Int {
    val code = implementation!!.instructions.toList()
    val offset = (code[at] as OffsetInstruction).codeOffset
    var address = 0
    val addresses = code.map { each -> address.also { address += each.codeUnits } }
    return addresses.indexOf(addresses[at] + offset)
}

private fun Method.sameAs(other: Method) = name == other.name && returnType == other.returnType &&
    parameterTypes.map(CharSequence::toString) == other.parameterTypes.map(CharSequence::toString)

/** Hooks the reader's returns, each grid's rows, and writes the bridges' bodies. */
internal fun hookLockedGrid(targets: LockedGridTargets) {
    val reader = targets.reader
    reader.returns.sortedDescending().forEach { at ->
        reader.method.addInstructionsAtControlFlowLabel(at, "invoke-static/range { v${reader.collection} .. v${reader.collection} }, $READ_COLLECTION")
    }
    targets.rows.forEach { rows ->
        rows.method.addInstructionsWithLabels(
            rows.at,
            """
                iget-object v${rows.scratch}, v${rows.screen}, ${rows.collection}
                invoke-static { v${rows.media}, v${rows.scratch} }, $LEAVE_OUT_POST
                move-result v${rows.scratch}
                if-nez v${rows.scratch}, :instagram_next_post
            """,
            ExternalLabel("instagram_next_post", rows.method.getInstruction(rows.next)),
        )
    }
    targets.bridges.getValue("savedIn").addInstructionsWithLabels(
        0,
        """
            check-cast p0, $SAVED_GRID_MEDIA
            invoke-virtual { p0 }, ${targets.savedIn}
            move-result-object p0
            return-object p0
        """.trimIndent(),
    )
    targets.bridges.getValue("idOf").addInstructionsWithLabels(
        0,
        """
            check-cast p0, $SAVED_COLLECTION_TYPE
            iget-object p0, p0, ${reader.id}
            return-object p0
        """.trimIndent(),
    )
    targets.bridges.getValue("nameOf").addInstructionsWithLabels(
        0,
        """
            check-cast p0, $SAVED_COLLECTION_TYPE
            iget-object p0, p0, ${reader.name}
            return-object p0
        """.trimIndent(),
    )
    targets.search?.let { search ->
        // After the write first, so search.at still names the write for the hook in front of it.
        search.method.addInstructionsWithLabels(
            search.at + 1,
            """
                invoke-static { v${search.posts} }, $LEFT_NOTHING
                move-result v${search.scratch}
                if-eqz v${search.scratch}, :shown
                sget-object v${search.state}, ${search.empty}
                const/16 v${search.shown}, 0x0
            """.trimIndent(),
            ExternalLabel("shown", search.method.getInstruction(search.at + 1)),
        )
        search.method.addInstructionsAtControlFlowLabel(
            search.at,
            """
                invoke-static { v${search.posts}, v${search.screen} }, $LEAVE_OUT_FOUND
                move-result-object v${search.posts}
            """.trimIndent(),
        )
    }
    val reels = targets.reels ?: return
    reels.method.addInstructions(
        reels.at,
        """
            invoke-static { v${reels.reels}, v${reels.screen} }, $LEAVE_OUT_REELS
            move-result-object v${reels.reels}
        """.trimIndent(),
    )
    targets.bridges.getValue("mediaOf").addInstructionsWithLabels(
        0,
        """
            check-cast p0, ${reels.media.definingClass}
            iget-object p0, p0, ${reels.media}
            return-object p0
        """.trimIndent(),
    )
    targets.bridges.getValue("collectionOf").addInstructionsWithLabels(
        0,
        """
            check-cast p0, ${reels.collection.definingClass}
            iget-object p0, p0, ${reels.collection}
            return-object p0
        """.trimIndent(),
    )
}

private fun refuseGrid(why: String): Nothing = throw PatchException("$LOCK_PATCH: $why")
