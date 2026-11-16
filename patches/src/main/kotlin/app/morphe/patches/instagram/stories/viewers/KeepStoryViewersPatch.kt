/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.viewers

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.download.USER
import app.morphe.patches.instagram.download.pandoGetter
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.stories.seen.userIdGetter
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference

internal const val STORY_VIEWERS_PATCH = "Keep story viewers"
internal const val STORY_VIEWERS = "$EXTENSION_PACKAGE/stories/StoryViewers;"
internal const val STORY_VIEWERS_LISTED = "$STORY_VIEWERS->listed(Ljava/lang/Object;Ljava/lang/Object;)V"

/** The request for who viewed a story, a string no Redex rename touches. */
internal const val VIEWER_LIST_REQUEST = "media/%s/list_reel_media_viewer/"

/** What the answer's success logs: how many viewers came, then the story's total, then Facebook's. */
internal const val VIEWERS_FETCHED = "fetched_ig_viewers_count"
internal const val VIEWERS_TOTAL = "total_viewer_count"

/** The name Instagram's parser of one viewer in that answer reports itself by. */
internal const val STORY_VIEWER_MODEL = "StoryViewerWithInteractions"

private const val LIST = "Ljava/util/List;"
private const val TEXT = "Ljava/lang/String;"
private const val ANY = "Ljava/lang/Object;"

/**
 * Where the story viewers hook goes and what its bridges read: the success method of the request's
 * callback, the callback's story id field, and in the answer the list of viewers, the story's total
 * and the story itself, with the field of one viewer that holds the account.
 */
internal class StoryViewersSite(
    val callback: String,
    val success: Method,
    val story: String,
    val answer: String,
    val viewers: String,
    val total: String,
    val media: String,
    val viewer: String,
    val account: String,
)

/**
 * Keeps a list on the phone of who viewed each of your stories (#710), from the list Instagram
 * already loads when you open your story's viewers. In the default selection with its switch off.
 *
 * Nothing is requested or sent: the hook reads each page of viewers as it comes back, before
 * Instagram's own screen shows it, and the list stays in the app's own storage.
 */
@Suppress("unused")
val keepStoryViewersPatch = bytecodePatch(
    name = "Keep story viewers",
    description = "Keeps a list on your phone of who viewed each of your stories, from the viewer list Instagram " +
        "already shows you, and sets it beside your followers. Nothing extra is asked of Instagram. Starts off. " +
        "Turn it on in HushGram settings > Stories.",
    default = true,
) {
    category("Downloads")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("storyViewers")
        val site = findStoryViewers()
        keepStoryViewers(site)
        enableStatus("storyViewers")
    }
}

private fun refuse(why: String): Nothing = throw PatchException("$STORY_VIEWERS_PATCH: $why")

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

private fun Instruction.field(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun Method.takes(vararg types: String) = parameterTypes.map(Any::toString) == types.toList()

/** One instance field of [type] declared on [owner], as a reference, or why there isn't exactly one. */
private fun ClassDef.onlyField(type: String): String {
    val matching = fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == type }
    val field = matching.singleOrNull() ?: refuse("expected one $type field on ${this.type}, found ${matching.size}")
    return "${this.type}->${field.name}:$type"
}

/**
 * Finds the callback by the request: of the classes the method building the request creates, the
 * one with a success method taking the answer that logs how many viewers came. The answer is the
 * class that method casts to, its list of viewers the one list of the answer's it reads before that
 * log, and its total the int the answer's parser writes for the story's total. One viewer is the
 * class with one account field that the viewer's parser creates.
 */
internal fun BytecodePatchContext.findStoryViewers(): StoryViewersSite {
    val builders = classesLoadingString(VIEWER_LIST_REQUEST).flatMap { it.methods }.filter { loadsString(it, VIEWER_LIST_REQUEST) }
    val created = builders.flatMap { builder ->
        builder.code().filter { it.opcode == Opcode.NEW_INSTANCE }
            .map { ((it as ReferenceInstruction).reference as TypeReference).type }
    }.distinct()
    val successes = created.mapNotNull { type -> classDefByOrNull(type) }.flatMap { type ->
        type.methods.filter { method ->
            !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" && method.takes(ANY) &&
                loadsString(method, VIEWERS_FETCHED)
        }.map { type to it }
    }
    val (callback, success) = successes.singleOrNull()
        ?: refuse("expected one callback of $VIEWER_LIST_REQUEST logging $VIEWERS_FETCHED, found " +
            successes.joinToString { "${it.first.type}->${it.second.name}" }.ifEmpty { "none" })

    val code = success.code()
    val answers = code.filter { it.opcode == Opcode.CHECK_CAST }.map { ((it as ReferenceInstruction).reference as TypeReference).type }
    val answer = answers.firstOrNull()?.let { classDefByOrNull(it) }
        ?: refuse("${callback.type}->${success.name} casts its answer to no class of the build")
    val fetched = code.indices.firstOrNull { stringLoadedAt(code, it) == VIEWERS_FETCHED }
        ?: refuse("${callback.type}->${success.name} doesn't load $VIEWERS_FETCHED")
    val lists = code.subList(0, fetched).mapNotNull { instruction ->
        instruction.field()?.takeIf { instruction.opcode == Opcode.IGET_OBJECT && it.definingClass == answer.type && it.type == LIST }
    }.map { "${it.definingClass}->${it.name}:${it.type}" }.distinct()
    val viewers = lists.singleOrNull() ?: refuse("expected ${callback.type}->${success.name} to read one list of ${answer.type} before its count, found $lists")

    val totals = classesLoadingString(VIEWERS_TOTAL).flatMap { it.methods }.flatMap { parser ->
        val body = parser.code()
        body.indices.filter { stringLoadedAt(body, it) == VIEWERS_TOTAL }.mapNotNull { at ->
            body.drop(at + 1).firstOrNull { it.opcode == Opcode.IPUT && it.field()?.definingClass == answer.type }?.field()
        }
    }.filter { it.type == "I" }.map { "${it.definingClass}->${it.name}:I" }.distinct()
    val total = totals.singleOrNull() ?: refuse("expected one int of ${answer.type} its parser writes for $VIEWERS_TOTAL, found $totals")

    val models = classesLoadingString(STORY_VIEWER_MODEL).flatMap { it.methods }.filter { loadsString(it, STORY_VIEWER_MODEL) }
        .flatMap { parser -> parser.code().filter { it.opcode == Opcode.NEW_INSTANCE } }
        .map { ((it as ReferenceInstruction).reference as TypeReference).type }.distinct()
        .mapNotNull { classDefByOrNull(it) }
        .filter { model -> model.fields.count { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == USER } == 1 }
    val viewer = models.singleOrNull() ?: refuse("expected one viewer with an account made where $STORY_VIEWER_MODEL is parsed, found ${models.map { it.type }}")

    for ((type, what) in listOf(callback to "callback", answer to "answer", viewer to "viewer")) {
        if (!AccessFlags.PUBLIC.isSet(type.accessFlags)) refuse("the $what ${type.type} isn't public")
    }
    val story = callback.onlyField(TEXT)
    val site = StoryViewersSite(
        callback = callback.type,
        success = success,
        story = story,
        answer = answer.type,
        viewers = viewers,
        total = total,
        media = answer.onlyField(MEDIA),
        viewer = viewer.type,
        account = viewer.onlyField(USER),
    )
    for (field in listOf(site.story, site.viewers, site.total, site.media, site.account)) {
        val owner = classDefBy(field.substringBefore("->"))
        val name = field.substringAfter("->").substringBefore(":")
        if (owner.fields.none { it.name == name && AccessFlags.PUBLIC.isSet(it.accessFlags) }) refuse("$field isn't public")
    }
    return site
}

/**
 * Hands the callback and the answer to the extension first thing in the success method, then writes
 * the bridges the extension reads them through. The hook uses the method's own two parameter
 * registers, so it borrows nothing, and the bridges each use their own parameter alone.
 */
internal fun BytecodePatchContext.keepStoryViewers(site: StoryViewersSite) {
    val success = mutableClassDefBy(site.callback).methods.single {
        it.name == site.success.name && it.takes(ANY) && it.returnType == "V"
    }
    if (0 in success.jumpTargets()) refuse("something in ${site.callback}->${success.name} jumps back to its start")

    val userId = userIdGetter(::refuse)
    val username = pandoGetter(STORY_VIEWERS_PATCH, USER, "username", TEXT)
    val takenAt = pandoGetter(STORY_VIEWERS_PATCH, MEDIA, "taken_at", "Ljava/lang/Long;")
    val mediaId = classDefBy(MEDIA).methods.singleOrNull { it.name == "getId" && it.parameterTypes.isEmpty() && it.returnType == TEXT }
        ?: refuse("$MEDIA has no getId()")

    fun read(cast: String, field: String, wide: Boolean = false) = if (wide) {
        "check-cast p0, $cast\niget p0, p0, $field\nreturn p0"
    } else {
        "check-cast p0, $cast\niget-object p0, p0, $field\nreturn-object p0"
    }
    fun call(cast: String, getter: Method) =
        "check-cast p0, $cast\ninvoke-virtual {p0}, $cast->${getter.name}()${getter.returnType}\nmove-result-object p0\nreturn-object p0"

    val bridges = linkedMapOf(
        "listViewers" to read(site.answer, site.viewers),
        "listTotal" to read(site.answer, site.total, wide = true),
        "listMedia" to read(site.answer, site.media),
        "viewerUser" to read(site.viewer, site.account),
        "listStory" to read(site.callback, site.story),
        "userId" to call(USER, userId),
        "username" to call(USER, username),
        "mediaId" to call(MEDIA, mediaId),
        "takenAt" to call(MEDIA, takenAt),
    )
    val stubs = mutableClassDefBy(STORY_VIEWERS).methods
    val found = bridges.keys.associateWith { name ->
        stubs.singleOrNull { it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) && it.takes(ANY) }
            ?: refuse("$STORY_VIEWERS has no static $name(Object)")
    }

    success.addInstructions(0, "invoke-static/range {p0 .. p1}, $STORY_VIEWERS_LISTED")
    for ((name, body) in bridges) {
        val stub = found.getValue(name)
        if (stub.implementation!!.instructions.first().opcode != Opcode.CHECK_CAST) stub.addInstructions(0, body)
    }
}
