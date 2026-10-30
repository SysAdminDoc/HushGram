/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.media.taptoplay

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.misc.extension.parameterRegisterNumber
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.patches.instagram.misc.extension.requireParameterIntact
import app.morphe.patches.instagram.misc.extension.typesMarked
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

internal const val AUTO_SCROLLED = "$TAP_TO_PLAY->autoScrolled()V"

/**
 * The purge marker, less its release, of the Reels auto scroller's move to the next reel. Matched
 * whole: Instagram 449 also has "UpNextNavigator_scrollToNextReel", which isn't the auto scroller.
 */
internal const val SCROLL_TO_NEXT_REEL = "ClipsAutoScroller_scrollToNextReel"

private const val OBJECT = "Ljava/lang/Object;"

/** 451 called its pager class directly; 452 made the pager an interface, so the move is an interface call there. */
private val PAGER_CALLS = setOf(
    Opcode.INVOKE_VIRTUAL, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.INVOKE_INTERFACE, Opcode.INVOKE_INTERFACE_RANGE,
)

/**
 * The auto scroller's move to the next reel, and the index of its call that moves the Reels pager on.
 *
 * @property move the static (scroller, boolean) method carrying [SCROLL_TO_NEXT_REEL]
 * @property call the index of its one call on the pager that takes nothing and returns nothing
 */
internal class AutoScroll(val move: Method, val call: Int)

/**
 * With Tap to play on, Instagram's own auto scroll in Reels stopped after one reel: the scroller
 * moved the pager on, the next reel's start came with no tap, the gate held it, and a reel that
 * never plays never ends, so the scroller never moved again. The scroller's move now tells the
 * extension, which lets the next reel's first start through as if it were tapped.
 *
 * The call goes in right in front of the pager's move, not at the method's entry. On 449 the move
 * (07SD.A03) reads its pager from the field its superclass keeps (0Amt.A02) first thing, and when
 * there's none it still goes on to log and reaches the end without moving anything, so a call at
 * the entry would let a start through that no move brought. The pager's call is reached only by
 * falling through its own null check, so in front of it the hook runs when the pager moves and
 * never otherwise. It goes before the call rather than after because the instruction after the call
 * is where the null check lands, and because the extension must know before anything the move sets
 * off asks the gate, however soon that is. The call names no register, so it borrows none, and its
 * method returns nothing and never throws, so Instagram's move goes on exactly as it would.
 */
internal fun BytecodePatchContext.hookAutoScroll(found: AutoScroll) {
    mutable(found.move).addInstructions(found.call, "invoke-static { }, $AUTO_SCROLLED")
}

/**
 * The auto scroller's move to the next reel, or null when this build has no method carrying
 * [SCROLL_TO_NEXT_REEL], with a warning in the patch log.
 *
 * The move must be the one method carrying the marker, a static (scroller, boolean) that returns
 * nothing. Its pager is the one object field the scroller inherits from its superclass that the
 * move reads off the scroller, and the move is the one call taking nothing and returning nothing on
 * that field's type. That call's receiver must come straight from a read of that field off the
 * scroller, with nothing jumping in between or to the call itself, since the hook goes in front of
 * the call and a jump there would skip it.
 *
 * A marker that's there but leads anywhere else stops the whole patch: the scroller is still in the
 * build and has changed in a way this hook doesn't understand, so guessing at it could let starts
 * through that no move brought. A marker that's gone doesn't. Instagram's build drops these markers
 * over time ("android_purge_" is what they're for), and auto scroll is a Reels setting most people
 * leave off. Without the hook, Tap to play still does everything it did before this one was added,
 * and with auto scroll on each reel after the first waits for a tap, as it always had. Refusing would
 * take the patch from everyone over that.
 */
internal fun BytecodePatchContext.findAutoScroll(): AutoScroll? {
    fun refuse(detail: String): Nothing = throw PatchException("$PATCH: the Reels auto scroller: $detail")
    val moves = mutableListOf<Method>()
    val marked = typesMarked(SCROLL_TO_NEXT_REEL)
    classDefForEach { classDef ->
        if (classDef.type in marked) classDef.methods.filterTo(moves) { SCROLL_TO_NEXT_REEL in it.markers() }
    }
    if (moves.isEmpty()) {
        patchLog.warning(
            "$PATCH: no method carries Instagram's $SCROLL_TO_NEXT_REEL marker, so Instagram's auto scroll in Reels " +
                "won't start the reel it moves to. With auto scroll on, each reel after the first waits for a tap. " +
                "The rest of the patch is in place.",
        )
        return null
    }
    val move = moves.singleOrNull() ?: refuse("expected one method marked $SCROLL_TO_NEXT_REEL, found ${moves.size}")
    val where = "${move.definingClass}->${move.name}"
    val scroller = classDefBy(move.definingClass)
    if (!AccessFlags.STATIC.isSet(move.accessFlags) || move.returnType != "V" ||
        move.parameterTypes.map(Any::toString) != listOf(scroller.type, "Z")
    ) {
        refuse("$where isn't static void (${scroller.type}, boolean)")
    }
    val parent = scroller.superclass?.takeUnless { it == OBJECT }
        ?: refuse("${scroller.type} extends no class of Instagram's to keep its pager in")

    val code = move.code()
    val self = move.parameterRegisterNumber(0)
    val reads = code.indices.filter { index ->
        code[index].opcode == Opcode.IGET_OBJECT && code[index].fieldReference()?.definingClass == parent &&
            (code[index] as TwoRegisterInstruction).registerB == self
    }
    val pagers = reads.map { code[it].fieldReference()!! }.distinctBy { it.toString() }
    val pager = pagers.singleOrNull()
        ?: refuse("expected $where to read one field ${scroller.type} inherits from $parent, its pager, found ${pagers.size}")

    val calls = code.indices.filter { index ->
        val call = code[index].methodReference()
        code[index].opcode in PAGER_CALLS && call != null &&
            call.definingClass == pager.type && call.parameterTypes.isEmpty() && call.returnType == "V"
    }
    val call = calls.singleOrNull()
        ?: refuse("expected one call on its pager ${pager.type} taking nothing and returning nothing in $where, the move, found ${calls.size}")
    val receiver = code[call].argumentRegisters().single()
    val loaded = (call - 1 downTo 0).firstOrNull { code[it].writes(receiver) }
    if (loaded == null || loaded !in reads || code[loaded].fieldReference().toString() != pager.toString()) {
        refuse("$where doesn't move the pager it reads from $pager, v$receiver at instruction $call")
    }
    val targets = move.jumpTargets()
    if (call in targets) refuse("something in $where jumps straight to the move at instruction $call, past where the hook would go")
    (loaded + 1 until call).firstOrNull { it in targets }
        ?.let { refuse("something in $where jumps to instruction $it, between the pager's read at $loaded and the move at $call") }
    move.requireParameterIntact("$PATCH: the Reels auto scroller", 0, listOf(loaded))
    return AutoScroll(move, call)
}

/** Whether [this] sets [register], or the wide pair that covers it. */
private fun Instruction.writes(register: Int): Boolean {
    if (!opcode.setsRegister()) return false
    val target = (this as? OneRegisterInstruction)?.registerA ?: return false
    return target == register || (opcode.setsWideRegister() && target + 1 == register)
}

private fun Instruction.argumentRegisters(): List<Int> = when (this) {
    is RegisterRangeInstruction -> List(registerCount) { startRegister + it }
    is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
    else -> emptyList()
}

private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
    mutableClassDefBy(method.definingClass).methods.single {
        it.name == method.name && it.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString) &&
            it.returnType == method.returnType
    }

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference

private fun Instruction.fieldReference(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference
