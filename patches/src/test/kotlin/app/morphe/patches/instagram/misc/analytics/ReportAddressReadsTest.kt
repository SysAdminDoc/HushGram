/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.analytics

import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Lacrima's report address: built and stored at startup, filtered where each send reads it back. */
class ReportAddressReadsTest {
    private val endpoint = "Lapp/hushgram/extension/instagram/misc/Analytics;->endpoint(Ljava/lang/String;)Ljava/lang/String;"
    private val builderType = "Lfixture/Address;"
    private val build = "$builderType->build([Ljava/lang/String;)Landroid/net/Uri;"
    private val toText = "Ljava/lang/Object;->toString()Ljava/lang/String;"
    private val parse = "Landroid/net/Uri;->parse(Ljava/lang/String;)Landroid/net/Uri;"

    /** The report's name in an array, in v0, as Lacrima passes it to the builder. */
    private val reportArray = """
        const-string v1, "reliability_event_log_upload"
        filled-new-array { v1 }, [Ljava/lang/String;
        move-result-object v0
    """

    /** Both stored copies are found, every read of them goes through endpoint(), and an unrelated field read doesn't. */
    @Test
    fun everyReadOfAStoredAddressGoesThroughTheExtension() {
        val context = PatchContexts.of(classes())

        val count = context.filterReportAddressReads(context.builder(), endpoint)

        assertEquals("reads filtered", 3, count)
        listOf("Lfixture/DirectSender;" to "run", "Lfixture/SenderFactory;" to "make").forEach { (type, name) ->
            val code = context.code(type, name)
            code.indices.filter { code[it].opcode == Opcode.IGET_OBJECT || code[it].opcode == Opcode.SGET_OBJECT }.forEach { read ->
                val field = code[read].referenceText()!!
                val register = (code[read] as OneRegisterInstruction).registerA
                if (field.endsWith("->other:Ljava/lang/String;")) {
                    assertTrue("$type: the unrelated read stays as it was", code[read + 1].referenceText() != endpoint)
                    return@forEach
                }
                assertEquals("$type: endpoint() right after $field", endpoint, code[read + 1].referenceText())
                assertEquals("$type: endpoint()'s argument", register, (code[read + 1] as RegisterRangeInstruction).startRegister)
                assertEquals("$type: the answer replaces the read", register, (code[read + 2] as OneRegisterInstruction).registerA)
                assertEquals(Opcode.MOVE_RESULT_OBJECT, code[read + 2].opcode)
            }
        }
        assertTrue("the writers aren't touched", context.code("Lfixture/Reporter;", "instance").none { it.referenceText() == endpoint })
    }

    /** A build whose address isn't stored as text finds nothing to filter, and says so by the count. */
    @Test
    fun anAddressKeptAsAUriFindsNothing() {
        val context = PatchContexts.of(classes(storeAsText = false))

        assertEquals(0, context.filterReportAddressReads(context.builder(), endpoint))
    }

    /**
     * Audit A20: only the builder's own text, stored on a straight run of code, counts. Each body
     * builds the address, then does one thing that means the field may hold something else.
     */
    @Test
    fun aStoreThatMayHoldAnotherValueIsNotFollowed() {
        val built = """
            invoke-static { v0 }, $build
            move-result-object v0
            invoke-virtual { v0 }, $toText
            move-result-object v0
        """
        val newReporter = """
            new-instance v1, Lfixture/Reporter;
            invoke-direct { v1 }, Lfixture/Reporter;-><init>()V
        """
        val cases = mapOf(
            "the register written again" to """
                $reportArray
                $built
                const-string v0, "https://example.com/"
                $newReporter
                iput-object v0, v1, Lfixture/Reporter;->address:Ljava/lang/String;
                return-object v1
            """,
            "a copy stored after the original changed" to """
                $reportArray
                $built
                move-object v2, v0
                const-string v0, "https://example.com/"
                $newReporter
                iput-object v2, v1, Lfixture/Reporter;->address:Ljava/lang/String;
                return-object v1
            """,
            "a branch before the store" to """
                $reportArray
                $built
                if-eqz v0, :keep
                const-string v0, "https://example.com/"
                :keep
                $newReporter
                iput-object v0, v1, Lfixture/Reporter;->address:Ljava/lang/String;
                return-object v1
            """,
            "a jump past the builder into the store" to """
                const/4 v2, 0x0
                $reportArray
                if-eqz v2, :store
                $built
                :store
                $newReporter
                iput-object v0, v1, Lfixture/Reporter;->address:Ljava/lang/String;
                return-object v1
            """,
        )
        for ((case, body) in cases) {
            val context = PatchContexts.of(reporterWith(body))
            assertEquals(case, 0, context.filterReportAddressReads(context.builder(), endpoint))
        }
    }

    /** The first store of the text is the address. A later store, after the register changed, isn't. */
    @Test
    fun onlyTheStoreBeforeTheRegisterChangesIsFollowed() {
        val context = PatchContexts.of(reporterWith("""
            $reportArray
            invoke-static { v0 }, $build
            move-result-object v0
            invoke-virtual { v0 }, $toText
            move-result-object v0
            new-instance v1, Lfixture/Reporter;
            invoke-direct { v1 }, Lfixture/Reporter;-><init>()V
            iput-object v0, v1, Lfixture/Reporter;->address:Ljava/lang/String;
            const-string v0, "https://example.com/"
            iput-object v0, v1, Lfixture/Reporter;->other:Ljava/lang/String;
            return-object v1
        """))

        assertEquals(1, context.filterReportAddressReads(context.builder(), endpoint))
        val code = context.code("Lfixture/Reader;", "read")
        val other = code.indexOfFirst { it.referenceText() == "Lfixture/Reporter;->other:Ljava/lang/String;" }
        assertTrue("the later store's read was filtered", code[other + 1].referenceText() != endpoint)
    }

    /**
     * Shaped like 450's 00Ya.A00: a check before the build jumps past the store, never into it, so
     * the store still counts.
     */
    @Test
    fun aJumpThatSkipsTheWholeStoreLeavesItFollowed() {
        val context = PatchContexts.of(reporterWith("""
            const/4 v2, 0x0
            if-nez v2, :done
            $reportArray
            invoke-static { v0 }, $build
            move-result-object v0
            invoke-virtual { v0 }, $toText
            move-result-object v0
            new-instance v1, Lfixture/Reporter;
            invoke-direct { v1 }, Lfixture/Reporter;-><init>()V
            iput-object v0, v1, Lfixture/Reporter;->address:Ljava/lang/String;
            :done
            return-object v1
        """))

        assertEquals(1, context.filterReportAddressReads(context.builder(), endpoint))
    }

    /** The builder, a Reporter whose instance() runs [body], and a reader of both its String fields. */
    private fun reporterWith(body: String): List<ClassDef> = listOf(
        classes().first { it.type == builderType },
        classOf("Lfixture/Reporter;", method("Lfixture/Reporter;", "instance", emptyList(), "Lfixture/Reporter;", static = true,
            registers = 3, body = body), fields = listOf("address" to "Ljava/lang/String;", "other" to "Ljava/lang/String;")),
        classOf("Lfixture/Reader;", method("Lfixture/Reader;", "read", listOf("Lfixture/Reporter;"), "V", static = true, registers = 2,
            body = """
                iget-object v0, p0, Lfixture/Reporter;->address:Ljava/lang/String;
                invoke-static { v0 }, $parse
                iget-object v1, p0, Lfixture/Reporter;->other:Ljava/lang/String;
                invoke-static { v1 }, $parse
                return-void
            """)),
    )

    private fun BytecodePatchContext.builder() = classDefBy(builderType).methods.single { it.name == "build" }

