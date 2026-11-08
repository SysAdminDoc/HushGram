/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.ads.plus

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
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OffsetInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HidePlusUpsellHookTest {
    private val gate = "Lfixture/PlusGate;"
    private val entry = "Lfixture/Entry;"
    private val resolver = "Lfixture/Resolver;"
    private val offer = "Lfixture/Offer;"
    private val parameters = listOf(USER_SESSION, entry, "Ljava/lang/Boolean;", "Lkotlin/jvm/functions/Function0;")

    /** The hook the patch writes is in the PlusUpsell the bundle ships, public and static. */
    @Test
    fun theHookIsInTheExtension() {
        val owner = PLUS_OFFER.substringBefore("->")
        val declared = ExtensionDex.classDef(owner).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$PLUS_OFFER is not in the extension: $declared", PLUS_OFFER.substringAfter("->") in declared)
    }

    /**
     * An offer check shaped like 454's 002K.A02: it answers no straight away when Plus isn't sold here, a
     * branch jumps onto the last return, and it answers the cached decision otherwise. Every return passes
     * the answer through the hook, the branch onto the return included, and nothing stock is taken out.
     * The session-only helper beside it, which loads neither string, is left alone.
     */
    @Test
    fun everyAnswerOfTheCheckIsFiltered() {
        val context = PatchContexts.of(classes())
        val stock = context.check().code()

        val found = context.findPlusOfferCheck()
        assertEquals("the check's class", gate, found.definingClass)
        assertEquals("the check", "decide", found.name)
        context.hidePlusOffers(found)

        val patched = context.check().code()
        assertFiltered("fixture", patched, 3)
        assertTrue("stock instructions kept in order", isKept(stock, patched))
        assertEquals("the helper is untouched", 0,
            context.mutableClassDefBy(gate).methods.single { it.name == "sold" }.code().count { it.referenceText() == PLUS_OFFER })
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val expected = "expected one Plus offer check loading \"$PLUS_DECISION\" and \"$PLUS_SERVER_UNAVAILABLE\""
        val cases = listOf(
            classes(decisionLoads = false) to "$expected, found 0",
            classes(serverLoads = false) to "$expected, found 0",
            classes(answersBoolean = false) to "$expected, found 0",
            classes(twoChecks = true) to "$expected, found 2",
        )
        for ((classes, message) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(message, PatchException::class.java) { context.hidePlusOffers(context.findPlusOfferCheck()) }
            assertTrue("$message: ${failure.message}", failure.message!!.contains(message))
            val written = classes.map { it.type }.flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() == PLUS_OFFER } }
            assertTrue("$message: something was written to $written", written.isEmpty())
        }
    }

    /**
     * A resolver shaped like 454's MetaSubsEntrypointExperienceResolver: the builder taking a cached
     * snapshot starts by asking the hook and hands back no offer on a yes, ahead of every stock
     * instruction, which all stay in order. The cached-offer lookup beside it (no snapshot) and the
     * fetch (an Object answer) are left alone.
     */
    @Test
    fun theOfferBuilderHandsBackNoOfferWhileHidden() {
        val context = PatchContexts.of(resolverClasses())
        val stock = context.builder().code()

        val found = context.findPlusOfferBuilder()
        assertEquals("the builder's class", resolver, found.definingClass)
        assertEquals("the builder", "build", found.name)
        context.holdPlusOfferBuilder(found)

        val patched = context.builder().code()
        assertHeld("fixture", patched)
        assertTrue("stock instructions kept in order", isKept(stock, patched))
        assertEquals("hook calls in the resolver", 1,
            context.mutableClassDefBy(resolver).methods.sumOf { m -> m.code().count { it.referenceText() == PLUS_HIDE } })
    }

    /** A resolver the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aResolverThePatchCantReadFailsBeforeAnythingChanges() {
        val expected = "expected one Plus offer builder taking $PLUS_SNAPSHOT beside \"$PLUS_FETCH\""
        val cases = listOf(
            resolverClasses(fetchLoads = false) to "$expected, found 0",
            resolverClasses(takesSnapshot = false) to "$expected, found 0",
            resolverClasses(twoBuilders = true) to "$expected, found 2",
        )
        for ((resolverDefs, message) in cases) {
            val all = resolverDefs + classes()
            val context = PatchContexts.of(all)
            val failure = assertThrows(message, PatchException::class.java) {
                val check = context.findPlusOfferCheck()
                val builder = context.findPlusOfferBuilder()
                context.hidePlusOffers(check)
                context.holdPlusOfferBuilder(builder)
            }
            assertTrue("$message: ${failure.message}", failure.message!!.contains(message))
            val written = all.map { it.type }.flatMap { type -> context.mutableClassDefBy(type).methods }
                .filter { method -> method.code().any { it.referenceText() == PLUS_OFFER || it.referenceText() == PLUS_HIDE } }
            assertTrue("$message: something was written to $written", written.isEmpty())
        }
    }

    /** In each declared build the offer check is found once and every answer it gives is filtered. */
    @Test
    fun eachDeclaredBuildFiltersTheOfferCheck() {
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
    fun eachOtherBuildFiltersTheOfferCheckToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val sliced = (FixtureDex.classesLoading(bundle, PLUS_DECISION) + FixtureDex.classesLoading(bundle, PLUS_FETCH)).distinctBy { it.type }
        val classes = FixtureDex.withStringPools(bundle, sliced)
        val context = PatchContexts.of(classes)

        val builder = context.findPlusOfferBuilder()
        assertTrue("$label: the builder answers an offer", builder.returnType.startsWith("L"))
        val builderStock = builder.implementation!!.instructions.toList()
        context.holdPlusOfferBuilder(builder)
        val held = context.mutableClassDefBy(builder.definingClass).methods.single {
            it.name == builder.name && it.parameterTypes.map(Any::toString) == builder.parameterTypes.map(Any::toString)
        }.code()
        assertHeld(label, held)
        assertTrue("$label: the builder's stock instructions kept in order", isKept(builderStock, held))
        assertEquals(
            "$label: builders held",
            1,
            classes.map { it.type }.flatMap { context.mutableClassDefBy(it).methods }
                .count { m -> m.code().any { it.referenceText() == PLUS_HIDE } },
        )

        val found = context.findPlusOfferCheck()
        val stock = found.implementation!!.instructions.toList()
        val returns = stock.count { it.opcode == Opcode.RETURN }
        assertTrue("$label: the check answers somewhere", returns > 0)
        context.hidePlusOffers(found)

        val patched = context.mutableClassDefBy(found.definingClass).methods.single {
            it.name == found.name && it.parameterTypes.map(Any::toString) == found.parameterTypes.map(Any::toString)
        }.code()
        assertFiltered(label, patched, returns)
        assertTrue("$label: stock instructions kept in order", isKept(stock, patched))
        assertEquals(
            "$label: methods hooked",
            1,
            classes.map { it.type }.flatMap { context.mutableClassDefBy(it).methods }
                .count { m -> m.code().any { it.referenceText() == PLUS_OFFER } },
        )
    }

    /**
     * One hook call in front of each of the [returns] returns: a range call over the returned register, its
     * answer moved back into that register, then the return. Every branch that lands on a return's spot
     * lands on the hook call, so no path skips it.
     */
    private fun assertFiltered(what: String, code: List<Instruction>, returns: Int) {
        val calls = code.indices.filter { code[it].referenceText() == PLUS_OFFER }
        assertEquals("$what: hook calls", returns, calls.size)
        assertEquals("$what: returns", returns, code.count { it.opcode == Opcode.RETURN })
        for (at in calls) {
            val range = code[at] as RegisterRangeInstruction
            val register = range.startRegister
            assertEquals("$what: the hook's call", Opcode.INVOKE_STATIC_RANGE, code[at].opcode)
            assertEquals("$what: registers handed to the hook", 1, range.registerCount)
            assertEquals("$what: the hook's answer", Opcode.MOVE_RESULT, code[at + 1].opcode)
            assertEquals("$what: the answer's register", register, (code[at + 1] as OneRegisterInstruction).registerA)
            assertEquals("$what: the return", Opcode.RETURN, code[at + 2].opcode)
            assertEquals("$what: the return's register", register, (code[at + 2] as OneRegisterInstruction).registerA)
        }
        val units = code.runningFold(0) { sum, instruction -> sum + instruction.codeUnits }
        val returnSpots = code.indices.filter { code[it].opcode == Opcode.RETURN }.map { units[it] }.toSet()
        code.indices.filter { code[it] is OffsetInstruction }.forEach { at ->
            val target = units[at] + (code[at] as OffsetInstruction).codeOffset
            assertTrue("$what: a branch skips the hook onto a return", target !in returnSpots)
        }
    }

    /**
     * The builder opens with the hook: its answer in v0, a branch past the early return onto the first
     * stock instruction when it says no, and otherwise a null offer handed back.
     */
    private fun assertHeld(what: String, code: List<Instruction>) {
        assertEquals("$what: the first call", PLUS_HIDE, code[0].referenceText())
        assertEquals("$what: the call", Opcode.INVOKE_STATIC, code[0].opcode)
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[1].opcode)
        val answer = (code[1] as OneRegisterInstruction).registerA
        assertEquals("$what: the answer's register", 0, answer)
        assertEquals("$what: the branch", Opcode.IF_EQZ, code[2].opcode)
        assertEquals("$what: the branch reads the answer", answer, (code[2] as OneRegisterInstruction).registerA)
        assertEquals("$what: no offer", Opcode.CONST_4, code[3].opcode)
        assertEquals("$what: no offer is null", 0L, (code[3] as NarrowLiteralInstruction).narrowLiteral.toLong())
        assertEquals("$what: the early return", Opcode.RETURN_OBJECT, code[4].opcode)
        assertEquals("$what: it returns the null", answer, (code[4] as OneRegisterInstruction).registerA)
        val units = code.runningFold(0) { sum, instruction -> sum + instruction.codeUnits }
        assertEquals("$what: a no lands on the first stock instruction", units[5], units[2] + (code[2] as OffsetInstruction).codeOffset)
        assertEquals("$what: hook calls", 1, code.count { it.referenceText() == PLUS_HIDE })
    }

    /** Whether every stock instruction is still there in order, a goto of any width read as one. */
    private fun isKept(stock: List<Instruction>, patched: List<Instruction>): Boolean {
        val gotos = setOf(Opcode.GOTO, Opcode.GOTO_16, Opcode.GOTO_32)
        fun Instruction.kind() = if (opcode in gotos) Opcode.GOTO else opcode
        val wanted = stock.filter { it.opcode != Opcode.NOP }
        val have = patched.filter { it.opcode != Opcode.NOP }
        var at = 0
        for (instruction in have) {
            if (at < wanted.size && instruction.kind() == wanted[at].kind() &&
                instruction.referenceText() == wanted[at].referenceText()) {
                at++
            }
        }
        return at == wanted.size
    }

    /**
     * An offer check shaped like 454's 002K.A02: a session-only helper says whether Plus is sold here; when
     * it isn't, the check answers no. Otherwise it logs its decision strings and answers what the entry's
     * cache says, through a branch onto the last return when the cache is empty.
     */
    private fun classes(
        decisionLoads: Boolean = true,
        serverLoads: Boolean = true,
        answersBoolean: Boolean = true,
        twoChecks: Boolean = false,
    ): List<ClassDef> {
        val static = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
        val returns = if (answersBoolean) "Z" else "I"
        fun body() = """
            const/4 v0, 0x0
            invoke-static {p0}, $gate->sold($USER_SESSION)Z
            move-result v1
            if-nez v1, :sold
            return v0
            :sold
            ${if (decisionLoads) "const-string v1, \"$PLUS_DECISION\"" else "const-string v1, \"other\""}
            invoke-static {v1}, Lfixture/Log;->mark(Ljava/lang/String;)V
            ${if (serverLoads) "const-string v1, \"$PLUS_SERVER_UNAVAILABLE\"" else "const-string v1, \"other\""}
            invoke-static {v1}, Lfixture/Log;->mark(Ljava/lang/String;)V
            invoke-virtual {p1}, $entry->cached()Ljava/lang/Boolean;
            move-result-object v1
            if-eqz v1, :none
            invoke-virtual {v1}, Ljava/lang/Boolean;->booleanValue()Z
            move-result v0
            return v0
            :none
            return v0
        """
        val soldBody = """
            const/4 v0, 0x1
            return v0
        """
        val methods = mutableListOf(
            method(gate, "decide", parameters, returns, 2, static, body()),
            method(gate, "sold", listOf(USER_SESSION), "Z", 1, static, soldBody),
        )
        if (twoChecks) methods += method(gate, "decideAgain", parameters, returns, 2, static, body())
        return listOf(classDef(gate, methods))
    }

    /**
     * A resolver shaped like 454's: an instance fetch loading [PLUS_FETCH] and answering an Object, a
     * static cached-offer lookup taking no snapshot, and the static builder taking a [PLUS_SNAPSHOT]
     * and answering the offer, which it marks as seen when asked to.
     */
    private fun resolverClasses(
        fetchLoads: Boolean = true,
        takesSnapshot: Boolean = true,
        twoBuilders: Boolean = false,
    ): List<ClassDef> {
        val static = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
        val instance = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
        val fetchBody = """
            const-string v0, "${if (fetchLoads) PLUS_FETCH else "other"}"
            invoke-static {v0}, Lfixture/Log;->mark(Ljava/lang/String;)V
            const/4 v0, 0x0
            return-object v0
        """
        val buildBody = """
            new-instance v0, $offer
            invoke-direct {v0}, $offer-><init>()V
            if-eqz p3, :quiet
            invoke-virtual {v0}, $offer->seen()V
            :quiet
            return-object v0
        """
        val cachedBody = """
            const/4 v0, 0x0
            return-object v0
        """
        val snapshot = if (takesSnapshot) PLUS_SNAPSHOT else "Lfixture/Other;"
        val buildParameters = listOf("Landroid/content/Context;", "Lfixture/Session;", snapshot, "Z")
        val methods = mutableListOf(
            method(resolver, "fetch", listOf("Landroid/content/Context;"), "Ljava/lang/Object;", 1, instance, fetchBody),
            method(resolver, "cached", listOf("Landroid/content/Context;", entry, "Lfixture/Session;", "Z"), offer, 1, static, cachedBody),
            method(resolver, "build", buildParameters, offer, 1, static, buildBody),
        )
        if (twoBuilders) methods += method(resolver, "buildAgain", buildParameters, offer, 1, static, buildBody)
        return listOf(classDef(resolver, methods))
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

    private fun BytecodePatchContext.check(): Method = mutableClassDefBy(gate).methods.single { it.name == "decide" }

    private fun BytecodePatchContext.builder(): Method = mutableClassDefBy(resolver).methods.single { it.name == "build" }

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()
}
