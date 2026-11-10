/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.suggested

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.misc.extension.jumpTargets
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Keep hidden accounts in Favorites' request hook (#601): the map of a request for a page of Home,
 * holding its paging source, goes to HiddenAccounts.homeRequest right before Instagram copies it
 * into the request, every branch onto that copy included, and the request's extra parameters,
 * where 454 puts Favorites' paging source, go to HiddenAccounts.homeRequestExtras right after the
 * builder reads them. A build where it can't be told for sure changes nothing.
 */
class HomeRequestSourceTest {
    private val builder = "Lfixture/HomeRequest;"
    private val session = "Lcom/instagram/common/session/UserSession;"
    private val config = "Lfixture/RequestConfig;"
    private val extrasField = "$config->extras:Ljava/util/Map;"
    private val entrySet = "Ljava/util/AbstractMap;->entrySet()Ljava/util/Set;"
    private val extrasEntrySet = "Ljava/util/Map;->entrySet()Ljava/util/Set;"

    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(HIDDEN_ACCOUNTS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$HIDDEN_ACCOUNTS->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(HOME_REQUEST, HOME_REQUEST_EXTRAS)) assertTrue("$hook is not in the extension: $declared", hook in declared)
    }

    /**
     * Shaped like 454's 03bn.A01: the map is made, the paging source key loaded, a branch skips the
     * put when there's no source, and both ways meet at the map's entrySet(). Later the config's
     * extra parameters are read and copied too.
     */
    private fun request(
        name: String = "A01",
        eager: Boolean = true,
        overwritten: Boolean = false,
        jumpedOver: Boolean = false,
        extrasReads: Int = 1,
        extrasFirst: Boolean = false,
        jumpPastExtrasRead: Boolean = false,
    ): Method {
        val extras = (1..extrasReads).flatMap { read ->
            listOfNotNull(
                "iget-object v2, p3, $extrasField",
                if (read == 1 && jumpPastExtrasRead) ":extras" else null,
                "invoke-interface {v2}, $extrasEntrySet",
                "move-result-object v0",
            )
        }
        val params = listOfNotNull(
            if (eager) "const-string v0, \"$EAGER_REFRESH\"" else "const-string v0, \"other\"",
            "new-instance v1, Ljava/util/LinkedHashMap;",
            "invoke-direct {v1}, Ljava/util/LinkedHashMap;-><init>()V",
            "const-string v2, \"$PAGINATION_SOURCE\"",
            "if-eqz p2, :copy",
            "const-string v0, \"favorites\"",
            "invoke-virtual {v1, v2, v0}, Ljava/util/AbstractMap;->put(Ljava/lang/Object;Ljava/lang/Object;)Ljava/lang/Object;",
            if (overwritten) "move-object v1, p0" else null,
            ":copy",
            "invoke-virtual {v1}, $entrySet",
            "move-result-object v0",
        )
        val start = listOfNotNull(
            if (jumpedOver) "if-nez p2, :copy" else null,
            if (jumpPastExtrasRead) "if-nez p2, :extras" else null,
        )
        val body = if (extrasFirst) start + extras + params else start + params + extras
        return method(name, listOf("Landroid/content/Context;", session, "I", config), "V", body + "return-void")
    }

    /** Elsewhere in the app: the config's extra parameters read for their paging source. */
    private fun extrasReader(): Method = method(
        "reader", listOf(config), "Ljava/lang/Object;",
        listOf(
            "iget-object v0, p0, $extrasField",
            "const-string v1, \"$PAGINATION_SOURCE\"",
            "invoke-interface {v0, v1}, Ljava/util/Map;->get(Ljava/lang/Object;)Ljava/lang/Object;",
            "move-result-object v0",
            "return-object v0",
        ),
    )

    private fun method(name: String, parameters: List<String>, returns: String, body: List<String>): Method {
        val mutable = MutableMethod(
            ImmutableMethod(
                builder, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value, null, null,
                ImmutableMethodImplementation(3 + parameters.size, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.joinToString("\n"))
        return ImmutableMethod.of(mutable)
    }

    private fun classes(vararg methods: Method, reader: Boolean = true): List<ClassDef> = listOf(
        ImmutableClassDef(
            builder, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, null,
            methods.toList() + listOfNotNull(if (reader) extrasReader() else null),
        ),
        ExtensionDex.classDef(HIDDEN_ACCOUNTS),
    )

    @Test
    fun theMapGoesOverRightBeforeItsCopyEveryWayIn() {
        val context = PatchContexts.of(classes(request()))

        requireNotNull(context.homeRequestSourceOrWarn()).write()

        val method = context.mutableClassDefBy(builder).methods.single { it.name == "A01" }
        val code = method.implementation!!.instructions.toList()
        val hook = code.indexOfFirst { it.methodText() == HOME_REQUEST }
        assertEquals(Opcode.INVOKE_STATIC_RANGE, code[hook].opcode)
        assertEquals("the map's register", 1, (code[hook] as RegisterRangeInstruction).startRegister)
        assertEquals(1, (code[hook] as RegisterRangeInstruction).registerCount)
        assertEquals("right before the copy", entrySet, code[hook + 1].methodText())
        val branch = code.single { it.opcode == Opcode.IF_EQZ } as BuilderOffsetInstruction
        assertEquals("the branch with no source runs the hook too", hook, branch.target.location.index)
        assertEquals("the hook once", 1, code.count { it.methodText() == HOME_REQUEST })

        val extras = code.indexOfFirst { it.methodText() == HOME_REQUEST_EXTRAS }
        assertEquals(Opcode.INVOKE_STATIC_RANGE, code[extras].opcode)
        assertEquals("the extras' register", 2, (code[extras] as RegisterRangeInstruction).startRegister)
        assertEquals(1, (code[extras] as RegisterRangeInstruction).registerCount)
        assertEquals("right after the extras are read", extrasField, code[extras - 1].reference().toString())
        assertEquals("before they're copied", extrasEntrySet, code[extras + 1].methodText())
        assertTrue("after the parameters' hook", extras > hook)
        assertEquals("the extras hook once", 1, code.count { it.methodText() == HOME_REQUEST_EXTRAS })
    }

    /** A builder that can't be told for sure, or two of them, leave every method as it was. */
    @Test
    fun whatCantBeToldForSureChangesNothing() {
        for ((case, built) in listOf(
            "no eager refresh key" to classes(request(eager = false)),
            "the map's register written over on the way" to classes(request(overwritten = true)),
            "a way in that skips the map's making" to classes(request(jumpedOver = true)),
            "two builders" to classes(request(), request(name = "A02")),
            "no read of the extras" to classes(request(extrasReads = 0)),
            "two reads of the extras" to classes(request(extrasReads = 2)),
            "the extras read before the parameters" to classes(request(extrasFirst = true)),
            "a jump onto right after the extras' read" to classes(request(jumpPastExtrasRead = true)),
            "nothing reads a paging source from the extras" to classes(request(), reader = false),
        )) {
            val context = PatchContexts.of(built)
            assertNull(case, context.homeRequestSourceOrWarn())
            val after = context.classDefBy(builder).methods.sumOf { it.implementation!!.instructions.count() }
            assertEquals(case, built.first().methods.sumOf { it.implementation!!.instructions.count() }, after)
        }
    }

    /**
     * On every build of the declared version, the hook goes in once, in the one static method of the
     * contract rule's shape loading both keys, right before entrySet() on the map that holds the
     * paging source, and nothing jumps past it onto the copy.
     */
    @Test
    fun everyBuildHandsTheRequestOver() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } + Fixtures.otherBuilds()
        assertEquals("every build: ${bundles.map { it.name }}", Fixtures.BUILDS, bundles.size)
        for (bundle in bundles) {
            val where = "${bundle.parentFile.name}/${bundle.name}"
            val sliced = FixtureDex.classesLoading(bundle, PAGINATION_SOURCE)
            val context = PatchContexts.of(FixtureDex.withStringPools(bundle, sliced) + ExtensionDex.classDef(HIDDEN_ACCOUNTS))

            val found = context.homeRequestSourceOrWarn()
            assertNotNull("$where: the request hook wasn't found", found)
            found!!.write()

            val hooked = sliced.flatMap { c -> context.mutableClassDefBy(c.type).methods }
                .filter { m -> m.implementation?.instructions?.any { it.methodText() == HOME_REQUEST } == true }
            assertEquals("$where: one hooked method", 1, hooked.size)
            val method = hooked.single()
            assertTrue("$where: a static builder", AccessFlags.STATIC.isSet(method.accessFlags))
            assertEquals("$where: the contract rule's shape", "V", method.returnType)
            val parameters = method.parameterTypes.map(Any::toString)
            assertEquals("$where: Context first", "Landroid/content/Context;", parameters.first())
            assertEquals("$where: the session fourth", session, parameters.getOrNull(3))
            val code = method.implementation!!.instructions.toList()
            assertEquals("$where: the hook once", 1, code.count { it.methodText() == HOME_REQUEST })
            val hook = code.indexOfFirst { it.methodText() == HOME_REQUEST }
            val map = (code[hook] as RegisterRangeInstruction).startRegister
            val copy = code[hook + 1]
            assertEquals("$where: right before the copy", "entrySet", (copy.reference() as MethodReference).name)
            assertEquals("$where: of the same map", map, (copy as? FiveRegisterInstruction)?.registerC ?: (copy as RegisterRangeInstruction).startRegister)
            assertTrue("$where: nothing jumps past the hook onto the copy", hook + 1 !in method.jumpTargets())

            assertEquals("$where: the extras hook once", 1, code.count { it.methodText() == HOME_REQUEST_EXTRAS })
            val extras = code.indexOfFirst { it.methodText() == HOME_REQUEST_EXTRAS }
            assertTrue("$where: after the parameters' hook", extras > hook)
            val read = code[extras - 1]
            assertEquals("$where: right after a field read", Opcode.IGET_OBJECT, read.opcode)
            val field = read.reference() as FieldReference
            assertEquals("$where: the extras are a Map", "Ljava/util/Map;", field.type)
            assertEquals("$where: of the request's config, the fifth parameter", parameters[4], field.definingClass)
            assertEquals("$where: handed what was read", (read as TwoRegisterInstruction).registerA, (code[extras] as RegisterRangeInstruction).startRegister)
            assertTrue("$where: nothing jumps onto the extras hook", extras !in method.jumpTargets())

            val shaped = sliced.flatMap { it.methods }.filter { m ->
                AccessFlags.STATIC.isSet(m.accessFlags) && m.returnType == "V" &&
                    m.parameterTypes.map(Any::toString).let { it.size == 6 && it[0] == "Landroid/content/Context;" && it[3] == session } &&
                    context.loadsString(m, PAGINATION_SOURCE) && context.loadsString(m, EAGER_REFRESH)
            }
            assertEquals("$where: the contract rule's one method", listOf("${method.definingClass}->${method.name}"), shaped.map { "${it.definingClass}->${it.name}" })
        }
    }

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.methodText(): String? = (reference() as? MethodReference)?.toString()
}
