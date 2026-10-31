/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.carousel

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Carousel arrows"

/** Instagram's carousel pager. Its name survives minifying, as Peek at the next carousel page relies on too. */
internal const val REBOUND_VIEW_PAGER = "Lcom/instagram/common/ui/widget/reboundviewpager/ReboundViewPager;"
internal const val CAROUSEL_TARGETS = "$EXTENSION_PACKAGE/feed/CarouselTargets;"
internal const val CAROUSEL_TURN = "$CAROUSEL_TARGETS->turn(Ljava/lang/Object;IF)V"

/** The page change's code: the page to a float, the speed to a double, "animate", the pager's own spring call, done. */
private val TURN_SHAPE = listOf(Opcode.INT_TO_FLOAT, Opcode.FLOAT_TO_DOUBLE, Opcode.CONST_4, Opcode.INVOKE_STATIC, Opcode.RETURN_VOID)

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Puts a back and a next arrow on each feed carousel (#417).
 *
 * Instagram's carousel pager slides to a page through one public method taking the page and a speed,
 * the one Instagram's own buttons call with a speed of 0 (A0S on 453). The patch writes the body of the
 * extension's [CAROUSEL_TARGETS] bridge to call it, and turns the extension's arrows on. Nothing of
 * Instagram's code changes: the extension finds each carousel on screen the way Peek at the next
 * carousel page does, adds the arrows above it and calls the bridge when one is tapped.
 *
 * Found and checked before anything changes: a build that differs stops the patch naming what it
 * couldn't find.
 */
@Suppress("unused")
val carouselArrowsPatch = bytecodePatch(
    name = "Carousel arrows",
    description = "Puts a back and a next arrow on a post's carousel in Home and Following, so a tap moves to the " +
        "next photo or video without a swipe, and can move its page dots onto a round pill over the photo. " +
        "Both start off. Turn them on in HushGram settings > Feed.",
) {
    category("Feed")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("carouselArrows")
        applyCarouselArrows(findCarouselTurn())
        enableStatus("carouselArrows")
    }
}

/**
 * The pager's one instance method taking a page and a speed whose code is [TURN_SHAPE], handing the
 * pager, the speed, the page and true (animate) to the pager's own static spring call. The jump without
 * an animation takes the page alone and hands false, so it never matches. The pager and the method
 * have to be public, since the bridge calls it from the extension's package.
 */
internal fun BytecodePatchContext.findCarouselTurn(): Method {
    val pager = classDefByOrNull(REBOUND_VIEW_PAGER) ?: refuse("no $REBOUND_VIEW_PAGER, Instagram's carousel pager")
    if (!AccessFlags.PUBLIC.isSet(pager.accessFlags)) refuse("$REBOUND_VIEW_PAGER isn't public, so the extension can't call it")
    val turns = pager.methods.filter { method ->
        if (AccessFlags.STATIC.isSet(method.accessFlags) || method.returnType != "V") return@filter false
        if (method.parameterTypes.map(Any::toString) != listOf("I", "F")) return@filter false
        val code = method.implementation?.instructions?.toList() ?: return@filter false
        if (code.map { it.opcode } != TURN_SHAPE) return@filter false
        if ((code[2] as NarrowLiteralInstruction).narrowLiteral != 1) return@filter false
        val spring = (code[3] as ReferenceInstruction).reference as MethodReference
        spring.definingClass == REBOUND_VIEW_PAGER && spring.returnType == "V" &&
            spring.parameterTypes.map(Any::toString) == listOf(REBOUND_VIEW_PAGER, "D", "F", "Z")
    }
    val turn = turns.singleOrNull()
        ?: refuse("expected one method of $REBOUND_VIEW_PAGER sliding to a page at a speed, found ${turns.size}")
    if (!AccessFlags.PUBLIC.isSet(turn.accessFlags)) refuse("$REBOUND_VIEW_PAGER->${turn.name}, the page change, isn't public, so the extension can't call it")
    return turn
}

/** Writes the bridge's body: the pager handed over, cast back, slides to the page at the speed. */
internal fun BytecodePatchContext.applyCarouselArrows(turn: Method) {
    val stub = mutableClassDefBy(CAROUSEL_TARGETS).methods.singleOrNull {
        it.name == "turn" && AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == "V" &&
            it.parameterTypes.map(Any::toString) == listOf("Ljava/lang/Object;", "I", "F")
    } ?: refuse("$CAROUSEL_TARGETS has no static void turn(Object, int, float)")
    stub.addInstructions(
        0,
        """
            check-cast p0, $REBOUND_VIEW_PAGER
            invoke-virtual { p0, p1, p2 }, $REBOUND_VIEW_PAGER->${turn.name}(IF)V
            return-void
        """,
    )
}
