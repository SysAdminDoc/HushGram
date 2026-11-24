/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.lock

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.direct.seen.NativeVisualSeenTest.Companion.fixtures
import app.morphe.patches.instagram.direct.seen.NativeVisualSeenTest.Companion.otherBuilds
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
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
import java.io.File

/**
 * Shown hidden chats page in the rest (#661): the inbox's load-more hands itself to the extension as
 * the inbox makes it, and the extension's bridge asks it for the next page the way the list's scroll
 * does near the end. A build whose inbox moved refuses before anything changes, and the lock goes on.
 */
class HiddenChatPagesHookTest {
    private val coordinator = "Lfixture/Coordinator;"
    private val presenter = "Lfixture/Presenter;"
    private val pager = "Lfixture/Pager;"
    private val loadMore = "Lfixture/LoadMore;"
    private val reason = "Lfixture/Reason;"
    private val final = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value

    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(HIDDEN_CHATS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue(declared.toString(), HIDDEN_PAGER.substringAfter("->") in declared)
        assertTrue(declared.toString(), "nextPage(Ljava/lang/Object;)V" in declared)
    }

    @Test
    fun theInboxsLoadMoreIsHandedOverAndAskedThroughTheBridge() {
        val context = context()
        val found = context.findInboxPager()
        assertEquals("$pager->more()V", found.more)
        val stock = found.init.instructions().map(::text)

        pageHiddenChats(found)

        assertHandsOver("fixture", found.init.instructions(), stock)
        assertBridge("fixture", context, pager, "$pager->more()V")
    }

    /**
     * 457's load more takes why it loads, an enum, and the load-more asks it with one of the enum's
     * values (01Po.A0g, asked by 01c0.EoU with 09Fp.A05 on 386314341). A null there isn't a value.
     */
    @Test
    fun aLoadMoreTakingWhyItLoadsIsHandedOverToo() {
        val context = context(why = true)
        val found = context.findInboxPager()
        assertEquals("$pager->more()V", found.more)
        val stock = found.init.instructions().map(::text)

        pageHiddenChats(found)

        assertHandsOver("why", found.init.instructions(), stock)
        assertBridge("why", context, pager, "$pager->more()V")
        refuses("expected one load-more asking $coordinator->load", context(why = true, yes = false))
    }

    @Test
    fun aBuildItCantReadRefusesAndTheLockGoesOn() {
        refuses("expected one class loading \"$INBOX_COORDINATOR\"", context(coordinators = 0))
        refuses("expected one class loading \"$INBOX_COORDINATOR\"", context(coordinators = 2))
        refuses("expected one load-more asking $coordinator->load", context(pagers = 0))
        refuses("expected one load-more asking $coordinator->load", context(pagers = 2))
        refuses("expected one load-more asking $coordinator->load", context(yes = false))
        refuses("has 2 constructors", context(constructors = 2))
        val empty = PatchContexts.of(emptyList<ClassDef>())
        assertThrows(PatchException::class.java) { empty.findInboxPager() }
        assertNull(empty.findInboxPagerOrSkip())
    }

    @Test fun declaredBuildsHandOverTheInboxsLoadMore() = fixtures { builds(it) }

    @Test fun otherBuildsHandOverTheInboxsLoadMoreToo() = otherBuilds { builds(it) }

