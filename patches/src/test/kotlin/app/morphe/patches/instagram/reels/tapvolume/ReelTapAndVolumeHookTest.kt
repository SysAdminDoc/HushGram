/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.tapvolume

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.NeutralNativePath
import app.morphe.patches.instagram.media.taptoplay.CLIPS_PAUSE
import app.morphe.patches.instagram.media.taptoplay.TOGGLE_PAUSE
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
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ReelTapAndVolumeHookTest {
    private val navigator = "Lfixture/PauseAndMuteNavigator;"
    private val controller = "Lfixture/ClipsVideoPlayerController;"
    private val other = "Lother/ClipsVideoPlayerController;"
    private val function0 = "Lkotlin/jvm/functions/Function0;"
    private val view = "Landroid/view/View;"
    private val objectType = "Ljava/lang/Object;"
    private val trace = "Lfixture/Trace;->begin(Ljava/lang/String;)V"
    private val tapMarker = "android_purge_26_q3_$TOGGLE_PAUSE"
    private val toggleMarker = "android_purge_26_q3_$TOGGLE_AUDIO"

    /** The instructions the hook adds: ask, test, read the controller, call its toggle and return. */
    private val hookLength = 10

    /** The hook the patch writes is in the ReelTapAndVolume the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(REEL_TAP_AND_VOLUME).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$MUTE_INSTEAD_OF_PAUSE is not in the extension: $declared", MUTE_INSTEAD_OF_PAUSE.substringAfter("->") in declared)
    }

    /** The pause path asks first, a yes toggles the controller's audio with the button's reason and returns, and nothing else changes. */
    @Test
    fun thePausePathAsksThenMutesOrPauses() {
        val context = PatchContexts.of(classes())
        val original = context.tap().code().map { it.describe() }

        context.apply { applyReelTap(findReelTapSite()) }

        val code = context.tap().code()
        val ask = code.indexOfFirst { it.referenceText() == MUTE_INSTEAD_OF_PAUSE }
        assertEquals("right after the load of \"$CLIPS_PAUSE\"", CLIPS_PAUSE, ((code[ask - 1] as ReferenceInstruction).reference as StringReference).string)
        assertEquals(listOf(
            Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.IGET_OBJECT, Opcode.INVOKE_INTERFACE,
            Opcode.MOVE_RESULT_OBJECT, Opcode.CHECK_CAST, Opcode.CONST_4, Opcode.INVOKE_VIRTUAL, Opcode.RETURN_VOID,
        ), code.subList(ask, ask + hookLength).map { it.opcode })
        assertEquals("the toggle", "$controller->toggleAudio(I)V", code[ask + 8].referenceText())
        assertEquals("the audio button's reason", -3, (code[ask + 7] as com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction).narrowLiteral)
        assertEquals("the supplier read from this", "$navigator->supplier:$function0", code[ask + 3].referenceText())
        assertTrue("a jump lands inside the hook", ask !in context.tap().jumpTargets())
        val now = code.map { it.describe() }
        assertEquals("only the hook is new", original, now.filterIndexed { at, _ -> at !in ask until ask + hookLength })
    }

    /** A build the patch can't read fails at patch time, saying what it found, and nothing is changed. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val cases = listOf(
            classes(taps = 0) to "the Reels tap: expected one method marked $TOGGLE_PAUSE, found 0",
            classes(taps = 2) to "the Reels tap: expected one method marked $TOGGLE_PAUSE, found 2",
            classes(toggles = 0) to "the Reels audio toggle: expected one method marked $TOGGLE_AUDIO, found 0",
            classes(toggles = 2) to "the Reels audio toggle: expected one method marked $TOGGLE_AUDIO, found 2",
            classes(toggleStatic = true) to "the audio toggle, isn't a public instance void (int)",
            classes(toggleTakesBoolean = true) to "the audio toggle, isn't a public instance void (int)",
            classes(controllerPublic = false, controllerType = other) to "isn't public, so $navigator->togglePause can't call its audio toggle",
            classes(pauseLoads = 0) to "$navigator->togglePause doesn't load \"$CLIPS_PAUSE\" once",
            classes(pauseLoads = 2) to "$navigator->togglePause doesn't load \"$CLIPS_PAUSE\" once",
            classes(branches = 0) to "expected one branch to the pause path of $navigator->togglePause, found 0",
            classes(branches = 2) to "expected one branch to the pause path of $navigator->togglePause, found 2",
            classes(jumpsIntoThePath = true) to "something in $navigator->togglePause jumps to the instruction after its load of \"$CLIPS_PAUSE\"",
            classes(supplierCastsTo = other) to "expected one field of $navigator->togglePause handing over $controller, found 0",
            classes(thisOverwritten = true) to "writes over this",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.apply { applyReelTap(findReelTapSite()) } }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            for (original in classes) {
                val now = context.mutableClassDefBy(original.type).methods.associateBy { it.key() }
                for (method in original.methods) {
                    assertEquals(
                        "$expected: ${original.type}->${method.name} changed",
                        method.code().map { it.describe() }, now.getValue(method.key()).code().map { it.describe() },
                    )
                }
            }
        }
    }

    /**
     * In each of the version's seven builds the Reels tap and the controller's audio toggle are found once, the hook
     * goes in once right after the load of the pause path's string with two borrowed locals, and every other
     * instruction keeps its place and its branches. Read as the patcher reads an APK, and copied.
     */
    @Test
    fun eachDeclaredBuildHoldsItsReelsTap() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        assertEquals("seven builds of the version", 7, bundles.size)
        for (bundle in bundles) {
            val name = if (bundle.extension == "apk") bundle.parentFile.name else bundle.name
            val holders = (FixtureDex.classesHolding(bundle, tapMarker) + FixtureDex.classesHolding(bundle, toggleMarker)).map { it.type }.toSet()
            for ((read, classesOf) in listOf("copied" to FixtureDex::classes, "as read" to FixtureDex::classesAsRead)) {
                val what = "$name ($read)"
                val classes = classesOf(bundle, holders).values
                assertEquals("$what: the tap's and the controller's classes", holders, classes.map { it.type }.toSet())
                val context = PatchContexts.of(classes)
                val originals = classes.flatMap { context.mutableClassDefBy(it.type).methods }
                    .filter { it.implementation != null }.associateWith(::NeutralNativePath)

                val site = context.findReelTapSite()
                assertEquals("$what: two borrowed locals", 2, site.scratch.distinct().size)
                assertTrue("$what: the borrowed locals fit an invoke", site.scratch.all { it in 0..15 })
                context.apply { applyReelTap(site) }

                val asking = holders.flatMap { type ->
                    context.mutableClassDefBy(type).methods.filter { method -> method.code().any { it.referenceText() == MUTE_INSTEAD_OF_PAUSE } }
                }
                assertEquals("$what: methods asking", listOf(site.tap.name), asking.map { it.name })
                val tap = asking.single()
                val code = tap.code()
                val ask = code.indexOfFirst { it.referenceText() == MUTE_INSTEAD_OF_PAUSE }
                assertEquals("$what: right after the load of the pause string", CLIPS_PAUSE, ((code[ask - 1] as ReferenceInstruction).reference as StringReference).string)
                assertEquals("$what: the hook's last instruction", Opcode.RETURN_VOID, code[ask + hookLength - 1].opcode)
                assertTrue("$what: a jump lands inside the hook", ask !in tap.jumpTargets())
                val toggle = code[ask + 8].referenceText()!!
                assertTrue("$what: the toggle is the controller's: $toggle", toggle.startsWith("${site.controller}->${site.toggle.name}(I)V"))
                val added = (ask until ask + hookLength).toSet()
                for ((method, original) in originals) {
                    val mine = method.code().indices.filter { method.name == tap.name && method.definingClass == tap.definingClass && it in added }.toSet()
                    original.assertPreserved("$what ${method.name}", method, mine)
                }
            }
        }
    }

    private fun BytecodePatchContext.tap(): MutableMethod =
        mutableClassDefBy(navigator).methods.single { it.name == "togglePause" }

    // ---- stand-ins shaped like Instagram 450's -------------------------------------------------

    /**
     * The navigator, whose tap traces its marker, branches to its pause path on its second parameter, and on that
     * path loads the pause string, reads the controller through a Function0 field and casts it; and the controller,
     * whose audio toggle traces its marker. v1 holds the string on the pause path and is read after the hook.
     */
    private fun classes(
        taps: Int = 1,
        toggles: Int = 1,
        toggleStatic: Boolean = false,
        toggleTakesBoolean: Boolean = false,
        controllerPublic: Boolean = true,
        controllerType: String = controller,
        pauseLoads: Int = 1,
        branches: Int = 1,
        jumpsIntoThePath: Boolean = false,
        supplierCastsTo: String = controllerType,
        thisOverwritten: Boolean = false,
    ): List<ClassDef> {
        val classes = mutableListOf<ClassDef>()
        val tap = { owner: String ->
            method(owner, "togglePause", listOf(view, objectType, objectType), "V", 6, body = """
                const-string v0, "$tapMarker"
                invoke-static { v0 }, $trace
                ${if (thisOverwritten) "const/4 p0, 0x0" else ""}
                ${(0 until branches).joinToString("\n") { "if-eqz p2, :pause" }}
                ${if (jumpsIntoThePath) "if-eqz p3, :inpath" else ""}
                return-void
                :pause
                ${if (pauseLoads == 0) "const-string v1, \"something_else\"" else "const-string v1, \"$CLIPS_PAUSE\""}
                :inpath
                ${(1 until pauseLoads).joinToString("\n") { "const-string v1, \"$CLIPS_PAUSE\"" }}
                invoke-static { v1 }, $trace
                iget-object v2, p0, $owner->supplier:$function0
                invoke-interface { v2 }, $function0->invoke()$objectType
                move-result-object v3
                check-cast v3, $supplierCastsTo
                return-void
            """)
        }
        for (index in 0 until taps) {
            val owner = if (index == 0) navigator else "Lfixture/OtherNavigator;"
            classes += classDef(owner, listOf(tap(owner)), fields = listOf("supplier" to function0))
        }
        val toggle = { type: String, name: String ->
            val parameters = if (toggleTakesBoolean) listOf("Z") else listOf("I")
            method(type, name, parameters, "V", 2, body = """
                const-string v0, "$toggleMarker"
                invoke-static { v0 }, $trace
                return-void
            """, static = toggleStatic)
        }
        if (toggles > 0) {
            val methods = (0 until toggles).map { toggle(controllerType, if (it == 0) "toggleAudio" else "toggleAudioAgain") }
            classes += classDef(controllerType, methods, public = controllerPublic)
        } else {
            classes += classDef(controllerType, listOf(method(controllerType, "other", emptyList(), "V", 0, body = "return-void")), public = controllerPublic)
        }
        return classes
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
        val total = registers + (if (static) 0 else 1) + parameters.size
        val flags = AccessFlags.PUBLIC.value or if (static) AccessFlags.STATIC.value else 0
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>, fields: List<Pair<String, String>> = emptyList(), public: Boolean = true): ClassDef =
        ImmutableClassDef(
            type, (if (public) AccessFlags.PUBLIC.value else 0) or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null,
            fields.map { (name, fieldType) -> ImmutableField(type, name, fieldType, AccessFlags.PUBLIC.value, null, null, null) },
            methods,
        )

    private fun MutableMethod.jumpTargets(): Set<Int> =
        implementation!!.instructions.filterIsInstance<BuilderOffsetInstruction>().map { it.target.location.index }.toSet()

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Method.key(): String = name + parameterTypes.joinToString(prefix = "(", postfix = ")")

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
