/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.blur

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.patch.bytecodePatch
import app.morphe.patches.instagram.misc.extension.EXTENSION_PACKAGE
import app.morphe.patches.instagram.misc.extension.enableStatus
import app.morphe.patches.instagram.misc.extension.instagramExtensionPatch
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.requireStatusMethod
import app.morphe.patches.instagram.misc.extension.uniqueMethod
import app.morphe.patches.instagram.misc.settings.settingsPatch
import app.morphe.patches.instagram.reels.scrolling.ENABLE_SCROLLING
import app.morphe.patches.instagram.reels.scrolling.PAGER_SETUP
import app.morphe.patches.instagram.reels.scrolling.ReelsPagerEnableFingerprint
import app.morphe.patches.instagram.reels.scrolling.ReelsViewerSetupFingerprint
import app.morphe.patches.instagram.reels.scrolling.SET_USER_INPUT
import app.morphe.patches.instagram.reels.scrolling.VIEW_PAGER
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference

private const val PATCH = "Blur the bars around Reels"
internal const val REEL_BLUR_BARS = "$EXTENSION_PACKAGE/reels/ReelBlurBars;"
internal const val BLUR_PAGER_HOOK = "$REEL_BLUR_BARS->pager(Ljava/lang/Object;)V"

private fun refuse(detail: String): Nothing = throw PatchException("$PATCH: $detail")

/**
 * Paints a blurred copy of a reel in the bars around it, where Instagram paints black. In the
 * default selection with its switch off: it changes how reels with bars look, so it's the user's pick.
 * Asked for in #72.
 *
 * Every reel's video is a TextureView on a page of the Reels viewer's ViewPager2, so the patch hands
 * the extension that pager once, right after the viewer's onViewCreated stores it. The extension
 * watches it from there: as pages settle and about once a second it copies the playing frame at a
 * sixteenth of its size, blurs those few thousand pixels and paints them as the background of the
 * page's container, behind the video. The pager is the one the Stop Reels scrolling patch finds, by
 * the trace names Instagram gives the pager's setup and its enable switch.
 *
 * Found and checked before anything changes: a build that differs stops the patch naming what it
 * couldn't find.
 */
@Suppress("unused")
val blurReelBarsPatch = bytecodePatch(
    name = "Blur the bars around Reels",
    description = "Shows a blurred copy of a reel above and below it, or beside it, where Instagram would leave " +
        "black bars. Starts off. Turn it on in HushGram settings > Reels.",
) {
    category("Reels")
    dependsOn(settingsPatch, instagramExtensionPatch)
    compatibleWith(*AppCompatibilities.instagram())

    execute {
        requireStatusMethod("reelBlurBars")
        applyBlurReelBars(findReelPagerStore())
        enableStatus("reelBlurBars")
    }
}

/** The viewer's onViewCreated, the index of its one store of the pager, and the register that holds it. */
internal class ReelPagerStore(val viewer: String, val store: Int, val pager: Int)

/**
 * The Reels pager is the [VIEW_PAGER] field its own [ENABLE_SCROLLING] switch reads, and the viewer
 * whose onViewCreated holds [PAGER_SETUP] stores it exactly once. Nothing may jump to the
 * instruction after the store, so the hook sits in the straight line behind it with the pager still
 * in its register.
 */
internal fun BytecodePatchContext.findReelPagerStore(): ReelPagerStore {
    val enable = uniqueMethod(PATCH, "Reels pager switch holding \"$ENABLE_SCROLLING\"", ReelsPagerEnableFingerprint)
    val enableCode = enable.instructions()
    val pagerReads = enableCode.mapNotNull { instruction ->
        instruction.fieldReference()?.takeIf {
            instruction.opcode == Opcode.IGET_OBJECT && it.definingClass == enable.definingClass && it.type == VIEW_PAGER
        }?.toString()
    }.distinct()
    val field = pagerReads.singleOrNull()
        ?: refuse("expected ${enable.definingClass}->${enable.name} to read one $VIEW_PAGER field of its class, found ${pagerReads.size}")
    if (enableCode.none { it.methodReference()?.let { called -> called.definingClass == VIEW_PAGER && called.name == SET_USER_INPUT } == true }) {
        refuse("${enable.definingClass}->${enable.name} doesn't call $VIEW_PAGER->$SET_USER_INPUT")
    }

    val viewer = uniqueMethod(PATCH, "Reels viewer onViewCreated holding \"$PAGER_SETUP\"", ReelsViewerSetupFingerprint)
    if (AccessFlags.STATIC.isSet(viewer.accessFlags)) refuse("${viewer.definingClass}->onViewCreated is static")
    val code = viewer.instructions()
    val where = "${viewer.definingClass}->onViewCreated"
    val stores = code.indices.filter { code[it].opcode == Opcode.IPUT_OBJECT && code[it].fieldReference()?.toString() == field }
    val store = stores.singleOrNull() ?: refuse("expected $where to store $field once, found ${stores.size}")
    if (store + 1 !in code.indices) refuse("$where ends at its store of $field")
    if (store + 1 in viewer.jumpTargets()) refuse("something in $where jumps to the instruction after its store of $field")
    // An iput names v15 at most, so the pager's register fits the hook's invoke as it is.
    return ReelPagerStore(viewer.definingClass, store, (code[store] as TwoRegisterInstruction).registerA)
}

/** Hands the pager to [BLUR_PAGER_HOOK] right after the viewer stores it. */
internal fun BytecodePatchContext.applyBlurReelBars(site: ReelPagerStore) {
    mutableClassDefBy(site.viewer).methods.single {
        it.name == "onViewCreated" && it.parameterTypes.map(CharSequence::toString) == listOf("Landroid/view/View;", "Landroid/os/Bundle;")
    }.addInstructions(site.store + 1, "invoke-static { v${site.pager} }, $BLUR_PAGER_HOOK")
}

private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

private fun Instruction.fieldReference(): FieldReference? = (this as? ReferenceInstruction)?.reference as? FieldReference

private fun Instruction.methodReference(): MethodReference? = (this as? ReferenceInstruction)?.reference as? MethodReference
