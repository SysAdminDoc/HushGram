/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.suggested

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/** Home's list builder hands its adapter to the extension, and the stubs reach Home's load more policy (#52). */
class HomeNextPageTest {
    private val adapter = "Lfixture/MainFeedAdapter;"
    private val feed = "Lfixture/Feed;"
    private val policy = "Lfixture/FeedState;"
    private val lazy = "Lfixture/Lazy;"
    private val spinner = "Lfixture/Spinner;"
    private val getValue = "$lazy->getValue()Ljava/lang/Object;"

    @Test
    fun theHookAndStubsAreInTheExtension() {
        val declared = ExtensionDex.classDef(HOME_NEXT_PAGE).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (hook in listOf(HOME_BUILD_STARTS.substringAfter("->"), "policyOf(Ljava/lang/Object;)Ljava/lang/Object;",
            "sourceOf(Ljava/lang/Object;)Ljava/lang/String;", "loadingNow(Ljava/lang/Object;)I", "moreLeft(Ljava/lang/Object;)I",
            "askNextPage(Ljava/lang/Object;Ljava/lang/Object;)V")) {
            assertTrue("$hook is not in the extension: $declared", hook in declared)
        }
    }

    /**
     * The builder hands the adapter over first thing, in p0, and each stub reaches what was found
     * with its own parameter registers alone: the adapter's lazy policy field, the feed's source,
     * the policy's two questions and its call for the next page.
     */
    @Test
    fun theBuilderHandsItsAdapterOverAndTheStubsReachThePolicy() {
        val context = PatchContexts.of(world())
        val targets = context.findHomeNextPage()
        assertEquals(adapter, targets.adapter)
        assertEquals("buildModels", targets.builder)
        assertEquals(listOf("I", "Ljava/lang/Integer;"), targets.builderParameters)
        assertEquals("$adapter->policy:$lazy", targets.policyField.toString())
        assertEquals(getValue, targets.lazyValue.toString())
        assertEquals(policy, targets.policy)
        assertEquals("$policy->ask(Ljava/util/Map;)V", targets.ask.toString())
        assertEquals("$policy->busy()Z", targets.loading.toString())
        assertEquals("$policy->hasMore()Z", targets.more.toString())
        assertEquals("$feed->source:Ljava/lang/String;", targets.source.toString())

        targets.write(context)

        val builder = context.classDefBy(adapter).methods.single { it.name == "buildModels" }
        val code = builder.implementation!!.instructions.toList()
        assertTrue("first thing", code[0].calls(HOME_BUILD_STARTS))
        assertEquals("the adapter, p0", builder.implementation!!.registerCount - 3, (code[0] as RegisterRangeInstruction).startRegister)
        assertEquals(1, (code[0] as RegisterRangeInstruction).registerCount)
        assertEquals("once", 1, code.count { it.calls(HOME_BUILD_STARTS) })
        assertEquals("the rest as it was", Opcode.CONST_STRING, code[1].opcode)

        val policyOf = stubBody(context, "policyOf", 5)
        assertEquals(listOf(Opcode.CHECK_CAST, Opcode.IGET_OBJECT, Opcode.INVOKE_INTERFACE, Opcode.MOVE_RESULT_OBJECT, Opcode.RETURN_OBJECT), policyOf.map { it.opcode })
        assertEquals(adapter, policyOf[0].type())
        assertTrue(policyOf[1].reads(targets.policyField.toString()))
        assertTrue(policyOf[2].calls(getValue))

        val sourceOf = stubBody(context, "sourceOf", 3)
        assertEquals(listOf(Opcode.CHECK_CAST, Opcode.IGET_OBJECT, Opcode.RETURN_OBJECT), sourceOf.map { it.opcode })
        assertEquals(feed, sourceOf[0].type())
        assertTrue(sourceOf[1].reads(targets.source.toString()))

        for ((stub, question) in listOf("loadingNow" to "busy", "moreLeft" to "hasMore")) {
            val body = stubBody(context, stub, 4)
            assertEquals(stub, listOf(Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL, Opcode.MOVE_RESULT, Opcode.RETURN), body.map { it.opcode })
            assertEquals(policy, body[0].type())
            assertTrue(stub, body[1].calls("$policy->$question()Z"))
        }

        val ask = stubBody(context, "askNextPage", 4)
        assertEquals(listOf(Opcode.CHECK_CAST, Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL, Opcode.RETURN_VOID), ask.map { it.opcode })
        assertEquals(policy, ask[0].type())
        assertEquals("Ljava/util/Map;", ask[1].type())
        assertTrue(ask[2].calls("$policy->ask(Ljava/util/Map;)V"))
        val call = ask[2] as FiveRegisterInstruction
        assertEquals("the policy, then the map", listOf((ask[0] as OneRegisterInstruction).registerA, (ask[1] as OneRegisterInstruction).registerA),
            listOf(call.registerC, call.registerD))
    }

