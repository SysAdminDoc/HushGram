/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.rounded

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.feed.zoom.GESTURE_DETECTOR
import app.morphe.patches.instagram.feed.zoom.MEDIA_FRAME
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference

private const val PATCH = "Rounded post corners"
internal const val ROUNDED_CORNERS = "$EXTENSION_PACKAGE/feed/RoundedCorners;"
internal const val ROUNDED_MADE = "$ROUNDED_CORNERS->made(Ljava/lang/Object;)V"
internal const val ROUNDED_TARGETS = "$EXTENSION_PACKAGE/feed/RoundedTargets;"

private const val ANY = "Ljava/lang/Object;"

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Cuts the corners of a feed post's photo, video and carousel pages.
 *
 * Instagram 450 makes one touch holder for each media frame of a feed post: the class that makes a
 * GestureDetector and keeps the post's Media and its MediaFrameLayout in instance fields (Long press
 * to zoom and Download any video find it the same way). The patch hands each new holder to the
 * extension last thing in the holder's one constructor, and the extension reads the holder's frame
 * through a stub this patch writes, and gives that frame a rounded outline. Nothing else of Instagram's
 * code changes, and the extension does the work once for each frame, when it is made, so scrolling
 * costs nothing extra.
 *
 * Rounded corners on Reels (#362) needs no change of its own: the extension rounds each reel's video
 * frame by its view id after the viewer lays out, so it comes with this patch, and Rounded corners on
 * stories (#363, #368) rounds each story's media container the same way. Peek at the next carousel
 * page (#361) is the same: the extension finds each carousel's pager above its page frame and narrows
 * its pages through the pager's public setters.
 *
 * Found and checked before anything changes: a build that differs stops the patch naming what it
 * couldn't find.
 */
@Suppress("unused")
val roundedPostCornersPatch = bytecodePatch(
    name = "Rounded post corners",
    description = "Rounds the corners of a post's photo, video and carousel pages in Home and Following, and of " +
        "reels and stories if you like, at a small or large size. Profiles and Explore stay as Instagram draws them. " +
        "Starts off. Turn it on in HushGram settings > Feed.",
) {
    category("Feed")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("roundedPostCorners")
        applyRoundedCorners(findRoundedSite())
        enableStatus("roundedPostCorners")
    }
}

/** The touch holder's one constructor, and the field of the holder that keeps its media frame. */
internal class RoundedSite(val make: Method, val frame: FieldReference)

private fun ClassDef.instanceFields() = fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }

/**
 * The feed's touch holder is the one class making a GestureDetector that keeps a MediaFrameLayout and a
 * Media in instance fields. It has to be public, and so does its one field of the frame type, since the
 * stub reads it from the extension's package. It has one constructor, where the call goes before each
 * return. The call hands over `this`, so the constructor has to be done with it: it never writes the
 * register `this` sits in, and nothing jumps to a return, where the call would be skipped.
 */
internal fun BytecodePatchContext.findRoundedSite(): RoundedSite {
    val holders = classesCalling(GESTURE_DETECTOR, "<init>").filter { classDef ->
        val types = classDef.instanceFields().map { it.type }
        MEDIA_FRAME in types && MEDIA in types
    }
    val holder = holders.singleOrNull()
        ?: refuse("expected one class making a GestureDetector that keeps a media frame and a Media, found ${holders.size}")
    if (!AccessFlags.PUBLIC.isSet(holder.accessFlags)) refuse("${holder.type} isn't public, so the extension can't read it")

    val frames = holder.instanceFields().filter { it.type == MEDIA_FRAME }
    val frame = frames.singleOrNull()
        ?: refuse("expected ${holder.type} to keep the media frame ($MEDIA_FRAME) in one field, found ${frames.size}")
    if (!AccessFlags.PUBLIC.isSet(frame.accessFlags)) refuse("${holder.type}->${frame.name}, the media frame, isn't public, so the extension can't read it")

    val makes = holder.methods.filter { it.name == "<init>" && it.implementation != null }
    val make = makes.singleOrNull() ?: refuse("expected ${holder.type} to have one constructor, found ${makes.size}")
    val code = make.implementation!!.instructions.toList()
    val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_VOID }
    if (returns.isEmpty()) refuse("${holder.type}'s constructor never returns void")
    if (returns.any { it in make.jumpTargets() }) refuse("something in ${holder.type}'s constructor jumps to a return")
    val self = make.implementation!!.registerCount - 1 - make.parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }
    if (code.any { it.writes(self) }) refuse("something in ${holder.type}'s constructor writes the register holding this")
    return RoundedSite(make, ImmutableFieldReference(frame.definingClass, frame.name, frame.type))
}

private fun Instruction.writes(register: Int): Boolean =
    this is OneRegisterInstruction && opcode.setsRegister() &&
        (registerA == register || (opcode.setsWideRegister() && registerA + 1 == register))

/** Before each return of the constructor: hand the new holder to [ROUNDED_MADE]. Then write the stub that reads the frame. */
internal fun BytecodePatchContext.applyRoundedCorners(site: RoundedSite) {
    val make: MutableMethod = mutableClassDefBy(site.make.definingClass).methods.single {
        it.name == "<init>" && it.parameterTypes.map(Any::toString) == site.make.parameterTypes.map(Any::toString)
    }
    val code = make.implementation!!.instructions.toList()
    for (at in code.indices.reversed()) {
        if (code[at].opcode == Opcode.RETURN_VOID) make.addInstructions(at, "invoke-static/range { p0 .. p0 }, $ROUNDED_MADE")
    }
    val stub = mutableClassDefBy(ROUNDED_TARGETS).methods.singleOrNull {
        it.name == "frame" && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == ANY &&
            it.parameterTypes.map(Any::toString) == listOf(ANY)
    } ?: refuse("$ROUNDED_TARGETS has no static Object frame(Object)")
    stub.addInstructions(
        0,
        """
            check-cast p0, ${site.make.definingClass}
            iget-object p0, p0, ${site.frame}
            return-object p0
        """,
    )
}
