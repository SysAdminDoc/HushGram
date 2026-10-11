/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.zoom

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.extensions.InstructionExtensions.getInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.smali.ExternalLabel
import app.morphe.patches.instagram.download.pandoGetter
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.freeLocalsAt
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.localRegisterCount
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.requireThisIntact
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Long press to zoom"
internal const val LONG_PRESS_ZOOM = "$EXTENSION_PACKAGE/feed/LongPressZoom;"
internal const val ZOOM_TOUCH = "$LONG_PRESS_ZOOM->touch(Landroid/view/MotionEvent;)V"
internal const val ZOOM_PRESS = "$LONG_PRESS_ZOOM->press(Landroid/view/View;Ljava/lang/Object;)I"

internal const val MEDIA_FRAME = "Lcom/instagram/ui/widget/framelayout/MediaFrameLayout;"
private const val MEDIA = "Lcom/instagram/feed/media/Media;"
private const val MOTION_EVENT = "Landroid/view/MotionEvent;"
internal const val GESTURE_DETECTOR = "Landroid/view/GestureDetector;"
private const val SIMPLE_LISTENER = "Landroid/view/GestureDetector\$SimpleOnGestureListener;"
private const val UPTIME = "Landroid/os/SystemClock;->uptimeMillis()J"
private const val INTEGER = "Ljava/lang/Integer;"

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Press and hold a photo in a feed to see it bigger.
 *
 * Instagram 450 handles the touches on a feed post's picture in one class for each post's media
 * frame: it keeps the post's Media and its MediaFrameLayout, makes a GestureDetector, and keeps a
 * gesture listener whose long press method starts Instagram's own long press on the post (a preview
 * or a menu, as Instagram's server settings decide). The patch hooks two places there. The frame's
 * touch method tells the extension about every touch first, so it knows where the finger went down,
 * whether it wandered past the touch slop, and when it lifts, is cancelled or a second finger joins.
 * The long press method asks the extension once Instagram has noted when the press happened, with the
 * frame and the post. A yes, given only while the switch is on, for a photo or a carousel whose
 * picture is on screen and when the finger stayed put, means the extension has shown its zoom and the
 * method returns there, so Instagram's own long press doesn't start under it. A no goes on with
 * Instagram's code exactly as it was. Pinch to zoom runs through the gesture listener's scale methods,
 * which the patch leaves alone, and taps and double taps never reach the long press method.
 *
 * The extension reads the post's media_type through a getter this patch writes into its stub.
 *
 * Found and checked before anything changes: a build that differs stops the patch naming what it
 * couldn't find.
 */
@Suppress("unused")
val longPressZoomPatch = bytecodePatch(
    name = "Long press to zoom",
    description = "Press and hold a photo in a feed to see it twice as big around your finger, and let go to " +
        "close it. Pinch to zoom still works. While its switch is on, it takes the place of Instagram's own long " +
        "press on a photo. Starts off. Turn it on in HushGram settings > Feed.",
) {
    category("Feed")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("longPressZoom")
        applyLongPressZoom(findLongPressZoomSite())
        enableStatus("longPressZoom")
    }
}

/**
 * The frame's touch method, the gesture listener's long press method, where in it the hook goes,
 * the fields it reads on the way, the two locals it borrows there, and the Media getter for
 * media_type.
 */
internal class LongPressZoomSite(
    val touch: Method,
    val press: Method,
    val index: Int,
    val holderField: FieldReference,
    val frameField: FieldReference,
    val mediaField: FieldReference,
    val scratch: List<Int>,
    val mediaType: Method,
)

/**
 * The frame's touch holder is the one class that makes a GestureDetector and keeps, in instance
 * fields, a MediaFrameLayout, a Media and a listener: a class extending
 * GestureDetector.SimpleOnGestureListener. The listener keeps the holder in one instance field. The
 * touch method is the holder's one instance method taking a MotionEvent and answering a boolean.
 * The long press method is the listener's one instance method taking two floats and returning void
 * that calls SystemClock.uptimeMillis. In it, the one iput-wide writes a long field of the holder,
 * the press time, and the hook goes right after it. The method reads the holder's frame and Media
 * fields itself, once each, which is also what proves the hook may read them.
 */
