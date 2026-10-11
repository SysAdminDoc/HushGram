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
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
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

    /** The instructions the hook adds: test the flag, ask, test the answer, set the flag. */
    private val hookLength = 5

    /** The hook the patch writes is in the FeedSound the bundle ships, public and static, and takes the controller as an Object. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(FEED_SOUND).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$FEED_START_WITH_SOUND is not in the extension: $declared", FEED_START_WITH_SOUND.substringAfter("->") in declared)
    }

    /**
     * In front of the call that gives the video's state its sound flag the hook tests the flag, asks with the controller
     * and on a yes sets the flag; every other instruction keeps its place and the hook never writes the controller's register.
     */
    @Test
    fun theHookGoesInFrontOfTheSoundFlagCall() {
        val context = PatchContexts.of(classes())
        val original = context.start().code().map { it.describe() }

        context.apply { applyFeedSound(findFeedSoundSite()) }

        val code = context.start().code()
        val ask = code.indexOfFirst { it.referenceText() == FEED_START_WITH_SOUND }
        assertEquals("the flag test comes first", Opcode.IF_NEZ, code[ask - 1].opcode)
        assertEquals(
            listOf(Opcode.IF_NEZ, Opcode.INVOKE_STATIC_RANGE, Opcode.MOVE_RESULT, Opcode.IF_EQZ, Opcode.CONST_4),
            code.subList(ask - 1, ask - 1 + hookLength).map { it.opcode },
        )
        val call = ask - 1 + hookLength
        assertEquals("the flag call follows", "$item->setSound(Z)V", code[call].referenceText())
        val flag = (code[call] as FiveRegisterInstruction).registerD
        assertEquals("the hook sets the register the call hands over", flag, (code[call - 1] as OneRegisterInstruction).registerA)
        assertEquals("the hook tests that register", flag, (code[ask - 1] as OneRegisterInstruction).registerA)
        val p0 = context.start().implementation!!.registerCount - 11
        assertEquals("the controller is read from its own register", listOf(p0), code[ask].arguments())
        val written = listOf(code[ask + 1], code[call - 1]).map { (it as OneRegisterInstruction).registerA }
        assertTrue("the hook wrote the controller's register or a parameter: $written", written.all { it < p0 })
        assertEquals("what's left of the method", original.size + hookLength, code.size)
        assertEquals("the original instructions keep their order", original, code.take(ask - 1).map { it.describe() } + code.drop(call).map { it.describe() })
        assertTrue("a jump lands inside the hook", (ask until call).none { it in context.start().jumpTargets() })
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
     * In each of the version's seven builds the feed controller is found by its string, its video start is found once, and
     * the hook goes in once, in front of the sound flag call, with one borrowed local up to v15 that nothing reads afterwards
     * and every other instruction in place. Read as the patcher reads an APK, and copied.
     */
    @Test
    fun eachDeclaredBuildHoldsItsFeedVideoStart() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        assertEquals("seven builds of the version", 7, bundles.size)
        for (apk in bundles) {
            val name = if (apk.extension == "apk") apk.parentFile.name else apk.name
            val holding = FixtureDex.classesHolding(apk, FEED_CONTROLLER_STRING)
            assertEquals("$name: classes loading the controller's string", 1, holding.size)
            val wanted = wanted(holding.single())
            for ((read, classesOf) in listOf("copied" to FixtureDex::classes, "as read" to FixtureDex::classesAsRead)) {
                val what = "$name ($read)"
                val classes = classesOf(apk, wanted).values
                val context = PatchContexts.of(classes)
                val originals = classes.flatMap { context.mutableClassDefBy(it.type).methods }
                    .filter { it.implementation != null && it.definingClass == holding.single().type }.associateWith(::NeutralNativePath)

                val site = context.findFeedSoundSite()
                assertTrue("$what: the borrowed local fits an invoke", site.scratch in 0..15)
                assertTrue("$what: the flag fits an invoke", site.flag in 0..15)
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
                val call = ask - 1 + hookLength
                assertEquals("$what: the flag test first", Opcode.IF_NEZ, code[ask - 1].opcode)
                assertEquals("$what: the flag call follows the hook", Opcode.INVOKE_VIRTUAL, code[call].opcode)
                assertEquals("$what: the flag call is a one boolean method", true, code[call].referenceText()!!.endsWith("(Z)V"))
                assertEquals("$what: the flag is what the call hands over", site.flag, (code[call] as FiveRegisterInstruction).registerD)
                assertTrue("$what: a jump lands inside the hook", (ask until call).none { it in start.jumpTargets() })
                val p0 = start.implementation!!.registerCount - 11
                assertEquals("$what: the controller is read from its own register", listOf(p0), code[ask].arguments())
                val borrowed = code.subList(ask - 1, call).mapNotNull { (it as? OneRegisterInstruction)?.registerA }
                    .filter { it != p0 && it != site.flag && it != site.scratch }
                assertTrue("$what: the hook touched a register it didn't borrow: $borrowed", borrowed.isEmpty())
                val added = (ask - 1 until call).toSet()
                for ((method, original) in originals) {
                    val mine = method.code().indices.filter { method.name == start.name && method.definingClass == start.definingClass && it in added }.toSet()
                    original.assertPreserved("$what ${method.name}", method, mine)
                }
            }
        }
    }

    // The classes a build is read from: the controller and the types its code makes.
    private fun wanted(holder: ClassDef): Set<String> =
        holder.methods.flatMap { m -> m.code().filter { it.opcode == Opcode.NEW_INSTANCE }.map { (it as ReferenceInstruction).reference.toString() } }
            .toSet() + holder.type

    private enum class StartShape { PLAIN, NO_CALL, TWO_STATES, FLAG_FROM_A_CALL, STATE_REWRITTEN, JUMP_TO_CALL, THIS_OVERWRITTEN }

    /**
     * A small app: a controller whose video start makes a state object, hands it a flag that is a 0 or a 1 on every path,
     * and holds it; and a state class with the one boolean setter. The controller's key handler loads the string the
     * finder looks for.
     */
    private fun classes(controllers: Int = 1, shape: StartShape = StartShape.PLAIN): List<ClassDef> {
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
        val controllerClass = ImmutableClassDef(
            controller, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null,
            listOf(ImmutableField(controller, "item", item, AccessFlags.PUBLIC.value, null, null, null)),
            listOf(start, keys, other),
        )
        val second = ImmutableClassDef(
            holder, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, null,
            listOf(keys.let { method(holder, "onKey", emptyList(), "V", 1, body = "const-string v0, \"$FEED_CONTROLLER_STRING\"\ninvoke-static { v0 }, $trace\nreturn-void") }),
        )
        val state = ImmutableClassDef(
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
            ), state)
            2 -> listOf(controllerClass, second, state)
            else -> listOf(controllerClass, state)
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
