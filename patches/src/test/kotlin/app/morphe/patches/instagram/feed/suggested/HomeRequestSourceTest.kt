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
 * into the request, every branch onto that copy included. A build where it can't be told for sure
 * changes nothing.
 */
class HomeRequestSourceTest {
    private val builder = "Lfixture/HomeRequest;"
    private val session = "Lcom/instagram/common/session/UserSession;"
    private val entrySet = "Ljava/util/AbstractMap;->entrySet()Ljava/util/Set;"

    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(HIDDEN_ACCOUNTS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$HIDDEN_ACCOUNTS->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$HOME_REQUEST is not in the extension: $declared", HOME_REQUEST in declared)
    }

    /**
     * Shaped like 454's 03bn.A01: the map is made, the paging source key loaded, a branch skips the
     * put when there's no source, and both ways meet at the map's entrySet().
     */
    private fun request(
        name: String = "A01",
        eager: Boolean = true,
        overwritten: Boolean = false,
        jumpedOver: Boolean = false,
    ): Method = method(
        name,
        listOfNotNull(
            if (jumpedOver) "if-nez p2, :copy" else null,
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
            "return-void",
        ),
    )

    private fun method(name: String, body: List<String>): Method {
        val parameters = listOf("Landroid/content/Context;", session, "I")
        val mutable = MutableMethod(
            ImmutableMethod(
                builder, name, parameters.map { ImmutableMethodParameter(it, null, null) }, "V",
                AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value, null, null,
                ImmutableMethodImplementation(3 + parameters.size, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.joinToString("\n"))
        return ImmutableMethod.of(mutable)
    }

    private fun classes(vararg methods: Method): List<ClassDef> = listOf(
        ImmutableClassDef(builder, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, null, methods.toList()),
        ExtensionDex.classDef(HIDDEN_ACCOUNTS),
    )

    @Test
    fun theMapGoesOverRightBeforeItsCopyEveryWayIn() {
        val context = PatchContexts.of(classes(request()))

        requireNotNull(context.homeRequestSourceOrWarn()).write()

        val method = context.mutableClassDefBy(builder).methods.single()
        val code = method.implementation!!.instructions.toList()
        val hook = code.indexOfFirst { it.methodText() == HOME_REQUEST }
        assertEquals(Opcode.INVOKE_STATIC_RANGE, code[hook].opcode)
        assertEquals("the map's register", 1, (code[hook] as RegisterRangeInstruction).startRegister)
        assertEquals(1, (code[hook] as RegisterRangeInstruction).registerCount)
        assertEquals("right before the copy", entrySet, code[hook + 1].methodText())
        val branch = code.single { it.opcode == Opcode.IF_EQZ } as BuilderOffsetInstruction
        assertEquals("the branch with no source runs the hook too", hook, branch.target.location.index)
        assertEquals("the hook once", 1, code.count { it.methodText() == HOME_REQUEST })
    }

    /** A builder that can't be told for sure, or two of them, leave every method as it was. */
    @Test
    fun whatCantBeToldForSureChangesNothing() {
        for ((case, built) in listOf(
            "no eager refresh key" to classes(request(eager = false)),
            "the map's register written over on the way" to classes(request(overwritten = true)),
            "a way in that skips the map's making" to classes(request(jumpedOver = true)),
            "two builders" to classes(request(), request(name = "A02")),
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
