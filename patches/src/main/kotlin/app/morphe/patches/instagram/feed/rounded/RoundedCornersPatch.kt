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
import app.morphe.patches.instagram.feed.carousel.REBOUND_VIEW_PAGER
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
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference

private const val PATCH = "Rounded post corners"
internal const val ROUNDED_CORNERS = "$EXTENSION_PACKAGE/feed/RoundedCorners;"
internal const val ROUNDED_MADE = "$ROUNDED_CORNERS->made(Ljava/lang/Object;)V"
internal const val ROUNDED_TARGETS = "$EXTENSION_PACKAGE/feed/RoundedTargets;"
internal const val PEEK_BUFFER = "$EXTENSION_PACKAGE/feed/CarouselPeek;->buffer(Ljava/lang/Object;I)I"

private const val ANY = "Ljava/lang/Object;"

/** The pager's buffer setter: it keeps the buffer, then makes its two page slot arrays max(1, 2 * buffer) + 1 long. */
private val BUFFER_SHAPE = listOf(
    Opcode.IPUT, Opcode.MUL_INT_LIT8, Opcode.CONST_4, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.ADD_INT_LIT8,
    Opcode.NEW_ARRAY, Opcode.IPUT_OBJECT, Opcode.NEW_ARRAY, Opcode.IPUT_OBJECT,
)

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
 * its pages through the pager's public setters. Its one call in Instagram's code goes first in the
 * pager's buffer setter, which Instagram's bind calls with 0: a pager Peek gave a buffer of one page
 * keeps it, since slot arrays sized for 0 under the page count it laid out with 1 crashed the app.
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
        val site = findRoundedSite()
        val buffer = findPagerBuffer()
        applyRoundedCorners(site)
        applyPagerBuffer(buffer)
        enableStatus("roundedPostCorners")
    }
}

/** The pager's buffer setter, and the parameter holding the buffer, as its pN number. */
internal class BufferSite(val set: Method, val pages: Int)

private fun Method.parameterWords() = parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }

/**
 * The pager's one instance method starting with [BUFFER_SHAPE]: an int parameter kept in an int field
 * of the pager, doubled, raised to at least 1 by Math.max and one added, then two int arrays that long
 * kept in two fields of the pager. The call goes first, so nothing may jump to its start, and the call
 * names the pager and the parameter in the short form, which reaches v15.
 */
internal fun BytecodePatchContext.findPagerBuffer(): BufferSite {
    val pager = classDefByOrNull(REBOUND_VIEW_PAGER) ?: refuse("no $REBOUND_VIEW_PAGER, Instagram's carousel pager")
    val sites = pager.methods.mapNotNull(::bufferSite)
    val site = sites.singleOrNull()
        ?: refuse("expected one method of $REBOUND_VIEW_PAGER keeping its buffer and sizing its page slots, found ${sites.size}")
    if (0 in site.set.jumpTargets()) refuse("something in $REBOUND_VIEW_PAGER->${site.set.name}, the buffer setter, jumps to its start")
    val self = site.set.implementation!!.registerCount - 1 - site.set.parameterWords()
    if (self + site.pages > 15) refuse("$REBOUND_VIEW_PAGER->${site.set.name}, the buffer setter, keeps the buffer past v15")
    return site
}

private fun bufferSite(method: Method): BufferSite? {
    if (AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "V") return null
    val implementation = method.implementation ?: return null
    val code = implementation.instructions.take(BUFFER_SHAPE.size)
    if (code.map { it.opcode } != BUFFER_SHAPE) return null
    val self = implementation.registerCount - 1 - method.parameterWords()
    fun field(at: Int) = (code[at] as ReferenceInstruction).reference as FieldReference
    fun owner(at: Int) = (code[at] as TwoRegisterInstruction).registerB == self && field(at).definingClass == REBOUND_VIEW_PAGER

    val kept = code[0] as TwoRegisterInstruction
    if (!owner(0) || field(0).type != "I") return null
    val pages = kept.registerA - self
    var word = 1
    val parameter = method.parameterTypes.map(Any::toString).firstOrNull { type ->
        (word == pages).also { word += if (type == "J" || type == "D") 2 else 1 }
    }
    if (parameter != "I") return null

    val doubled = code[1] as TwoRegisterInstruction
    if (doubled.registerB != kept.registerA || (code[1] as NarrowLiteralInstruction).narrowLiteral != 2) return null
    if ((code[2] as NarrowLiteralInstruction).narrowLiteral != 1) return null
    if (((code[3] as ReferenceInstruction).reference).toString() != "Ljava/lang/Math;->max(II)I") return null
    if ((code[5] as NarrowLiteralInstruction).narrowLiteral != 1) return null
    val arrays = listOf(6, 8).map { ((code[it] as ReferenceInstruction).reference as TypeReference).type }
    if (arrays != listOf("[I", "[I")) return null
    if (!owner(7) || !owner(9) || field(7).type != "[I" || field(9).type != "[I" || field(7).name == field(9).name) return null
    return BufferSite(method, pages)
}

/** First thing in the buffer setter: the pager and the buffer go to [PEEK_BUFFER], whose answer is the buffer kept. */
internal fun BytecodePatchContext.applyPagerBuffer(site: BufferSite) {
    val set: MutableMethod = mutableClassDefBy(REBOUND_VIEW_PAGER).methods.single {
        it.name == site.set.name && it.parameterTypes.map(Any::toString) == site.set.parameterTypes.map(Any::toString)
    }
    set.addInstructions(
        0,
        """
            invoke-static { p0, p${site.pages} }, $PEEK_BUFFER
            move-result p${site.pages}
        """,
    )
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
