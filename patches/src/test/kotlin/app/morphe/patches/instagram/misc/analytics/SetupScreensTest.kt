/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.analytics

import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
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
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SetupScreensTest {
    private val opener = "Lfixture/ScreenOpener;"
    private val config = "Lcom/instagram/bloks/hosting/IgBloksScreenConfig;"
    private val screenParameters = listOf("Landroid/content/Context;", config)

    /**
     * Both of the opener's instance ways of showing a screen ask setupScreen() first with the app id
     * the constructor kept, not the first String field they read, and return on a yes. Its static
     * helper and the method answering a screen object are left alone.
     */
    @Test
    fun eachWayOfShowingAScreenAsksFirst() {
        val context = PatchContexts.of(classes())

        assertNull(context.skipSetupScreens(SETUP_SCREEN))

        for (name in listOf("open", "sheet")) {
            val method = context.method(name)
            val code = method.code()
            val self = method.implementation!!.registerCount - 3
            assertEquals("$name: this", Opcode.MOVE_OBJECT_FROM16, code[0].opcode)
            assertEquals("$name: this", self, (code[0] as TwoRegisterInstruction).registerB)
            assertEquals("$name: the app id", "$opener->appId:Ljava/lang/String;", code[1].referenceText())
            assertEquals("$name: the hook", SETUP_SCREEN, code[2].referenceText())
            assertEquals("$name: its argument", 0, (code[2] as Instruction35c).registerC)
            assertEquals(Opcode.MOVE_RESULT, code[3].opcode)
            assertEquals(Opcode.IF_EQZ, code[4].opcode)
            assertEquals(0, (code[4] as OneRegisterInstruction).registerA)
            assertEquals(Opcode.RETURN_VOID, code[5].opcode)
            assertEquals("$name: the original start follows", Opcode.IGET_OBJECT, code[6].opcode)
        }
        for (name in listOf("helper", "screen")) {
            assertTrue(name, context.method(name).code().none { it.referenceText() == SETUP_SCREEN })
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

            val reason = context.skipSetupScreens(SETUP_SCREEN)

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
                val classes = AlternateSetupFixture.nativeClasses(bundle)
                val context = PatchContexts.of(classes)

                assertNull(bundle.name, context.skipSetupScreens(SETUP_SCREEN))

                val found = classes.single { classDef -> classDef.methods.any { SCREEN_FETCH in it.strings() } }
                val hooked = context.classDefBy(found.type).methods.filter { method ->
                    !AccessFlags.STATIC.isSet(method.accessFlags) && method.returnType == "V" &&
                        method.parameterTypes.map(Any::toString) == screenParameters
                }
                assertTrue("${bundle.name}: the opener's ways of showing a screen", hooked.size >= 2)
                for (method in hooked) {
                    val code = method.code()
                    val read = code[1].referenceText()!!
                    assertTrue("${bundle.name}: ${method.name} reads a String of the opener", read.startsWith("${found.type}->") && read.endsWith(":Ljava/lang/String;"))
                    assertEquals("${bundle.name}: ${method.name} asks first", SETUP_SCREEN, code[2].referenceText())
                    assertEquals("${bundle.name}: ${method.name} returns on a yes", Opcode.RETURN_VOID, code[5].opcode)
                }
                val presenter = classes.flatMap { it.methods }.single { it.strings().containsAll(AlternateSetupFixture.PRESENTER_HELD) }
                val model = classes.single { it.type == presenter.parameterTypes[1].toString() }
                val constructor = model.methods.single { it.name == "<init>" && it.parameterTypes.size == 19 }
                val source = constructor.implementation!!.registerCount - 21 + 5
                val stored = constructor.code().windowed(2).single { pair ->
                    pair[0].opcode == Opcode.MOVE_OBJECT_FROM16 && (pair[0] as TwoRegisterInstruction).registerB == source &&
                        pair[1].opcode == Opcode.IPUT_OBJECT && (pair[1] as TwoRegisterInstruction).registerA == (pair[0] as TwoRegisterInstruction).registerA
                }[1].referenceText()!!
                val changed = context.classDefBy(presenter.definingClass).methods.single { it.toString() == presenter.toString() }
                AlternateSetupFixture.assertGuard(changed.code(), presenter.implementation!!.registerCount - 5, stored)
                AlternateSetupFixture.assertBodies(classes, context, hooked.associate { it.toString() to 6 } + (presenter.toString() to 7))
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    private fun BytecodePatchContext.method(name: String): Method = classDefBy(opener).methods.single { it.name == name }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Method.strings(): List<String> = code().mapNotNull {
        if (it.opcode == Opcode.CONST_STRING || it.opcode == Opcode.CONST_STRING_JUMBO) ((it as ReferenceInstruction).reference as StringReference).string else null
    }

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