internal fun BytecodePatchContext.findLongPressZoomSite(): LongPressZoomSite {
    val holders = classesCalling(GESTURE_DETECTOR, "<init>").filter { listenerOf(it) != null }
    val holder = holders.singleOrNull()
        ?: refuse("expected one class making a GestureDetector that keeps a media frame, a Media and a gesture listener, found ${holders.size}")
    val listener = listenerOf(holder)!!

    val touches = holder.methods.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation != null && method.returnType == "Z" &&
            method.parameterTypes.map(Any::toString) == listOf(MOTION_EVENT)
    }
    val touch = touches.singleOrNull()
        ?: refuse("expected ${holder.type} to hold one touch method taking a MotionEvent, found ${touches.size}")
    if (0 in touch.jumpTargets()) refuse("something in ${holder.type}->${touch.name} jumps back to its start")

    val holderFields = listener.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) && it.type == holder.type }
    val holderField = holderFields.singleOrNull()
        ?: refuse("expected ${listener.type} to keep ${holder.type} in one field, found ${holderFields.size}")

    val presses = listener.methods.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation != null && method.returnType == "V" &&
            method.parameterTypes.map(Any::toString) == listOf("F", "F") &&
            method.code().any { it.methodReference()?.toString() == UPTIME }
    }
    val press = presses.singleOrNull()
        ?: refuse("expected ${listener.type} to hold one long press method that reads the clock, found ${presses.size}")
    val where = "${listener.type}->${press.name}"
    val code = press.code()

    val timeWrites = code.indices.filter { code[it].opcode == Opcode.IPUT_WIDE }
    val timeWrite = timeWrites.singleOrNull()
        ?: refuse("expected $where to write one long, found ${timeWrites.size}")
    val written = code[timeWrite].fieldReference()!!
    if (written.definingClass != holder.type || written.type != "J") refuse("$where writes its long into ${written.definingClass}, not ${holder.type}")
    val clock = code.indexOfFirst { it.methodReference()?.toString() == UPTIME }
    if (clock > timeWrite) refuse("$where writes its long before it reads the clock")

    fun holderRead(type: String): FieldReference {
        val reads = code.mapNotNull { instruction ->
            instruction.fieldReference()?.takeIf {
                instruction.opcode == Opcode.IGET_OBJECT && it.definingClass == holder.type && it.type == type
            }
        }
        return reads.singleOrNull() ?: refuse("expected $where to read the $type of ${holder.type} once, found ${reads.size}")
    }
    val frameField = holderRead(MEDIA_FRAME)
    val mediaField = holderRead(MEDIA)

    val index = timeWrite + 1
    if (index >= code.size) refuse("$where ends at its press time")
    if (index in press.jumpTargets()) refuse("something in $where jumps past its press time")
    press.requireThisIntact(PATCH, listOf(index))
    if (press.localRegisterCount() > 15) refuse("$where keeps this in v${press.localRegisterCount()}, past what a field read can name")
    val scratch = press.freeLocalsAt(PATCH, index, 2)

    val mediaType = pandoGetter(PATCH, MEDIA, "media_type", INTEGER)
    if (!AccessFlags.PUBLIC.isSet(classDefBy(MEDIA).accessFlags) || !AccessFlags.PUBLIC.isSet(mediaType.accessFlags)) {
        refuse("$MEDIA->${mediaType.name} isn't public, so the extension can't reach it")
    }
    return LongPressZoomSite(touch, press, index, holderField, frameField, mediaField, scratch, mediaType)
}

/** The gesture listener [holder] keeps when it also keeps a media frame and a Media, else null. */
private fun BytecodePatchContext.listenerOf(holder: ClassDef): ClassDef? {
    val types = holder.fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }.map { it.type }
    if (MEDIA_FRAME !in types || MEDIA !in types) return null
    val listeners = types.distinct().mapNotNull { type -> classDefByOrNull(type)?.takeIf { it.superclass == SIMPLE_LISTENER } }
    return listeners.singleOrNull()
}

/**
 * First in the touch method: hand the event to [ZOOM_TOUCH]. After the long press method notes the
 * press time: read the holder, its frame and its Media, and ask [ZOOM_PRESS]. A yes returns, a no
 * goes on with Instagram's own long press. Then the extension's media_type stub gets its body.
 */
internal fun BytecodePatchContext.applyLongPressZoom(site: LongPressZoomSite) {
    mutable(site.touch).addInstructions(0, "invoke-static/range { p1 .. p1 }, $ZOOM_TOUCH")

    val (holder, frame) = site.scratch
    mutable(site.press).apply {
        addInstructionsWithLabels(
            site.index,
            """
                iget-object v$holder, p0, ${site.holderField}
                iget-object v$frame, v$holder, ${site.frameField}
                iget-object v$holder, v$holder, ${site.mediaField}
                invoke-static { v$frame, v$holder }, $ZOOM_PRESS
                move-result v$holder
                if-eqz v$holder, :stock
                return-void
            """,
            ExternalLabel("stock", getInstruction(site.index)),
        )
    }

    val stub = mutableClassDefBy(LONG_PRESS_ZOOM).methods.singleOrNull {
        it.name == "mediaType" && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "I" &&
            it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;")
    } ?: refuse("$LONG_PRESS_ZOOM has no static mediaType(Object)I")
    stub.addInstructionsWithLabels(
        0,
        """
            check-cast p0, $MEDIA
            invoke-virtual { p0 }, $MEDIA->${site.mediaType.name}()$INTEGER
            move-result-object p0
            if-nez p0, :boxed
            const/4 p0, 0x0
            return p0
            :boxed
            invoke-virtual { p0 }, $INTEGER->intValue()I
            move-result p0
            return p0
        """,
    )
}

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString) &&
            it.returnType == method.returnType
    }

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.fieldReference(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference
