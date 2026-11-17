/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.viewhistory

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutablePackedSwitchPayload
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableSwitchElement
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Don't send feed view history (#664): each place a Home request adds feed_view_info hands the
 * record of what you looked at to the extension right after reading it, and Instagram's own null
 * test then decides whether the parameter goes in.
 */
class FeedViewHistoryHookTest {
    /** The hook the patch writes is in the FeedViewInfo the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val type = HELD.substringBefore("->")
        val declared = ExtensionDex.classDef(type).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$HELD is not in the extension: $declared", HELD.substringAfter("->") in declared)
    }

    /**
     * Both builders get the call right after the record is read, then the cast back, with the null
     * test still next and still landing where it did. A method reading a plain string under the
     * same name is left as it was.
     */
    @Test
    fun eachSiteHandsTheRecordOverBeforeItsNullTest() {
        val context = PatchContexts.of(listOf(request()))

        context.holdBackFeedViewInfo()

        val patched = context.mutableClassDefBy(REQUEST)
        for (name in listOf("cold", "next")) {
            val method = patched.methods.single { it.name == name }
            assertEquals("$name: three instructions went in", site().size + 3, method.instructions().size)
            assertHeld(name, method.instructions(), test = 2, register = 0, record = RECORD)
            assertNullTestLands(name, method.instructions(), test = 5)
        }
        val plain = patched.methods.single { it.name == "plain" }
        assertEquals("the plain string read was touched", plain().size, plain.instructions().size)
    }

    /** On a Redex build the name comes from a string pool, and the hook goes in all the same. */
    @Test
    fun aPooledNameIsFoundToo() {
        val context = PatchContexts.of(listOf(request(pooled = true), pool()))

        context.holdBackFeedViewInfo()

        val method = context.mutableClassDefBy(REQUEST).methods.single { it.name == "cold" }
        assertHeld("pooled", method.instructions(), test = 4, register = 0, record = RECORD)
        assertNullTestLands("pooled", method.instructions(), test = 7)
    }

    @Test
    fun withoutASiteThePatchFailsNamingItself() {
        val context = PatchContexts.of(listOf(request(sites = false)))
        val failure = assertThrows(PatchException::class.java) { context.holdBackFeedViewInfo() }
        assertTrue(failure.message!!, failure.message!!.startsWith("Don't send feed view history:"))
    }

    /** A jump straight to the null test would go around the hook, so the patch refuses before changing anything. */
    @Test
    fun aJumpOntoTheNullTestFailsThePatch() {
        val context = PatchContexts.of(listOf(request(jumpOnto = true)))
        val failure = assertThrows(PatchException::class.java) { context.holdBackFeedViewInfo() }
        assertTrue(failure.message!!, failure.message!!.contains("jumps to the null test"))
        val patched = context.mutableClassDefBy(REQUEST)
        assertEquals("cold was changed before the refusal", site().size, patched.methods.single { it.name == "cold" }.instructions().size)
        assertEquals("next was changed before the refusal", site().size + 1, patched.methods.single { it.name == "next" }.instructions().size)
    }

