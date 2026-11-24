/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.watchagain

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Watch again in the feed (#568): the hooks are in the extension, and in every build of 454 the end
 * card controller's constructor hands the in-place replay flag to the extension before storing it.
 */
class WatchAgainInFeedPatchTest {
    @Test
    fun theHooksAreInTheExtension() {
        for (hook in listOf(WATCH_AGAIN_IN_FEED, WATCH_MORE_REELS, WATCH_AGAIN_HERE)) {
            val declared = ExtensionDex.classDef(hook.substringBefore("->")).methods
                .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
                .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    /**
     * Hide Watch more reels (#652): in every build of 454 the method that wires the button's tap hands
     * the button to the extension before each return, read fresh off the holder.
     */
    @Test
    fun eachBuildHandsWatchMoreReelsToTheExtension() {
        var checked = 0
        for (bundle in bundles()) {
            val classes = (FixtureDex.classesLoading(bundle, CONTINUE_WATCHING) + FixtureDex.classesLoading(bundle, KEEP_WATCHING_BUTTON))
                .distinctBy { it.type }
            val context = PatchContexts.of(classes)

            val site = context.findWatchMoreReels(context.findWatchAgain().controller)
            context.hideWatchMoreReels(site)

            val bind = context.mutableClassDefBy(site.controller).methods.single {
                "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" == site.bind
            }
            val code = bind.code()
            val returns = code.indices.filter { code[it].opcode == Opcode.RETURN_VOID }
            assertTrue("${bundle.name}: ${site.bind} returns", returns.isNotEmpty())
            for (at in returns) {
                assertEquals("${bundle.name}: the holder is read", site.holder, code[at - 4].reference().toString())
                assertEquals("${bundle.name}: the button is fetched", site.button, code[at - 3].reference().toString())
                assertEquals("${bundle.name}: the hook before the return", WATCH_MORE_REELS, code[at - 1].reference().toString())
            }
            checked++
        }
        assertEquals("every build of 454 is read", Fixtures.BUILDS, checked)
    }

    private fun bundles() = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        .flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } + Fixtures.otherBuilds()

    @Test
    fun eachBuildHandsTheReplayFlagToTheExtension() {
        var checked = 0
        for (bundle in bundles()) {
            val context = PatchContexts.of(FixtureDex.classesLoading(bundle, CONTINUE_WATCHING))

            val site = context.findWatchAgain()
            context.replayWatchAgainInFeed(site)

            var stores = 0
            for (constructor in context.mutableClassDefBy(site.controller).methods.filter { it.name == "<init>" }) {
                val code = constructor.code()
                for (at in code.indices) {
                    if (code[at].opcode != Opcode.IPUT_BOOLEAN || code[at].reference().toString() != site.flag) continue
                    val value = (code[at] as TwoRegisterInstruction).registerA
                    val call = code[at - 2]
                    assertEquals("${bundle.name}: the hook before the store", WATCH_AGAIN_IN_FEED, call.reference().toString())
                    assertEquals("${bundle.name}: the hook takes the flag", value, (call as RegisterRangeInstruction).startRegister)
                    assertEquals("${bundle.name}: the answer is stored", Opcode.MOVE_RESULT, code[at - 1].opcode)
                    assertEquals("${bundle.name}: into the same register", value, (code[at - 1] as OneRegisterInstruction).registerA)
                    stores++
                }
            }
            assertTrue("${bundle.name}: no constructor stores ${site.flag}", stores > 0)
            checked++
        }
        assertEquals("every build of 454 is read", Fixtures.BUILDS, checked)
    }

    /**
     * The card in Saved and the other lists of posts (#568): past the flag, the card's setup builds
     * another listener for "Watch again" than the in-place one. In every build the setup asks the
     * extension right before the button gets its listener, and on a yes builds the in-place listener,
     * the one the flag's copy hands on, into the register the setter reads.
     */
    @Test
    fun eachBuildPutsTheInPlaceListenerOnWatchAgain() {
        var checked = 0
        for (bundle in bundles()) {
            val context = PatchContexts.of(FixtureDex.classesLoading(bundle, CONTINUE_WATCHING))
            val site = context.findWatchAgain()
            val stock = context.classDefBy(site.controller).methods.single { text(it) == site.bind }.code()

            context.replayWatchAgainInFeed(site)
            context.replayWatchAgainHere(site)

            val code = context.mutableClassDefBy(site.controller).methods.single { text(it) == site.bind }.code()
            val asks = code.indices.filter { code[it].reference().toString() == WATCH_AGAIN_HERE }
            assertEquals("${bundle.name}: the setup asks once", 1, asks.size)
            val at = asks.single()
            val answer = (code[at + 1] as OneRegisterInstruction).registerA
            assertEquals("${bundle.name}: the answer is kept", Opcode.MOVE_RESULT, code[at + 1].opcode)
            assertEquals("${bundle.name}: a no skips the build", Opcode.IF_EQZ, code[at + 2].opcode)
            assertEquals("${bundle.name}: on the answer", answer, (code[at + 2] as OneRegisterInstruction).registerA)
            val made = code[at + 3]
            assertEquals("${bundle.name}: a new listener", Opcode.NEW_INSTANCE, made.opcode)
            assertEquals("${bundle.name}: of the in-place kind", site.listener, made.reference().toString())
            val listener = (made as OneRegisterInstruction).registerA
            assertEquals("${bundle.name}: its number", site.case, (code[at + 4] as NarrowLiteralInstruction).narrowLiteral)
            val init = code[at + 5] as FiveRegisterInstruction
            assertEquals("${bundle.name}: built", site.init, code[at + 5].reference().toString())
            val self = context.mutableClassDefBy(site.controller).methods.single { text(it) == site.bind }.implementation!!.registerCount - 1
            assertEquals("${bundle.name}: from the controller and the number", listOf(listener, self, answer),
                listOf(init.registerC, init.registerD, init.registerE))
            // The setter comes right after, where a no lands, and takes that listener for the TextView.
            val setter = code[at + 6] as FiveRegisterInstruction
            val shape = (code[at + 6].reference() as MethodReference).let { "(${it.parameterTypes.joinToString("")})${it.returnType}" }
            assertEquals("${bundle.name}: the click setter", "(Landroid/view/View\$OnClickListener;Landroid/view/View;)V", shape)
            assertEquals("${bundle.name}: hands the listener", listener, setter.registerC)
            val target = context.mutableClassDefBy(site.controller).methods.single { text(it) == site.bind }
                .implementation!!.instructions.toList().let { list ->
                    val offsets = list.runningFold(0) { sum, instruction -> sum + instruction.codeUnits }
                    offsets.indexOf(offsets[at + 2] + (list[at + 2] as OffsetInstruction).codeOffset)
                }
            assertEquals("${bundle.name}: a no lands on the setter", at + 6, target)
            // The in-place listener is the one the flag's copy hands on: built with the same number before the copy.
            val inPlace = stock.indices.filter { stock[it].opcode == Opcode.INVOKE_DIRECT && stock[it].reference().toString() == site.init }
                .map { index -> (stock[index] as FiveRegisterInstruction).registerE.let { number ->
                    (index - 1 downTo 0).first { (stock[it] as? OneRegisterInstruction)?.registerA == number }.let { stock[it] as? NarrowLiteralInstruction }?.narrowLiteral
                } }
            assertTrue("${bundle.name}: the number ${site.case} is one the setup builds: $inPlace", site.case in inPlace)
            checked++
        }
        assertEquals("every build of 456 is read", Fixtures.BUILDS, checked)
    }

    private fun text(method: Method) = "${method.definingClass}->${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}"

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference
}
