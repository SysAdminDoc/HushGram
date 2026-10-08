/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.scrolling

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.misc.extension.classesAccessing
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference

internal const val PAGE_HOOK = "$REEL_SCROLLING->page(Ljava/lang/Object;I)V"

/** AndroidX keeps the pager's getter for the page it's on, which reads the field every move stores. */
internal const val CURRENT_ITEM = "getCurrentItem"

/** One store of the pager's current page: the method it's in, where, and the registers it names. */
internal class PageStore(val type: String, val name: String, val shape: String, val index: Int, val pager: Int, val position: Int)

/**
 * Every store of [VIEW_PAGER]'s current page, the field its [CURRENT_ITEM] reads: the pager's own
 * when it's told to move or given its pages, and its page callback's, outside the pager, when a
 * swipe settles on a new page. Fails before anything changes when the getter reads anything but one
 * int field of the pager, or when no store outside the pager is left to report a swipe.
 */
internal fun BytecodePatchContext.findPageStores(): List<PageStore> {
    val pagerClass = classDefByOrNull(VIEW_PAGER) ?: throw PatchException("Stop Reels scrolling: $VIEW_PAGER isn't in this build")
    val getter = pagerClass.methods.singleOrNull { it.name == CURRENT_ITEM && it.parameterTypes.isEmpty() && it.returnType == "I" }
        ?: throw PatchException("Stop Reels scrolling: $VIEW_PAGER has no $CURRENT_ITEM()I")
    val reads = getter.code().mapNotNull { instruction ->
        instruction.field()?.takeIf { instruction.opcode == Opcode.IGET && it.definingClass == VIEW_PAGER && it.type == "I" }
    }
    val field = reads.singleOrNull()
        ?: throw PatchException("Stop Reels scrolling: $VIEW_PAGER->$CURRENT_ITEM reads ${reads.size} int fields, not one")
    val stores = classesAccessing(VIEW_PAGER, field.name, Opcode.IPUT).flatMap { classDef ->
        classDef.methods.flatMap { method ->
            method.code().withIndex().filter { (_, instruction) ->
                instruction.opcode == Opcode.IPUT && instruction.field()?.let {
                    it.definingClass == VIEW_PAGER && it.name == field.name && it.type == "I"
                } == true
            }.map { (index, instruction) ->
                val store = instruction as TwoRegisterInstruction
                PageStore(method.definingClass, method.name, method.shape(), index, store.registerB, store.registerA)
            }
        }
    }
    if (stores.none { it.type != VIEW_PAGER }) {
        throw PatchException("Stop Reels scrolling: nothing outside $VIEW_PAGER stores its current page, so a swipe can't be counted")
    }
    return stores
}

/**
 * Hands the pager and its new page to [PAGE_HOOK] right after each store, which counts the reels a
 * session plays for the cap. Stores in one method go in from the last, so each index still points
 * at its store. An iput names v15 at most, so both registers fit the call as they are.
 */
internal fun BytecodePatchContext.reportPages(stores: List<PageStore>) {
    stores.groupBy { Triple(it.type, it.name, it.shape) }.forEach { (where, inMethod) ->
        val method = mutableClassDefBy(where.first).methods.single { it.name == where.second && it.shape() == where.third }
        inMethod.sortedByDescending { it.index }.forEach { store ->
            method.addInstructions(
                store.index + 1,
                """
                    invoke-static { v${store.pager}, v${store.position} }, $PAGE_HOOK
                """,
            )
        }
    }
}

private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Method.shape(): String = parameterTypes.joinToString("", "(", ")") + returnType

private fun Instruction.field(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference
