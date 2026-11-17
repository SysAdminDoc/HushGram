/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.download.video

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.feed.zoom.ZOOM_TOUCH
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Field
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference

private const val PATCH = "Download any video"

internal const val HOLD_DOWNLOAD = "$EXTENSION_PACKAGE/download/HoldDownload;"
internal const val HOLD_FEED_TOUCH = "$HOLD_DOWNLOAD->feedTouch(Ljava/lang/Object;Landroid/view/MotionEvent;)V"
internal const val HOLD_FEED_MADE = "$HOLD_DOWNLOAD->feedMade(Ljava/lang/Object;)V"
internal const val HOLD_REEL_TOUCH = "$HOLD_DOWNLOAD->reelTouch(Ljava/lang/Object;Landroid/view/View;Landroid/view/MotionEvent;)V"
internal const val HOLD_TARGETS = "$EXTENSION_PACKAGE/download/HoldTargets;"

/**
 * A string a reel's gesture detector loads in the method its touch listener hands every touch to, the
 * name it logs a swipe off an ad's end scene by (0DCL.A04 on 456's 386214574). 455 also traced
 * ClipsItemGestureDetector_onTouch there, which 456 dropped. A Litho part and a string pool load it
 * too, but no other instance (View, MotionEvent) boolean method.
 */
internal const val REEL_TOUCH_STRING = "end_scene_dismissal"

private const val GESTURE_DETECTOR = "Landroid/view/GestureDetector;"
private const val MEDIA_FRAME = "Lcom/instagram/ui/widget/framelayout/MediaFrameLayout;"
private const val MOTION_EVENT = "Landroid/view/MotionEvent;"
private const val VIEW = "Landroid/view/View;"
private const val ANY = "Ljava/lang/Object;"

/**
 * Where Hold to download listens, and what it reads there. A feed post's media frame hands each touch
 * to its touch holder's [feedTouch], and the holder keeps the post ([feedMedia]), its feed state
 * ([feedState], which knows the carousel page on screen) and the frame ([feedFrame]). A reel's gesture
 * detector takes each touch in [reelTouch] and keeps its reel item ([reelItem]), which keeps the post
 * ([reelMedia]).
 */
internal class HoldSites(
    val feedTouch: Method,
    val feedMake: Method,
    val feedMedia: FieldReference,
    val feedState: FieldReference,
    val feedFrame: FieldReference,
    val reelTouch: Method,
    val reelItem: FieldReference,
    val reelMedia: FieldReference,
)

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

private fun ClassDef.instanceFields(): List<Field> = fields.filter { !AccessFlags.STATIC.isSet(it.accessFlags) }

private fun Method.takes(vararg types: String) = parameterTypes.map(Any::toString) == types.toList()

/**
 * Finds [HoldSites] without changing anything, every piece by structure, so a build that differs
 * stops the patch naming what it couldn't find.
 *
 * The feed's touch holder is the one class making a GestureDetector that keeps a MediaFrameLayout and
 * a Media in instance fields (Long press to zoom finds it the same way), and its touch method is its one
 * instance method taking a MotionEvent and answering a boolean. The feed state is the holder's one field
 * of [stateType], the type the carousel page is read from. The reel detector is the class whose method
 * loads [REEL_TOUCH_STRING], taking a View and a MotionEvent and answering a boolean, and its
 * reel item is its one field of a class keeping exactly one Media. Each class and field the extension
 * reads has to be public, since it reads them from its own package, and nothing may jump back to the
 * start of either touch method, where the calls go. The touch holder has one constructor, which is
 * where the call that keeps its frame goes (see [findFeedMake]).
 */
internal fun BytecodePatchContext.findHoldSites(stateType: String): HoldSites {
    val holders = classesCalling(GESTURE_DETECTOR, "<init>").filter { classDef ->
        val types = classDef.instanceFields().map { it.type }
        MEDIA_FRAME in types && MEDIA in types
    }
    val holder = holders.singleOrNull()
        ?: refuse("expected one class making a GestureDetector that keeps a media frame and a Media, found ${holders.size}")
    val feedTouches = holder.methods.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.implementation != null && method.returnType == "Z" && method.takes(MOTION_EVENT)
    }
    val feedTouch = feedTouches.singleOrNull()
        ?: refuse("expected ${holder.type} to hold one touch method taking a MotionEvent, found ${feedTouches.size}")
    if (0 in feedTouch.jumpTargets()) refuse("something in ${holder.type}->${feedTouch.name} jumps back to its start")
    requirePublic(holder)
    val feedMake = findFeedMake(holder)

    val reelTouches = classesLoadingString(REEL_TOUCH_STRING).sortedBy { it.type }.flatMap { it.methods }.filter { method ->
        !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "Z" && method.takes(VIEW, MOTION_EVENT) &&
            loadsString(method, REEL_TOUCH_STRING)
    }
    val reelTouch = reelTouches.singleOrNull() ?: refuse(
        "expected one instance method taking a View and a MotionEvent and answering a boolean that loads " +
            "\"$REEL_TOUCH_STRING\", found ${reelTouches.size}",
    )
    val where = "${reelTouch.definingClass}->${reelTouch.name}"
    if (0 in reelTouch.jumpTargets()) refuse("something in $where jumps back to its start")
    val detector = classDefBy(reelTouch.definingClass)
    requirePublic(detector)
    val items = detector.instanceFields().filter { field ->
        classDefByOrNull(field.type)?.instanceFields()?.count { it.type == MEDIA } == 1
    }
    val item = items.singleOrNull()
        ?: refuse("expected ${detector.type} to keep one reel item holding a Media, found ${items.size}")
    val itemClass = classDefBy(item.type)
    requirePublic(itemClass)

    return HoldSites(
        feedTouch,
        feedMake,
        kept(holder, MEDIA, "the post"),
        kept(holder, stateType, "the feed state"),
        kept(holder, MEDIA_FRAME, "the media frame"),
        reelTouch,
        kept(detector, item.type, "the reel item"),
        kept(itemClass, MEDIA, "the reel's post"),
    )
}

/**
 * The feed touch holder's one constructor, where the call that keeps its frame goes before each return.
 * The call hands over `this`, so the constructor has to be done with it: nothing in it may write the
 * register `this` sits in, and nothing may jump to a return, where the call would be skipped or run
 * from a path that never set the frame.
 */
private fun findFeedMake(holder: ClassDef): Method {
    val makes = holder.methods.filter { it.name == "<init>" && it.implementation != null }
    val make = makes.singleOrNull() ?: refuse("expected ${holder.type} to have one constructor, found ${makes.size}")
    val code = make.implementation!!.instructions.toList()
    val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_VOID }
    if (returns.isEmpty()) refuse("${holder.type}'s constructor never returns void")
    if (returns.any { it in make.jumpTargets() }) refuse("something in ${holder.type}'s constructor jumps to a return")
    val self = make.thisRegister()
    if (code.any { it.writes(self) }) refuse("something in ${holder.type}'s constructor writes the register holding this")
    return make
}

/** The register `this` sits in, the first of the method's parameter registers. */
private fun Method.thisRegister(): Int =
    implementation!!.registerCount - 1 - parameterTypes.sumOf { if (it.toString() == "J" || it.toString() == "D") 2 else 1 }

private fun Instruction.writes(register: Int): Boolean =
    this is OneRegisterInstruction && opcode.setsRegister() &&
        (registerA == register || (opcode.setsWideRegister() && registerA + 1 == register))

private fun requirePublic(classDef: ClassDef) {
    if (!AccessFlags.PUBLIC.isSet(classDef.accessFlags)) refuse("${classDef.type} isn't public, so the extension can't read it")
}

/** [owner]'s one instance field of [type], which has to be public. */
private fun kept(owner: ClassDef, type: String, what: String): FieldReference {
    val fields = owner.instanceFields().filter { it.type == type }
    val field = fields.singleOrNull() ?: refuse("expected ${owner.type} to keep $what ($type) in one field, found ${fields.size}")
    if (!AccessFlags.PUBLIC.isSet(field.accessFlags)) refuse("${owner.type}->${field.name}, $what, isn't public, so the extension can't read it")
    return ImmutableFieldReference(field.definingClass, field.name, field.type)
}

/**
 * Puts the two calls in and writes the four reads.
 *
 * The holder's constructor hands the new holder to [HOLD_FEED_MADE] before each return, for a hold on a
 * video whose own cover takes the touches (the extension looks for the frame under the fingers then).
 * The feed touch method hands its holder and event to [HOLD_FEED_TOUCH] first thing, or right after
 * Long press to zoom's call when that patch went first, so its call stays first, as its own check
 * asks. When Long press to zoom goes second, it puts its call in front of this one, which ends the
 * same. The reel detector's touch method hands itself, the view and the event to [HOLD_REEL_TOUCH]
 * first thing. Both calls only pass registers the method already has, so nothing of Instagram's code
 * changes. Each read in [HOLD_TARGETS] checks its owner's type and reads the field.
 */
internal fun BytecodePatchContext.addHoldToDownload(sites: HoldSites) {
    val feed = mutable(sites.feedTouch)
    val zoomFirst = (feed.implementation!!.instructions.first() as? ReferenceInstruction)?.reference?.toString() == ZOOM_TOUCH
    feed.addInstructions(if (zoomFirst) 1 else 0, "invoke-static/range { p0 .. p1 }, $HOLD_FEED_TOUCH")
    mutable(sites.reelTouch).addInstructions(0, "invoke-static/range { p0 .. p2 }, $HOLD_REEL_TOUCH")
    val make = mutable(sites.feedMake)
    val code = make.implementation!!.instructions.toList()
    for (at in code.indices.reversed()) {
        if (code[at].opcode == Opcode.RETURN_VOID) make.addInstructions(at, "invoke-static/range { p0 .. p0 }, $HOLD_FEED_MADE")
    }

    val targets = mutableClassDefBy(HOLD_TARGETS)
    fun stub(name: String) = targets.methods.singleOrNull {
        it.name == name && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == ANY && it.takes(ANY)
    } ?: refuse("$HOLD_TARGETS has no static Object $name(Object)")
    val holder = sites.feedTouch.definingClass
    // Each read answers an Object.
    stub("feedMedia").addInstructions(
        0,
        """
            check-cast p0, $holder
            iget-object p0, p0, ${sites.feedMedia}
            return-object p0
        """,
    )
    stub("feedState").addInstructions(
        0,
        """
            check-cast p0, $holder
            iget-object p0, p0, ${sites.feedState}
            return-object p0
        """,
    )
    stub("feedFrame").addInstructions(
        0,
        """
            check-cast p0, $holder
            iget-object p0, p0, ${sites.feedFrame}
            return-object p0
        """,
    )
    // Answers an Object: a detector made before its reel item was set answers null.
    stub("reelMedia").addInstructionsWithLabels(
        0,
        """
            check-cast p0, ${sites.reelTouch.definingClass}
            iget-object p0, p0, ${sites.reelItem}
            if-eqz p0, :none
            iget-object p0, p0, ${sites.reelMedia}
            return-object p0
            :none
            const/4 p0, 0x0
            return-object p0
        """,
    )
}
