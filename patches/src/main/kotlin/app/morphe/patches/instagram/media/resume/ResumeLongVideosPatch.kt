/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media.resume

import app.morphe.patcher.extensions.InstructionExtensions.addInstruction
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.media.taptoplay.PLAY_INTERNAL
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesHolding
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference

private const val PATCH = "Resume long videos"

internal const val RESUME_PLAYBACK = "$EXTENSION_PACKAGE/media/ResumePlayback;"
internal const val STARTED = "$RESUME_PLAYBACK->started(Ljava/lang/Object;)V"
internal const val STOPPED = "$RESUME_PLAYBACK->stopped(Ljava/lang/Object;Ljava/lang/String;)V"
internal const val REBOUND = "$RESUME_PLAYBACK->rebound(Ljava/lang/Object;)V"
internal const val ENDED = "$RESUME_PLAYBACK->ended(Ljava/lang/Object;)V"
internal const val SEEKING = "$RESUME_PLAYBACK->seeking(Ljava/lang/Object;I)V"
internal const val SESSION_ENDED = "$RESUME_PLAYBACK->sessionEnded(Ljava/lang/Object;)V"

/** The strings IgVideoPlayerImpl keeps in its own log lines, which pick each method out. */
internal const val PLAYBACK_STARTED = "Playback started "
internal const val PLAYBACK_COMPLETED = "Playback completed "
internal const val PLAYBACK_LOOPING = "Playback looping "
internal const val PREPARE_VIDEO = "IgVideoPlayerImpl.prepareVideo autoPlay:"
internal val SEEK = listOf("clips_viewer", "resume")
internal val STOP = listOf("clips_pip", "ig_text")

/**
 * The labels of IgVideoSource's debug dump, each written just after the field it names is read:
 * the media ID, the product type and whether it's sponsored.
 */
internal const val MEDIA_ID = "MEDIA_ID"
internal const val PRODUCT_TYPE_LABEL = "PRODUCT_TYPE"
internal const val IS_SPONSORED = "IS_SPONSORED"
internal val SOURCE_DUMP = listOf(MEDIA_ID, PRODUCT_TYPE_LABEL, IS_SPONSORED)

/** Instagram's product type, which Redex leaves under its own name. */
internal const val PRODUCT_TYPE = "Lcom/instagram/model/mediatype/ProductType;"

/** The signed-in account's session, a kept name, and its user ID field, kept too. */
internal const val RESUME_SESSION = "Lcom/instagram/common/session/UserSession;"
internal const val RESUME_USER_ID = "userId"

/** Where UserSession lets an account's session go, and its flag for a sign-out, both kept names. */
internal const val RESUME_END_SESSION = "completeEndSession"
internal const val RESUME_LOGGED_OUT = "isLoggedOut"

/** The position reader answers 0 past a day, so it holds this literal and the length reader doesn't. */
private const val DAY_MS = 86_400_000
private const val STRING = "Ljava/lang/String;"
private const val OBJECT = "Ljava/lang/Object;"

/**
 * A long video picks up where it was left.
 *
 * IgVideoPlayerImpl, the player behind feed videos and reels, tells the extension when playback
 * starts, when it's paused or stopped, when it's given a video, when it seeks, and when a video
 * plays to its end or loops. The extension's stubs are filled with the player's position and
 * length readers, its seek, the IgVideoSource it plays, and that source's media ID, product type and
 * sponsored flag. The extension decides the rest; see its ResumePlayback.
 *
 * UserSession's completeEndSession tells the extension when an account's session ends, at an
 * account switch or a sign-out, with stubs for the player's session, that session's user ID and its
 * isLoggedOut flag, so the extension drops that account's waiting resumes and, at a sign-out, its
 * points, and a signed-out session's players save nothing as they let go.
 *
 * Everything is found before anything changes, so a build that differs stops the patch naming
 * what it couldn't find, and nothing is half done.
 */
@Suppress("unused")
val resumeLongVideosPatch = bytecodePatch(
    name = "Resume long videos",
    description = "A video or reel longer than two minutes picks up where you left off the next time you play it " +
        "on the same account. Live videos and ads start as usual. Starts off. Turn it on in HushGram settings > " +
        "Playback.",
    default = true,
) {
    category("Playback")
    dependsOn(settingsPatch)
    compatibleWith(*AppCompatibilities.instagram())
    dependsOn(instagramExtensionPatch)

    execute {
        requireStatusMethod("resumeLongVideos")
        resumeLongVideos()
        enableStatus("resumeLongVideos")
    }
}

/** Everything the patch needs of IgVideoPlayerImpl and IgVideoSource, found and checked before any change. */
internal class ResumePlayer(
    val player: ClassDef,
    val started: Method,
    val pause: Method,
    val stop: Method,
    val bind: Method,
    val seek: Method,
    val completed: Method,
    val looping: Method,
    val position: Method,
    val length: Method,
    /** The player's hold on what it plays, and that holder's IgVideoSource. */
    val holder: FieldReference,
    val source: FieldReference,
    val mediaId: FieldReference,
    val productType: FieldReference,
    val sponsored: FieldReference,
    /** The account the player was made for: its UserSession field, and that session's user ID. */
    val session: FieldReference,
    val userId: FieldReference,
    /** Where UserSession lets the session go, and its flag saying the account signed out. */
    val sessionEnd: Method,
    val loggedOut: FieldReference,
)

internal fun BytecodePatchContext.resumeLongVideos() {
    val found = findResumePlayer()
    val stubs = resumeStubs()

    val player = mutableClassDefBy(found.player.type)
    fun mutable(method: Method): MutableMethod = player.methods.single {
        it.name == method.name && it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString) &&
            it.returnType == method.returnType
    }
    mutable(found.started).addInstruction(0, "invoke-static/range { p0 .. p0 }, $STARTED")
    // The pause and the stop each take Instagram's reason first, which the extension reads to
    // tell a seek's pause from a real one.
    mutable(found.pause).addInstruction(0, "invoke-static/range { p0 .. p1 }, $STOPPED")
    mutable(found.stop).addInstruction(0, "invoke-static/range { p0 .. p1 }, $STOPPED")
    mutable(found.bind).addInstruction(0, "invoke-static/range { p0 .. p0 }, $REBOUND")
    mutable(found.seek).addInstruction(0, "invoke-static/range { p0 .. p1 }, $SEEKING")
    mutable(found.completed).addInstruction(0, "invoke-static/range { p0 .. p0 }, $ENDED")
    mutable(found.looping).addInstruction(0, "invoke-static/range { p0 .. p0 }, $ENDED")
    mutableClassDefBy(RESUME_SESSION).methods.single {
        it.name == found.sessionEnd.name && it.parameterTypes.isEmpty() && it.returnType == "V"
    }.addInstruction(0, "invoke-static/range { p0 .. p0 }, $SESSION_ENDED")

    stubs.fill(found)
}

/**
 * IgVideoPlayerImpl is the class of playInternal, which Tap to play finds by [PLAY_INTERNAL].
 * Each hooked method is its one instance method of its shape holding its strings; the pause is the
 * one (String) method the seek calls on its own player. The position reader is the int reader
 * holding [DAY_MS], and the length reader the other one, which narrows a long.
 */
internal fun BytecodePatchContext.findResumePlayer(): ResumePlayer {
    val internals = mutableListOf<Method>()
    val dumps = mutableListOf<Method>()
    classesHolding(PLAY_INTERNAL).forEach { classDef -> classDef.methods.filterTo(internals) { PLAY_INTERNAL in it.strings() } }
    classesHolding(*SOURCE_DUMP.toTypedArray()).forEach { classDef ->
        classDef.methods.filterTo(dumps) { it.strings().containsAll(SOURCE_DUMP) }
    }
    val playInternal = internals.singleOrNull()
        ?: throw PatchException("$PATCH: expected one method holding \"$PLAY_INTERNAL\", found ${internals.size}")
    val player = classDefBy(playInternal.definingClass)

    fun single(what: String, found: List<Method>): Method = found.singleOrNull()
        ?: throw PatchException("$PATCH: expected one $what in ${player.type}, found ${found.size}")
    fun instance(parameters: List<String>?, returns: String, filter: (Method) -> Boolean): List<Method> =
        player.methods.filter { method ->
            !AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation != null && method.returnType == returns &&
                (parameters == null || method.parameterTypes.map(Any::toString) == parameters) && filter(method)
        }

    val started = single("(long, long) callback holding \"$PLAYBACK_STARTED\"",
        instance(listOf("J", "J"), "V") { PLAYBACK_STARTED in it.strings() })
    val completed = single("one-argument callback holding \"$PLAYBACK_COMPLETED\"",
        instance(null, "V") { it.parameterTypes.size == 1 && PLAYBACK_COMPLETED in it.strings() })
    val looping = single("callback holding \"$PLAYBACK_LOOPING\"",
        instance(emptyList(), "V") { PLAYBACK_LOOPING in it.strings() })
    val bind = single("one-argument bind holding \"$PREPARE_VIDEO\"",
        instance(null, "V") { it.parameterTypes.size == 1 && PREPARE_VIDEO in it.strings() })
    val seek = single("(int, boolean, boolean) seek holding $SEEK",
        instance(listOf("I", "Z", "Z"), "V") { it.strings().containsAll(SEEK) })
    val stop = single("(String, boolean) stop holding $STOP",
        instance(listOf(STRING, "Z"), "V") { it.strings().containsAll(STOP) })

    val pauses = seek.code().mapNotNull { instruction ->
        instruction.methodReference()?.takeIf {
            instruction.opcode == Opcode.INVOKE_VIRTUAL && it.definingClass == player.type && it.returnType == "V" &&
                it.parameterTypes.map(Any::toString) == listOf(STRING)
        }?.name
    }.distinct()
    val pause = single("(String) pause the seek calls", instance(listOf(STRING), "V") { it.name in pauses })

    val readers = instance(emptyList(), "I") { true }
    val position = single("int reader holding $DAY_MS", readers.filter { reader -> reader.code().any { it.loads(DAY_MS) } })
    val length = single("int reader narrowing a long", readers.filter { reader ->
        reader != position && reader.code().any { it.opcode == Opcode.LONG_TO_INT }
    })

    val dump = dumps.singleOrNull()
        ?: throw PatchException("$PATCH: expected one method holding $SOURCE_DUMP, found ${dumps.size}")
    val source = dump.parameterTypes.firstOrNull()?.toString()
        ?: throw PatchException("$PATCH: ${dump.definingClass}->${dump.name}, IgVideoSource's dump, takes nothing")
    val sourceClass = classDefByOrNull(source)
        ?: throw PatchException("$PATCH: IgVideoSource $source isn't in this build")
    val labelled = dump.labelledReads(source)
    val mediaId = labelled.field(MEDIA_ID, dump, STRING)
    val productType = labelled.field(PRODUCT_TYPE_LABEL, dump, PRODUCT_TYPE)
    val sponsored = labelled.field(IS_SPONSORED, dump, "Z")

    val (holder, sourceField) = player.sourcePath(sourceClass.type, mediaId)

    // Whose points the player reads and writes: the UserSession its constructor is given, and the
    // session's user ID. The extension keeps each account's points apart by it.
    val sessions = player.fields.filter { it.type == RESUME_SESSION && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val session = sessions.singleOrNull()
        ?: throw PatchException("$PATCH: expected one $RESUME_SESSION field in ${player.type}, found ${sessions.size}")
    val sessionClass = classDefByOrNull(RESUME_SESSION)
        ?: throw PatchException("$PATCH: $RESUME_SESSION isn't in this build")
    val userId = sessionClass.fields.singleOrNull { it.name == RESUME_USER_ID && it.type == STRING }
        ?: throw PatchException("$PATCH: $RESUME_SESSION has no $RESUME_USER_ID:$STRING")
    // Where Instagram lets a session go, at an account switch and at a sign-out, and the flag that
    // tells the two apart. The extension forgets that account's waiting resumes, and its points at a sign-out.
    val sessionEnd = sessionClass.methods.singleOrNull {
        it.name == RESUME_END_SESSION && it.parameterTypes.isEmpty() && it.returnType == "V" &&
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.implementation != null
    } ?: throw PatchException("$PATCH: $RESUME_SESSION has no $RESUME_END_SESSION()V")
    val loggedOut = sessionClass.fields.singleOrNull { it.name == RESUME_LOGGED_OUT && it.type == "Z" }
        ?: throw PatchException("$PATCH: $RESUME_SESSION has no $RESUME_LOGGED_OUT:Z")

    // The extension's stubs reach these from outside Instagram's packages.
    val holderClass = classDefByOrNull(holder.type)
        ?: throw PatchException("$PATCH: ${holder.type}, what the player plays, isn't in this build")
    listOf(player, holderClass, sourceClass, sessionClass).forEach { reachable ->
        if (!AccessFlags.PUBLIC.isSet(reachable.accessFlags)) {
            throw PatchException("$PATCH: ${reachable.type} isn't public, so the extension can't reach it")
        }
    }
    listOf(position, length, seek).forEach { method ->
        if (!AccessFlags.PUBLIC.isSet(method.accessFlags)) {
            throw PatchException("$PATCH: ${player.type}->${method.name} isn't public, so the extension can't call it")
        }
    }
    listOf(player to holder, holderClass to sourceField, sourceClass to mediaId, sourceClass to productType,
        sourceClass to sponsored, player to session, sessionClass to userId, sessionClass to loggedOut,
    ).forEach { (owner, field) ->
        val declared = owner.fields.singleOrNull { it.name == field.name && it.type == field.type }
            ?: throw PatchException("$PATCH: ${owner.type} doesn't declare ${field.name}:${field.type}")
        if (!AccessFlags.PUBLIC.isSet(declared.accessFlags) || AccessFlags.STATIC.isSet(declared.accessFlags)) {
            throw PatchException("$PATCH: ${owner.type}->${field.name} isn't a public instance field, so the extension can't read it")
        }
    }

    return ResumePlayer(player, started, pause, stop, bind, seek, completed, looping, position, length,
        holder, sourceField, mediaId, productType, sponsored, session, userId, sessionEnd, loggedOut)
}

/**
 * The fields of [source] that [this], IgVideoSource's dump, reads between one label and the next,
 * by the label written after them.
 */
private fun Method.labelledReads(source: String): Map<String, List<FieldReference>> {
    val reads = mutableMapOf<String, List<FieldReference>>()
    var since = mutableListOf<FieldReference>()
    for (instruction in code()) {
        val reference = (instruction as? ReferenceInstruction)?.reference
        if (reference is StringReference &&
            (instruction.opcode == Opcode.CONST_STRING || instruction.opcode == Opcode.CONST_STRING_JUMBO)
        ) {
            reads.putIfAbsent(reference.string, since.distinctBy { it.toString() })
            since = mutableListOf()
        } else if (reference is FieldReference && instruction.opcode.name.startsWith("iget") && reference.definingClass == source) {
            since += reference
        }
    }
    return reads
}

private fun Map<String, List<FieldReference>>.field(label: String, dump: Method, type: String): FieldReference {
    val fields = get(label).orEmpty()
    val field = fields.singleOrNull()
        ?: throw PatchException("$PATCH: ${dump.definingClass}->${dump.name} reads ${fields.size} IgVideoSource fields before \"$label\", expected one")
    if (field.type != type) {
        throw PatchException("$PATCH: the IgVideoSource field before \"$label\" is ${field.name}:${field.type}, not a $type")
    }
    return field
}

/**
 * How [this], the player, reaches the IgVideoSource it plays: its media ID reader reads a field of
 * its own, that field's IgVideoSource, and [mediaId] from it, and returns it. Exactly one such path.
 */
private fun ClassDef.sourcePath(source: String, mediaId: FieldReference): Pair<FieldReference, FieldReference> {
    val paths = methods.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.parameterTypes.isEmpty() && it.returnType == STRING
    }.mapNotNull { reader ->
        val code = reader.code()
        val reads = code.filter { it.opcode == Opcode.IGET_OBJECT }.map { it to (it as ReferenceInstruction).reference as FieldReference }
        if (reads.size != 3 || code.none { it.opcode == Opcode.RETURN_OBJECT }) return@mapNotNull null
        val (first, holder) = reads[0]
        val sourceField = reads[1].second
        val id = reads[2].second
        val fromThis = (first as TwoRegisterInstruction).registerB == reader.localRegisterCount()
        if (!fromThis || holder.definingClass != type || sourceField.definingClass != holder.type ||
            sourceField.type != source || id.toString() != mediaId.toString()
        ) {
            null
        } else {
            holder to sourceField
        }
    }.distinctBy { (holder, sourceField) -> "$holder $sourceField" }
    return paths.singleOrNull()
        ?: throw PatchException("$PATCH: expected one way from $type to its IgVideoSource's ${mediaId.name}, found ${paths.size}")
}

/** The extension's stubs, found before anything changes, and the step that fills them. */
private class ResumeStubs(
    val position: MutableMethod,
    val duration: MutableMethod,
    val videoSource: MutableMethod,
    val videoId: MutableMethod,
    val productType: MutableMethod,
    val sponsored: MutableMethod,
    val seekPlayer: MutableMethod,
    val playerSession: MutableMethod,
    val sessionUserId: MutableMethod,
    val sessionLoggedOut: MutableMethod,
) {
    fun fill(found: ResumePlayer) {
        val player = found.player.type
        listOf(position to found.position, duration to found.length).forEach { (stub, reader) ->
            stub.addInstructionsWithLabels(
                0,
                """
                    check-cast p0, $player
                    invoke-virtual/range { p0 .. p0 }, $player->${reader.name}()I
                    move-result p0
                    return p0
                """,
            )
        }
        videoSource.addInstructionsWithLabels(
            0,
            """
                check-cast p0, $player
                iget-object p0, p0, ${found.holder}
                if-eqz p0, :none
                iget-object p0, p0, ${found.source}
                :none
                return-object p0
            """,
        )
        val source = found.source.type
        listOf(videoId to found.mediaId, productType to found.productType).forEach { (stub, field) ->
            stub.addInstructionsWithLabels(
                0,
                """
                    check-cast p0, $source
                    iget-object p0, p0, $field
                    return-object p0
                """,
            )
        }
        sponsored.addInstructionsWithLabels(
            0,
            """
                check-cast p0, $source
                iget-boolean p0, p0, ${found.sponsored}
                return p0
            """,
        )
        playerSession.addInstructionsWithLabels(
            0,
            """
                check-cast p0, $player
                iget-object p0, p0, ${found.session}
                return-object p0
            """,
        )
        sessionUserId.addInstructionsWithLabels(
            0,
            """
                check-cast p0, $RESUME_SESSION
                iget-object p0, p0, ${found.userId}
                return-object p0
            """,
        )
        sessionLoggedOut.addInstructionsWithLabels(
            0,
            """
                check-cast p0, $RESUME_SESSION
                iget-boolean p0, p0, ${found.loggedOut}
                return p0
            """,
        )
        // The stub's own registers are its four parameters, p0 to p3, all below v16, so the
        // plain invoke names them.
        seekPlayer.addInstructionsWithLabels(
            0,
            """
                check-cast p0, $player
                invoke-virtual { p0, p1, p2, p3 }, $player->${found.seek.name}(IZZ)V
                const/4 p0, 0x1
                return p0
            """,
        )
    }
}

private fun BytecodePatchContext.resumeStubs(): ResumeStubs {
    val extension = mutableClassDefBy(RESUME_PLAYBACK)
    fun stub(name: String, parameters: List<String>, returns: String): MutableMethod = extension.methods.singleOrNull {
        it.name == name && it.returnType == returns && AccessFlags.STATIC.isSet(it.accessFlags) &&
            it.parameterTypes.map(Any::toString) == parameters
    } ?: throw PatchException("$PATCH: $RESUME_PLAYBACK has no static $returns $name(${parameters.joinToString("")})")

    return ResumeStubs(
        position = stub("position", listOf(OBJECT), "I"),
        duration = stub("duration", listOf(OBJECT), "I"),
        videoSource = stub("videoSource", listOf(OBJECT), OBJECT),
        videoId = stub("videoId", listOf(OBJECT), STRING),
        productType = stub("productType", listOf(OBJECT), OBJECT),
        sponsored = stub("sponsored", listOf(OBJECT), "Z"),
        seekPlayer = stub("seekPlayer", listOf(OBJECT, "I", "Z", "Z"), "Z"),
        playerSession = stub("playerSession", listOf(OBJECT), OBJECT),
        sessionUserId = stub("sessionUserId", listOf(OBJECT), STRING),
        sessionLoggedOut = stub("sessionLoggedOut", listOf(OBJECT), "Z"),
    )
}

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Method.strings(): Set<String> = code().mapNotNull { instruction ->
    if (instruction.opcode != Opcode.CONST_STRING && instruction.opcode != Opcode.CONST_STRING_JUMBO) null
    else ((instruction as ReferenceInstruction).reference as StringReference).string
}.toSet()

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.loads(value: Int): Boolean =
    (opcode == Opcode.CONST || opcode == Opcode.CONST_HIGH16) && (this as NarrowLiteralInstruction).narrowLiteral == value
