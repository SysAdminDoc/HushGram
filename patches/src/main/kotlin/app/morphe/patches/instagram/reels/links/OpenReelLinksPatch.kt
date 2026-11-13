/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.links

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.stringLoadedAt
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.reels.sound.VIEWER_CONFIG
import app.morphe.patches.instagram.reels.sound.VIEWER_SOURCE
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference

private const val PATCH = "Open reel links on that reel"
internal const val REEL_LINKS = "$EXTENSION_PACKAGE/reels/ReelLinks;"
internal const val REEL_LINKS_SKIP = "$REEL_LINKS->skipsCachedReels(Ljava/lang/Object;)I"
internal const val REEL_LINKS_HOLD =
    "$REEL_LINKS->holdsReels(Ljava/lang/Object;Ljava/lang/Object;Ljava/lang/Object;IIIII)I"
internal const val REEL_LINKS_IN = "$REEL_LINKS->linkedReelIn(Ljava/lang/Object;)V"
internal const val REEL_LINKS_COMMENT = "$REEL_LINKS->sharedCommentOpened(Ljava/lang/String;)V"

/**
 * The query parameters a tap on a comment shared in a chat reads from the comment's link, the
 * comment and then what it was on, and the type that opens a Reels viewer. The build keeps them.
 */
internal const val COMMENT_ID = "comment_id"
internal const val COMMENT_MEDIA_TYPE = "media_type"
internal const val COMMENT_ON_REEL = "clips"
private const val QUERY_PARAMETER = "Landroid/net/Uri;->getQueryParameter(Ljava/lang/String;)Ljava/lang/String;"

/**
 * The flag name Instagram's Reels viewer loads as it builds the controller playing reels it kept on
 * the phone, right past the config's check for turning that off. The build keeps it.
 */
internal const val FLASH_UI_STATE = "android_purge_26_q3_ClipsFlashMediaUseCase_getUiState"

/**
 * The line Instagram logs as a chunk of reels from the network goes into a viewer, a little before
 * the call adding it, which ends the method. The build keeps it.
 */
internal const val CHUNK_ADDED = "Add to UI"

/**
 * The flag name the viewer's adapter loads first as it inserts reels at a position, which is how a
 * link's viewer puts the linked reel in. The build keeps it.
 */
internal const val ITEMS_INSERTED = "android_purge_26_q2_ClipsViewerAdapter_insertClipsItemsAtPosition"

/** The call adding a chunk: the head item, the reels and five flags (first page, refreshing, loading, more pages, forward). */
private val CHUNK_PARAMETERS = listOf("Ljava/util/List;", "Z", "Z", "Z", "Z", "Z")

