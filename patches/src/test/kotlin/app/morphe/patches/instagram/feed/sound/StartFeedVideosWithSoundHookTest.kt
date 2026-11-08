/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.sound

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.NeutralNativePath
import app.morphe.patches.instagram.reels.tapvolume.TOGGLE_AUDIO
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StartFeedVideosWithSoundHookTest {
    private val media = "Lcom/instagram/feed/media/Media;"
    private val controller = "Lfixture/FeedController;"
    private val item = "Lfixture/VideoState;"
    private val holder = "Lfixture/Holder;"
    private val trace = "Lfixture/Trace;->begin(Ljava/lang/String;)V"
    private val userSession = "Lcom/instagram/common/session/UserSession;"
    private val state = "Lfixture/AudioState;"
    private val reels = "Lfixture/ClipsVideoPlayerController;"
    private val toggleMarker = "android_purge_26_q3_$TOGGLE_AUDIO"

    /**
     * The instructions the hook adds: test the flag, ask, test the answer, set the flag, take the session, test it,
     * take the account's audio state and set it on.
     */
    private val hookLength = 11

    /** The hook's opcodes, with a one boolean setter. */
    private val hookOpcodes = listOf(
        Opcode.IF_NEZ, Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4,
        Opcode.MOVE_OBJECT_FROM16, Opcode.IGET_OBJECT, Opcode.IF_EQZ, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT,
        Opcode.INVOKE_VIRTUAL,
    )

    /** The hook the patch writes is in the FeedSound the bundle ships, public and static, and takes the controller as an Object. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(FEED_SOUND).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$FEED_START_WITH_SOUND is not in the extension: $declared", FEED_START_WITH_SOUND.substringAfter("->") in declared)
    }

    /**
     * In front of the call that gives the video's state its sound flag the hook tests the flag, asks with the controller,
     * and on a yes sets the flag and turns the account's audio state on through the controller's session (#504), so the
     * videos after the first start with sound too. Every other instruction keeps its place and the hook never writes the
     * controller's register.
     */
    @Test
    fun theHookGoesInFrontOfTheSoundFlagCallAndTurnsTheAccountsSoundOn() {
        val context = PatchContexts.of(classes())
        val original = context.start().code().map { it.describe() }

        context.apply { applyFeedSound(findFeedSoundSite()) }

        val code = context.start().code()
        val ask = code.indexOfFirst { it.referenceText() == FEED_START_WITH_SOUND }
        assertEquals("the flag test comes first", Opcode.IF_NEZ, code[ask - 1].opcode)
        assertEquals(hookOpcodes, code.subList(ask - 1, ask - 1 + hookLength).map { it.opcode })
        val call = ask - 1 + hookLength
        assertEquals("the flag call follows", "$item->setSound(Z)V", code[call].referenceText())
        val flag = (code[call] as FiveRegisterInstruction).registerD
        assertEquals("the hook sets the register the call hands over", flag, (code[ask + 3] as OneRegisterInstruction).registerA)
        assertEquals("to true", 1, (code[ask + 3] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("the hook tests that register", flag, (code[ask - 1] as OneRegisterInstruction).registerA)
        val p0 = context.start().implementation!!.registerCount - 11
        assertEquals("the controller is read from its own register", listOf(p0), code[ask].arguments())
        assertEquals("the session is the controller's own field", "$controller->session:$userSession", code[ask + 5].referenceText())
        val session = (code[ask + 5] as TwoRegisterInstruction).registerA
        assertEquals("taken from the controller", p0, (code[ask + 4] as TwoRegisterInstruction).registerB)
        assertEquals("a missing session goes on to Instagram's code", Opcode.IF_EQZ, code[ask + 6].opcode)
        assertEquals("the account's audio state", "$state->of($userSession)$state", code[ask + 7].referenceText())
        assertEquals("of that session", listOf(session), code[ask + 7].arguments())
        assertEquals("the setter", "$state->set(Z)V", code[ask + 9].referenceText())
        assertEquals("set on the state, to the flag just set true", listOf(session, flag), code[ask + 9].arguments())
        val written = code.subList(ask - 1, call).mapNotNull { (it as? OneRegisterInstruction)?.registerA }
        assertTrue("the hook wrote the controller's register or a parameter: $written", written.all { it < p0 })
        assertEquals("what's left of the method", original.size + hookLength, code.size)
        assertEquals("the original instructions keep their order", original, code.take(ask - 1).map { it.describe() } + code.drop(call).map { it.describe() })
        assertTrue("a jump lands inside the hook", (ask until call).none { it in context.start().jumpTargets() })
    }

    /** Without one session field on the controller, or with the audio state out of its reach, the patch stops first. */
    @Test
    fun withoutTheSessionOrAReachableAudioStateThePatchFails() {
        val noSession = PatchContexts.of(classes(sessions = 0))
        val none = assertThrows(PatchException::class.java) { noSession.findFeedSoundSite() }
        assertTrue(none.message!!, none.message!!.contains("field, found 0"))
        val two = assertThrows(PatchException::class.java) { PatchContexts.of(classes(sessions = 2)).findFeedSoundSite() }
        assertTrue(two.message!!, two.message!!.contains("field, found 2"))
        val hidden = PatchContexts.of(classes(statePublic = false))
        val unreachable = assertThrows(PatchException::class.java) { hidden.findFeedSoundSite() }
        assertTrue(unreachable.message!!, unreachable.message!!.contains("can't reach the audio state"))
        for (context in listOf(noSession, hidden)) {
            assertTrue("something was written", context.start().code().none { it.referenceText() == FEED_START_WITH_SOUND })
        }
    }

    @Test
    fun withoutOneControllerThePatchFails() {
        val none = assertThrows(PatchException::class.java) { PatchContexts.of(classes(controllers = 0)).findFeedSoundSite() }
        assertTrue(none.message!!, none.message!!.contains("found 0"))
        val two = assertThrows(PatchException::class.java) { PatchContexts.of(classes(controllers = 2)).findFeedSoundSite() }
        assertTrue(two.message!!, two.message!!.contains("found 2"))
    }

    @Test
    fun aVideoStartThatDoesNotSetTheFlagOnceFails() {
        for (shape in listOf(StartShape.NO_CALL, StartShape.TWO_STATES, StartShape.FLAG_FROM_A_CALL, StartShape.STATE_REWRITTEN)) {
            val context = PatchContexts.of(classes(shape = shape))
            val failure = assertThrows(shape.name, PatchException::class.java) { context.findFeedSoundSite() }
            assertTrue("$shape: ${failure.message}", failure.message!!.contains("found 0") || failure.message!!.contains("found 2"))
            assertTrue("something was written", context.start().code().none { it.referenceText() == FEED_START_WITH_SOUND })
        }
    }

    @Test
    fun aJumpIntoTheCallOrAnOverwrittenControllerFails() {
        val jump = assertThrows(PatchException::class.java) { PatchContexts.of(classes(shape = StartShape.JUMP_TO_CALL)).findFeedSoundSite() }
        assertTrue(jump.message!!, jump.message!!.contains("jumps to the sound flag call"))
        val overwritten = assertThrows(PatchException::class.java) { PatchContexts.of(classes(shape = StartShape.THIS_OVERWRITTEN)).findFeedSoundSite() }
        assertTrue(overwritten.message!!, overwritten.message!!.isNotEmpty())
    }

    /**
     * In each of the version's three builds the feed controller is found by its string, its video start is found once, and
     * the hook goes in once, in front of the sound flag call, with three borrowed locals up to v15 that nothing reads
     * afterwards and every other instruction in place. It turns on the audio state the Reels toggle flips, through the
     * controller's session field. Read as the patcher reads an APK, and copied.
     */
    @Test
    fun eachDeclaredBuildHoldsItsFeedVideoStart() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        assertEquals("every 452 build", Fixtures.BUILDS, bundles.size)
        for (apk in bundles) {
            val name = if (apk.extension == "apk") apk.parentFile.name else apk.name
            val holding = FixtureDex.classesHolding(apk, FEED_CONTROLLER_STRING)
            assertEquals("$name: classes loading the controller's string", 1, holding.size)
            val wanted = wanted(apk, holding.single())
            for ((read, classesOf) in listOf("copied" to FixtureDex::classes, "as read" to FixtureDex::classesAsRead)) {
                val what = "$name ($read)"
                val classes = classesOf(apk, wanted).values
                val context = PatchContexts.of(classes)
                val originals = classes.flatMap { context.mutableClassDefBy(it.type).methods }
                    .filter { it.implementation != null && it.definingClass == holding.single().type }.associateWith(::NeutralNativePath)

                val site = context.findFeedSoundSite()
                assertEquals("$what: three borrowed locals", 3, site.scratch.distinct().size)
                assertTrue("$what: the borrowed locals fit an invoke", site.scratch.all { it in 0..15 })
                assertTrue("$what: the flag fits an invoke", site.flag in 0..15)
                assertTrue("$what: the flag isn't borrowed", site.flag !in site.scratch)
                assertEquals("$what: the session field", userSession, site.session.type)
                assertEquals("$what: on the controller", holding.single().type, site.session.definingClass)
                // 453 and later: the setter takes a second boolean, whether the user asked, and the hook loads a false for it.
                val extra = if (site.setter.parameterTypes.size == 2) 1 else 0
                val length = hookLength + extra
                assertEquals("$what: the hook goes into the video start", holding.single().type, site.start.definingClass)
                context.apply { applyFeedSound(site) }

                val asking = classes.flatMap { c ->
                    context.mutableClassDefBy(c.type).methods.filter { m -> m.code().any { it.referenceText() == FEED_START_WITH_SOUND } }
                }
                assertEquals("$what: methods asking", listOf(site.start.definingClass to site.start.name), asking.map { it.definingClass to it.name })
                val start = asking.single()
                val code = start.code()
                val asks = code.indices.filter { code[it].referenceText() == FEED_START_WITH_SOUND }
                assertEquals("$what: asks once", 1, asks.size)
                val ask = asks.single()
                val call = ask - 1 + length
                assertEquals("$what: the flag test first", Opcode.IF_NEZ, code[ask - 1].opcode)
                assertEquals("$what: the flag call follows the hook", Opcode.INVOKE_VIRTUAL, code[call].opcode)
                assertEquals("$what: the flag call is a one boolean method", true, code[call].referenceText()!!.endsWith("(Z)V"))
                assertEquals("$what: the flag is what the call hands over", site.flag, (code[call] as FiveRegisterInstruction).registerD)
                assertEquals("$what: the session", site.session.toString(), code[ask + 5].referenceText())
                assertEquals("$what: the factory", site.factory.toString(), code[ask + 7].referenceText())
                if (extra == 1) {
                    assertEquals("$what: not the user's own change", 0, (code[ask + 9] as NarrowLiteralInstruction).narrowLiteral)
                }
                assertEquals("$what: the setter", site.setter.toString(), code[ask + 9 + extra].referenceText())
                assertEquals("$what: set to the flag", site.flag, (code[ask + 9 + extra] as FiveRegisterInstruction).registerD)
                assertTrue("$what: a jump lands inside the hook", (ask until call).none { it in start.jumpTargets() })
                val p0 = start.implementation!!.registerCount - 11
                assertEquals("$what: the controller is read from its own register", listOf(p0), code[ask].arguments())
                val borrowed = code.subList(ask - 1, call).mapNotNull { (it as? OneRegisterInstruction)?.registerA }
                    .filter { it != p0 && it != site.flag && it !in site.scratch }
                assertTrue("$what: the hook touched a register it didn't borrow: $borrowed", borrowed.isEmpty())
                val added = (ask - 1 until call).toSet()
                for ((method, original) in originals) {
                    val mine = method.code().indices.filter { method.name == start.name && method.definingClass == start.definingClass && it in added }.toSet()
                    original.assertPreserved("$what ${method.name}", method, mine)
                }
            }
        }
    }

    // The classes a build is read from: the controller and the types its code makes, the Reels audio toggles and the
    // classes their code calls into, the audio state among them.
    private fun wanted(apk: File, holder: ClassDef): Set<String> {
        val toggles = FixtureDex.classesHolding(apk, toggleMarker)
        val called = toggles.flatMap { c -> c.methods.flatMap { m -> m.code().mapNotNull { (it.reference() as? MethodReference)?.definingClass } } }
        return holder.methods.flatMap { m -> m.code().filter { it.opcode == Opcode.NEW_INSTANCE }.map { (it as ReferenceInstruction).reference.toString() } }
            .toSet() + holder.type + toggles.map { it.type } + called.filter { it.startsWith("L") }
    }

    private enum class StartShape { PLAIN, NO_CALL, TWO_STATES, FLAG_FROM_A_CALL, STATE_REWRITTEN, JUMP_TO_CALL, THIS_OVERWRITTEN }

    /**
     * A small app: a controller holding the user session, whose video start makes a state object, hands it a flag that
     * is a 0 or a 1 on every path, and holds it; a state class with the one boolean setter; and a Reels controller whose
     * audio toggle flips the account's audio state. The controller's key handler loads the string the finder looks for.
     */
    private fun classes(
        controllers: Int = 1,
        shape: StartShape = StartShape.PLAIN,
        sessions: Int = 1,
        statePublic: Boolean = true,
    ): List<ClassDef> {
        val flagInit = when (shape) {
            StartShape.FLAG_FROM_A_CALL -> "invoke-virtual { p0 }, $controller->soundSetting()Z\nmove-result v0"
            else -> "const/4 v0, 0x0\nif-eqz p8, :off\nconst/4 v0, 0x1\n:off"
        }
        val make = """
            new-instance v2, $item
            move-object v3, p1
            move-object v4, p2
            const/4 v5, 0x0
            const/4 v6, 0x0
            const/4 v7, 0x0
            const/4 v8, 0x0
            const/4 v9, 0x0
            invoke-direct/range { v2 .. v9 }, $item-><init>(${media}Ljava/lang/Object;IIIZZ)V
        """
        val setCall = when (shape) {
            StartShape.NO_CALL -> ""
            StartShape.TWO_STATES -> "invoke-virtual { v2, v0 }, $item->setSound(Z)V\nnew-instance v2, $item\ninvoke-virtual { v2, v0 }, $item->setSound(Z)V"
            StartShape.JUMP_TO_CALL -> ":call\ninvoke-virtual { v2, v0 }, $item->setSound(Z)V"
            else -> "invoke-virtual { v2, v0 }, $item->setSound(Z)V"
        }
        val rewritten = if (shape == StartShape.STATE_REWRITTEN) "const/4 v2, 0x0" else ""
        val jumpIn = if (shape == StartShape.JUMP_TO_CALL) "if-eqz p9, :call" else ""
        val thisBefore = if (shape == StartShape.THIS_OVERWRITTEN) "const/4 p0, 0x0" else ""
        val parameters = listOf(media, "Ljava/lang/Object;", "Ljava/lang/Object;", "Ljava/lang/Object;", "I", "I", "I", "Z", "Z", "Z")
        val start = method(controller, "start", parameters, "V", 10, body = """
            $thisBefore
            $flagInit
            $jumpIn
            $make
            $rewritten
            $setCall
            iput-object v2, p0, $controller->item:$item
            return-void
        """)
        val keys = method(controller, "onKey", emptyList(), "V", 1, body = """
            const-string v0, "$FEED_CONTROLLER_STRING"
            invoke-static { v0 }, $trace
            return-void
        """)
        // A start with the same parameters that returns something: not the video start.
        val other = method(controller, "other", parameters, "Z", 2, body = "const/4 v0, 0x0\nreturn v0")
        val sessionFields = (0 until sessions).map { n ->
            ImmutableField(controller, if (n == 0) "session" else "session$n", userSession, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null)
        }
        // A static session field is not the controller's own.
        val staticSession = ImmutableField(controller, "lastSession", userSession, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, null)
        val controllerClass = ImmutableClassDef(
            controller, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null,
            listOf(ImmutableField(controller, "item", item, AccessFlags.PUBLIC.value, null, null, null), staticSession) + sessionFields,
            listOf(start, keys, other),
        )
        val audioState = if (statePublic) state else "Lother/AudioState;"
        val toggle = method(reels, "toggleAudio", listOf("I"), "V", 6, body = """
            const-string v0, "$toggleMarker"
            invoke-static { v0 }, $trace
            iget-object v1, p0, $reels->session:$userSession
            invoke-static { v1 }, $audioState->of($userSession)$audioState
            move-result-object v2
            invoke-virtual { v2 }, $audioState->isOn()Z
            move-result v3
            xor-int/lit8 v4, v3, 0x1
            invoke-virtual { v2, v4 }, $audioState->set(Z)V
            return-void
        """)
        val reelsClass = ImmutableClassDef(
            reels, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null,
            listOf(ImmutableField(reels, "session", userSession, AccessFlags.PUBLIC.value, null, null, null)),
            listOf(toggle),
        )
        // Out of the controller's package, a state class that isn't public can't be reached from it.
        val stateClass = ImmutableClassDef(
            audioState, (if (statePublic) AccessFlags.PUBLIC.value else 0) or AccessFlags.FINAL.value, "Ljava/lang/Object;",
            null, null, null, null,
            listOf(
                method(audioState, "of", listOf(userSession), audioState, 1, body = "const/4 v0, 0x0\nreturn-object v0", static = true),
                method(audioState, "isOn", emptyList(), "Z", 1, body = "const/4 v0, 0x1\nreturn v0"),
                method(audioState, "set", listOf("Z"), "V", 0, body = "return-void"),
            ),
        )
        val second = ImmutableClassDef(
            holder, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, null,
            listOf(keys.let { method(holder, "onKey", emptyList(), "V", 1, body = "const-string v0, \"$FEED_CONTROLLER_STRING\"\ninvoke-static { v0 }, $trace\nreturn-void") }),
        )
        val itemClass = ImmutableClassDef(
            item, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, null,
            listOf(
                method(item, "<init>", listOf(media, "Ljava/lang/Object;", "I", "I", "I", "Z", "Z"), "V", 0, body = "return-void"),
                method(item, "setSound", listOf("Z"), "V", 0, body = "return-void"),
            ),
        )
        return when (controllers) {
            0 -> listOf(ImmutableClassDef(
                controller, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, null,
                listOf(start.let { method(controller, "start", parameters, "V", 10, body = "return-void") }),
            ), itemClass, reelsClass, stateClass)
            2 -> listOf(controllerClass, second, itemClass, reelsClass, stateClass)
            else -> listOf(controllerClass, itemClass, reelsClass, stateClass)
        }
    }

    private fun method(
        owner: String,
        name: String,
        parameters: List<String>,
        returns: String,
        registers: Int,
        body: String,
        static: Boolean = false,
        flags: Int = AccessFlags.PUBLIC.value,
    ): Method {
        val total = registers + (if (static) 0 else 1) + parameters.size
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
                flags or (if (static) AccessFlags.STATIC.value else 0), null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent().lines().filter { it.isNotBlank() }.joinToString("\n"))
        return ImmutableMethod.of(mutable)
    }

    private fun app.morphe.patcher.patch.BytecodePatchContext.start(): MutableMethod =
        mutableClassDefBy(controller).methods.single { it.name == "start" }

    private fun MutableMethod.jumpTargets(): Set<Int> =
        implementation!!.instructions.filterIsInstance<BuilderOffsetInstruction>().map { it.target.location.index }.toSet()

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.referenceText(): String? = reference()?.toString()

    private fun Instruction.arguments(): List<Int> = when (this) {
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        else -> emptyList()
    }

    /** An instruction as text: its opcode, registers and reference, enough to see a change. */
    private fun Instruction.describe(): String = buildString {
        append(opcode.name)
        if (this@describe is OneRegisterInstruction) append(" v$registerA")
        if (this@describe is TwoRegisterInstruction) append(" v$registerB")
        append(arguments().joinToString(prefix = " {", postfix = "}") { "v$it" })
        referenceText()?.let { append(" $it") }
    }
}
