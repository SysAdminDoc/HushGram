/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.presence

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.extensions.InstructionExtensions.replaceInstruction
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod.Companion.toMutable
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
import com.android.tools.smali.dexlib2.iface.instruction.ThreeRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.WideLiteralInstruction
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Shorter Active now (#683): on every declared build, each place the Active now limit of 390 seconds
 * comes from goes through the extension, and nothing else changes. Two 455 builds (386113922 and
 * 386113923) kept it in the presence formatter's static final long, whose static initializer hands it
 * over right before storing it. The 456 builds load it in each check, which hands its copy over right
 * after loading it. A build the patch can't read fails it before an instruction changes.
 */
class ShorterActiveNowHookTest {
    @Test
    fun simpleModePicksThePatchSinceItsSwitchStartsOff() {
        assertTrue(shorterActiveNowPatch.default)
    }

    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(ACTIVE_NOW).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$ACTIVE_NOW->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$ACTIVE_NOW_WINDOW is not in the extension: $declared", ACTIVE_NOW_WINDOW in declared)
    }

    @Test
    fun everyBuildShortensTheLimit() {
        val checked = mutableListOf<String>()
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) checked += shortens(bundle, bundle.name)
        }
        for (base in Fixtures.otherBuilds()) checked += shortens(base, base.parentFile.name)
        assertTrue("no fixture was checked", checked.isNotEmpty())
    }

    @Test
    fun anExtensionWithoutTheHookFailsThePatch() {
        refuses(ACTIVE_NOW) { PatchContexts.of(emptyList()).findActiveNow() }
    }

    @Test
    fun noPresenceTextMethodFailsThePatch() {
        refuses("found 0") { PatchContexts.of(listOf(ExtensionDex.classDef(ACTIVE_NOW))).findActiveNow() }
    }

    /** A limit something besides the initializer could set can't be trusted to be the one Instagram keeps. */
    @Test
    fun aLimitThatIsNotFinalFailsThePatch() {
        val kept = PatchContexts.of(fieldForm(isFinal = true)).findActiveNow().stored
        assertEquals("the field form is read off the stand-in's long", LIMIT_FIELD, kept?.field?.name)
        refuses("isn't static final") { PatchContexts.of(fieldForm(isFinal = false)).findActiveNow() }
    }

    /**
     * A declared build rewritten into the field form 386113922 and 386113923 had, which no 456 build
     * has: the presence text method reads the limit off its class's static long right before comparing
     * it, and the class's static initializer sets that long straight from TimeUnit.SECONDS.toMillis(390).
     */
    private fun fieldForm(isFinal: Boolean): List<ClassDef> {
        val build = Fixtures.otherBuilds().firstOrNull() ?: throw AssertionError("no other build to rewrite")
        val kept = keptFrom(build)
        val text = kept.flatMap { it.methods }.single { it.isPresenceText() }
        val owner = kept.single { it.type == text.definingClass }
        val load = text.limitCompares().firstOrNull() ?: throw AssertionError("${text.key()} loads no limit to rewrite")
        val pair = (text.code()[load] as OneRegisterInstruction).registerA
        val field = "${owner.type}->$LIMIT_FIELD:J"
        val reads = ImmutableMethod.of(text.toMutable().apply { replaceInstruction(load, "sget-wide v$pair, $field") })

        val stock = owner.methods.singleOrNull { it.name == "<clinit>" }
        val init = ImmutableMethod.of(
            ImmutableMethod(
                owner.type, "<clinit>", emptyList(), "V", stock?.accessFlags ?: (AccessFlags.STATIC.value or AccessFlags.CONSTRUCTOR.value),
                null, null,
                ImmutableMethodImplementation(
                    maxOf(stock?.implementation?.registerCount ?: 0, 3),
                    stock?.implementation?.instructions ?: listOf(ImmutableInstruction10x(Opcode.RETURN_VOID)),
                    stock?.implementation?.tryBlocks,
                    null,
                ),
            ).toMutable().apply {
                addInstructions(
                    0,
                    """
                        sget-object v0, Ljava/util/concurrent/TimeUnit;->SECONDS:Ljava/util/concurrent/TimeUnit;
                        const-wide/16 v1, 0x186
                        invoke-virtual {v0, v1, v2}, $TO_MILLIS
                        move-result-wide v0
                        sput-wide v0, $field
                    """,
                )
            },
        )
        val flags = AccessFlags.PRIVATE.value or AccessFlags.STATIC.value or (if (isFinal) AccessFlags.FINAL.value else 0)
        val rewritten = ImmutableClassDef(
            owner.type, owner.accessFlags, owner.superclass, owner.interfaces, owner.sourceFile, owner.annotations,
            owner.fields.toList() + ImmutableField(owner.type, LIMIT_FIELD, "J", flags, null, null, null),
            owner.methods.filter { it.name != "<clinit>" }.map { if (it.key() == text.key()) reads else it } + init,
        )
        return kept.filter { it.type != owner.type } + rewritten + ExtensionDex.classDef(ACTIVE_NOW)
    }

    /** A check that does something else between loading the limit and comparing it isn't the one the hook was written for. */
    @Test
    fun aCheckThatDoesNotCompareRightAwayFailsThePatch() {
        val (kept, targets) = firstOtherBuild("loads the limit in its checks") { it.checks.isNotEmpty() }
        val check = targets.checks.first()
        val context = PatchContexts.of(kept + ExtensionDex.classDef(ACTIVE_NOW))
        context.mutable(check.method).addInstructions(check.index + 1, "nop")
        refuses("as soon as it loads it") { context.findActiveNow() }
    }

    private val keptCache = mutableMapOf<File, List<ClassDef>>()

    /** The one build's classes that load the week limit or the Active now limit, plus nothing else. */
    private fun keptFrom(bundle: File): List<ClassDef> = keptCache.getOrPut(bundle) {
        val kept = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            for (classDef in dex.classes) {
                if (classDef.methods.any { m -> m.code().any { (it as? WideLiteralInstruction)?.wideLiteral in listOf(WEEK_IN_MINUTES, LIMIT) } }) {
                    kept += ImmutableClassDef.of(classDef)
                }
            }
        }
        kept
    }

    private fun firstOtherBuild(what: String, form: (ActiveNowTargets) -> Boolean): Pair<List<ClassDef>, ActiveNowTargets> =
        Fixtures.otherBuilds().asSequence()
            .map { keptFrom(it) }
            .map { kept -> kept to PatchContexts.of(kept + ExtensionDex.classDef(ACTIVE_NOW)).findActiveNow() }
            .firstOrNull { form(it.second) } ?: throw AssertionError("no other build $what")

    private fun shortens(bundle: File, label: String): String {
        val kept = keptFrom(bundle)
        val context = PatchContexts.of(kept + ExtensionDex.classDef(ACTIVE_NOW))
        val targets = runCatching { context.findActiveNow() }.getOrElse { throw AssertionError("$label: ${it.message}", it) }

        // Read off the stock code apart from the finder. The presence text method either reads the
        // limit off a static long, which only the initializer's store sets, or loads it itself. Every
        // load of the limit as a long that a long compare takes right away, in a method reading a long
        // off an object, is a check.
        val text = kept.flatMap { it.methods }.single { it.isPresenceText() }
        val readsStatic = text.code().any { it.opcode == Opcode.SGET_WIDE }
        assertEquals("$label: the initializer's store exactly when the text method reads a static long", readsStatic, targets.stored != null)
        val expected = kept.flatMap { it.methods }.flatMap { method -> method.limitCompares().map { method.key() to it } }
        assertEquals("$label: every check", expected.toSet(), targets.checks.map { it.method.key() to it.index }.toSet())
        if (!readsStatic) assertTrue("$label: the text method is a check", expected.any { it.first == text.key() })

        val checked = targets.checks.map { it.method }.distinctBy { it.key() }
        val before = checked.associate { it.key() to context.mutable(it).code() }
        val clinit = { type: String -> context.mutableClassDefBy(type).methods.single { it.name == "<clinit>" } }
        val initBefore = targets.stored?.let { clinit(it.type).code() }

        context.shortenActiveNow(targets)

        targets.stored?.let { site -> assertStoreHooked(label, site, initBefore!!, clinit(site.type).code()) }
        for (method in checked) {
            val where = "$label: ${method.key()}"
            val stock = before.getValue(method.key())
            val loads = stock.indices.filter { at -> expected.contains(method.key() to at) }
            val code = context.mutable(method).code()
            assertEquals("$where: two instructions per load", stock.size + 2 * loads.size, code.size)
            loads.forEachIndexed { n, load ->
                val at = load + 2 * n
                val pair = (stock[load] as OneRegisterInstruction).registerA
                assertEquals("$where: the load stays", stock[load].opcode, code[at].opcode)
                val call = code[at + 1] as RegisterRangeInstruction
                assertEquals("$where: the call", Opcode.INVOKE_STATIC_RANGE, call.opcode)
                assertEquals("$where: the hook", ACTIVE_NOW_WINDOW, call.reference())
                assertEquals("$where: on the limit's pair", pair to 2, call.startRegister to call.registerCount)
                assertEquals("$where: the answer", Opcode.MOVE_RESULT_WIDE, code[at + 2].opcode)
                assertEquals("$where: into the same pair", pair, (code[at + 2] as OneRegisterInstruction).registerA)
                assertEquals("$where: right before the compare", Opcode.CMP_LONG, code[at + 3].opcode)
            }
            val hooks = code.indices.filter { code[it].reference() == ACTIVE_NOW_WINDOW }
            val unhooked = code.filterIndexed { at, _ -> at !in hooks && at - 1 !in hooks }
            assertEquals("$where: the rest is Instagram's", stock.map { it.shape() }, unhooked.map { it.shape() })
        }
        val hooks = kept.flatMap { context.mutableClassDefBy(it.type).methods }.sumOf { method -> method.code().count { it.reference() == ACTIVE_NOW_WINDOW } }
        assertEquals("$label: a hook for each place the limit comes from", expected.size + (if (readsStatic) 1 else 0), hooks)
        return label
    }

    private fun assertStoreHooked(label: String, site: ActiveNowSite, before: List<Instruction>, code: List<Instruction>) {
        // The one store of the limit, and the 390 seconds it's worked out from.
        val stores = before.indices.filter { before[it].opcode == Opcode.SPUT_WIDE && before[it].reference() == site.field.toString() }
        assertEquals("$label: the initializer sets the limit once", listOf(site.index), stores)
        val register = (before[site.index] as OneRegisterInstruction).registerA
        val leadIn = before.take(site.index)
        assertTrue("$label: from seconds", leadIn.any { it.reference() == "Ljava/util/concurrent/TimeUnit;->SECONDS:Ljava/util/concurrent/TimeUnit;" })
        assertTrue("$label: 390 of them", leadIn.any { (it as? WideLiteralInstruction)?.wideLiteral == 390L })

        assertEquals("$label: two instructions added", before.size + 2, code.size)
        assertEquals("$label: the initializer up to the store", before.take(site.index).map { it.shape() }, code.take(site.index).map { it.shape() })
        assertEquals("$label: the store and after", before.drop(site.index).map { it.shape() }, code.drop(site.index + 2).map { it.shape() })
        val call = code[site.index] as RegisterRangeInstruction
        assertEquals("$label: the call", Opcode.INVOKE_STATIC_RANGE, call.opcode)
        assertEquals("$label: the hook", ACTIVE_NOW_WINDOW, call.reference())
        assertEquals("$label: on the limit's pair", register to 2, call.startRegister to call.registerCount)
        assertEquals("$label: the answer", Opcode.MOVE_RESULT_WIDE, code[site.index + 1].opcode)
        assertEquals("$label: into the same pair", register, (code[site.index + 1] as OneRegisterInstruction).registerA)
        assertEquals("$label: right after Instagram's own", Opcode.MOVE_RESULT_WIDE, code[site.index - 1].opcode)
        assertEquals("$label: and right before the store", Opcode.SPUT_WIDE, code[site.index + 2].opcode)
        assertEquals("$label: one hook", 1, code.count { it.reference() == ACTIVE_NOW_WINDOW })
    }

    /** The patch refuses, for the reason given. */
    private fun refuses(reason: String, search: () -> Unit) {
        val refusal = assertThrows(PatchException::class.java) { search() }
        assertTrue("refused for another reason: ${refusal.message}", refusal.message.orEmpty().contains(reason))
    }

    private fun Method.isPresenceText(): Boolean {
        val wides = code().mapNotNull { (it as? WideLiteralInstruction)?.wideLiteral }
        return !AccessFlags.STATIC.isSet(accessFlags) && returnType == "Ljava/lang/String;" &&
            parameterTypes.map(Any::toString) == listOf("Landroid/content/res/Resources;", "Ljava/util/List;") &&
            WEEK_IN_MINUTES in wides && EIGHT_HOURS_IN_MINUTES in wides
    }

    /** Where this loads the limit as a long that a long compare takes right away, when it reads a long off an object. */
    private fun Method.limitCompares(): List<Int> {
        val code = code()
        if (code.none { it.opcode == Opcode.IGET_WIDE }) return emptyList()
        return code.indices.filter { at ->
            val load = code[at]
            val compare = code.getOrNull(at + 1) as? ThreeRegisterInstruction
            load.opcode in setOf(Opcode.CONST_WIDE_16, Opcode.CONST_WIDE_32, Opcode.CONST_WIDE) &&
                (load as WideLiteralInstruction).wideLiteral == LIMIT && compare?.opcode == Opcode.CMP_LONG &&
                (load as OneRegisterInstruction).registerA in listOf(compare.registerB, compare.registerC)
        }
    }

    private fun BytecodePatchContext.mutable(method: Method): MutableMethod =
        mutableClassDefBy(method.definingClass).methods.single { it.key() == method.key() }

    private fun Method.key() = "$definingClass->$name(${parameterTypes.joinToString("")})$returnType"
    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()
    private fun Instruction.reference(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    /** What an instruction does, with a widened goto read as the goto it was. */
    private fun Instruction.shape(): Pair<Opcode, String?> = when (opcode) {
        Opcode.GOTO_16, Opcode.GOTO_32 -> Opcode.GOTO
        else -> opcode
    } to reference()

    private companion object {
        /** Instagram's Active now limit in milliseconds, as its 454 initializer worked it out. */
        val LIMIT = TimeUnit.SECONDS.toMillis(390)

        /** The static long the field-form stand-in keeps the limit in. */
        const val LIMIT_FIELD = "hushLimit"
    }
}
