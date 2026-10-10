/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.loop

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.stories.autoadvance.HOLD
import app.morphe.patches.instagram.stories.autoadvance.HOLD_UNLESS_IT_LOOPS
import app.morphe.patches.instagram.stories.autoadvance.STORY_VIEWER
import app.morphe.patches.instagram.stories.autoadvance.holdFinishedStories
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class LoopStoryHookTest {
    private val storyItem = "Lcom/instagram/model/reels/ReelItem;"
    private val configs = "Lcom/facebook/mobileconfig/factory/MobileConfigUnsafeContext;"
    private val flagHex = STORY_LOOP_FLAG.toString(16)

    /** The hook the patch writes is in the StoryLoop the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(STORY_LOOP).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$LOOP is not in the extension: $declared", LOOP.substringAfter("->") in declared)
    }

    /** The viewer's loop check gets its flag answered through the extension; the handler stays as it was. */
    @Test
    fun theLoopCheckIsAnswered() {
        val context = PatchContexts.of(classes())

        context.loop()

        val viewer = context.mutableClassDefBy(STORY_VIEWER)
        assertLoopAnswered("stand-in", viewer.methods.single { it.name == "plays" })
        assertEquals("the finished story handler was touched", handlerSize(), viewer.methods.single { it.name == "FrS" }.code().size)
    }

    /**
     * Stop Story auto-advance and Loop a story patch the same build in either order. Stop's guard
     * asks the same loop check Loop answers, and Loop still finds Instagram's one ask of it past
     * the guard's own.
     */
    @Test
    fun stopAndLoopPatchTogetherInEitherOrder() {
        val check = "$STORY_VIEWER->plays($storyItem)Z"
        for (stopFirst in listOf(true, false)) {
            val context = PatchContexts.of(classes())
            if (stopFirst) context.holdFinishedStories()
            context.loop()
            if (!stopFirst) context.holdFinishedStories()

            val viewer = context.mutableClassDefBy(STORY_VIEWER)
            assertLoopAnswered("stop first $stopFirst", viewer.methods.single { it.name == "plays" })
            val handler = viewer.methods.single { it.name == "FrS" }.code()
            assertEquals("stop first $stopFirst: the hold", HOLD, handler.first().referenceText())
            assertEquals("stop first $stopFirst: the guard's second hook", HOLD_UNLESS_IT_LOOPS, handler[3].referenceText())
            assertEquals("stop first $stopFirst: handler size", handlerSize() + 11, handler.size)
            assertEquals("stop first $stopFirst: asks of the loop check", 2, handler.count { it.referenceText() == check })
            assertEquals("stop first $stopFirst: the guard's ask", check, handler[7].referenceText())
        }
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val check = "$STORY_VIEWER->plays"
        val cases = listOf(
            classes(handlerHolds = "sponsored") to "expected exactly one finished story handler in this Instagram build, found none",
            classes(reads = 0) to "expected one read of the story loop flag $flagHex, found 0",
            classes(reads = 2) to "expected one read of the story loop flag $flagHex, found 2",
            classes(flag = STORY_LOOP_FLAG + 1) to "expected one read of the story loop flag $flagHex, found 0",
            classes(sharedRead = true) to "the read of $flagHex in $check is shared with another flag",
            classes(checkOwner = "Lfixture/Elsewhere;") to "$flagHex is read in Lfixture/Elsewhere;->plays($storyItem)Z, not in the story viewer's check of one story item",
            classes(checkTakes = "Ljava/lang/Object;") to "$flagHex is read in $check(Ljava/lang/Object;)Z, not in the story viewer's check of one story item",
            classes(asks = 0) to "expected the finished story handler $STORY_VIEWER->FrS to ask $check once, found 0",
            classes(asks = 2) to "expected the finished story handler $STORY_VIEWER->FrS to ask $check once, found 2",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.loop() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() == LOOP } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /**
     * In each declared build the flag's one read is in the story viewer's loop check, and it's
     * answered. On 449 the check is asked by the finished story handler, where a photo starts
     * over, and by the viewer's other check of a story item, the one the viewer's video start
     * asks whether the player should loop.
     */
    @Test
    fun eachDeclaredBuildLoopsTheStory() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                val holders = mutableListOf<ClassDef>()
                FixtureDex.forEach(bundle) { dex ->
                    for (classDef in dex.classes) {
                        if (classDef.type == STORY_VIEWER || classDef.methods.any { it.loadsTheFlag() }) holders += ImmutableClassDef.of(classDef)
                    }
                }
                assertEquals("${bundle.name}: classes loading $flagHex", setOf(STORY_VIEWER), holders.filter { c -> c.methods.any { it.loadsTheFlag() } }.map { it.type }.toSet())
                val viewer = holders.single { it.type == STORY_VIEWER }
                val context = PatchContexts.of(holders)

                val read = context.findStoryLoop()
                context.loopStories()

                val patched = context.mutableClassDefBy(STORY_VIEWER).methods
                val check = patched.single { it.name == read.name && it.parameterTypes.map(CharSequence::toString) == listOf(storyItem) }
                assertLoopAnswered(bundle.name, check)
                assertEquals("${bundle.name}: methods hooked", 1, patched.count { m -> m.code().any { it.referenceText() == LOOP } })
                val askers = viewer.methods.filter { m -> m.code().any { it.calls(read.name) } }
                val handler = askers.filter { AccessFlags.BRIDGE.isSet(it.accessFlags) }
                assertEquals("${bundle.name}: the finished story handler asks: ${askers.map { it.name }}", 1, handler.size)
                assertTrue("${bundle.name}: the handler holds \"userSession\"", handler.single().code().any { it.string() == "userSession" })
                assertEquals("${bundle.name}: what asks the loop check: ${askers.map { it.name }}", 2, askers.size)
                val other = askers.single { it !in handler }
                assertEquals("${bundle.name}: the other asker's shape", listOf(storyItem), other.parameterTypes.map(CharSequence::toString))
                assertEquals("${bundle.name}: the other asker's answer", "Z", other.returnType)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /** Right after the flag's move-result: the range call with that register, and its answer back in it. */
    private fun assertLoopAnswered(what: String, method: Method) {
        val code = method.code()
        val load = code.indexOfFirst { it.opcode == Opcode.CONST_WIDE && (it as WideLiteralInstruction).wideLiteral == STORY_LOOP_FLAG }
        assertTrue("$what: no load of $flagHex", load >= 0)
        val result = (load + 1 until code.size).first { code[it].opcode == Opcode.MOVE_RESULT }
        val register = (code[result] as OneRegisterInstruction).registerA
        assertEquals("$what: hooks", 1, code.count { it.referenceText() == LOOP })
        assertEquals("$what: the call", Opcode.INVOKE_STATIC_RANGE, code[result + 1].opcode)
        assertEquals("$what: the hook", LOOP, code[result + 1].referenceText())
        assertEquals("$what: what it's handed", register, (code[result + 1] as RegisterRangeInstruction).startRegister)
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[result + 2].opcode)
        assertEquals("$what: the register", register, (code[result + 2] as OneRegisterInstruction).registerA)
    }

    private fun BytecodePatchContext.loop() = loopStories()

    private fun handlerSize() = classes().single { it.type == STORY_VIEWER }.methods.single { it.name == "FrS" }.code().size

    /**
     * A story viewer shaped like Instagram 449's. Its finished story handler is a bridge taking one
     * Object that casts it to a story item, holds "userSession" and asks the viewer's private loop
     * check about the item; a second bridge holds "sponsored". The check says no for a sponsored
     * item, then reads [STORY_LOOP_FLAG] through a cast config and answers it.
     */
    private fun classes(
        handlerHolds: String = "userSession",
        asks: Int = 1,
        reads: Int = 1,
        flag: Long = STORY_LOOP_FLAG,
        sharedRead: Boolean = false,
        checkOwner: String = STORY_VIEWER,
        checkTakes: String = storyItem,
    ): List<ClassDef> {
        val bridge = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value or AccessFlags.BRIDGE.value or AccessFlags.SYNTHETIC.value
        // Three locals, this in v3, the item in v4.
        val handler = method(
            STORY_VIEWER, "FrS", listOf("Ljava/lang/Object;"), "V", 3, bridge,
            listOf(
                "check-cast v4, $storyItem",
                "const-string v0, \"$handlerHolds\"",
            ).plus(List(asks) { "invoke-direct {v3, v4}, $checkOwner->plays($checkTakes)Z\nmove-result v0" })
                .plus("return-void")
                .joinToString("\n"),
        )
        val other = method(
            STORY_VIEWER, "FrU", listOf("Ljava/lang/Object;"), "V", 3, bridge,
            "check-cast v4, $storyItem\nconst-string v0, \"sponsored\"\nreturn-void",
        )
        val readFlag = List(reads) {
            """
                invoke-static {v3}, Lfixture/Configs;->of(Ljava/lang/Object;)Ljava/lang/Object;
                move-result-object v2
                ${if (sharedRead) "if-eqz v2, :cast$it" else ""}
                const-wide v0, 0x${flag.toString(16)}L
                ${if (sharedRead) ":cast$it" else ""}
                check-cast v2, $configs
                invoke-interface {v2, v0, v1}, $configs->read(J)Z
                move-result v0
                if-nez v0, :yes
            """.trimIndent()
        }.joinToString("\n")
        // Three locals, this in v3, the item in v4.
        val check = method(
            checkOwner, "plays", listOf(checkTakes), "Z", 3, AccessFlags.PRIVATE.value or AccessFlags.FINAL.value,
            listOf(
                "invoke-virtual {v4}, Ljava/lang/Object;->hashCode()I",
                "move-result v0",
                "if-nez v0, :no",
                readFlag,
                ":no",
                "const/4 v0, 0x0",
                "return v0",
                ":yes",
                "const/4 v0, 0x1",
                "return v0",
            ).filter { it.isNotBlank() }.joinToString("\n"),
        )
        val viewerMethods = listOf(handler, other) + if (checkOwner == STORY_VIEWER) listOf(check) else emptyList()
        val viewer = classDef(STORY_VIEWER, "Landroidx/fragment/app/Fragment;", viewerMethods)
        return if (checkOwner == STORY_VIEWER) listOf(viewer) else listOf(viewer, classDef(checkOwner, "Ljava/lang/Object;", listOf(check)))
    }

    private fun method(owner: String, name: String, parameters: List<String>, returns: String, registers: Int, flags: Int, body: String): Method {
        val total = registers + 1 + parameters.size
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.lines().filter { it.isNotBlank() }.joinToString("\n") { it.trim() })
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, superclass: String, methods: List<Method>): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, superclass, null, null, null, null, methods)

    private fun Method.loadsTheFlag(): Boolean = code().any {
        it.opcode == Opcode.CONST_WIDE && (it as WideLiteralInstruction).wideLiteral == STORY_LOOP_FLAG
    }

    private fun Instruction.calls(name: String): Boolean {
        val called = (this as? ReferenceInstruction)?.reference as? MethodReference ?: return false
        return called.definingClass == STORY_VIEWER && called.name == name && called.returnType == "Z" &&
            called.parameterTypes.map(CharSequence::toString) == listOf(storyItem)
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun Instruction.string(): String? = ((this as? ReferenceInstruction)?.reference as? StringReference)?.string
}