/** The adapter's insert: the reels, then the position. */
private val INSERT_PARAMETERS = listOf("Ljava/util/List;", "I")

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Opens a reel link on the reel it points to (#606).
 *
 * As a Reels viewer is set up, Instagram builds a controller that fills it from reels it kept on the
 * phone, so something plays at once. For a viewer opened from a link it puts those kept reels ahead
 * of the linked one, which shows as a blank placeholder while it loads, so a different reel plays
 * first and the linked one turns up a swipe or two down. The viewer's config has a flag that skips
 * the controller, which Instagram checks just before building it. The patch ORs the extension's
 * answer for the viewer's source into that check: a yes for a link source skips the controller and
 * the viewer opens on the linked reel. Every other source keeps Instagram's own.
 *
 * When Instagram is already running, a chunk of reels the viewer asked for alongside the linked one
 * usually lands first, and the linked reel then goes in at the reel on screen or one past it with
 * no scroll. So the patch also asks the extension before the call adding each chunk, where a yes
 * returns instead (the extension adds the chunk later with the same arguments), and tells it as the
 * adapter's insert starts, which for a link's viewer is the linked reel going in.
 *
 * A comment shared in a chat is a link too (#662): tapping one on a reel opens a viewer from the chat
 * with only the reel's id, and the same chunk can land first. The patch tells the extension as the
 * tap reads what the comment was on, and the next chat viewer holds its chunks like a link's.
 *
 * Found and checked before anything changes: a build that differs stops the patch naming what it
 * couldn't find.
 */
@Suppress("unused")
val openReelLinksPatch = bytecodePatch(
    name = "Open reel links on that reel",
    description = "A reel link opens on the reel it points to, instead of another reel Instagram kept on your " +
        "phone playing first. Starts on. Turn it off in HushGram settings > Reels.",
    default = true,
) {
    category("Fixes")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("reelLinks")
        val links = findReelLinksSite()
        val chunk = findReelChunkSite()
        val insert = findLinkInsertSite(chunk.adapter)
        val comment = findSharedCommentSite()
        applyReelLinks(links)
        applyReelChunk(chunk)
        applyLinkInsert(insert)
        applySharedComment(comment)
        enableStatus("reelLinks")
    }
}

/**
 * The method setting up the viewer's kept-reels controller, the index of the config's check (the
 * `if-nez` reading the flag), the registers holding the flag and the config, the config's source
 * field, and a local the hook borrows there.
 */
internal class ReelLinksSite(
    val method: Method,
    val index: Int,
    val flag: Int,
    val config: Int,
    val source: FieldReference,
    val scratch: Int,
)

internal fun BytecodePatchContext.findReelLinksSite(): ReelLinksSite {
    val methods = classesLoadingString(FLASH_UI_STATE).flatMap { it.methods }.filter { loadsString(it, FLASH_UI_STATE) }
    // The config's boolean flag, checked by an if-nez that falls through to the controller's flag name.
    val sites = methods.flatMap { method ->
        val code = method.code()
        code.indices.filter { at ->
            val read = code[at]
            val field = (read as? ReferenceInstruction)?.reference as? FieldReference
            val check = code.getOrNull(at + 1)
            read.opcode == Opcode.IGET_BOOLEAN && field?.definingClass == VIEWER_CONFIG && field.type == "Z" &&
                check?.opcode == Opcode.IF_NEZ && (check as OneRegisterInstruction).registerA == (read as TwoRegisterInstruction).registerA &&
                (at + 2..at + 3).any { it in code.indices && stringLoadedAt(code, it) == FLASH_UI_STATE }
        }.map { method to it }
    }
    val (method, read) = sites.singleOrNull()
        ?: refuse("expected one $VIEWER_CONFIG flag checked right before \"$FLASH_UI_STATE\", found ${sites.size}")
    val where = "${method.definingClass}->${method.name}"
    val index = read + 1
    if (index in method.jumpTargets()) refuse("something in $where jumps to the config's check")
    val code = method.code()
    val flag = (code[read] as TwoRegisterInstruction).registerA
    val config = (code[read] as TwoRegisterInstruction).registerB
    // or-int/2addr and iget-object name four-bit registers.
    if (flag > 15 || config > 15) refuse("$where checks the config's flag in v$flag of v$config, past v15")

    val configClass = classDefByOrNull(VIEWER_CONFIG) ?: refuse("Instagram has no $VIEWER_CONFIG")
    val sources = configClass.fields.filter { it.type == VIEWER_SOURCE && !AccessFlags.STATIC.isSet(it.accessFlags) }
    val source = sources.singleOrNull() ?: refuse("expected one $VIEWER_SOURCE field in $VIEWER_CONFIG, found ${sources.size}")
    if (!AccessFlags.PUBLIC.isSet(source.accessFlags) || !AccessFlags.PUBLIC.isSet(configClass.accessFlags)) {
        refuse("$VIEWER_CONFIG->${source.name} can't be read from $where")
    }
    val scratch = method.freeLocalsAt(PATCH, index, 1, except = listOf(flag, config)).single()
    return ReelLinksSite(method, index, flag, config, ImmutableFieldReference(VIEWER_CONFIG, source.name, VIEWER_SOURCE), scratch)
}

/**
 * In front of the config's check: read the viewer's source and OR [REEL_LINKS_SKIP]'s answer for it
 * into the flag, so a yes takes the branch Instagram takes when the config turns the controller off.
 */
internal fun BytecodePatchContext.applyReelLinks(site: ReelLinksSite) {
    val s = site.scratch
    mutable(site.method).addInstructions(
        site.index,
        """
            iget-object v$s, v${site.config}, ${site.source}
            invoke-static { v$s }, $REEL_LINKS_SKIP
            move-result v$s
            or-int/2addr v${site.flag}, v$s
        """,
    )
}

/**
 * The method handing a chunk of reels to the viewer, the index of the call adding it (a range call
 * the method returns right after), the call's first register (the adapter, then the head item, the
 * reels and the five flags), the adapter's class, and a local the hook borrows there.
 */
internal class ReelChunkSite(
    val method: Method,
    val index: Int,
    val first: Int,
    val adapter: String,
    val scratch: Int,
)

internal fun BytecodePatchContext.findReelChunkSite(): ReelChunkSite {
    val methods = classesLoadingString(CHUNK_ADDED).flatMap { it.methods }.filter { loadsString(it, CHUNK_ADDED) }
    val sites = methods.flatMap { method ->
        val code = method.code()
        code.indices.filter { at ->
            val call = (code[at] as? ReferenceInstruction)?.reference as? MethodReference
            code[at].opcode == Opcode.INVOKE_VIRTUAL_RANGE && call != null && call.addsAChunk() &&
                code.getOrNull(at + 1)?.opcode == Opcode.RETURN_VOID
        }.map { method to it }
    }
    val (method, index) = sites.singleOrNull()
        ?: refuse("expected one call adding a chunk of reels, then a return, in a method logging \"$CHUNK_ADDED\", found ${sites.size}")
    val where = "${method.definingClass}->${method.name}"
    if (index in method.jumpTargets()) refuse("something in $where jumps to the call adding the chunk")
    val call = method.code()[index] as RegisterRangeInstruction
    val first = call.startRegister
    val arguments = (first until first + call.registerCount).toList()
    val scratch = method.freeLocalsAt(PATCH, index, 1, except = arguments).single()
    return ReelChunkSite(method, index, first, ((call as ReferenceInstruction).reference as MethodReference).definingClass, scratch)
}

/**
 * In front of the call adding the chunk: ask [REEL_LINKS_HOLD] with the call's own registers, and
 * return when it holds the chunk. A no goes on to Instagram's call.
 */
internal fun BytecodePatchContext.applyReelChunk(site: ReelChunkSite) {
    val method = mutable(site.method)
    val s = site.scratch
    method.addInstructionsWithLabels(
        site.index,
        """
            invoke-static/range { v${site.first} .. v${site.first + 7} }, $REEL_LINKS_HOLD
            move-result v$s
            if-eqz v$s, :add
            return-void
        """,
        ExternalLabel("add", method.getInstruction(site.index)),
    )
}

/** The adapter's method inserting reels at a position, which it marks by loading [ITEMS_INSERTED]. */
internal fun BytecodePatchContext.findLinkInsertSite(adapter: String): Method {
    val type = classDefByOrNull(adapter) ?: refuse("Instagram has no $adapter")
    val inserts = type.methods.filter {
        !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" &&
            it.parameterTypes.map(Any::toString) == INSERT_PARAMETERS && loadsString(it, ITEMS_INSERTED)
    }
    val method = inserts.singleOrNull()
        ?: refuse("expected one $adapter method inserting reels at a position (\"$ITEMS_INSERTED\"), found ${inserts.size}")
    if (0 in method.jumpTargets()) refuse("something in $adapter->${method.name} jumps to its first instruction")
    return method
}

/** At the top of the adapter's insert, while its first parameter register still holds the adapter: tell [REEL_LINKS_IN]. */
internal fun BytecodePatchContext.applyLinkInsert(method: Method) {
    val self = method.selfRegister()
    mutable(method).addInstructions(0, "invoke-static/range { v$self .. v$self }, $REEL_LINKS_IN")
}

/**
 * The method handling a tap on a comment shared in a chat, the index right after it reads what the
 * comment was on (where it next loads [COMMENT_ON_REEL] to compare), and the register holding it.
 */
internal class SharedCommentSite(val method: Method, val index: Int, val type: Int)

internal fun BytecodePatchContext.findSharedCommentSite(): SharedCommentSite {
    val methods = classesLoadingString(COMMENT_ID).flatMap { it.methods }
        .filter { loadsString(it, COMMENT_ID) && loadsString(it, COMMENT_MEDIA_TYPE) }
    // const-string "media_type", getQueryParameter, move-result-object, then const-string "clips".
    val sites = methods.flatMap { method ->
        val code = method.code()
        code.indices.filter { at ->
            stringLoadedAt(code, at) == COMMENT_MEDIA_TYPE &&
                (code.getOrNull(at + 1) as? ReferenceInstruction)?.reference?.toString() == QUERY_PARAMETER &&
                code.getOrNull(at + 2)?.opcode == Opcode.MOVE_RESULT_OBJECT &&
                stringLoadedAt(code, at + 3) == COMMENT_ON_REEL
        }.map { method to it + 3 }
    }
    val (method, index) = sites.singleOrNull()
        ?: refuse("expected one tap reading \"$COMMENT_MEDIA_TYPE\" of a shared comment, then comparing it to \"$COMMENT_ON_REEL\", found ${sites.size}")
    if (index in method.jumpTargets()) refuse("something in ${method.definingClass}->${method.name} jumps to the comparison with \"$COMMENT_ON_REEL\"")
    val type = (method.code()[index - 1] as OneRegisterInstruction).registerA
    return SharedCommentSite(method, index, type)
}

/** Right after the tap reads what the shared comment was on: tell [REEL_LINKS_COMMENT] with it. */
internal fun BytecodePatchContext.applySharedComment(site: SharedCommentSite) {
    mutable(site.method).addInstructions(site.index, "invoke-static/range { v${site.type} .. v${site.type} }, $REEL_LINKS_COMMENT")
}

/** The register holding `this` as an instance method starts: the first of its parameter registers. */
internal fun Method.selfRegister(): Int {
    val parameters = parameterTypes.map { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }.sum()
    return implementation!!.registerCount - 1 - parameters
}

private fun MethodReference.addsAChunk(): Boolean {
    val parameters = parameterTypes.map(Any::toString)
    return returnType == "V" && parameters.size == 7 && parameters[0].startsWith("L") && parameters.drop(1) == CHUNK_PARAMETERS
}

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString) &&
            it.returnType == method.returnType
    }

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
