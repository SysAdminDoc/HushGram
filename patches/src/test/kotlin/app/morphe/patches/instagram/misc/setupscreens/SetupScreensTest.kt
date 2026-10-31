/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.setupscreens

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SetupScreensTest {
    private val opener = "Lfixture/ScreenOpener;"
    private val config = "Lcom/instagram/bloks/hosting/IgBloksScreenConfig;"
    private val screenParameters = listOf("Landroid/content/Context;", config)

    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(SETUP_POPUPS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$SKIP_SETUP is not in the extension: $declared", SKIP_SETUP.substringAfter("->") in declared)
    }

    /**
     * Both of the opener's instance ways of showing a screen ask skip() first with the app id the
     * constructor kept, not the first String field they read, and return on a yes. Its static
     * helper and the method answering a screen object are left alone.
     */
    @Test
    fun eachWayOfShowingAScreenAsksFirst() {
        val context = PatchContexts.of(classes())

        assertNull(context.skipSetupScreens(SKIP_SETUP))

        for (name in listOf("open", "sheet")) {
            val method = context.method(name)
            val code = method.code()
            val self = method.implementation!!.registerCount - 3
            assertEquals("$name: this", Opcode.MOVE_OBJECT_FROM16, code[0].opcode)
            assertEquals("$name: this", self, (code[0] as TwoRegisterInstruction).registerB)
            assertEquals("$name: the app id", "$opener->appId:Ljava/lang/String;", code[1].referenceText())
            assertEquals("$name: the hook", SKIP_SETUP, code[2].referenceText())
            assertEquals("$name: its argument", 0, (code[2] as Instruction35c).registerC)
            assertEquals(Opcode.MOVE_RESULT, code[3].opcode)
            assertEquals(Opcode.IF_EQZ, code[4].opcode)
            assertEquals(0, (code[4] as OneRegisterInstruction).registerA)
            assertEquals(Opcode.RETURN_VOID, code[5].opcode)
            assertEquals("$name: the original start follows", Opcode.IGET_OBJECT, code[6].opcode)
        }
        for (name in listOf("helper", "screen")) {
            assertTrue(name, context.method(name).code().none { it.referenceText() == SKIP_SETUP })
        }
        val prefixes = listOf("open", "sheet").associate { context.method(it).toString() to 6 } +
            (context.classDefBy(AlternateSetupFixture.PRESENTER).methods.single().toString() to 7)
        AlternateSetupFixture.assertBodies(classes(), context, prefixes)
    }

    /** A build without exactly one opener, or whose opener keeps no app id it can read, says so and changes nothing. */
    @Test
    fun whatItCantPlaceItLeavesAlone() {
        val cases = mapOf(
            "no opener" to classes(fetch = false),
            "two openers" to classes() + classes(owner = "Lfixture/OtherOpener;", direct = false),
            "no constructor" to classes(constructor = false),
            "no app id field" to classes(keepsAppId = false),
        )
        for ((case, classes) in cases) {
            val context = PatchContexts.of(classes)
            val before = AlternateSetupFixture.snapshot(classes)

            val reason = context.skipSetupScreens(SKIP_SETUP)

            assertTrue(case, reason != null)
            assertEquals(case, before, AlternateSetupFixture.snapshot(classes.map { context.classDefBy(it.type) }))
        }
    }

    /** In each declared build, every instance (Context, IgBloksScreenConfig)V of the opener asks first with its app id field. */
    @Test
    fun eachDeclaredBuildAsksBeforeEveryScreen() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildAsksBeforeEveryScreenToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val classes = AlternateSetupFixture.nativeClasses(bundle)
        val context = PatchContexts.of(classes)
        val found = classes.filter { classDef -> classDef.methods.any { it.opensScreen() && context.loadsString(it, SCREEN_FETCH) } }
            .map { it.type }.distinct()
        assertEquals("$label: one class opens a Bloks screen with $SCREEN_FETCH", 1, found.size)

        assertNull(label, context.skipSetupScreens(SKIP_SETUP))

        val owner = found.single()
        val hooked = context.classDefBy(owner).methods.filter { it.opensScreen() }
        // Full screen, bottom sheet, and the hand-on to the method taking options as well.
        assertEquals("$label: the opener's ways of showing a screen", 3, hooked.size)
        for (method in hooked) {
            val code = method.code()
            val read = code[1].referenceText()!!
            assertTrue("$label: ${method.name} reads a String of the opener", read.startsWith("$owner->") && read.endsWith(":Ljava/lang/String;"))
            assertEquals("$label: ${method.name} asks first", SKIP_SETUP, code[2].referenceText())
            assertEquals("$label: ${method.name} returns on a yes", Opcode.RETURN_VOID, code[5].opcode)
        }
        // The presenter is the one static method the patch guarded, whichever of its strings this build pools.
        val guarded = classes.flatMap { it.methods }.filter { AccessFlags.STATIC.isSet(it.accessFlags) }.mapNotNull { before ->
            context.classDefBy(before.definingClass).methods.single { it.toString() == before.toString() }
                .takeIf { after -> after.code().any { it.referenceText() == SKIP_SETUP } }?.let { before to it }
        }
        assertEquals("$label: one presenter is guarded", 1, guarded.size)
        val (presenter, changed) = guarded.single()
        assertTrue("$label: the presenter holds its strings", DIRECT_SCREEN_PRESENTER.all { context.loadsString(presenter, it) })
        val model = classes.single { it.type == presenter.parameterTypes[1].toString() }
        val constructor = model.methods.single { it.name == "<init>" && it.parameterTypes.size == 19 }
        val source = constructor.implementation!!.registerCount - 21 + 5
        val body = constructor.code()
        val stores = body.indices.filter { index ->
            val put = body[index] as? TwoRegisterInstruction
            body[index].opcode == Opcode.IPUT_OBJECT && put != null && (put.registerA == source || body.getOrNull(index - 1)?.let { move ->
                move.opcode in listOf(Opcode.MOVE_OBJECT, Opcode.MOVE_OBJECT_FROM16, Opcode.MOVE_OBJECT_16) &&
                    (move as TwoRegisterInstruction).registerA == put.registerA && move.registerB == source
            } == true)
        }
        assertEquals("$label: the screen data keeps its second String once", 1, stores.size)
        AlternateSetupFixture.assertGuard(changed.code(), presenter.implementation!!.registerCount - 5, body[stores.single()].referenceText()!!)
        AlternateSetupFixture.assertBodies(classes, context, hooked.associate { it.toString() to 6 } + (presenter.toString() to 7))
    }

    private fun Method.opensScreen() = !AccessFlags.STATIC.isSet(accessFlags) && returnType == "V" &&
        parameterTypes.map(Any::toString) == screenParameters

    private fun BytecodePatchContext.method(name: String): Method = classDefBy(opener).methods.single { it.name == name }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    /**
     * The Bloks screen opener: a constructor keeping the app id, a full-screen method holding the
     * fetch string that reads another String field first, a bottom sheet method, a static helper of
     * the same shape and a method answering a screen object.
     */
    private fun classes(
        owner: String = opener,
        fetch: Boolean = true,
        constructor: Boolean = true,
        keepsAppId: Boolean = true,
        direct: Boolean = true,
    ): List<ClassDef> {
        val methods = mutableListOf<Method>()
        if (constructor) {
            methods += method(owner, "<init>", listOf("Ljava/lang/String;", "Ljava/util/Map;", "Ljava/util/Map;"), "V", 4,
                AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value, """
                    invoke-direct { p0 }, Ljava/lang/Object;-><init>()V
                    ${if (keepsAppId) "iput-object p1, p0, $owner->appId:Ljava/lang/String;" else "nop"}
                    iput-object p2, p0, $owner->params:Ljava/util/Map;
                    return-void
                """)
        }
        methods += method(owner, "open", screenParameters, "V", 7, AccessFlags.PUBLIC.value, """
            iget-object v1, p0, $owner->title:Ljava/lang/String;
            iget-object v2, p0, $owner->appId:Ljava/lang/String;
            ${if (fetch) "const-string v3, \"$SCREEN_FETCH\"" else "nop"}
            return-void
        """)
        methods += method(owner, "sheet", screenParameters, "V", 5, AccessFlags.PUBLIC.value, """
            iget-object v1, p0, $owner->appId:Ljava/lang/String;
            return-void
        """)
        methods += method(owner, "helper", screenParameters, "V", 3, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, """
            return-void
        """)
        methods += method(owner, "screen", screenParameters, "Ljava/lang/Object;", 4, AccessFlags.PUBLIC.value, """
            const/4 v0, 0x0
            return-object v0
        """)
        val fields = listOf("appId" to "Ljava/lang/String;", "title" to "Ljava/lang/String;", "params" to "Ljava/util/Map;").map { (name, type) ->
            ImmutableField(owner, name, type, AccessFlags.PUBLIC.value, null, null, null)
        }
        return listOf(ImmutableClassDef(owner, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, fields, methods)) +
            if (direct) AlternateSetupFixture.classes(opener = false) else emptyList()
    }

    private fun method(owner: String, name: String, parameters: List<String>, returnType: String, registers: Int, flags: Int, body: String): Method {
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returnType, flags, null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }
}
