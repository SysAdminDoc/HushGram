/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.stories.sound

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.NeutralNativePath
import app.morphe.patches.instagram.reels.tapvolume.TOGGLE_AUDIO
import app.morphe.patches.instagram.stories.autoadvance.STORY_VIEWER
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class StartStoriesWithSoundHookTest {
    private val userSession = "Lcom/instagram/common/session/UserSession;"
    private val bundle = "Landroid/os/Bundle;"
    private val base = "Lfixture/BaseFragment;"
    private val state = "Lfixture/AudioState;"
    private val hiddenState = "Lother/AudioState;"
    private val reels = "Lfixture/ClipsVideoPlayerController;"
    private val check = "Lfixture/SoundCheck;"
    private val integer = "Ljava/lang/Integer;"
    private val trace = "Lfixture/Trace;->begin(Ljava/lang/String;)V"
    private val toggleMarker = "android_purge_26_q3_$TOGGLE_AUDIO"

    /** The instructions the hook adds: ask, test, take the session, take the state, set it on. */
    private val hookLength = 10

    /** The hook the patch writes is in the StorySound the bundle ships, public and static, and takes the viewer as an Object. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(STORY_SOUND).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$START_WITH_SOUND is not in the extension: $declared", START_WITH_SOUND.substringAfter("->") in declared)
    }

    /**
     * Right after the super call the viewer asks, and a yes sets the account's audio state to on through its own setter;
     * every other instruction keeps its place, and the hook never writes the viewer's own register.
     */
    @Test
    fun theViewerAsksThenSetsTheAudioState() {
        val context = PatchContexts.of(classes())
        val original = context.onCreate().code().map { it.describe() }

        context.apply { applyStorySound(findStorySoundSite()) }

        val code = context.onCreate().code()
        val ask = code.indexOfFirst { it.referenceText() == START_WITH_SOUND }
        assertEquals("right after the super call", "$base->onCreate($bundle)V", code[ask - 1].referenceText())
        assertEquals(
            listOf(
                Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.INVOKE_VIRTUAL_RANGE, Opcode.MOVE_RESULT_OBJECT,
                Opcode.IF_EQZ, Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.CONST_4, Opcode.INVOKE_VIRTUAL,
            ),
            code.subList(ask, ask + hookLength).map { it.opcode },
        )
        assertEquals("the session getter, called on the viewer", "$STORY_VIEWER->getSession()$userSession", code[ask + 3].referenceText())
        assertEquals("the state factory", "$state->of($userSession)$state", code[ask + 6].referenceText())
        assertEquals("the setter", "$state->set(Z)V", code[ask + 9].referenceText())
        val p0 = context.onCreate().implementation!!.registerCount - 2
        assertEquals("the hook reads the viewer from its own register", listOf(p0), code[ask].arguments())
        assertEquals(listOf(p0), code[ask + 3].arguments())
        val written = (ask until ask + hookLength).filter { code[it].opcode in setOf(Opcode.MOVE_RESULT, Opcode.MOVE_RESULT_OBJECT, Opcode.CONST_4) }
            .map { (code[it] as OneRegisterInstruction).registerA }
        assertTrue("the hook wrote the viewer's register or a parameter: $written", written.isNotEmpty() && written.all { it < p0 })
        assertEquals("what's left of the method", original.size + hookLength, code.size)
        assertEquals("the original instructions keep their order", original, code.take(ask).map { it.describe() } + code.drop(ask + hookLength).map { it.describe() })
        assertTrue("a jump lands inside the hook", ask !in context.onCreate().jumpTargets())
    }

    @Test
    fun withoutTheViewerThePatchFails() {
        val context = PatchContexts.of(classes(viewerType = "Lfixture/SomeOtherFragment;"))
        val failure = assertThrows(PatchException::class.java) { context.findStorySoundSite() }
        assertTrue(failure.message!!, failure.message!!.contains(STORY_VIEWER))
    }

    @Test
    fun anOnCreateThatDoesNotCallSuperOnceFailsBeforeAnythingChanges() {
        for ((supers, expected) in listOf(0 to "found 0", 2 to "found 2")) {
            val context = PatchContexts.of(classes(supers = supers))
            val failure = assertThrows(PatchException::class.java) { context.findStorySoundSite() }
            assertTrue(failure.message!!, failure.message!!.contains(expected))
            assertTrue("something was written", context.onCreate().code().none { it.referenceText() == START_WITH_SOUND })
        }
    }

    @Test
    fun aJumpIntoThePointOrAnOverwrittenViewerFails() {
        val jump = assertThrows(PatchException::class.java) { PatchContexts.of(classes(jumpsToPoint = true)).findStorySoundSite() }
        assertTrue(jump.message!!, jump.message!!.contains("jumps to just past its super call"))
        val overwritten = assertThrows(PatchException::class.java) { PatchContexts.of(classes(thisOverwritten = true)).findStorySoundSite() }
        assertTrue(overwritten.message!!, overwritten.message!!.isNotEmpty())
    }

    @Test
    fun aViewerWithoutOneSessionGetterFails() {
        for (getters in listOf(0, 2)) {
            val failure = assertThrows(PatchException::class.java) { PatchContexts.of(classes(getters = getters)).findStorySoundSite() }
            assertTrue(failure.message!!, failure.message!!.contains("found $getters"))
        }
    }

    @Test
    fun anAudioToggleThatDoesNotFlipTheStateOnceFails() {
        for ((toggles, flip) in listOf(0 to 1, 2 to 1, 1 to 2)) {
            val failure = assertThrows(PatchException::class.java) { PatchContexts.of(classes(toggles = toggles, flip = flip)).findStorySoundSite() }
            assertTrue("$toggles toggles, flip $flip: ${failure.message}", failure.message!!.contains("audio"))
        }
    }

    @Test
    fun aViewerThatDoesNotReadTheStateThroughItsSoundCheckFails() {
        val noCheck = assertThrows(PatchException::class.java) { PatchContexts.of(classes(viewerChecks = false)).findStorySoundSite() }
        assertTrue(noCheck.message!!, noCheck.message!!.contains("sound check"))
        val otherState = assertThrows(PatchException::class.java) { PatchContexts.of(classes(checkReadsOtherState = true)).findStorySoundSite() }
        assertTrue(otherState.message!!, otherState.message!!.contains("doesn't read the audio state"))
    }

    @Test
    fun anAudioStateTheViewerCantReachFails() {
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(classes(statePublic = false)).findStorySoundSite() }
        assertTrue(failure.message!!, failure.message!!.contains("can't reach the audio state"))
    }

    /**
     * In each of the version's seven builds the viewer's onCreate, the session getter above it, the audio toggle and
     * the viewer's sound check are found once, and the hook goes in once, right after the super call, with two borrowed
     * locals up to v15 that nothing reads afterwards and every other instruction in place. Read as the patcher reads
     * an APK, and copied.
     */
    @Test
    fun eachDeclaredBuildHoldsItsStoryViewer() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        assertEquals("seven builds of the version", 7, bundles.size)
        for (apk in bundles) {
            val name = if (apk.extension == "apk") apk.parentFile.name else apk.name
            val wanted = wanted(apk)
            for ((read, classesOf) in listOf("copied" to FixtureDex::classes, "as read" to FixtureDex::classesAsRead)) {
                val what = "$name ($read)"
                val classes = classesOf(apk, wanted).values
                assertTrue("$what: the viewer", classes.any { it.type == STORY_VIEWER })
                val context = PatchContexts.of(classes)
                val originals = classes.flatMap { context.mutableClassDefBy(it.type).methods }
                    .filter { it.implementation != null && it.definingClass == STORY_VIEWER && it.name == "onCreate" }.associateWith(::NeutralNativePath)

                val site = context.findStorySoundSite()
                assertEquals("$what: two borrowed locals", 2, site.scratch.distinct().size)
                assertTrue("$what: the borrowed locals fit an invoke", site.scratch.all { it in 0..15 })
                assertEquals("$what: the hook goes into onCreate", "onCreate", site.onCreate.name)
                context.apply { applyStorySound(site) }

                val asking = classes.flatMap { c ->
                    context.mutableClassDefBy(c.type).methods.filter { m -> m.code().any { it.referenceText() == START_WITH_SOUND } }
                }
                assertEquals("$what: methods asking", listOf(STORY_VIEWER to "onCreate"), asking.map { it.definingClass to it.name })
                val onCreate = asking.single()
                val code = onCreate.code()
                val asks = code.indices.filter { code[it].referenceText() == START_WITH_SOUND }
                assertEquals("$what: asks once", 1, asks.size)
                val ask = asks.single()
                assertTrue("$what: right after the super call", code[ask - 1].opcode == Opcode.INVOKE_SUPER && code[ask - 1].referenceText()!!.contains("->onCreate($bundle)V"))
                assertEquals("$what: the session getter", "$STORY_VIEWER->${site.session.name}()$userSession", code[ask + 3].referenceText())
                assertEquals("$what: the factory", site.factory.toString(), code[ask + 6].referenceText())
                assertEquals("$what: the setter", site.setter.toString(), code[ask + 9].referenceText())
                assertTrue("$what: a jump lands inside the hook", (ask + 1 until ask + hookLength).none { it in onCreate.jumpTargets() })
                val p0 = onCreate.implementation!!.registerCount - 2
                assertEquals("$what: the viewer is read from its own register, last", listOf(p0), code[ask].arguments())
                val borrowed = code.subList(ask, ask + hookLength).mapNotNull { (it as? OneRegisterInstruction)?.registerA }
                    .filter { it != p0 && it !in site.scratch && it !in code[ask].arguments() }
                assertTrue("$what: the hook touched a register it didn't borrow: $borrowed", borrowed.isEmpty())
                val added = (ask until ask + hookLength).toSet()
                for ((method, original) in originals) {
                    val mine = method.code().indices.filter { method.name == onCreate.name && method.definingClass == onCreate.definingClass && it in added }.toSet()
                    original.assertPreserved("$what ${method.name}", method, mine)
                }
            }
        }
    }

    // The files a build is read from, by the types the finder walks: the viewer, what's above it, the toggles and what they and the viewer name.
    private fun wanted(apk: File): Set<String> {
        val toggles = FixtureDex.classesHolding(apk, toggleMarker)
        val seed = mutableSetOf(STORY_VIEWER) + toggles.map { it.type }
        var up = FixtureDex.classes(apk, setOf(STORY_VIEWER)).getValue(STORY_VIEWER).superclass
        var depth = 0
        val chain = mutableSetOf<String>()
        while (up != null && depth++ < 12) {
            chain += up
            up = FixtureDex.classes(apk, setOf(up))[up]?.superclass
        }
        val loaded = FixtureDex.classes(apk, seed + chain)
        val named = loaded.values.filter { it.type == STORY_VIEWER || it.type in toggles.map { t -> t.type } }
            .flatMap { c -> c.methods.flatMap { m -> m.code().flatMap { instruction ->
                when (val ref = instruction.reference()) {
                    is MethodReference -> listOf(ref.definingClass, ref.returnType) + ref.parameterTypes.map(Any::toString)
                    is FieldReference -> listOf(ref.definingClass, ref.type)
                    else -> emptyList()
                }
            } } }.filter { it.startsWith("L") }
        return seed + chain + named
    }

    /**
     * A small app: a viewer whose onCreate calls super (or not), reads a session getter from its base class and asks a
     * sound check, a reels controller with the audio toggle, and the audio state both read.
     */
    private fun classes(
        viewerType: String = STORY_VIEWER,
        supers: Int = 1,
        jumpsToPoint: Boolean = false,
        thisOverwritten: Boolean = false,
        getters: Int = 1,
        toggles: Int = 1,
        flip: Int = 1,
        viewerChecks: Boolean = true,
        checkReadsOtherState: Boolean = false,
        statePublic: Boolean = true,
    ): List<ClassDef> {
        val superCalls = (0 until supers).joinToString("\n") { "invoke-super { p0, p1 }, $base->onCreate($bundle)V" }
        val onCreate = method(viewerType, "onCreate", listOf(bundle), "V", 6, body = """
            const-string v0, "ReelViewerFragment.onCreate"
            invoke-static { v0 }, $trace
            ${if (jumpsToPoint) "if-eqz p1, :point" else ""}
            ${if (thisOverwritten) "const/4 p0, 0x0" else ""}
            $superCalls
            :point
            const/4 v1, 0x0
            iput-object v1, p0, $viewerType->field:Ljava/lang/Object;
            return-void
        """)
        val asksCheck = method(viewerType, "bound", emptyList(), "V", 5, body = """
            iget-object v0, p0, $viewerType->soundCheck:$check
            const/4 v1, 0x0
            const/4 v2, 0x0
            const/4 v3, 0x0
            invoke-virtual { v0, v1, v2, v3 }, $check->sound(${integer}ZZ)Z
            return-void
        """)
        val viewerMethods = listOf(onCreate) + (if (viewerChecks) listOf(asksCheck) else emptyList())
        val readState = if (checkReadsOtherState) hiddenState else state
        val toggle = { name: String ->
            method(reels, name, listOf("I"), "V", 6, body = """
                const-string v0, "$toggleMarker"
                invoke-static { v0 }, $trace
                iget-object v1, p0, $reels->session:$userSession
                invoke-static { v1 }, $state->of($userSession)$state
                move-result-object v2
                invoke-virtual { v2 }, $state->isOn()Z
                move-result v3
                xor-int/lit8 v4, v3, ${if (flip == 1) "0x1" else "0x2"}
                invoke-virtual { v2, v4 }, $state->set(Z)V
                return-void
            """)
        }
        val reelMethods = if (toggles == 0) listOf(method(reels, "other", emptyList(), "V", 0, body = "return-void"))
        else (0 until toggles).map { toggle(if (it == 0) "toggleAudio" else "toggleAudioAgain") }
        val stateFlags = if (statePublic) AccessFlags.PUBLIC.value else 0
        val stateMethods = { owner: String, factoryFlags: Int ->
            listOf(
                method(owner, "of", listOf(userSession), owner, 1, body = "const/4 v0, 0x0\nreturn-object v0", static = true, flags = factoryFlags),
                method(owner, "isOn", emptyList(), "Z", 1, body = "const/4 v0, 0x1\nreturn v0", flags = factoryFlags),
                method(owner, "set", listOf("Z"), "V", 0, body = "return-void", flags = factoryFlags),
            )
        }
        val getterMethods = (0 until getters).map { n ->
            method(base, if (n == 0) "getSession" else "getSessionAgain", emptyList(), userSession, 1, body = "const/4 v0, 0x0\nreturn-object v0")
        } + method(base, "unrelated", emptyList(), "V", 0, body = "return-void") +
            method(base, "bridgeGet", emptyList(), userSession, 1, body = "const/4 v0, 0x0\nreturn-object v0", flags = AccessFlags.PUBLIC.value or AccessFlags.BRIDGE.value or AccessFlags.SYNTHETIC.value)
        val checkMethods = listOf(
            method(check, "sound", listOf(integer, "Z", "Z"), "Z", 4, body = """
                const/4 v1, 0x0
                invoke-static { v1 }, $readState->of($userSession)$readState
                const/4 v0, 0x1
                return v0
            """),
        )
        return listOf(
            ImmutableClassDef(
                viewerType, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, base, null, null, null,
                listOf(
                    ImmutableField(viewerType, "field", "Ljava/lang/Object;", AccessFlags.PUBLIC.value, null, null, null),
                    ImmutableField(viewerType, "soundCheck", check, AccessFlags.PUBLIC.value, null, null, null),
                ),
                viewerMethods,
            ),
            classDef(base, getterMethods),
            classDef(reels, reelMethods, fields = listOf("session" to userSession)),
            ImmutableClassDef(
                state, stateFlags or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, null,
                stateMethods(state, AccessFlags.PUBLIC.value),
            ),
            classDef(hiddenState, stateMethods(hiddenState, AccessFlags.PUBLIC.value)),
            classDef(check, checkMethods),
        )
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
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>, fields: List<Pair<String, String>> = emptyList()): ClassDef =
        ImmutableClassDef(
            type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null,
            fields.map { (name, fieldType) -> ImmutableField(type, name, fieldType, AccessFlags.PUBLIC.value, null, null, null) },
            methods,
        )

    private fun app.morphe.patcher.patch.BytecodePatchContext.onCreate(): MutableMethod =
        mutableClassDefBy(STORY_VIEWER).methods.single { it.name == "onCreate" }

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
