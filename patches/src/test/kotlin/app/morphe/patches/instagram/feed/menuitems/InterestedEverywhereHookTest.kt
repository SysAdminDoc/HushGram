/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.menuitems

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
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Interested on every post and reel (#815): the reel menu hands its follow test's answer to
 * InterestedEverywhere.reelFollowing right before the test, and the post menu's opener hands the
 * flags maker's Interested argument to postOffered right before the call, each answer back in the
 * same register. A build whose menus moved refuses before anything changes, and Three-dot menu
 * items goes in without the switch.
 */
class InterestedEverywhereHookTest {
    private val reelMenu = "Lfixture/ReelMenu;"
    private val flags = "Lfixture/Flags;"
    private val keys = "Lfixture/Keys;"
    private val factory = "Lfixture/Factory;"
    private val defaults = "Lfixture/Defaults;"
    private val postMenu = "Lfixture/PostMenu;"
    private val final = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
    private val static = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
    private val makerTakes = listOf("Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;", "Z", "Z")

    @Test
    fun theHooksAreInTheExtension() {
        for (hook in listOf(REEL_FOLLOWING, POST_OFFERED)) {
            val type = hook.substringBefore("->")
            val declared = ExtensionDex.classDef(type).methods
                .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
                .map { "$type->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            assertTrue("$hook is not in the extension: $declared", hook in declared)
        }
    }

    @Test
    fun bothMenusAskBeforeTheyDecide() {
        val context = context()
        val plan = context.findInterested()
        val reel = plan.reels.single()
        assertEquals("the reel test", Triple(reelMenu, "A06", 5), Triple(reel.type, reel.name, reel.at))
        assertEquals("the equals answer's register", 1, reel.register)
        assertEquals("the call of the maker", Triple(postMenu, "FLn", 6), Triple(plan.post.type, plan.post.name, plan.post.at))
        assertEquals("the maker's first boolean, past three strings", 4, plan.post.register)
        assertEquals("$factory->A01", plan.maker)
        val reelStock = context.code(reel)
        val postStock = context.code(plan.post)
        context.offerInterested(plan)
        assertReelAsks("fixture", context.code(reel), reelStock, reel)
        assertPostAsks("fixture", context.code(plan.post), postStock, plan)
    }

    /** Two reel menus testing the follow state, as 457's three-dot list and press sheet do, each ask. */
    @Test
    fun everyReelMenuTestingTheFollowAsks() {
        val context = context(reels = 2)
        val plan = context.findInterested()
        assertEquals("both reel menus", setOf(reelMenu, "Lfixture/ReelMenu1;"), plan.reels.map { it.type }.toSet())
        val stock = plan.reels.map { context.code(it) }
        context.offerInterested(plan)
        plan.reels.forEachIndexed { index, reel -> assertReelAsks(reel.type, context.code(reel), stock[index], reel) }
    }

    @Test
    fun menusItCantReadRefuseAndTheRestGoesIn() {
        refuses("reel menu testing whether you follow the account right before adding Interested, found 0", context(reels = 0))
        refuses("found 0", context(testLandsOnNotInterested = false))
        refuses("found 0", context(equalsBeforeTheTest = false))
        refuses("jumps onto its follow test", context(jumpOntoTheReelTest = true))
        refuses("boolean a post menu reads right before adding \"interested\", found 0", context(keysTestTheFlag = false))
        refuses("from its parameters, found 0", context(makerWritesAParameter = false))
        refuses("method opening $POST_SHEET for a view and a post, found 0", context(openerLoadsTheSheet = false))
        refuses("2 times, not once", context(makerCalls = 2))
        refuses("without a register range", context(rangeCall = false))
        refuses("jumps onto its call of", context(jumpOntoTheCall = true))
        val empty = PatchContexts.of(emptyList<ClassDef>())
        assertThrows(PatchException::class.java) { empty.findInterested() }
        assertNull(empty.findInterestedOrWarn())
    }

    /** A menu changed between the find and the hook refuses before either menu changes. */
    @Test
    fun aMenuThatChangedRefusesBothHooks() {
        val context = context(reels = 2)
        val plan = context.findInterested()
        val moved = InterestedPlan(
            plan.reels,
            InterestedHook(plan.post.type, plan.post.name, plan.post.parameters, plan.post.returnType, plan.post.at - 1, plan.post.register),
            plan.maker,
        )
        val reelStock = plan.reels.map { context.code(it) }
        val failure = assertThrows(PatchException::class.java) { context.offerInterested(moved) }
        assertTrue(failure.message, failure.message!!.startsWith("Three-dot menu items: ") && "changed before Interested" in failure.message!!)
        plan.reels.forEachIndexed { index, reel ->
            assertEquals("${reel.type} is left as it was", reelStock[index].shape(), context.code(reel).shape())
        }
    }

    @Test
    fun declaredBuildsAskBeforeTheyDecide() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var count = 0
        for (version in versions) for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
            builds(bundle)
            count++
        }
        assertTrue("no fixture of a declared build", count > 0)
    }

    @Test
    fun otherBuildsAskBeforeTheyDecideToo() {
        for (bundle in Fixtures.otherBuilds()) builds(bundle)
    }

    /**
     * The classes reading Interested, the ones loading the post menu's key or sheet (pools included)
     * and the ones making the flags that key reads: some reel menus test the follow state, one maker
     * writes the flag from its parameters, one opener calls it, and each is hooked there.
     */
    private fun builds(bundle: File) {
        val name = bundle.parentFile.name + "/" + bundle.name
        val readers = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            if (dex.fieldSection.none { it.toString() == INTERESTED }) return@forEach
            for (classDef in dex.classes) {
                val reads = classDef.methods.any { method ->
                    method.implementation?.instructions?.any {
                        it.opcode == Opcode.SGET_OBJECT && (it as ReferenceInstruction).reference.toString() == INTERESTED
                    } == true
                }
                if (reads) readers += ImmutableClassDef.of(classDef)
            }
        }
        val loading = FixtureDex.classesLoadingAny(bundle, setOf(INTERESTED_KEY, POST_SHEET))
        val flagType = try {
            PatchContexts.of(loading).interestedFlag().definingClass
        } catch (failure: PatchException) {
            throw AssertionError("$name: ${failure.message}", failure)
        }
        val classes = (readers + loading + FixtureDex.classesCreating(bundle, setOf(flagType))).distinctBy { it.type }
        val context = PatchContexts.of(classes)
        val plan = try {
            context.findInterested()
        } catch (failure: PatchException) {
            throw AssertionError("$name: ${failure.message}", failure)
        }
        assertTrue("$name: a reel menu", plan.reels.isNotEmpty())
        val reelStock = plan.reels.map { context.code(it) }
        val postStock = context.code(plan.post)
        context.offerInterested(plan)
        plan.reels.forEachIndexed { index, reel -> assertReelAsks("$name ${reel.type}->${reel.name}", context.code(reel), reelStock[index], reel) }
        assertPostAsks(name, context.code(plan.post), postStock, plan)
    }

    /**
     * Right before the follow test: the hook, handed the equals answer's register alone, its answer
     * back in that register, then the test itself, still landing on Not interested, and Interested
     * right after. Nothing jumps onto the hook, the rest as it was.
     */
    private fun assertReelAsks(what: String, code: List<Instruction>, stock: List<Instruction>, hook: InterestedHook) {
        assertEquals("$what: the stock test", Opcode.IF_NEZ to hook.register,
            stock[hook.at].opcode to (stock[hook.at] as OneRegisterInstruction).registerA)
        assertEquals("$what: one hook, right before the test", listOf(hook.at), code.indices.filter { text(code[it]) == REEL_FOLLOWING })
        assertCall(what, code, hook)
        val test = code[hook.at + 2]
        assertEquals("$what: then the test of the answer", Opcode.IF_NEZ to hook.register, test.opcode to (test as OneRegisterInstruction).registerA)
        val lands = (test as BuilderOffsetInstruction).target.location.index
        assertEquals("$what: still landing on Not interested", NOT_INTERESTED, text(code[lands]))
        assertEquals("$what: Interested right after", INTERESTED, text(code[hook.at + 3]))
        assertKept(what, code, stock, hook)
    }

    /**
     * Right before the maker: the hook, handed the Interested argument's register alone, its answer
     * back there, then the maker's call reading it as the parameter it writes the flag from.
     */
    private fun assertPostAsks(what: String, code: List<Instruction>, stock: List<Instruction>, plan: InterestedPlan) {
        val hook = plan.post
        assertEquals("$what: one hook, right before the maker", listOf(hook.at), code.indices.filter { text(code[it]) == POST_OFFERED })
        assertCall(what, code, hook)
        val maker = code[hook.at + 2]
        val asked = (maker as ReferenceInstruction).reference.toString()
        assertEquals("$what: then the maker", plan.maker, asked.substringBefore("("))
        val range = maker as RegisterRangeInstruction
        assertTrue("$what: the maker reads the answer", hook.register in range.startRegister until range.startRegister + range.registerCount)
        assertKept(what, code, stock, hook)
    }

    private fun assertCall(what: String, code: List<Instruction>, hook: InterestedHook) {
        val call = code[hook.at] as RegisterRangeInstruction
        assertEquals("$what: a static call", Opcode.INVOKE_STATIC_RANGE, code[hook.at].opcode)
        assertEquals("$what: of the register alone", 1 to hook.register, call.registerCount to call.startRegister)
        val answer = code[hook.at + 1]
        assertEquals("$what: its answer back in that register", Opcode.MOVE_RESULT to hook.register,
            answer.opcode to (answer as OneRegisterInstruction).registerA)
        assertEquals("$what: nothing jumps onto the hook", 0,
            code.count { (it as? BuilderOffsetInstruction)?.target?.location?.index in hook.at..hook.at + 1 })
    }

    private fun assertKept(what: String, code: List<Instruction>, stock: List<Instruction>, hook: InterestedHook) {
        val kept = code.filterIndexed { at, _ -> at !in hook.at..hook.at + 1 }
        assertEquals("$what: the menu's own code is kept", stock.shape(), kept.shape())
    }

    private fun refuses(reason: String, context: BytecodePatchContext) {
        val failure = assertThrows(PatchException::class.java) { context.findInterested() }
        assertTrue(failure.message, failure.message!!.startsWith("Three-dot menu items: "))
        assertTrue(failure.message, reason in failure.message!!)
        assertNull(context.findInterestedOrWarn())
    }

    private fun BytecodePatchContext.code(hook: InterestedHook): List<Instruction> =
        mutableClassDefBy(hook.type).methods.single { it.name == hook.name && it.parameterTypes.map(Any::toString) == hook.parameters }
            .implementation?.instructions?.toList().orEmpty()

    /**
     * Shaped like 457: the reel menu (0Ckr.A06) asks whether the account's following answer equals
     * true and skips Interested when it does, landing on Not interested. The post menu's keys
     * (0Clj.A01) test the flags' A03 right before "interested". The factory (0U9Q.A01) makes the flags
     * from three strings and the booleans, A03 from the first; a second maker (06rD.A00) writes a
     * constant. The opener (01Cy.FLn) loads the sheet's name and calls the factory with a range.
     */
    private fun context(
        reels: Int = 1,
        testLandsOnNotInterested: Boolean = true,
        equalsBeforeTheTest: Boolean = true,
        jumpOntoTheReelTest: Boolean = false,
        keysTestTheFlag: Boolean = true,
        makerWritesAParameter: Boolean = true,
        openerLoadsTheSheet: Boolean = true,
        makerCalls: Int = 1,
        rangeCall: Boolean = true,
        jumpOntoTheCall: Boolean = false,
    ): BytecodePatchContext {
        val classes = mutableListOf<ClassDef>()
        repeat(reels) { index ->
            val type = if (index == 0) reelMenu else "Lfixture/ReelMenu$index;"
            val body = buildString {
                appendLine("new-instance v0, Ljava/util/ArrayList;")
                appendLine("invoke-direct {v0}, Ljava/util/ArrayList;-><init>()V")
                appendLine("sget-object v1, Ljava/lang/Boolean;->TRUE:Ljava/lang/Boolean;")
                if (jumpOntoTheReelTest) appendLine("if-eqz p1, :test")
                appendLine(
                    if (equalsBeforeTheTest) "invoke-virtual {v1, p0}, Ljava/lang/Object;->equals(Ljava/lang/Object;)Z"
                    else "invoke-virtual {v1, p0}, Ljava/lang/Object;->hashCode()I",
                )
                appendLine("move-result v1")
                if (jumpOntoTheReelTest) appendLine(":test")
                appendLine(if (testLandsOnNotInterested) "if-nez v1, :not" else "if-nez v1, :end")
                appendLine("sget-object v2, $INTERESTED")
                appendLine("invoke-virtual {v0, v2}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z")
                appendLine(":not")
                appendLine("sget-object v2, $NOT_INTERESTED")
                appendLine("invoke-virtual {v0, v2}, Ljava/util/ArrayList;->add(Ljava/lang/Object;)Z")
                appendLine(":end")
                appendLine("return-object v0")
            }
            classes += classDef(type, listOf(method(type, "A06", listOf("Ljava/lang/Object;", "Ljava/lang/Object;", "Ljava/lang/Object;"),
                "Ljava/util/ArrayList;", 3, static, body)))
        }
        classes += classDef(flags, emptyList(), listOf(
            ImmutableField(flags, "A03", "Z", final, null, null, null),
            ImmutableField(flags, "A0C", "Z", final, null, null, null),
        ))
        val keysBody = buildString {
            appendLine("new-instance v0, Ljava/util/ArrayList;")
            appendLine("invoke-direct {v0}, Ljava/util/ArrayList;-><init>()V")
            appendLine("iget-boolean v1, p0, $flags->A03:Z")
            appendLine("const-string v2, \"not_interested\"")
            appendLine(if (keysTestTheFlag) "if-eqz v1, :skip" else "if-eqz v2, :skip")
            appendLine("const-string v1, \"$INTERESTED_KEY\"")
            appendLine("invoke-interface {v0, v1}, Ljava/util/List;->add(Ljava/lang/Object;)Z")
            appendLine(":skip")
            appendLine("invoke-interface {v0, v2}, Ljava/util/List;->add(Ljava/lang/Object;)Z")
            appendLine("return-object v0")
        }
        classes += classDef(keys, listOf(method(keys, "A01", listOf(flags), "Ljava/util/List;", 3, static, keysBody)))
        val makerBody = buildString {
            appendLine("new-instance v0, $flags")
            appendLine(if (makerWritesAParameter) "iput-boolean p3, v0, $flags->A03:Z" else "iput-boolean v1, v0, $flags->A03:Z")
            appendLine("iput-boolean p4, v0, $flags->A0C:Z")
            appendLine("return-object v0")
        }
        classes += classDef(factory, listOf(method(factory, "A01", makerTakes, flags, 2, static, "const/4 v1, 0x0\n$makerBody")))
        classes += classDef(defaults, listOf(method(defaults, "A00", emptyList(), flags, 2, static,
            "new-instance v0, $flags\nconst/4 v1, 0x0\niput-boolean v1, v0, $flags->A03:Z\nreturn-object v0")))
        val call = if (rangeCall) "invoke-static/range {v1 .. v5}, $factory->A01(${makerTakes.joinToString("")})$flags"
        else "invoke-static {v1, v2, v3, v4, v5}, $factory->A01(${makerTakes.joinToString("")})$flags"
        val openerBody = buildString {
            appendLine(if (openerLoadsTheSheet) "const-string v0, \"$POST_SHEET\"" else "const-string v0, \"MediaOverflowBottomSheet\"")
            appendLine("const-string v1, \"a\"")
            appendLine("const-string v2, \"b\"")
            appendLine("const-string v3, \"c\"")
            appendLine("const/4 v4, 0x0")
            appendLine("const/4 v5, 0x1")
            if (jumpOntoTheCall) appendLine("if-eqz p4, :call")
            if (jumpOntoTheCall) appendLine(":call")
            repeat(makerCalls) {
                appendLine(call)
                appendLine("move-result-object v1")
            }
            appendLine("return-void")
        }
        classes += classDef(postMenu, listOf(method(postMenu, "FLn",
            listOf("Landroid/view/View;", "Lcom/instagram/feed/media/Media;", "Ljava/lang/Object;", "I"), "V", 6, final, openerBody)))
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

    private fun classDef(type: String, methods: List<Method>, fields: List<ImmutableField> = emptyList()): ClassDef =
        ImmutableClassDef(type, final, "Ljava/lang/Object;", emptyList(), null, null, fields, methods)

    private fun text(instruction: Instruction): String =
        (instruction as? ReferenceInstruction)?.reference?.toString() ?: "${instruction.opcode}"

    /** Opcodes and references without padding, with a widened goto read as a goto. */
    private fun List<Instruction>.shape() = filter { it.opcode != Opcode.NOP && !it.opcode.format.isPayloadFormat }
        .map { if (it.opcode == Opcode.GOTO_16 || it.opcode == Opcode.GOTO_32) Opcode.GOTO to "${Opcode.GOTO}" else it.opcode to text(it) }
}
