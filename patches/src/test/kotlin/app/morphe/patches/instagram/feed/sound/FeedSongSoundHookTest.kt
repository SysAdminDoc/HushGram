/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.sound

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.NeutralNativePath
import app.morphe.patches.instagram.media.taptoplay.REELS_PLAYER_PAUSE
import app.morphe.patches.instagram.stories.sound.findAudioState
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
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** The photo song half of Start feed videos with sound (#762). */
class FeedSongSoundHookTest {
    private val media = "Lcom/instagram/feed/media/Media;"
    private val controller = "Lfixture/MusicController;"
    private val userSession = "Lcom/instagram/common/session/UserSession;"
    private val state = "Lfixture/AudioState;"
    private val trace = "Lfixture/Trace;->begin(Ljava/lang/String;)V"
    private val factory = ImmutableMethodReference(state, "of", listOf(userSession), state)
    private val reader = "$state->read(Z)Z"

    /**
     * The instructions the hook adds in front of the test of the state's answer: skip a true answer, take the
     * session from the controller, skip a missing one, take the account's audio state, ask its reader with true,
     * skip a state that was set, then ask the extension with the controller into the answer's register.
     */
    private val hookOpcodes = listOf(
        Opcode.IF_NEZ, Opcode.MOVE_OBJECT_FROM16, Opcode.IGET_OBJECT, Opcode.IF_EQZ, Opcode.INVOKE_STATIC,
        Opcode.MOVE_RESULT_OBJECT, Opcode.CONST_4, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT, Opcode.IF_EQZ,
        Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT,
    )
    private val hookLength = hookOpcodes.size

    /** The hook the patch writes is in the FeedSound the bundle ships, public and static, and takes the controller as an Object. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(FEED_SOUND).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$FEED_START_SONG_WITH_SOUND is not in the extension: $declared", FEED_START_SONG_WITH_SOUND.substringAfter("->") in declared)
    }

    /**
     * Before the hook a photo post's song played only on a state already on, so a song before any video stayed
     * silent. The hook goes in front of the test of the state's answer: a true answer goes straight on, a missing
     * session or a state that was set (the reader's true default comes back false) leaves the answer false, and only
     * a state never set asks the extension with the controller, whose answer the test reads. Every other instruction
     * keeps its place, and the stock test still sends a false answer to the muted path.
     */
    @Test
    fun theHookAsksOnlyWhenTheStateWasNeverSet() {
        val context = PatchContexts.of(classes())
        val before = NeutralNativePath(context.visible())
        val stockTest = context.visible().code().indexOfFirst { it.referenceText() == reader } + 2
        val muted = context.visible().code()[stockTest].jumpTarget()!!

        val site = context.findFeedSongSite(factory)
        assertEquals("the test of the state's answer", stockTest, site.index)
        context.apply { applyFeedSong(site) }

        val method = context.visible()
        val code = method.code()
        val start = code.indexOfFirst { it.referenceText() == FEED_START_SONG_WITH_SOUND } - 10
        assertEquals("the hook goes in front of the test", stockTest, start)
        assertEquals(hookOpcodes, code.subList(start, start + hookLength).map { it.opcode })
        val test = start + hookLength
        assertEquals("the stock test follows the hook", Opcode.IF_EQZ, code[test].opcode)
        assertEquals("and still sends a false answer to the muted path", muted + hookLength, code[test].jumpTarget())
        val answer = (code[test] as OneRegisterInstruction).registerA
        assertEquals("a true answer goes straight on", test, code[start].jumpTarget())
        assertEquals("tested in the answer's register", answer, (code[start] as OneRegisterInstruction).registerA)
        val p2 = method.implementation!!.registerCount - 2
        assertEquals("the controller is taken from its own register", p2, (code[start + 1] as TwoRegisterInstruction).registerB)
        val borrowed = (code[start + 1] as OneRegisterInstruction).registerA
        assertEquals("into the one borrowed local", site.state, borrowed)
        assertEquals("the controller's session", "$controller->session:$userSession", code[start + 2].referenceText())
        assertEquals("a missing session goes on to the test", test, code[start + 3].jumpTarget())
        assertEquals("the account's audio state", factory.toString(), code[start + 4].referenceText())
        assertEquals("of that session", listOf(borrowed), code[start + 4].arguments())
        assertEquals("the reader is asked with true", 1, (code[start + 6] as NarrowLiteralInstruction).narrowLiteral)
        assertEquals("loaded into the answer's register", answer, (code[start + 6] as OneRegisterInstruction).registerA)
        assertEquals("the reader", reader, code[start + 7].referenceText())
        assertEquals("on the state, with that true", listOf(borrowed, answer), code[start + 7].arguments())
        assertEquals("its answer in the answer's register", answer, (code[start + 8] as OneRegisterInstruction).registerA)
        assertEquals("a state that was set goes on to the test, answer false", test, code[start + 9].jumpTarget())
        assertEquals("the extension is asked with the controller", listOf(p2), code[start + 10].arguments())
        assertEquals("its answer is the one the test reads", answer, (code[start + 11] as OneRegisterInstruction).registerA)
        val written = code.subList(start, test).filter { it.opcode.setsRegister() }.map { (it as OneRegisterInstruction).registerA }.toSet()
        assertEquals("the hook writes only the answer and its borrowed local", setOf(answer, borrowed), written)
        assertEquals("nothing outside the hook jumps into it", listOf(test), (start + 1..test).filter { it in method.offsetTargets() }.distinct())
        before.assertPreserved("the music controller", method, (start until test).toSet())
    }

    /** Without the controller's one session field the patch stops first, and nothing changes. */
    @Test
    fun withoutOneSessionFieldThePatchFails() {
        for ((sessions, found) in listOf(0 to "found 0", 2 to "found 2")) {
            val context = PatchContexts.of(classes(sessions = sessions))
            val failure = assertThrows(PatchException::class.java) { context.findFeedSongSite(factory) }
            assertTrue(failure.message!!, failure.message!!.contains("field, $found"))
            assertTrue("something was written", context.visible().code().none { it.referenceText() == FEED_START_SONG_WITH_SOUND })
        }
    }

    /** A music controller that asks the state some other way, twice, or not at all isn't the one the patch knows. */
    @Test
    fun aSongTestOfAnotherShapeFails() {
        for ((shape, found) in listOf(Shape.ASKS_TRUE to "found 0", Shape.OTHER_FACTORY to "found 0", Shape.NOT_STATIC to "found 0", Shape.TWICE to "found 2")) {
            val context = PatchContexts.of(classes(shape = shape))
            val failure = assertThrows(shape.name, PatchException::class.java) { context.findFeedSongSite(factory) }
            assertTrue("$shape: ${failure.message}", failure.message!!.contains(found))
        }
    }

    /**
     * A branch onto the test, an answer kept past v15, a controller register written over before the test and no
     * local free there each stop the patch naming why.
     */
    @Test
    fun aTestTheHookCantSitInFrontOfFails() {
        val cases = listOf(
            Shape.JUMP_ONTO_TEST to "jumps onto its test",
            Shape.HIGH_ANSWER to "past what the hook can load",
            Shape.CONTROLLER_OVERWRITTEN to "parameter 2",
            Shape.NO_FREE_LOCAL to "needs 1",
        )
        for ((shape, why) in cases) {
            val context = PatchContexts.of(classes(shape = shape))
            val failure = assertThrows(shape.name, PatchException::class.java) { context.findFeedSongSite(factory) }
            assertTrue("$shape: ${failure.message}", failure.message!!.contains(why))
        }
    }

    /**
     * In each of the version's declared builds the music controller's song test is found once, with the account
     * audio state the Reels toggle flips, and the hook goes in once, in front of it, with one borrowed local up to
     * v15 that nothing reads afterwards and every other instruction of the controller in place. Read as the patcher
     * reads an APK, and copied.
     */
    @Test
    fun eachDeclaredBuildHoldsItsSongTest() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        assertEquals("every declared build", Fixtures.BUILDS, bundles.size)
        for (apk in bundles) {
            val name = if (apk.extension == "apk") apk.parentFile.name else apk.name
            val wanted = wanted(apk)
            for ((read, classesOf) in listOf("copied" to FixtureDex::classes, "as read" to FixtureDex::classesAsRead)) {
                val what = "$name ($read)"
                val classes = classesOf(apk, wanted).values
                val context = PatchContexts.of(classes)
                val (stateFactory, _, _) = context.findAudioState()
                val site = context.findFeedSongSite(stateFactory)
                val owner = site.visible.definingClass
                val originals = context.mutableClassDefBy(owner).methods.filter { it.implementation != null }.associateWith(::NeutralNativePath)
                assertEquals("$what: the post's media first", media, site.visible.parameterTypes.first().toString())
                assertEquals("$what: the controller third", owner, site.visible.parameterTypes[2].toString())
                assertTrue("$what: the answer fits a const/4", site.answer in 0..15)
                assertTrue("$what: the borrowed local fits an invoke", site.state in 0..15)
                assertTrue("$what: the answer isn't borrowed", site.state != site.answer)
                assertEquals("$what: the session field", userSession, site.session.type)
                assertEquals("$what: on the controller", owner, site.session.definingClass)
                assertEquals("$what: the state's reader", stateFactory.returnType, site.reader.definingClass)
                context.apply { applyFeedSong(site) }

                val asking = classes.flatMap { c ->
                    context.mutableClassDefBy(c.type).methods.filter { m -> m.code().any { it.referenceText() == FEED_START_SONG_WITH_SOUND } }
                }
                assertEquals("$what: methods asking", listOf(owner to site.visible.name), asking.map { it.definingClass to it.name })
                val method = asking.single()
                val code = method.code()
                val asks = code.indices.filter { code[it].referenceText() == FEED_START_SONG_WITH_SOUND }
                assertEquals("$what: asks once", 1, asks.size)
                val start = asks.single() - 10
                val test = start + hookLength
                assertEquals("$what: the hook's shape", hookOpcodes, code.subList(start, test).map { it.opcode })
                assertEquals("$what: the stock test follows", Opcode.IF_EQZ, code[test].opcode)
                assertEquals("$what: on the answer", site.answer, (code[test] as OneRegisterInstruction).registerA)
                for (skip in listOf(start, start + 3, start + 9)) assertEquals("$what: a skip at $skip goes to the test", test, code[skip].jumpTarget())
                assertEquals("$what: the session", site.session.toString(), code[start + 2].referenceText())
                assertEquals("$what: the factory", stateFactory.toString(), code[start + 4].referenceText())
                assertEquals("$what: the reader asked with true", 1, (code[start + 6] as NarrowLiteralInstruction).narrowLiteral)
                assertEquals("$what: the reader", site.reader.toString(), code[start + 7].referenceText())
                val controllerRegister = method.implementation!!.registerCount - method.parameterTypes.size + 2
                assertEquals("$what: the controller from its own register", listOf(controllerRegister), code[start + 10].arguments())
                val written = code.subList(start, test).filter { it.opcode.setsRegister() }.map { (it as OneRegisterInstruction).registerA }.toSet()
                assertEquals("$what: the hook writes only the answer and its borrowed local", setOf(site.answer, site.state), written)
                assertEquals("$what: nothing outside the hook jumps into it", listOf(test), (start + 1..test).filter { it in method.offsetTargets() }.distinct())
                val added = (start until test).toSet()
                for ((m, original) in originals) {
                    val hooked = m.name == method.name && m.parameterTypes.map(Any::toString) == method.parameterTypes.map(Any::toString)
                    original.assertPreserved("$what ${m.name}", m, if (hooked) added else emptySet())
                }
            }
        }
    }

    // The classes a build is read from: the ones loading the music controller's string, the Reels audio toggles and
    // the classes their code calls into, the audio state among them.
    private fun wanted(apk: File): Set<String> {
        val music = FixtureDex.classesLoading(apk, FEED_MUSIC_STRING)
        val toggles = FixtureDex.classesLoading(apk, REELS_PLAYER_PAUSE)
        val called = toggles.flatMap { c -> c.methods.flatMap { m -> m.code().mapNotNull { (it.reference() as? MethodReference)?.definingClass } } }
        return music.map { it.type }.toSet() + toggles.map { it.type } + called.filter { it.startsWith("L") }
    }

    private enum class Shape { PLAIN, ASKS_TRUE, OTHER_FACTORY, NOT_STATIC, TWICE, JUMP_ONTO_TEST, HIGH_ANSWER, CONTROLLER_OVERWRITTEN, NO_FREE_LOCAL }

    /**
     * A small app: a music controller holding the user session, whose static method for a post coming into view
     * asks whether enough of it shows, takes the account's audio state from the session, asks its reader with false,
     * plays the song on a true and takes the muted path on a false; a key handler loading the string the finder looks
     * for; and the audio state with its factory and reader.
     */
    private fun classes(shape: Shape = Shape.PLAIN, sessions: Int = 1): List<ClassDef> {
        // Past v15 the parameters are copied down first, as Redex does, since an invoke can't name them there.
        val high = shape == Shape.HIGH_ANSWER
        val answer = if (high) "v16" else "v0"
        val (m, c, f) = if (high) Triple("v3", "v2", "v4") else Triple("p0", "p2", "p3")
        val copies = if (high) "move-object/from16 v2, p2\nmove-object/from16 v3, p0\nmove/from16 v4, p3" else ""
        val zero = if (shape == Shape.ASKS_TRUE) "const/4 v1, 0x1" else "const/4 v1, 0x0"
        val made = if (shape == Shape.OTHER_FACTORY) "$state->other($userSession)$state" else factory.toString()
        val ask = """
            iget-object v1, $c, $controller->session:$userSession
            invoke-static { v1 }, $made
            move-result-object v0
            $zero
            invoke-virtual { v0, v1 }, $reader
            move-result $answer
        """
        val second = if (shape == Shape.TWICE) "$ask\nif-eqz $answer, :muted" else ""
        // A branch onto the test from before the state is asked.
        val jump = if (shape == Shape.JUMP_ONTO_TEST) "if-eqz p1, :test" else ""
        val overwrite = if (shape == Shape.CONTROLLER_OVERWRITTEN) "const/4 p2, 0x0" else ""
        val locals = when (shape) {
            Shape.HIGH_ANSWER -> 17
            Shape.NO_FREE_LOCAL -> 2
            else -> 3
        }
        val parameters = listOf(media, "Ljava/lang/Object;", controller, "F")
        val visible = method(controller, "visible", parameters, "V", locals, static = shape != Shape.NOT_STATIC, body = """
            $copies
            invoke-static { $c, $f }, $controller->shows(${controller}F)Z
            move-result v0
            if-eqz v0, :end
            $overwrite
            $jump
            $second
            $ask
            :test
            if-eqz $answer, :muted
            invoke-static { $m, $c, v1 }, $controller->play(${media}${controller}Z)V
            :end
            return-void
            :muted
            invoke-static { $m, $c }, $controller->muted(${media}${controller})V
            return-void
        """)
        val keys = method(controller, "onKey", emptyList(), "V", 1, body = """
            const-string v0, "$FEED_MUSIC_STRING"
            invoke-static { v0 }, $trace
            return-void
        """)
        // The same parameters with a boolean return: not the method a post coming into view runs.
        val other = method(controller, "other", parameters, "Z", 1, static = true, body = "const/4 v0, 0x0\nreturn v0")
        val sessionFields = (0 until sessions).map { n ->
            ImmutableField(controller, if (n == 0) "session" else "session$n", userSession, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null)
        }
        // A static session field is not the controller's own.
        val staticSession = ImmutableField(controller, "lastSession", userSession, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, null)
        val controllerClass = ImmutableClassDef(
            controller, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null,
            listOf(staticSession) + sessionFields,
            listOf(visible, keys, other),
        )
        val stateClass = ImmutableClassDef(
            state, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, null,
            listOf(
                method(state, "of", listOf(userSession), state, 1, body = "const/4 v0, 0x0\nreturn-object v0", static = true),
                method(state, "other", listOf(userSession), state, 1, body = "const/4 v0, 0x0\nreturn-object v0", static = true),
                method(state, "read", listOf("Z"), "Z", 0, body = "return p1"),
            ),
        )
        return listOf(controllerClass, stateClass)
    }

    private fun method(
        owner: String,
        name: String,
        parameters: List<String>,
        returns: String,
        registers: Int,
        body: String,
        static: Boolean = false,
    ): Method {
        val total = registers + (if (static) 0 else 1) + parameters.sumOf { if (it == "J" || it == "D") 2 else 1 }
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
                AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0), null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent().lines().filter { it.isNotBlank() }.joinToString("\n"))
        return ImmutableMethod.of(mutable)
    }

    private fun BytecodePatchContext.visible(): MutableMethod =
        mutableClassDefBy(controller).methods.single { it.name == "visible" }

    /** Where the method's branches and gotos land, as indexes. */
    private fun MutableMethod.offsetTargets(): List<Int> =
        implementation!!.instructions.filterIsInstance<BuilderOffsetInstruction>().map { it.target.location.index }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.jumpTarget(): Int? = (this as? BuilderOffsetInstruction)?.target?.location?.index

    private fun Instruction.referenceText(): String? = reference()?.toString()

    private fun Instruction.arguments(): List<Int> = when (this) {
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        else -> emptyList()
    }
}