    /**
     * In each declared build both builders of the Home request are found, and each gets the call
     * right after the record is read, the cast back to the record's type, and the null test next.
     */
    @Test
    fun eachDeclaredBuildHoldsBackBothSites() {
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
    fun eachOtherBuildHoldsBackBothSitesToo() {
        val others = Fixtures.otherBuilds()
        assertEquals("every other build is in the fixture folder", Fixtures.OTHER_BUILDS, others.size)
        for (base in others) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val context = PatchContexts.of(FixtureDex.classesLoading(bundle, FEED_VIEW_INFO))
        val sites = context.feedViewInfoSites()
        assertEquals("$label: the Home request's builders adding $FEED_VIEW_INFO", 2, sites.size)
        val stock = sites.associateWith { it.method.instructions() }

        context.holdBackFeedViewInfo()

        for (site in sites) {
            val where = "$label: ${site.method.definingClass}->${site.method.name}"
            val before = stock.getValue(site)
            val after = context.mutableClassDefBy(site.method.definingClass).methods.single {
                it.name == site.method.name && it.parameterTypes.map(Any::toString) == site.method.parameterTypes.map(Any::toString)
            }.instructions()
            val earlier = sites.count { it.method == site.method && it.test < site.test }
            val test = site.test + 3 * earlier
            assertEquals("$where: size", before.size + 3 * sites.count { it.method == site.method }, after.size)
            val read = after[test - 1]
            assertEquals("$where: the record's read before the hook", Opcode.IGET_OBJECT, read.opcode)
            assertEquals("$where: the record's register", site.register, (read as TwoRegisterInstruction).registerA)
            assertHeld(where, after, test, site.register, site.record)
            assertEquals(
                "$where: the null test lands where it did",
                (before[site.test] as OffsetInstruction).codeOffset,
                (after[test + 3] as OffsetInstruction).codeOffset,
            )
        }
    }

    /** The call at [test] hands [register] to the hook, takes the answer back and casts it, and the null test follows. */
    private fun assertHeld(what: String, code: List<Instruction>, test: Int, register: Int, record: String) {
        assertEquals(
            "$what: the hook's opcodes and the null test after them",
            listOf(Opcode.INVOKE_STATIC, Opcode.MOVE_RESULT_OBJECT, Opcode.CHECK_CAST, Opcode.IF_EQZ, Opcode.IGET_OBJECT),
            code.subList(test, test + 5).map { it.opcode },
        )
        val call = code[test] as FiveRegisterInstruction
        assertEquals("$what: the hook called", HELD, (call as ReferenceInstruction).reference.toString())
        assertEquals("$what: the call takes one register", 1, call.registerCount)
        assertEquals("$what: the call takes the record", register, call.registerC)
        assertEquals("$what: the answer goes back", register, (code[test + 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: the cast's register", register, (code[test + 2] as OneRegisterInstruction).registerA)
        assertEquals("$what: the cast's type", record, (code[test + 2] as ReferenceInstruction).reference.toString())
        assertEquals("$what: the null test's register", register, (code[test + 3] as OneRegisterInstruction).registerA)
        assertEquals("$what: the string read's holder", register, (code[test + 4] as TwoRegisterInstruction).registerB)
    }

    /** The null test at [test] still lands on the method's return-void, past the call adding the parameter. */
    private fun assertNullTestLands(what: String, code: List<Instruction>, test: Int) {
        val address = code.take(test).sumOf { it.codeUnits }
        val target = address + (code[test] as OffsetInstruction).codeOffset
        var at = 0
        val landed = code.firstOrNull { instruction -> (at == target).also { at += instruction.codeUnits } }
        assertNotNull("$what: the null test lands on no instruction", landed)
        assertEquals("$what: the null test's target", Opcode.RETURN_VOID, landed!!.opcode)
    }

    /**
     * A request class shaped like 455's builders: two static methods adding feed_view_info from a
     * record held by the request, and one adding a plain string field under the same name, which
     * has no record to test. [pooled] makes it one builder asking a string pool for the name, so the
     * class is only found through the pool.
     */
    private fun request(sites: Boolean = true, pooled: Boolean = false, jumpOnto: Boolean = false): ClassDef {
        val methods = mutableListOf<ImmutableMethod>()
        if (pooled) {
            methods += method("cold", pooledSite())
        } else {
            if (sites) {
                methods += method("cold", site())
                methods += method("next", if (jumpOnto) site() + ImmutableInstruction10t(Opcode.GOTO, -8) else site())
            }
            methods += method("plain", plain())
        }
        return ImmutableClassDef(REQUEST, PUBLIC_FINAL, OBJECT, null, null, null, null, methods)
    }

    /** v1 the name, v0 the record and then its string, p0 (v2) the builder, p1 (v3) the request. */
    private fun site(): List<Instruction> = listOf(
        ImmutableInstruction21c(Opcode.CONST_STRING, 1, ImmutableStringReference(FEED_VIEW_INFO)),
        ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 3, RECORD_FIELD),
        ImmutableInstruction21t(Opcode.IF_EQZ, 0, 7),
        ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 0, VALUE_FIELD),
        ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 3, 2, 1, 0, 0, 0, ADD),
        ImmutableInstruction10x(Opcode.RETURN_VOID),
    )

    private fun pooledSite(): List<Instruction> = listOf(
        ImmutableInstruction11n(Opcode.CONST_4, 1, POOLED),
        ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 1, 0, 0, 0, 0, POOL_CALL),
        ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 1),
    ) + site().drop(1)

    private fun plain(): List<Instruction> = listOf(
        ImmutableInstruction21c(Opcode.CONST_STRING, 1, ImmutableStringReference(FEED_VIEW_INFO)),
        ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 3, PLAIN_FIELD),
        ImmutableInstruction21t(Opcode.IF_EQZ, 0, 5),
        ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 3, 2, 1, 0, 0, 0, ADD),
        ImmutableInstruction10x(Opcode.RETURN_VOID),
    )

    /** A static `(int)String` pool answering [POOLED] with feed_view_info, laid out as 450's Redex pools are. */
    private fun pool(): ClassDef = ImmutableClassDef(
        POOL, PUBLIC_FINAL, OBJECT, null, null, null, null,
        listOf(
            ImmutableMethod(
                POOL, POOL_CALL.name, listOf(ImmutableMethodParameter("I", null, null)), STRING,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                ImmutableMethodImplementation(
                    2,
                    listOf(
                        ImmutableInstruction31t(Opcode.PACKED_SWITCH, 1, 8),
                        ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                        ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                        ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(FEED_VIEW_INFO)),
                        ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                        ImmutablePackedSwitchPayload(listOf(ImmutableSwitchElement(POOLED, 5))),
                    ),
                    null, null,
                ),
            ),
        ),
    )

    private fun method(name: String, code: List<Instruction>) = ImmutableMethod(
        REQUEST, name, listOf(BUILDER, REQUEST).map { ImmutableMethodParameter(it, null, null) }, "V",
        AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
        ImmutableMethodImplementation(4, code, null, null),
    )

    private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private companion object {
        const val OBJECT = "Ljava/lang/Object;"
        const val STRING = "Ljava/lang/String;"
        const val REQUEST = "Lfixture/HomeRequest;"
        const val RECORD = "Lfixture/FeedViewRecord;"
        const val BUILDER = "Lfixture/RequestBuilder;"
        const val POOL = "Lfixture/Strings;"
        const val POOLED = 7
        val PUBLIC_FINAL = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
        val RECORD_FIELD = ImmutableFieldReference(REQUEST, "viewRecord", RECORD)
        val VALUE_FIELD = ImmutableFieldReference(RECORD, "encoded", STRING)
        val PLAIN_FIELD = ImmutableFieldReference(REQUEST, "plainValue", STRING)
        val ADD = ImmutableMethodReference(BUILDER, "addParam", listOf(STRING, STRING), "V")
        val POOL_CALL = ImmutableMethodReference(POOL, "pooled", listOf("I"), STRING)
    }
}