    /** The coordinator and its runnables, every class calling into them and the extension: the inbox's load-more is among them, hooked and bridged. */
    private fun builds(bundle: File) {
        val label = bundle.parentFile.name + "/" + bundle.name
        val loading = FixtureDex.classesLoading(bundle, INBOX_COORDINATOR)
        val types = loading.mapTo(HashSet()) { it.type }
        val calling = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                if (classDef.type in types) continue
                val calls = classDef.methods.any { method ->
                    method.implementation?.instructions?.any { ((it as? ReferenceInstruction)?.reference as? MethodReference)?.definingClass in types } == true
                }
                if (calls) calling += ImmutableClassDef.of(classDef)
            }
        }
        val context = PatchContexts.of(loading + calling + ImmutableClassDef.of(ExtensionDex.classDef(HIDDEN_CHATS)))
        val found = try {
            context.findInboxPager()
        } catch (failure: PatchException) {
            throw AssertionError("$label: ${failure.message}", failure)
        }
        val owner = found.more.substringBefore("->")
        val stock = found.init.instructions().map(::text)
        pageHiddenChats(found)
        assertHandsOver(label, found.init.instructions(), stock)
        assertBridge(label, context, owner, found.more)
    }

    /** The constructor hands itself over right before each return, and the rest is as it was. */
    private fun assertHandsOver(what: String, code: List<Instruction>, stock: List<String>) {
        val hands = code.indices.filter { (code[it] as? ReferenceInstruction)?.reference?.toString() == HIDDEN_PAGER }
        assertTrue("$what: handed over", hands.isNotEmpty())
        val self = (code.first { it.opcode == Opcode.IPUT_OBJECT } as TwoRegisterInstruction).registerB
        for (at in hands) {
            val call = code[at] as RegisterRangeInstruction
            assertEquals("$what: a static call", Opcode.INVOKE_STATIC_RANGE, code[at].opcode)
            assertEquals("$what: of one register", 1, call.registerCount)
            assertEquals("$what: the load-more itself", self, call.startRegister)
            assertEquals("$what: right before a return", Opcode.RETURN_VOID, code[at + 1].opcode)
        }
        assertEquals("$what: one before each return", code.count { it.opcode == Opcode.RETURN_VOID }, hands.size)
        assertEquals("$what: Instagram's own code stays", stock, code.filterIndexed { at, _ -> at !in hands }.map(::text))
    }

    /** The extension's bridge casts to the load-more and asks it for the next page, ahead of the stub's own empty body. */
    private fun assertBridge(what: String, context: BytecodePatchContext, owner: String, more: String) {
        val bridge = context.mutableClassDefBy(HIDDEN_CHATS).methods.single { it.name == "nextPage" }.instructions()
        assertEquals("$what: the bridge", listOf(Opcode.CHECK_CAST, Opcode.INVOKE_VIRTUAL, Opcode.RETURN_VOID), bridge.take(3).map { it.opcode })
        assertEquals("$what: then the stub's unreachable return", listOf(Opcode.RETURN_VOID), bridge.drop(3).map { it.opcode })
        assertEquals("$what: cast to the load-more", owner, text(bridge[0]))
        assertEquals("$what: its next page", more, text(bridge[1]))
    }

    private fun refuses(reason: String, context: BytecodePatchContext) {
        val failure = assertThrows(PatchException::class.java) { context.findInboxPager() }
        assertTrue(failure.message, failure.message!!.startsWith("$LOCK_PATCH: "))
        assertTrue(failure.message, reason in failure.message!!)
        assertNull(context.findInboxPagerOrSkip())
    }

    /**
     * Shaped like 456.1's 386215122: the coordinator (01CE) logs its name and has one instance (Z)Z
     * load more, and the load-more (01Lo) of a load-more interface asks it for the next page with a
     * yes through the inbox's presenter. A runnable calls the load more with a no.
     */
    private fun context(
        coordinators: Int = 1,
        pagers: Int = 1,
        yes: Boolean = true,
        constructors: Int = 1,
        why: Boolean = false,
    ): BytecodePatchContext {
        val takes = if (why) reason else "Z"
        val ask = when {
            !yes -> "const/4 v1, 0x0"
            why -> "sget-object v1, $reason->TAP:$reason"
            else -> "const/4 v1, 0x1"
        }
        val classes = mutableListOf<ClassDef>()
        repeat(coordinators) { index ->
            val type = if (index == 0) coordinator else "Lfixture/Coordinator$index;"
            classes += classDef(
                type,
                listOf(
                    method(type, "log", emptyList(), "V", 1, final, "const-string v0, \"$INBOX_COORDINATOR\"\nreturn-void"),
                    method(type, "load", listOf(takes), "Z", 1, final, "const/4 v0, 0x1\nreturn v0"),
                ),
            )
        }
        repeat(pagers) { index ->
            val type = if (index == 0) pager else "Lfixture/Pager$index;"
            val inits = List(constructors) { n ->
                val takes = if (n == 0) listOf(presenter) else listOf(presenter, "I")
                method(
                    type, "<init>", takes, "V", 0, AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value,
                    "invoke-direct {p0}, Ljava/lang/Object;-><init>()V\niput-object p1, p0, $type->presenter:$presenter\nreturn-void",
                )
            }
            classes += classDef(
                type,
                inits + listOf(
                    method(type, "isLoading", emptyList(), "Z", 1, final, "const/4 v0, 0x0\nreturn v0"),
                    method(
                        type, "more", emptyList(), "V", 2, final,
                        """
                            iget-object v0, p0, $type->presenter:$presenter
                            invoke-virtual {v0}, $presenter->coordinator()$coordinator
                            move-result-object v0
                            $ask
                            invoke-virtual {v0, v1}, $coordinator->load($takes)Z
                            return-void
                        """,
                    ),
                ),
                listOf(ImmutableField(type, "presenter", presenter, final, null, null, null)),
                listOf(loadMore),
            )
        }
        classes += classDef(
            "Lfixture/Runnable;",
            listOf(
                method(
                    "Lfixture/Runnable;", "run", emptyList(), "V", 2, final,
                    """
                        iget-object v0, p0, Lfixture/Runnable;->coordinator:$coordinator
                        const/4 v1, 0x0
                        invoke-virtual {v0, v1}, $coordinator->load($takes)Z
                        return-void
                    """,
                ),
            ),
            interfaces = listOf("Ljava/lang/Runnable;"),
        )
        classes += ImmutableClassDef.of(ExtensionDex.classDef(HIDDEN_CHATS))
        return PatchContexts.of(classes)
    }

    private fun method(owner: String, name: String, parameters: List<String>, returns: String, registers: Int, flags: Int, body: String): Method {
        val words = parameters.size + parameters.count { it == "J" || it == "D" }
        val total = registers + (if (AccessFlags.STATIC.isSet(flags)) 0 else 1) + words
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.lines().filter { it.isNotBlank() }.joinToString("\n") { it.trim() })
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>, fields: List<ImmutableField> = emptyList(), interfaces: List<String> = emptyList()): ClassDef =
        ImmutableClassDef(type, final, "Ljava/lang/Object;", interfaces, null, null, fields, methods)

    private fun Method.instructions(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun text(instruction: Instruction): String =
        (instruction as? ReferenceInstruction)?.reference?.toString() ?: "${instruction.opcode}"
}
