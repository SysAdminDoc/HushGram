/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.zoom

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
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
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class PinchZoomReelsHookTest {
    private val configs = "Lcom/facebook/mobileconfig/factory/MobileConfigUnsafeContext;"
    private val settings = "Lfixture/ZoomSettings;"
    private val factory = "Lfixture/ZoomFactory;"
    private val util = "Lfixture/ZoomUtil;"
    private val zoomable = ZOOMABLE_FLAG.toString(16)
    private val gesture = ZOOM_GESTURE_FLAG.toString(16)
    private val marker = "android_purge_26_q3_$CREATE_ZOOM_CONFIG"

    /** The hook the patch writes is in the ReelZoom the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(REEL_ZOOM).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$ZOOM is not in the extension: $declared", ZOOM.substringAfter("->") in declared)
    }

    /** Both zoom flags get their answers through the extension, and nothing else changes. */
    @Test
    fun bothFlagsAreAnswered() {
        val context = PatchContexts.of(classes())

        context.zoomReels()

        val made = context.mutableClassDefBy(factory).methods.single { it.name == "make" }
        assertAnswered("stand-in", made)
        val asks = context.mutableClassDefBy(util).methods.single().code()
        assertTrue("the zoom config maker was touched", asks.none { it.referenceText() == ZOOM })
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val made = "$factory->make"
        val unstored = "isn't stored straight into a field of the zoom settings $settings"
        val cases = listOf(
            classes(makers = 0) to "expected one method marked $CREATE_ZOOM_CONFIG, found 0",
            classes(makers = 2) to "expected one method marked $CREATE_ZOOM_CONFIG, found 2",
            classes(askedClasses = listOf(settings, "Lfixture/Other;")) to "$util->create asks for 2 classes, not the zoom settings alone",
            classes(askedClasses = emptyList()) to "$util->create asks for 0 classes, not the zoom settings alone",
            classes(zoomableReads = 2) to "expected one read of the zoom flag $zoomable, found 2",
            classes(zoomableReads = 0) to "expected one read of the zoom flag $zoomable, found 0",
            classes(gestureReads = 0) to "expected one read of the zoom flag $gesture, found 0",
            classes(sharedRead = true) to "the read of $zoomable in $made is shared with another flag",
            classes(gestureElsewhere = true) to "the zoom flags are read in $made and $factory->other, not in one method",
            classes(storedIn = "Lfixture/Other;") to "the read of $zoomable $unstored",
            classes(storedLater = true) to "the read of $gesture $unstored",
            classes(makerReads = listOf("zoomable")) to "$util->create doesn't read gesture, where $gesture goes",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.zoomReels() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            val written = classes.map { it.type }.distinct().flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() == ZOOM } }
            assertTrue("$expected: something was written to $written", written.isEmpty())
        }
    }

    /** In each declared build both flags are read in the zoom settings' factory, and both are answered. */
    @Test
    fun eachDeclaredBuildAnswersBothFlags() {
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
    fun eachOtherBuildAnswersBothFlagsToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val holders = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                if (classDef.methods.any { it.loadsAFlag() || it.marked() }) holders += ImmutableClassDef.of(classDef)
            }
        }
        val context = PatchContexts.of(holders)

        val reads = context.findReelZoom()
        context.zoomReels()

        val read = reads.first()
        val patched = context.mutableClassDefBy(read.type).methods
        assertAnswered(label, patched.single { it.name == read.name && it.parameterTypes.map(CharSequence::toString) == read.parameters })
        assertEquals("${label}: methods hooked", 1, holders.map { it.type }.flatMap { context.mutableClassDefBy(it).methods }
            .count { m -> m.code().any { it.referenceText() == ZOOM } })
    }

    /**
     * Right after each flag's move-result: the range call with that register, its answer back in
     * it, and Instagram's store of that answer after them.
     */
    private fun assertAnswered(what: String, method: Method) {
        val code = method.code()
        assertEquals("$what: hooks", 2, code.count { it.referenceText() == ZOOM })
        for (flag in listOf(ZOOMABLE_FLAG, ZOOM_GESTURE_FLAG)) {
            val id = flag.toString(16)
            val load = code.indexOfFirst { it.opcode == Opcode.CONST_WIDE && (it as WideLiteralInstruction).wideLiteral == flag }
            assertTrue("$what: no load of $id", load >= 0)
            val result = (load + 1 until code.size).first { code[it].opcode == Opcode.MOVE_RESULT }
            val register = (code[result] as OneRegisterInstruction).registerA
            assertEquals("$what: $id's call", Opcode.INVOKE_STATIC_RANGE, code[result + 1].opcode)
            assertEquals("$what: $id's hook", ZOOM, code[result + 1].referenceText())
            assertEquals("$what: what $id's hook is handed", register, (code[result + 1] as RegisterRangeInstruction).startRegister)
            assertEquals("$what: $id's answer", Opcode.MOVE_RESULT, code[result + 2].opcode)
            assertEquals("$what: $id's register", register, (code[result + 2] as OneRegisterInstruction).registerA)
            assertEquals("$what: $id's store", Opcode.IPUT_BOOLEAN, code[result + 3].opcode)
        }
    }

    /**
     * A factory shaped like Instagram 453's 0SXH.A0A: it makes the zoom settings and reads both
     * flags through a cast config, storing each answer straight into one of their booleans. A
     * maker shaped like 0FeQ.A05 holds [CREATE_ZOOM_CONFIG]'s marker, asks for the settings by
     * class and reads both booleans.
     */
    private fun classes(
        makers: Int = 1,
        askedClasses: List<String> = listOf(settings),
        zoomableReads: Int = 1,
        gestureReads: Int = 1,
        sharedRead: Boolean = false,
        gestureElsewhere: Boolean = false,
        storedIn: String = settings,
        storedLater: Boolean = false,
        makerReads: List<String> = listOf("zoomable", "gesture"),
    ): List<ClassDef> {
        fun read(flag: Long, field: String, owner: String, at: Int, later: Boolean, shared: Boolean) = """
            ${if (shared) "if-eqz v4, :cast$at" else ""}
            const-wide v0, 0x${flag.toString(16)}L
            ${if (shared) ":cast$at" else ""}
            check-cast v4, $configs
            invoke-interface {v4, v0, v1}, $configs->BbW(J)Z
            move-result v0
            ${if (later) "const/4 v2, 0x0" else ""}
            iput-boolean v0, v3, $owner->$field:Z
        """
        val start = """
            new-instance v3, $settings
            invoke-direct {v3}, Ljava/lang/Object;-><init>()V
            invoke-static {p0}, Lfixture/Configs;->of(Ljava/lang/Object;)Ljava/lang/Object;
            move-result-object v4
        """
        val zoomableBody = List(zoomableReads) { read(ZOOMABLE_FLAG, "zoomable", storedIn, it, false, sharedRead) }.joinToString("\n")
        val gestureBody = List(gestureReads) { read(ZOOM_GESTURE_FLAG, "gesture", settings, 10 + it, storedLater, false) }.joinToString("\n")
        val static = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
        val takes = listOf("Ljava/lang/Object;")
        val factories = if (gestureElsewhere) {
            listOf(
                method(factory, "make", takes, "Ljava/lang/Object;", 5, static, "$start\n$zoomableBody\nreturn-object v3"),
                method(factory, "other", takes, "Ljava/lang/Object;", 5, static, "$start\n$gestureBody\nreturn-object v3"),
            )
        } else {
            listOf(method(factory, "make", takes, "Ljava/lang/Object;", 5, static, "$start\n$zoomableBody\n$gestureBody\nreturn-object v3"))
        }
        val makerBody = """
            ${askedClasses.joinToString("\n") { "const-class v0, $it" }}
            const-string v1, "$marker"
            check-cast p0, $settings
            ${makerReads.joinToString("\n") { "iget-boolean v0, p0, $settings->$it:Z" }}
            return-object p0
        """
        val makerMethods = List(maxOf(makers, 1)) {
            val body = if (makers == 0) makerBody.replace(marker, "ClipsZoomUtil.createZoomConfig") else makerBody
            method(util, if (it == 0) "create" else "create$it", takes, "Ljava/lang/Object;", 2, static, body)
        }
        return listOf(classDef(factory, factories), classDef(util, makerMethods))
    }

    private fun method(owner: String, name: String, parameters: List<String>, returns: String, registers: Int, flags: Int, body: String): Method {
        val total = registers + (if (AccessFlags.STATIC.isSet(flags)) 0 else 1) + parameters.size
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.lines().filter { it.isNotBlank() }.joinToString("\n") { it.trim() })
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", emptyList(), null, null, emptyList(), methods)

    private fun Method.loadsAFlag(): Boolean = code().any {
        it.opcode == Opcode.CONST_WIDE && (it as WideLiteralInstruction).wideLiteral in setOf(ZOOMABLE_FLAG, ZOOM_GESTURE_FLAG)
    }

    private fun Method.marked(): Boolean = code().any {
        (it.opcode == Opcode.CONST_STRING || it.opcode == Opcode.CONST_STRING_JUMBO) &&
            ((it as ReferenceInstruction).reference as StringReference).string.endsWith("_$CREATE_ZOOM_CONFIG")
    }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()
}