    private fun BytecodePatchContext.code(type: String, name: String): List<Instruction> =
        classDefBy(type).methods.single { it.name == name }.implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private fun classes(storeAsText: Boolean = true): List<ClassDef> {
        val store = if (storeAsText) {
            """
                invoke-virtual { v0 }, $toText
                move-result-object v0
                new-instance v1, Lfixture/Reporter;
                invoke-direct { v1 }, Lfixture/Reporter;-><init>()V
                iput-object v0, v1, Lfixture/Reporter;->address:Ljava/lang/String;
            """
        } else {
            """
                new-instance v1, Lfixture/Reporter;
                invoke-direct { v1 }, Lfixture/Reporter;-><init>()V
                iput-object v0, v1, Lfixture/Reporter;->uri:Landroid/net/Uri;
            """
        }
        val staticStore = if (storeAsText) {
            """
                invoke-virtual { v0 }, $toText
                move-result-object v0
                sput-object v0, Lfixture/Config;->address:Ljava/lang/String;
            """
        } else {
            """
                sput-object v0, Lfixture/Config;->uri:Landroid/net/Uri;
            """
        }
        return listOf(
            classOf(builderType, method(builderType, "build", listOf("[Ljava/lang/String;"), "Landroid/net/Uri;", static = true, registers = 3,
                body = """
                    const-string v0, "https"
                    const-string v1, "b-www.facebook.com"
                    const/4 v2, 0x0
                    return-object v2
                """)),
            classOf("Lfixture/Reporter;", method("Lfixture/Reporter;", "instance", emptyList(), "Lfixture/Reporter;", static = true, registers = 3,
                body = """
                    const-string v1, "reliability_event_log_upload"
                    filled-new-array { v1 }, [Ljava/lang/String;
                    move-result-object v0
                    invoke-static { v0 }, $build
                    move-result-object v0
                    $store
                    return-object v1
                """), fields = listOf("address" to "Ljava/lang/String;", "uri" to "Landroid/net/Uri;")),
            classOf("Lfixture/Config;", method("Lfixture/Config;", "<clinit>", emptyList(), "V", static = true, registers = 2,
                body = """
                    const-string v1, "reliability_event_log_upload"
                    filled-new-array { v1 }, [Ljava/lang/String;
                    move-result-object v0
                    invoke-static { v0 }, $build
                    move-result-object v0
                    $staticStore
                    return-void
                """), fields = listOf("address" to "Ljava/lang/String;", "uri" to "Landroid/net/Uri;", "other" to "Ljava/lang/String;")),
            classOf("Lfixture/DirectSender;", method("Lfixture/DirectSender;", "run", listOf("Lfixture/Reporter;"), "V", static = true, registers = 2,
                body = """
                    iget-object v0, p0, Lfixture/Reporter;->address:Ljava/lang/String;
                    invoke-static { v0 }, $parse
                    return-void
                """)),
            classOf("Lfixture/SenderFactory;", method("Lfixture/SenderFactory;", "make", listOf("Z"), "V", static = true, registers = 3,
                body = """
                    if-eqz p0, :second
                    sget-object v0, Lfixture/Config;->address:Ljava/lang/String;
                    invoke-static { v0 }, $parse
                    return-void
                    :second
                    sget-object v1, Lfixture/Config;->other:Ljava/lang/String;
                    sget-object v0, Lfixture/Config;->address:Ljava/lang/String;
                    invoke-static { v0 }, $parse
                    return-void
                """)),
        )
    }

    private fun method(type: String, name: String, parameters: List<String>, returnType: String, static: Boolean, registers: Int, body: String): ImmutableMethod {
        val flags = AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else 0) or
            (if (name == "<clinit>") AccessFlags.CONSTRUCTOR.value else 0)
        val mutable = MutableMethod(
            ImmutableMethod(
                type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returnType, flags, null, null,
                ImmutableMethodImplementation(registers + parameters.size, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun classOf(type: String, method: ImmutableMethod, fields: List<Pair<String, String>> = emptyList()): ClassDef =
        ImmutableClassDef(
            type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null,
            fields.map { (name, fieldType) -> ImmutableField(type, name, fieldType, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null, null) },
            listOf(method),
        )
}
