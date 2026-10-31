/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.consent

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.setupscreens.AlternateSetupFixture
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.formats.Instruction35c
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class AdDataReminderTest {
    private val callback = "Lfixture/ConsentQpCallback;"
    private val session = "Lcom/instagram/common/session/UserSession;"
    private val launch = listOf("Landroid/content/Context;", "Lfixture/Module;", session, "Lfixture/Eligible;", "Lfixture/Source;")
    private val ask = listOf(session, "Ljava/lang/String;", "Ljava/lang/String;", "Lfixture/Continuation;")

    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(AD_DATA_REMINDER).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$SKIP_AD_DATA_REMINDER is not in the extension: $declared", SKIP_AD_DATA_REMINDER.substringAfter("->") in declared)
    }

    /**
     * Each route asks first with its own name and leaves on a yes the way it does with nothing to
     * show: void for the two that show, null for the one that answers. The failure path and the
     * launcher's eligibility checks are left alone, and the original bodies follow unchanged.
     */
    @Test
    fun eachRouteAsksFirstAndLeavesWithNothingShown() {
        val classes = classes()
        val context = PatchContexts.of(classes)

        assertNull(context.skipAdDataReminder(SKIP_AD_DATA_REMINDER))

        assertAsks(context.method(callback, "shown"), ROUTE_QUICK_PROMOTION, listOf(Opcode.RETURN_VOID), Opcode.CONST_STRING)
        assertAsks(context.method(EVERGREEN_LAUNCHER, "launch"), ROUTE_EVERGREEN, listOf(Opcode.RETURN_VOID), Opcode.CONST_4)
        assertAsks(context.method(UCF_LAUNCHER, "ask"), ROUTE_TRIGGER, listOf(Opcode.CONST_4, Opcode.RETURN_OBJECT), Opcode.CONST_4)
        val answer = context.method(UCF_LAUNCHER, "ask").code()[4]
        assertEquals("the trigger answers null", 0L, (answer as NarrowLiteralInstruction).narrowLiteral.toLong())

        for ((owner, name) in listOf(callback to "failed", callback to "other", EVERGREEN_LAUNCHER to "check", UCF_LAUNCHER to "other")) {
            assertTrue("$owner $name", context.method(owner, name).code().none { it.referenceText() == SKIP_AD_DATA_REMINDER })
        }
        val prefixes = mapOf(
            context.method(callback, "shown").toString() to 5,
            context.method(EVERGREEN_LAUNCHER, "launch").toString() to 5,
            context.method(UCF_LAUNCHER, "ask").toString() to 6,
        )
        AlternateSetupFixture.assertBodies(classes, context, prefixes)
    }

    /** A build without exactly one of any route, or with no register to spare, says so and changes nothing. */
    @Test
    fun whatItCantPlaceItLeavesAlone() {
        val cases = mapOf(
            "no callback" to classes(shows = false),
            "two callbacks" to classes() + callbackClass("Lfixture/OtherCallback;"),
            "no evergreen launcher" to classes().filter { it.type != EVERGREEN_LAUNCHER },
            "two launches" to classes(launches = 2),
            "no launch" to classes(launches = 0),
            "no trigger launcher" to classes().filter { it.type != UCF_LAUNCHER },
            "two triggers" to classes(triggers = 2),
            "no trigger" to classes(triggers = 0),
            "no register in the callback" to classes(callbackRegisters = 2),
            "no register in the launch" to classes(launchRegisters = 6),
            "no register in the trigger" to classes(triggerRegisters = 5),
        )
        for ((case, classes) in cases) {
            val context = PatchContexts.of(classes)
            val before = AlternateSetupFixture.snapshot(classes)

            val reason = context.skipAdDataReminder(SKIP_AD_DATA_REMINDER)

            assertTrue(case, reason != null)
            assertEquals(case, before, AlternateSetupFixture.snapshot(classes.map { context.classDefBy(it.type) }))
        }
    }

    @Test
    fun eachDeclaredBuildAsksInEveryRoute() {
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
    fun eachOtherBuildAsksInEveryRouteToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val loading = FixtureDex.classesLoading(bundle, CONSENT_QP_SHOWN)
        val named = FixtureDex.classes(bundle, setOf(EVERGREEN_LAUNCHER, UCF_LAUNCHER)).values
        val classes = FixtureDex.withStringPools(bundle, (loading + named).distinctBy { it.type }).distinctBy { it.type }
        val context = PatchContexts.of(classes)

        assertNull(label, context.skipAdDataReminder(SKIP_AD_DATA_REMINDER))

        val hooked = classes.flatMap { before ->
            context.classDefBy(before.type).methods.filter { after -> after.code().any { it.referenceText() == SKIP_AD_DATA_REMINDER } }
        }
        assertEquals("$label: the hooked methods", 3, hooked.size)
        val byRoute = hooked.associateBy { (it.code()[0] as ReferenceInstruction).reference.toString() }
        assertEquals("$label: each route once", setOf(ROUTE_QUICK_PROMOTION, ROUTE_EVERGREEN, ROUTE_TRIGGER), byRoute.keys)
        assertEquals("$label: the launcher's route", EVERGREEN_LAUNCHER, byRoute.getValue(ROUTE_EVERGREEN).definingClass)
        assertEquals("$label: the trigger's route", UCF_LAUNCHER, byRoute.getValue(ROUTE_TRIGGER).definingClass)
        assertEquals("$label: the launch's return", "V", byRoute.getValue(ROUTE_EVERGREEN).returnType)
        assertEquals("$label: the trigger's return", "Ljava/lang/Object;", byRoute.getValue(ROUTE_TRIGGER).returnType)
        assertAsks(byRoute.getValue(ROUTE_QUICK_PROMOTION), ROUTE_QUICK_PROMOTION, listOf(Opcode.RETURN_VOID), null)
        assertAsks(byRoute.getValue(ROUTE_EVERGREEN), ROUTE_EVERGREEN, listOf(Opcode.RETURN_VOID), null)
        assertAsks(byRoute.getValue(ROUTE_TRIGGER), ROUTE_TRIGGER, listOf(Opcode.CONST_4, Opcode.RETURN_OBJECT), null)
        val prefixes = hooked.associate { it.toString() to if (it.returnType == "V") 5 else 6 }
        AlternateSetupFixture.assertBodies(classes, context, prefixes)
    }

    private fun assertAsks(method: Method, route: String, leaves: List<Opcode>, original: Opcode?) {
        val code = method.code()
        assertEquals("$route: its name", Opcode.CONST_STRING, code[0].opcode)
        assertEquals("$route: its name", route, code[0].referenceText())
        assertEquals("$route: the name's register", 0, (code[0] as OneRegisterInstruction).registerA)
        assertEquals("$route: the hook", SKIP_AD_DATA_REMINDER, code[1].referenceText())
        assertEquals("$route: its argument", 0, (code[1] as Instruction35c).registerC)
        assertEquals("$route: the answer", Opcode.MOVE_RESULT, code[2].opcode)
        assertEquals("$route: the branch", Opcode.IF_EQZ, code[3].opcode)
        assertEquals("$route: the branch's register", 0, (code[3] as OneRegisterInstruction).registerA)
        assertEquals("$route: how it leaves", leaves, code.subList(4, 4 + leaves.size).map { it.opcode })
        if (original != null) assertEquals("$route: the original start follows", original, code[4 + leaves.size].opcode)
    }

    private fun BytecodePatchContext.method(owner: String, name: String): Method = classDefBy(owner).methods.single { it.name == name }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList() ?: emptyList()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    /**
     * The three routes: the callback with its success and failure paths and another (Object)V, the
     * evergreen launcher with an eligibility check beside its launch, and the trigger launcher with
     * another method of a different shape.
     */
    private fun classes(
        shows: Boolean = true,
        launches: Int = 1,
        triggers: Int = 1,
        callbackRegisters: Int = 4,
        launchRegisters: Int = 8,
        triggerRegisters: Int = 7,
    ): List<ClassDef> {
        val evergreen = mutableListOf(
            method(EVERGREEN_LAUNCHER, "check", listOf(session, "Lfixture/Continuation;"), "Ljava/lang/Object;", 4, """
                const/4 v0, 0x0
                return-object v0
            """),
        )
        repeat(launches) { index ->
            evergreen += method(EVERGREEN_LAUNCHER, if (index == 0) "launch" else "launch$index", launch, "V", launchRegisters, """
                const/4 v1, 0x7
                return-void
            """)
        }
        val trigger = mutableListOf(
            method(UCF_LAUNCHER, "other", listOf(session), "V", 3, """
                return-void
            """),
        )
        repeat(triggers) { index ->
            trigger += method(UCF_LAUNCHER, if (index == 0) "ask" else "ask$index", ask, "Ljava/lang/Object;", triggerRegisters, """
                const/4 v0, 0x1
                return-object p4
            """)
        }
        return listOf(
            callbackClass(callback, shows, callbackRegisters),
            classDef(EVERGREEN_LAUNCHER, evergreen),
            classDef(UCF_LAUNCHER, trigger),
        )
    }

    private fun callbackClass(type: String, shows: Boolean = true, registers: Int = 4): ClassDef = classDef(type, listOf(
        method(type, "shown", listOf("Ljava/lang/Object;"), "V", registers, if (shows) """
            const-string p1, "$CONSENT_QP_SHOWN"
            return-void
        """ else """
            return-void
        """),
        method(type, "failed", listOf("Lfixture/Failure;"), "V", 4, """
            const-string v0, "consent_qp_controller_load_qp_bloks_parse_fail"
            return-void
        """),
        method(type, "other", listOf("Ljava/lang/Object;"), "V", 4, """
            return-void
        """),
    ))

    private fun classDef(type: String, methods: List<Method>): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, emptyList(), methods)

    private fun method(owner: String, name: String, parameters: List<String>, returnType: String, registers: Int, body: String): Method {
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returnType, AccessFlags.PUBLIC.value, null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }
}