    /** A build where any part can't be pinned down to one is refused, and the patch warns and leaves everything as it was. */
    @Test
    fun aBuildMissingAnyPartIsRefusedAndLeftAlone() {
        for ((what, classes) in listOf(
            "two calls for the next page" to world(asks = listOf("ask", "askToo")),
            "two fields holding the policy" to world(fields = listOf("policy", "policyToo")),
            "a private source" to world(publicSource = false),
            "no visible spinner" to world(spinnerString = "triggered_by_scroll"),
        )) {
            val context = PatchContexts.of(classes)
            assertThrows(what, PatchException::class.java) { context.findHomeNextPage() }
            val before = stubs(context)
            assertNull(what, context.homeNextPageOrWarn())
            val builder = context.classDefBy(adapter).methods.single { it.name == "buildModels" }.implementation!!.instructions
            assertTrue("$what: the builder is left", builder.none { it.calls(HOME_BUILD_STARTS) })
            assertEquals("$what: the stubs are left", before, stubs(context))
        }
    }

    /**
     * In each build of the declared version, the declared bundle and the other builds of it, Home's
     * list builder is MainfeedAdapter.buildModels(int, Integer), the policy's call for the next page
     * is the (Map)V method the visible spinner's caller makes, the policy is held in a lazy field of
     * the adapter, and the feed's paging source is a String field. The builder hands its adapter to
     * the extension first thing. On 450's 385611438 that's LX/01ls->A1A, LX/01ls->A0m, LX/01lQ->A00,
     * A02() and EBU(), and LX/01mK->A01.
     */
    @Test
    fun eachBuildAsksHomesPolicyForTheNextPage() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } }
        val others = Fixtures.otherBuilds()
        assertTrue("no fixture of a declared build", bundles.isNotEmpty())
        assertTrue("other builds of the declared version were not read", others.isNotEmpty())
        var checked = 0
        for (bundle in bundles + others) {
            val where = "${bundle.parentFile.name}/${bundle.name}"
            val holders = mutableListOf<ClassDef>()
            FixtureDex.forEach(bundle) { dex ->
                for (classDef in dex.classes) {
                    if (classDef.methods.any { it.holds(BUILD_MODELS) || it.holds(FOLLOWING_FEED) || it.holds(VISIBLE_SPINNER) }) {
                        holders += ImmutableClassDef.of(classDef)
                    }
                }
            }
            val policyClass = PatchContexts.of(holders.distinctBy { it.type }).findLoadMoreRow().first
            val policyTypes = setOf(policyClass.type) + policyClass.interfaces
            val callers = mutableListOf<ClassDef>()
            FixtureDex.forEach(bundle) { dex ->
                for (classDef in dex.classes) {
                    val calls = classDef.methods.any { method ->
                        method.implementation?.instructions?.any {
                            ((it as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass in policyTypes
                        } == true
                    }
                    if (calls) callers += ImmutableClassDef.of(classDef)
                }
            }
            // The feed the builder reads its flag from and the adapter's field types, for the public checks.
            val adapterClass = holders.distinctBy { it.type }.single { classDef -> classDef.methods.any { it.holds(BUILD_MODELS) && it.holds(SHIMMER_KEY) } }
            val flagOwners = adapterClass.methods.flatMap { it.implementation?.instructions?.toList().orEmpty() }
                .filter { it.opcode == Opcode.IGET_BOOLEAN }.map { ((it as ReferenceInstruction).reference as FieldReference).definingClass }
            val reached = (adapterClass.fields.map { it.type }.filter { it.startsWith("L") } + flagOwners).toSet()
            val reachedClasses = FixtureDex.classes(bundle, reached).values.map { ImmutableClassDef.of(it) }
            val sliced = (holders + callers + reachedClasses).distinctBy { it.type }
            val context = PatchContexts.of((FixtureDex.withStringPools(bundle, sliced) + ExtensionDex.classDef(HOME_NEXT_PAGE)).distinctBy { it.type })

            val targets = context.findHomeNextPage()
            assertEquals("$where: the builder", listOf("I", "Ljava/lang/Integer;"), targets.builderParameters)
            assertEquals("$where: the adapter", adapterClass.type, targets.adapter)
            assertEquals("$where: the policy", policyClass.type, targets.policy)
            assertEquals("$where: asked of the policy", targets.policy, targets.ask.definingClass)
            assertEquals("$where: with a map", listOf("Ljava/util/Map;"), targets.ask.parameterTypes.map(CharSequence::toString))
            assertTrue("$where: two questions, ${targets.loading} and ${targets.more}", targets.loading.name != targets.more.name)
            assertEquals("$where: the policy's field", targets.adapter, targets.policyField.definingClass)
            assertEquals("$where: read through the lazy", "getValue", targets.lazyValue.name)
            assertEquals("$where: the source", "Ljava/lang/String;", targets.source.type)
            assertTrue("$where: the source is the flag's feed's", targets.source.definingClass in flagOwners)

            targets.write(context)

            val builder = context.classDefBy(targets.adapter).methods
                .single { it.name == targets.builder && it.parameterTypes.map(CharSequence::toString) == targets.builderParameters }
            val code = builder.implementation!!.instructions.toList()
            assertTrue("$where: first thing", code[0].calls(HOME_BUILD_STARTS))
            assertEquals("$where: the adapter, p0", builder.implementation!!.registerCount - 3, (code[0] as RegisterRangeInstruction).startRegister)
            assertEquals("$where: once", 1, code.count { it.calls(HOME_BUILD_STARTS) })
            assertTrue("$where: the stub asks ${targets.ask}", stubBody(context, "askNextPage", 4)[2].calls(targets.ask.toString()))
            assertTrue("$where: the stub reads ${targets.policyField}", stubBody(context, "policyOf", 5)[1].reads(targets.policyField.toString()))
            checked++
        }
        assertEquals("every build was checked", bundles.size + others.size, checked)
        assertEquals("the declared build and the six others", 7, checked)
    }

    /**
     * The first [size] instructions of the stub [name], checked to use parameter registers alone: a
     * stub that put a value in a local would land it on a register the stub doesn't own.
     */
    private fun stubBody(context: BytecodePatchContext, name: String, size: Int): List<Instruction> {
        val stub = context.classDefBy(HOME_NEXT_PAGE).methods.single { it.name == name }
        val implementation = stub.implementation!!
        val body = implementation.instructions.toList().take(size)
        val firstParameter = implementation.registerCount - stub.parameterTypes.size
        for (register in body.flatMap { it.registers() }) {
            assertTrue("$name: v$register isn't a parameter register (p0 is v$firstParameter)", register >= firstParameter)
        }
        return body
    }

    private fun stubs(context: BytecodePatchContext): Map<String, List<Opcode>> =
        context.classDefBy(HOME_NEXT_PAGE).methods.filter { it.name in setOf("policyOf", "sourceOf", "loadingNow", "moreLeft", "askNextPage") }
            .associate { method -> method.name to method.implementation!!.instructions.map { it.opcode } }

    /**
     * Home's adapter, its feed, the load more policy, the lazy holding it and the class that asks
     * it for the next page as the loading row comes into view, plus the extension. [asks] are the
     * policy's (Map)V calls that class makes, [fields] the adapter's fields it reads the policy from.
     */
    private fun world(
        asks: List<String> = listOf("ask"),
        fields: List<String> = listOf("policy"),
        publicSource: Boolean = true,
        spinnerString: String = VISIBLE_SPINNER,
    ): List<ClassDef> = listOf(
        classDef(adapter, listOf(
            method(adapter, "buildModels", "V", registers = 6, parameters = listOf("I", "Ljava/lang/Integer;"), body = """
                const-string v0, "$BUILD_MODELS"
                iget-object v1, v3, $adapter->feed:$feed
                iget-boolean v2, v1, $feed->ended:Z
                if-nez v2, :done
                const-string v0, "$SHIMMER_KEY"
                :done
                return-void
            """),
        ), fields = (listOf("feed" to feed) + fields.map { it to lazy }).map { (name, type) -> field(adapter, name, type) }),
        classDef(feed, emptyList(), fields = listOf(field(feed, "ended", "Z"), field(feed, "source", "Ljava/lang/String;", public = publicSource))),
        classDef(policy, listOf(
            method(policy, "shows", "Z", registers = 3, body = """
                iget-object v0, v2, $policy->feed:$feed
                iget-object v0, v0, $feed->source:Ljava/lang/String;
                const-string v1, "$FOLLOWING_FEED"
                invoke-virtual { v1, v0 }, Ljava/lang/String;->equals(Ljava/lang/Object;)Z
                move-result v0
                if-eqz v0, :done
                invoke-virtual { v2 }, $policy->hasMore()Z
                move-result v0
                :done
                return v0
            """),
            method(policy, "isLoading", "Z", registers = 2, body = """
                invoke-virtual { v1 }, $policy->busy()Z
                move-result v0
                invoke-virtual { v1 }, $policy->hasMore()Z
                move-result v0
                return v0
            """),
            method(policy, "hasMore", "Z", registers = 2, body = "const/4 v0, 0x1\nreturn v0"),
            method(policy, "busy", "Z", registers = 2, body = "const/4 v0, 0x0\nreturn v0"),
        ) + asks.map { name ->
            method(policy, name, "V", registers = 3, parameters = listOf("Ljava/util/Map;"), body = """
                invoke-virtual { v1 }, $policy->busy()Z
                move-result v0
                if-nez v0, :done
                invoke-virtual { v1 }, $policy->hasMore()Z
                move-result v0
                :done
                return-void
            """)
        }, interfaces = listOf("Lfixture/LoadMorePolicy;"), fields = listOf(field(policy, "feed", feed))),
        ImmutableClassDef(
            lazy, AccessFlags.PUBLIC.value or AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value, "Ljava/lang/Object;",
            null, null, null, emptyList(),
            listOf(ImmutableMethod(lazy, "getValue", emptyList<ImmutableMethodParameter>(), "Ljava/lang/Object;",
                AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, null, null, null)),
        ),
        classDef(spinner, listOf(
            method(spinner, "onVisible", "V", registers = 5, parameters = listOf(adapter), body = buildString {
                appendLine("const-string v0, \"$spinnerString\"")
                for (name in fields) {
                    appendLine("iget-object v1, v4, $adapter->$name:$lazy")
                    appendLine("invoke-interface { v1 }, $getValue")
                    appendLine("move-result-object v1")
                    appendLine("check-cast v1, $policy")
                    for (ask in asks) {
                        appendLine("new-instance v2, Ljava/util/HashMap;")
                        appendLine("invoke-direct { v2 }, Ljava/util/HashMap;-><init>()V")
                        appendLine("invoke-virtual { v1, v2 }, $policy->$ask(Ljava/util/Map;)V")
                    }
                }
                append("return-void")
            }),
        )),
        ExtensionDex.classDef(HOME_NEXT_PAGE),
    )

    private fun Instruction.calls(reference: String) =
        ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString() == reference

    private fun Instruction.reads(reference: String) =
        ((this as? ReferenceInstruction)?.reference as? FieldReference)?.toString() == reference

    private fun Instruction.type() = ((this as ReferenceInstruction).reference as TypeReference).type

    private fun Instruction.registers(): List<Int> = when (this) {
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        is TwoRegisterInstruction -> listOf(registerA, registerB)
        is OneRegisterInstruction -> listOf(registerA)
        else -> emptyList()
    }

    private fun Method.holds(string: String): Boolean = implementation?.instructions?.any {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == string
    } == true

    private fun method(type: String, name: String, returnType: String, registers: Int, body: String, parameters: List<String> = emptyList()): Method {
        val mutable = MutableMethod(
            ImmutableMethod(
                type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returnType,
                AccessFlags.PUBLIC.value, null, null, ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun field(type: String, name: String, fieldType: String, public: Boolean = true) =
        ImmutableField(type, name, fieldType, if (public) AccessFlags.PUBLIC.value else AccessFlags.PRIVATE.value, null, null, null)

    private fun classDef(type: String, methods: List<Method>, interfaces: List<String>? = null, fields: List<ImmutableField> = emptyList()): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", interfaces, null, null, fields, methods)
}
