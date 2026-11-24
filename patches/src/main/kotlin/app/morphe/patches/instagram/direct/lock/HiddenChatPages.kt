/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.lock

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.classesCalling
import app.morphe.patches.instagram.misc.extension.classesLoadingString
import app.morphe.patches.instagram.misc.extension.patchLog
import app.morphe.util.addInstructionsAtControlFlowLabel
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

/**
 * What the inbox's data load coordinator logs (01CE on 456.1's 386215122). The coordinator loads it,
 * and so do a few runnables of its own, none of them with a load more.
 */
internal const val INBOX_COORDINATOR = "DirectInboxDataLoadCoordinator"

/** Handed each inbox's load-more as the inbox makes it, so the hidden chats a double tap shows can page in the rest (#661). */
internal const val HIDDEN_PAGER = "$HIDDEN_CHATS->pager(Ljava/lang/Object;)V"

private const val HIDDEN_NEXT_PAGE = "nextPage"

/**
 * The inbox's load-more, what its list's scroll asks for the next page of chats near the end (01Lo
 * on 386215122): [init] its one constructor, with the index of each return there, [more] its call
 * for the next page, and [bridge] the extension's stub that makes that call.
 */
internal class InboxPager(val init: MutableMethod, val returns: List<Int>, val more: String, val bridge: MutableMethod)

private fun refusePages(why: String): Nothing = throw PatchException("$LOCK_PATCH: $why")

/**
 * [findInboxPager], or null with a warning in the patch log when this build's inbox has moved: the
 * hidden chats a double tap shows are then the ones the inbox has loaded, and the rest of the lock
 * goes on.
 */
internal fun BytecodePatchContext.findInboxPagerOrSkip(): InboxPager? = try {
    findInboxPager()
} catch (e: PatchException) {
    patchLog.warning("${e.message}. Shown hidden chats stay the ones the inbox has loaded, and the rest of the lock goes on.")
    null
}

/**
 * Finds the inbox's load-more before anything changes. The inbox's data load coordinator is the one
 * class loading [INBOX_COORDINATOR] with an instance load more answering a boolean and taking one
 * boolean (01CE.A0f on 456.1) or, from 457, one object, the enum of why it loads (01Po.A0g, taking
 * 09Fp on 386314341). The load-more is the one other class calling that with a constant (a yes, or
 * one of that enum's values) from an instance method taking and answering nothing, its next page
 * (01Lo.EvW, 01c0.EoU), with the isLoading()Z of the load-more interface it implements and one
 * constructor. Fails when any of it isn't so.
 */
internal fun BytecodePatchContext.findInboxPager(): InboxPager {
    val coordinators = classesLoadingString(INBOX_COORDINATOR).mapNotNull { owner ->
        owner.methods.singleOrNull { method ->
            !method.static() && method.returnType == "Z" && method.implementation != null &&
                method.parameterTypes.map(Any::toString).let { it == listOf("Z") || it.size == 1 && it[0].startsWith("L") }
        }?.let { owner to it }
    }
    val (coordinator, load) = coordinators.singleOrNull()
        ?: refusePages("expected one class loading \"$INBOX_COORDINATOR\" with an instance (Z)Z or (enum)Z load more, found ${coordinators.size}")
    val takes = load.parameterTypes.map(Any::toString)
    val pagers = classesCalling(coordinator.type, load.name, takes, "Z").filter { it.type != coordinator.type }.mapNotNull { owner ->
        val more = owner.methods.singleOrNull { method ->
            !method.static() && method.parameterTypes.isEmpty() && method.returnType == "V" && method.asksConstant(load)
        }
        val loading = owner.methods.any { it.name == "isLoading" && it.parameterTypes.isEmpty() && it.returnType == "Z" && !it.static() }
        if (more == null || !loading || owner.interfaces.isEmpty()) null else owner to more
    }
    val (pager, more) = pagers.singleOrNull()
        ?: refusePages("expected one load-more asking ${coordinator.type}->${load.name} for the next page, found ${pagers.size}")
    val inits = mutableClassDefBy(pager.type).methods.filter { it.name == "<init>" }
    val init = inits.singleOrNull() ?: refusePages("the inbox's load-more ${pager.type} has ${inits.size} constructors, not one")
    val returns = init.implementation!!.instructions.withIndex().filter { it.value.opcode == Opcode.RETURN_VOID }.map { it.index }
    if (returns.isEmpty()) refusePages("${pager.type}'s constructor never returns")

    val extension = mutableClassDefBy(HIDDEN_CHATS).methods
    fun publicStatic(method: Method) = AccessFlags.STATIC.isSet(method.accessFlags) && AccessFlags.PUBLIC.isSet(method.accessFlags)
    val takesObject = listOf("Ljava/lang/Object;")
    if (extension.none { it.name == "pager" && it.parameterTypes.map(Any::toString) == takesObject && it.returnType == "V" && publicStatic(it) }) {
        refusePages("the extension has no $HIDDEN_PAGER")
    }
    val bridge = extension.singleOrNull {
        it.name == HIDDEN_NEXT_PAGE && it.parameterTypes.map(Any::toString) == takesObject && it.returnType == "V" && publicStatic(it)
    } ?: refusePages("the extension has no public static $HIDDEN_NEXT_PAGE bridge")
    return InboxPager(init, returns, "${pager.type}->${more.name}()V", bridge)
}

/**
 * Hands the inbox's load-more to the extension right before its constructor returns, and writes the
 * extension's bridge asking it for the next page, the call its list's scroll makes near the end.
 */
internal fun pageHiddenChats(found: InboxPager) {
    for (at in found.returns.sortedDescending()) {
        found.init.addInstructionsAtControlFlowLabel(at, "invoke-static/range { p0 .. p0 }, $HIDDEN_PAGER")
    }
    found.bridge.addInstructions(
        0,
        """
            check-cast p0, ${found.more.substringBefore("->")}
            invoke-virtual { p0 }, ${found.more}
            return-void
        """,
    )
}

private fun Method.static() = AccessFlags.STATIC.isSet(accessFlags)

/**
 * Whether this calls [load] with a constant loaded right before the call: a yes when it takes a
 * boolean, or one of the values its object parameter's own class keeps (457's enum of why it loads).
 */
private fun Method.asksConstant(load: Method): Boolean {
    val takes = load.parameterTypes.map(Any::toString)
    val code = implementation?.instructions?.toList() ?: return false
    return code.indices.any { at ->
        val call = code[at]
        val called = (call as? ReferenceInstruction)?.reference as? MethodReference ?: return@any false
        if (call.opcode != Opcode.INVOKE_VIRTUAL || called.definingClass != load.definingClass || called.name != load.name ||
            called.parameterTypes.map(Any::toString) != takes || called.returnType != "Z"
        ) return@any false
        val constant = code.getOrNull(at - 1) ?: return@any false
        if ((constant as? OneRegisterInstruction)?.registerA != (call as FiveRegisterInstruction).registerD) return@any false
        if (takes == listOf("Z")) {
            constant.opcode == Opcode.CONST_4 && (constant as NarrowLiteralInstruction).narrowLiteral == 1
        } else {
            val value = (constant as? ReferenceInstruction)?.reference as? FieldReference
            constant.opcode == Opcode.SGET_OBJECT && value?.definingClass == takes.single() && value.type == takes.single()
        }
    }
}
