/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.tray

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.download.pandoGetter
import app.morphe.patches.instagram.feed.requireOneKindField
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.flags.FlagLoad
import app.morphe.patches.instagram.misc.flags.answerFlagLoads
import app.morphe.patches.instagram.misc.flags.findFlagLoads
import app.morphe.patches.instagram.misc.settings.EXTENSION_ROOT
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.stories.seen.userIdGetter
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

private const val PATCH = "Hide suggested stories"
internal const val HIDE_TRAY = "$EXTENSION_PACKAGE/stories/StoriesTray;->hideTray()Z"
/** Asked where Instagram floats the tray over the feed: yes while the tray is hidden or kept in the feed (#357). */
internal const val FLOAT_TRAY = "$EXTENSION_PACKAGE/stories/StoriesTray;->keepTrayOutOfOverlay()Z"
internal const val TRAY_FILTER = "$EXTENSION_PACKAGE/stories/StoriesTray;->filter(Ljava/lang/Object;)Ljava/lang/Object;"
internal const val TRAY_REMAINING_FILTER = "$EXTENSION_PACKAGE/stories/StoriesTray;->remaining(Ljava/util/ArrayList;)Ljava/util/ArrayList;"
private const val ARRAY_LIST = "Ljava/util/ArrayList;"
internal const val MUSIC_CARD = "$EXTENSION_PACKAGE/stories/StoriesTray;->musicCard(I)Z"

/**
 * The server flag that lets Instagram's Music for you card into the stories viewer: the
 * `is_enabled` parameter of the `ig_stories_music_midcard` config. On 451's 385713073 it's read
 * once, by a static check of the session, and the check is asked by the code that adds the card to
 * the viewer's list, which casts to the music midcard repository first. 452 renumbered it from
 * 451's 0x810cc0000146f1; its one static session check there is still asked by the code that
 * casts to the repository right after. 453 renumbered it again to 0x810c9b0001461a, still the one
 * static session check asked by the path that casts to the repository.
 */
internal const val MUSIC_CARD_FLAG = 0x810c9b0001461aL

/** The name only the music midcard repository's constructor holds, which it gives its base class. */
internal const val MUSIC_CARD_REPOSITORY = "MusicMidcardRepository"
private const val USER_SESSION = "Lcom/instagram/common/session/UserSession;"

/** The trace name only the method adding Home's story tray row holds. */
internal const val TRAY_ROWS = "MainFeedStoryTrayBinderGroup.buildRowViewTypes"

/**
 * The tag of the overlay the floating tray helper moves the tray into, and the reason it logs when
 * that overlay is missing. Only its show method holds both.
 */
internal const val FLOATING_TRAY = "floating_tray_overlay_container"
internal const val FLOATING_TRAY_FAILED = "show_tray_fail_reason"

/** The floating tray show's parameters: the screen, the tray, why it's shown and a number. */
private val FLOATING_TRAY_SHOW = listOf("Landroid/view/View;", "Landroid/view/View;", "Ljava/lang/String;", "I")

/** Keys only the stories tray response's parser holds together; [TRAY_ITEMS] is the list of tray items. */
internal const val TRAY_ITEMS = "tray"
internal const val TRAY_TOKEN = "story_ranking_token"
internal const val TRAY_REMAINING = "remaining_reel_ids_to_fetch"

/** The kept interface every stories tray item implements. */
internal const val TRAY_ITEM_INTF = "Lcom/instagram/model/reels/ReelResponseItemIntf;"

/** The reel types of suggested tray items, which StoriesTray drops: an account to follow, its story, and a creator's story. */
internal val SUGGESTED_REELS = listOf("SUGGESTED_USER", "SUGGESTED_USER_REEL", "SUGGESTED_CREATOR_REEL")

/**
 * The reel types of the cards Instagram makes for the tray, which StoriesTray drops on their own
 * switches: a rewind, then memories, your week, the year in review, follow anniversaries and
 * birthdays, and the empty story state's cards of accounts to follow.
 */
internal val MADE_REELS = listOf(
    "HIGHLIGHT_REWIND_REEL", "MEMORY_REEL", "MY_WEEK_REEL", "END_OF_YEAR", "FOLLOW_VERSARIES", "BIRTHDAY_HIGHLIGHTS",
    "EMPTY_STORY_STATE_REEL",
)

@Suppress("unused")
val hideSuggestedStoriesPatch = bytecodePatch(
    name = "Hide suggested stories",
    description = "Removes stories from accounts you don't follow from the row at the top of Home. More switches " +
        "hide rewinds and memories, keep only verified or unverified accounts, or hide the whole row. Starts on. " +
        "Turn it off in HushGram settings > Stories.",
) {
    category("Stories")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("storiesTray")
        guardTray(findTrayRowBuild())
        guardTray(findFloatingTrayShow(), FLOAT_TRAY)
        val parse = findTrayItemParse()
        val netegoCheck = findStoryNetegoCheck()
        val midcards = findStoryMidcards()
        val owner = findTrayOwnerOrWarn()
        hookTrayParser(parse, findTrayRemaining(parse.site))
        owner?.let { fillTrayOwnerStubs(it) }
        hideMusicCard()
        hideStorySuggestions(netegoCheck)
        hideStoryMidcards(midcards)
        enableStatus("storiesTray")
    }
}

/** Finds the Music for you card's flag read with [findMusicCard] and has the extension answer it through [MUSIC_CARD]. */
internal fun BytecodePatchContext.hideMusicCard() {
    answerFlagLoads(listOf(findMusicCard() to MUSIC_CARD))
}

/**
 * Finds the one read of [MUSIC_CARD_FLAG]. It has to be the flag's own read, in a check taking the
 * session and answering a boolean, and something calling that check has to cast to the class
 * built with the name [MUSIC_CARD_REPOSITORY] ([musicCardRepositories]), since that's the card's
 * own data. Fails before anything changes when any of it isn't so, since that's an update this
 * patch hasn't seen.
 */
internal fun BytecodePatchContext.findMusicCard(): FlagLoad {
    val flag = MUSIC_CARD_FLAG.toString(16)
    val repositories = musicCardRepositories()
    if (repositories.isEmpty()) refuse("no class holds $MUSIC_CARD_REPOSITORY, so $flag isn't the Music for you card's flag")
    val reads = findFlagLoads(PATCH, MUSIC_CARD_FLAG, "Z")
    val read = reads.singleOrNull() ?: refuse("expected one read of the Music for you card flag $flag, found ${reads.size}")
    val where = "${read.type}->${read.name}"
    if (read.shared) refuse("the read of $flag in $where is shared with another flag")
    if (read.parameters != listOf(USER_SESSION) || read.returnType != "Z") {
        refuse("$flag is read in $where(${read.parameters.joinToString("")})${read.returnType}, not in a check of the session")
    }
    val casts = classesCalling(read.type, read.name).filterNot { it.type.startsWith(EXTENSION_ROOT) }.any { classDef ->
        classDef.methods.any { method ->
            method.implementation?.instructions?.any {
                it.opcode == Opcode.CHECK_CAST && ((it as ReferenceInstruction).reference as TypeReference).type in repositories
            } == true
        }
    }
    if (!casts) refuse("nothing that asks $where casts to the class holding $MUSIC_CARD_REPOSITORY")
    return read
}

/**
 * The classes built with the name [MUSIC_CARD_REPOSITORY], which the repository hands its base
 * class: the class whose constructor holds it, as on 450, or the one class a static factory
 * holding it makes and answers, as 451's 385713073 builds `08KQ` in `0QbN.A00`. 452's 385810270
 * inlines the repository's constructor into its factory (`08PT.A00` builds `08PV`), which then
 * also makes the repository's own java.util lists and set, so those don't count.
 */
private fun BytecodePatchContext.musicCardRepositories(): Set<String> =
    classesHolding(MUSIC_CARD_REPOSITORY).filterNot { it.type.startsWith(EXTENSION_ROOT) }.flatMapTo(HashSet()) { holder ->
        holder.methods.filter { MUSIC_CARD_REPOSITORY in it.strings() }.mapNotNull { method ->
            val made = method.implementation?.instructions?.toList().orEmpty().mapNotNull { instruction ->
                ((instruction as? ReferenceInstruction)?.reference as? TypeReference)?.type?.takeIf { instruction.opcode == Opcode.NEW_INSTANCE }
            }.filterNot { it.startsWith("Ljava/util/") }.toSet()
            when {
                method.name == "<init>" -> holder.type
                AccessFlags.STATIC.isSet(method.accessFlags) && made == setOf(method.returnType) -> method.returnType
                else -> null
            }
        }
    }

/** A method by its class, name and parameter types. */
internal class MethodSite(val type: String, val name: String, val parameters: List<String>)

/**
 * Finds the one method holding [TRAY_ROWS]: the story tray binder group's row build, which adds
 * the tray's row to Home's list through the row builder it takes first. Fails when there isn't
 * exactly one, or it adds no row that way, since that's an update the patch hasn't seen.
 */
internal fun BytecodePatchContext.findTrayRowBuild(): MethodSite {
    val found = mutableListOf<Pair<String, Method>>()
    val holders = classesHolding(TRAY_ROWS).mapTo(HashSet()) { it.type }
    classDefForEach { classDef ->
        if (classDef.type !in holders || classDef.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        classDef.methods.forEach { method -> if (TRAY_ROWS in method.strings()) found += classDef.type to method }
    }
    val (type, method) = found.singleOrNull() ?: refuse("expected one method holding $TRAY_ROWS, found ${found.size}")
    val where = "$type->${method.name}"
    val builder = method.parameterTypes.firstOrNull()?.toString()
    if (method.returnType != "V" || builder == null) refuse("$where isn't a row build taking a row builder")
    val adds = method.implementation!!.instructions.count {
        it.opcode == Opcode.INVOKE_INTERFACE && it.methodReference()?.let { call ->
            call.definingClass == builder && call.parameterTypes.map(CharSequence::toString) == listOf("I") && call.returnType == "V"
        } == true
    }
    if (adds == 0) refuse("$where adds no row through $builder")
    val parameterRegisters = method.parameterTypes.sumOf { if (it == "J" || it == "D") 2 else 1 } + 1
    if (method.implementation!!.registerCount <= parameterRegisters) refuse("$where has no register of its own")
    return MethodSite(type, method.name, method.parameterTypes.map(CharSequence::toString))
}

/**
 * Finds the one method holding [FLOATING_TRAY] and [FLOATING_TRAY_FAILED]: the floating tray
 * helper's show. Coming back to Home mid-feed (from a story opened in DMs, say) or scrolling up
 * quickly, Instagram moves the tray into an overlay above the feed until it scrolls away, without
 * building its row again (#88). Fails when there isn't exactly one, it isn't an instance method
 * taking two views, a reason and a number, or it has no register of its own.
 */
internal fun BytecodePatchContext.findFloatingTrayShow(): MethodSite {
    val found = mutableListOf<Pair<String, Method>>()
    val holders = classesHolding(FLOATING_TRAY, FLOATING_TRAY_FAILED).mapTo(HashSet()) { it.type }
    classDefForEach { classDef ->
        if (classDef.type !in holders || classDef.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        classDef.methods.forEach { method ->
            val strings = method.strings()
            if (FLOATING_TRAY in strings && FLOATING_TRAY_FAILED in strings) found += classDef.type to method
        }
    }
    val (type, method) = found.singleOrNull()
        ?: refuse("expected one method holding $FLOATING_TRAY and $FLOATING_TRAY_FAILED, found ${found.size}")
    val where = "$type->${method.name}"
    val parameters = method.parameterTypes.map(CharSequence::toString)
    if (AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "V" || parameters != FLOATING_TRAY_SHOW) {
        refuse("$where isn't a floating tray show taking two views, a reason and a number")
    }
    if (method.implementation!!.registerCount <= parameters.size + 1) refuse("$where has no register of its own")
    return MethodSite(type, method.name, parameters)
}

/** Asks [hook] ([HIDE_TRAY] unless named) first thing in the method at [site], and on a yes returns before it shows the tray. */
internal fun BytecodePatchContext.guardTray(site: MethodSite, hook: String = HIDE_TRAY) {
    mutableMethod(site).addInstructions(
        0,
        """
            invoke-static { }, $hook
            move-result v0
            if-eqz v0, :show
            return-void
            :show
            nop
        """,
    )
}

/** Where the tray parser reads one item: the parser method, and the index and register of the item's move-result. */
internal class TrayItemParse(val site: MethodSite, val moveResult: Int, val register: Int)

/**
 * Finds the one `unsafeParseFromJson` holding [TRAY_ITEMS], [TRAY_TOKEN] and [TRAY_REMAINING], and in
 * it the one read of an item between the [TRAY_ITEMS] key and the next key: a `parseFromJsonParser`
 * call on a parser whose items implement [TRAY_ITEM_INTF], its move-result-object, and a null test of
 * it, so a null item is skipped. Checks the item's reel type is an enum field the extension can read,
 * naming every kind a switch takes out.
 */
internal fun BytecodePatchContext.findTrayItemParse(): TrayItemParse {
    val found = mutableListOf<Pair<String, Method>>()
    val holders = classesHolding(TRAY_ITEMS, TRAY_TOKEN, TRAY_REMAINING).mapTo(HashSet()) { it.type }
    classDefForEach { classDef ->
        if (classDef.type !in holders || classDef.type.startsWith(EXTENSION_ROOT)) return@classDefForEach
        classDef.methods.forEach { method ->
            if (method.name != "unsafeParseFromJson") return@forEach
            val strings = method.strings()
            if (TRAY_ITEMS in strings && TRAY_TOKEN in strings && TRAY_REMAINING in strings) found += classDef.type to method
        }
    }
    val (type, method) = found.singleOrNull()
        ?: refuse("expected one stories tray parser holding $TRAY_ITEMS, $TRAY_TOKEN and $TRAY_REMAINING, found ${found.size}")
    val code = method.implementation!!.instructions.toList()
    val where = "$type->${method.name}"
    val key = code.indexOfFirst { it.string() == TRAY_ITEMS }
    val nextKey = (key + 1 until code.size).firstOrNull { code[it].string() != null } ?: code.size
    val reads = (key + 1 until nextKey).filter { code[it].methodReference()?.name == "parseFromJsonParser" }
    val read = reads.singleOrNull() ?: refuse("$where reads its tray items ${reads.size} times, expected once")
    val result = code.getOrNull(read + 1)
    if (result?.opcode != Opcode.MOVE_RESULT_OBJECT) refuse("$where drops the tray item it reads")
    val register = (result as OneRegisterInstruction).registerA
    val test = code.getOrNull(read + 2)
    if (test?.opcode != Opcode.IF_EQZ || (test as OneRegisterInstruction).registerA != register) {
        refuse("$where doesn't skip a tray item that didn't parse")
    }

    val parser = (read - 3 until read).reversed().firstNotNullOfOrNull { at ->
        code.getOrNull(at)?.takeIf { it.opcode == Opcode.SGET_OBJECT }?.let { ((it as ReferenceInstruction).reference as FieldReference).type }
    } ?: refuse("$where reads its tray items with no parser before the call")
    val casts = classDefBy(parser).methods.filter { it.name == "parseFromJsonParser" }.flatMap { m ->
        m.implementation?.instructions?.filter { it.opcode == Opcode.CHECK_CAST }
            ?.map { ((it as ReferenceInstruction).reference as TypeReference).type }.orEmpty()
    }.distinct()
    val item = casts.singleOrNull { classDefByOrNull(it)?.interfaces?.contains(TRAY_ITEM_INTF) == true }
        ?: refuse("expected $parser to read one class implementing $TRAY_ITEM_INTF, found $casts")
    requireOneKindField(PATCH, item, SUGGESTED_REELS + MADE_REELS)
    return TrayItemParse(MethodSite(type, method.name, method.parameterTypes.map(CharSequence::toString)), read + 1, register)
}

/** Where the tray parser reads the ids of the reels it fetches after the tray: the index and register of that read's move-result. */
internal class TrayRemainingRead(val site: MethodSite, val moveResult: Int, val register: Int)

/**
 * In the tray parser at [site], the one read of [TRAY_REMAINING]'s value between that key and the
 * next: a call answering an ArrayList, and its move-result-object. Fails when there isn't exactly
 * one, since then Stop loading stories and highlights would leave those reels to load.
 */
internal fun BytecodePatchContext.findTrayRemaining(site: MethodSite): TrayRemainingRead {
    val method = classDefBy(site.type).methods.single {
        it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
    }
    val code = method.implementation!!.instructions.toList()
    val where = "${site.type}->${site.name}"
    val key = code.indexOfFirst { it.string() == TRAY_REMAINING }
    if (key < 0) refuse("$where doesn't hold $TRAY_REMAINING")
    val nextKey = (key + 1 until code.size).firstOrNull { code[it].string() != null } ?: code.size
    val reads = (key + 1 until nextKey).filter { code[it].methodReference()?.returnType == ARRAY_LIST }
    val read = reads.singleOrNull() ?: refuse("$where reads $TRAY_REMAINING ${reads.size} times, expected once")
    val result = code.getOrNull(read + 1)
    if (result?.opcode != Opcode.MOVE_RESULT_OBJECT) refuse("$where drops the reel ids it reads")
    return TrayRemainingRead(site, read + 1, (result as OneRegisterInstruction).registerA)
}

/** Passes the ids of the reels left to fetch through [TRAY_REMAINING_FILTER] right after the parser reads them. */
internal fun BytecodePatchContext.trimTrayRemaining(read: TrayRemainingRead) {
    val ids = read.register
    mutableMethod(read.site).addInstructions(
        read.moveResult + 1,
        """
            invoke-static/range { v$ids .. v$ids }, $TRAY_REMAINING_FILTER
            move-result-object v$ids
        """,
    )
}

/** Both tray parser hooks, the later one first, so writing one doesn't move where the other goes. */
internal fun BytecodePatchContext.hookTrayParser(parse: TrayItemParse, remaining: TrayRemainingRead) {
    if (remaining.moveResult > parse.moveResult) {
        trimTrayRemaining(remaining)
        filterTrayItems(parse)
    } else {
        filterTrayItems(parse)
        trimTrayRemaining(remaining)
    }
}

/** Passes each tray item through [TRAY_FILTER] right after the parser reads it, ahead of its null test. */
internal fun BytecodePatchContext.filterTrayItems(parse: TrayItemParse) {
    val item = parse.register
    mutableMethod(parse.site).addInstructions(
        parse.moveResult + 1,
        """
            invoke-static/range { v$item .. v$item }, $TRAY_FILTER
            move-result-object v$item
        """,
    )
}

private const val TRAY_USER = "Lcom/instagram/user/model/User;"
private const val STORIES_TRAY = "$EXTENSION_PACKAGE/stories/StoriesTray;"

/**
 * What Filter the Stories tray by verified accounts reads (#340): [owner], the tray item
 * interface's getter of the account whose story it is, and that User's [verified] getter (the
 * `is_verified` field) and [id] getter. All public, so the extension's stubs can call them.
 */
internal class TrayOwner(val owner: Method, val verified: Method, val id: Method)

/**
 * Finds [TrayOwner]: the one no-argument User getter [TRAY_ITEM_INTF] declares (452.1's Dy7, which
 * both tray item classes answer), User's Boolean getter of `is_verified`, and its id getter.
 */
internal fun BytecodePatchContext.findTrayOwner(): TrayOwner {
    val items = classDefByOrNull(TRAY_ITEM_INTF) ?: refuse("$TRAY_ITEM_INTF isn't present")
    if (!AccessFlags.PUBLIC.isSet(items.accessFlags)) refuse("$TRAY_ITEM_INTF isn't public")
    val owners = items.methods.filter { it.parameterTypes.isEmpty() && it.returnType == TRAY_USER }
    val owner = owners.singleOrNull() ?: refuse("expected $TRAY_ITEM_INTF to declare one User getter, found ${owners.size}")
    val user = classDefByOrNull(TRAY_USER) ?: refuse("the User model isn't present")
    val verified = pandoGetter(PATCH, TRAY_USER, "is_verified", "Ljava/lang/Boolean;")
    if (!AccessFlags.PUBLIC.isSet(user.accessFlags) || !AccessFlags.PUBLIC.isSet(verified.accessFlags)) {
        refuse("$TRAY_USER->${verified.name} isn't public, so the extension can't reach it")
    }
    return TrayOwner(owner, verified, userIdGetter(::refuse))
}

/** [findTrayOwner], or null with a warning: the patch goes in, and the verified filter keeps every story. */
internal fun BytecodePatchContext.findTrayOwnerOrWarn(): TrayOwner? = try {
    findTrayOwner()
} catch (moved: PatchException) {
    patchLog.warning("${moved.message}. Hide suggested stories goes in without Filter the Stories tray by verified accounts.")
    null
}

/** Writes StoriesTray's trayOwner, userVerified and userId stubs to call [found]'s getters. */
internal fun BytecodePatchContext.fillTrayOwnerStubs(found: TrayOwner) {
    val tray = mutableClassDefBy(STORIES_TRAY)
    val reads = listOf(
        Triple("trayOwner", TRAY_ITEM_INTF, found.owner),
        Triple("userVerified", TRAY_USER, found.verified),
        Triple("userId", TRAY_USER, found.id),
    )
    // Every stub is checked before any is written.
    val stubs = reads.map { (name, _, getter) ->
        tray.methods.singleOrNull {
            it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == getter.returnType.let { type ->
                if (type == TRAY_USER) "Ljava/lang/Object;" else type
            } && it.parameterTypes.map(CharSequence::toString) == listOf("Ljava/lang/Object;")
        } ?: refuse("$STORIES_TRAY has no static $name(Object)")
    }
    for ((stub, read) in stubs.zip(reads)) {
        val (_, type, getter) = read
        val invoke = if (type == TRAY_ITEM_INTF) "invoke-interface" else "invoke-virtual"
        stub.addInstructions(
            0,
            """
                check-cast p0, $type
                $invoke { p0 }, $type->${getter.name}()${getter.returnType}
                move-result-object p0
                return-object p0
            """,
        )
    }
}

private fun BytecodePatchContext.mutableMethod(site: MethodSite) = mutableClassDefBy(site.type).methods.single {
    it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
}

private fun Method.strings(): Set<String> = implementation?.instructions?.mapNotNull { it.string() }?.toSet() ?: emptySet()

private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")
