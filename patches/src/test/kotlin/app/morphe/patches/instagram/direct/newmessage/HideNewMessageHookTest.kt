/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.direct.newmessage

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.direct.ghost.GHOST_BIND_ACTION
import app.morphe.patches.instagram.direct.ghost.GhostEntry
import app.morphe.patches.instagram.direct.ghost.GhostModeEntryHookTest
import app.morphe.patches.instagram.direct.ghost.IGDS_ACTION_BAR
import app.morphe.patches.instagram.direct.ghost.addGhostModeEntry
import app.morphe.patches.instagram.direct.ghost.findGhostEntries
import app.morphe.patches.instagram.direct.ghost.ghostActionFound
import app.morphe.patches.instagram.direct.ghost.ghostEntriesFound
import app.morphe.patches.instagram.direct.seen.visualCode
import app.morphe.patches.instagram.direct.seen.visualReference
import app.morphe.patches.shared.compat.AppCompatibilities
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Hide New message button and suggestions (#762): both inbox top bars hand their buttons to the
 * extension with what each is bound to, and the New message and new group pickers' row controller
 * is marked as the picker makes it, with both its fillers asking the extension for their list. On
 * every declared build and the other builds of that version, and nothing else changes.
 */
class HideNewMessageHookTest {
    @Test
    fun theHooksAreInTheExtension() {
        for (hook in listOf(NEW_MESSAGE_BUTTON, PICKER_ROWS, SUGGESTED_ROWS, FOUND_ROWS)) {
            val declared = ExtensionDex.classDef(hook.substringBefore("->")).methods
                .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
                .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
            assertTrue("$hook is not in the extension: $declared", hook.substringAfter("->") in declared)
        }
    }

    @Test
    fun eachBuildOfADeclaredVersionHidesBothBarsButtonAndThePickersSuggestions() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } }
        val others = Fixtures.otherBuilds()
        assertTrue("no fixture of a declared build", bundles.isNotEmpty())
        assertTrue("other builds of the declared version were not read", others.isNotEmpty())
        val checked = (bundles + others).map { checkBuild(it) }
        assertEquals("every build was checked", bundles.size + others.size, checked.size)
    }

    /** With nothing to find, the patch fails rather than going in as a switch that does nothing. */
    @Test
    fun aBuildWithNeitherBarNorPickerFailsThePatch() {
        val context = PatchContexts.of(emptyList())
        assertFalse(context.addGhostModeEntry())
        val refusal = assertThrows(PatchException::class.java) { context.hideNewMessageExtras() }
        assertTrue(refusal.message, refusal.message.orEmpty().contains("found neither the New message button nor the recipient picker"))
    }

    /** Without the bars the picker still loses its suggestions, and without the picker both bars still lose the button. */
    @Test
    fun eachPartGoesInWithoutTheOther() {
        val bundle = declaredBundles().first()
        val all = slice(bundle)
        val picker = FixtureDex.classesLoadingAny(bundle, setOf(COMMUNITY_ROW, PICKER_ADAPTER)).map { it.type }.toSet()

        val pickerOnly = PatchContexts.of(all.filter { it.type in picker })
        assertFalse("no bar to find", pickerOnly.addGhostModeEntry())
        val rows = pickerOnly.findPickerRows()
        pickerOnly.hideNewMessageExtras()
        val filler = method(pickerOnly, rows.type, rows.suggested, listOf("Ljava/util/List;"))
        assertEquals(SUGGESTED_ROWS, filler.visualCode()[0].visualReference().toString())
        val maker = method(pickerOnly, rows.maker.definingClass, rows.maker.name, rows.maker.parameterTypes.map(CharSequence::toString))
        assertEquals(PICKER_ROWS, maker.visualCode()[rows.markAt].visualReference().toString())

        val barsOnly = all.filter { it.type !in picker }
        val context = PatchContexts.of(barsOnly)
        assertTrue(context.addGhostModeEntry())
        val refusal = assertThrows(PatchException::class.java) { context.findPickerRows() }
        assertTrue(refusal.message, refusal.message.orEmpty().contains("expected one class loading \"$COMMUNITY_ROW\", found 0"))
        val older = context.findOlderBinder(context.ghostEntriesFound()!!)
        context.hideNewMessageExtras()
        val binder = method(context, older.method.definingClass, older.method.name, older.method.parameterTypes.map(CharSequence::toString))
        assertEquals(NEW_MESSAGE_BUTTON, binder.visualCode()[older.insertAt].visualReference().toString())
        val action = context.ghostActionFound() ?: error("the newer bar wasn't found")
        assertEquals(NEW_MESSAGE_BUTTON, method(context, IGDS_ACTION_BAR, action.binderName, action.binderParameters).visualCode()[0].visualReference().toString())
    }

    /** The older bar's buttons have to share one tap field, or the binder can't be told apart. */
    @Test
    fun entriesWithDifferentTapFieldsFailTheOlderBarsSearch() {
        fun entry(tap: String) = GhostEntry("Lfixture/Bar;", "A00", emptyList(), 1, 0, 1, "Lfixture/Config;->A01:Landroid/view/View\$OnLongClickListener;", 2, tap)
        val refusal = assertThrows(PatchException::class.java) {
            PatchContexts.of(emptyList()).findOlderBinder(
                listOf(entry("Lfixture/Config;->A02:Landroid/view/View\$OnClickListener;"), entry("Lfixture/Config;->A03:Landroid/view/View\$OnClickListener;")),
            )
        }
        assertTrue(refusal.message, refusal.message.orEmpty().contains("share one tap field, found 2"))
    }

    /** Returns the build's name, so the caller can count the builds it read. */
    private fun checkBuild(bundle: File): String {
        val where = "${bundle.parentFile.name}/${bundle.name}"
        val classes = slice(bundle)
        val context = PatchContexts.of(classes)
        assertTrue(where, context.addGhostModeEntry())
        val entries = context.ghostEntriesFound() ?: error("$where: the older bar wasn't found")
        val action = context.ghostActionFound() ?: error("$where: the newer bar wasn't found")
        val older = context.findOlderBinder(entries)
        val picker = context.findPickerRows()
        val before = classes.associate { it.type to snapshot(context.mutableClassDefBy(it.type)) }

        context.hideNewMessageExtras()

        // The older bar: right after it reads the built configuration's tap listener, the button and that listener.
        val olderCode = method(context, older.method.definingClass, older.method.name, older.method.parameterTypes.map(CharSequence::toString)).visualCode()
        val read = olderCode[older.insertAt - 1]
        assertEquals("$where: the tap listener's read", Opcode.IGET_OBJECT, read.opcode)
        assertEquals("$where: read into the register handed over", older.tap, read.namedRegisters()[0])
        assertEquals("$where: a View.OnClickListener", "Landroid/view/View\$OnClickListener;", (read.visualReference() as FieldReference).type)
        assertEquals("$where: the older bar's hook", NEW_MESSAGE_BUTTON, olderCode[older.insertAt].visualReference().toString())
        assertEquals("$where: a plain call", Opcode.INVOKE_STATIC, olderCode[older.insertAt].opcode)
        val olderRegisters = older.method.implementation!!.registerCount
        assertEquals("$where: handed the button and the listener", listOf(olderRegisters - 2, older.tap), olderCode[older.insertAt].namedRegisters())
        val olderSignature = signature(older.method.definingClass, older.method)

        // The newer bar: its binder hands the button and its model over first, ahead of the ghost hook.
        val newer = method(context, IGDS_ACTION_BAR, action.binderName, action.binderParameters)
        val newerCode = newer.visualCode()
        val newerRegisters = newer.implementation!!.registerCount
        assertEquals("$where: the newer bar's hook", NEW_MESSAGE_BUTTON, newerCode[0].visualReference().toString())
        assertEquals("$where: a range call", Opcode.INVOKE_STATIC_RANGE, newerCode[0].opcode)
        assertEquals("$where: handed p1 and p2", listOf(newerRegisters - 2, newerRegisters - 1), newerCode[0].namedRegisters())
        assertEquals("$where: the ghost hook stays next", GHOST_BIND_ACTION, newerCode[1].visualReference().toString())
        val newerSignature = signature(IGDS_ACTION_BAR, newer)

        // The picker: its row controller marked right after it's stored, from the register it was made in.
        val maker = method(context, picker.maker.definingClass, picker.maker.name, picker.maker.parameterTypes.map(CharSequence::toString))
        val makerCode = maker.visualCode()
        val store = makerCode[picker.markAt - 1]
        assertEquals("$where: the controller's store", Opcode.IPUT_OBJECT, store.opcode)
        assertEquals("$where: a field of the controller's type", picker.type, (store.visualReference() as FieldReference).type)
        assertEquals("$where: the stored register", picker.register, store.namedRegisters()[0])
        assertEquals("$where: the mark", PICKER_ROWS, makerCode[picker.markAt].visualReference().toString())
        assertEquals("$where: the mark's register", listOf(picker.register), makerCode[picker.markAt].namedRegisters())
        assertEquals("$where: one mark", 1, makerCode.count { it.visualReference().toString() == PICKER_ROWS })
        val makerSignature = signature(picker.maker.definingClass, maker)

        // Both fillers: the extension's answer replaces the list first thing.
        val suggested = method(context, picker.type, picker.suggested, listOf("Ljava/util/List;"))
        checkFiller(where, suggested, SUGGESTED_ROWS, 2)
        val results = method(context, picker.type, picker.results, listOf("Ljava/lang/String;", "Ljava/util/List;"))
        checkFiller(where, results, FOUND_ROWS, 3)
        val fillers = setOf(signature(picker.type, suggested), signature(picker.type, results))

        for ((type, original) in before) {
            val now = snapshot(context.mutableClassDefBy(type))
            for ((method, instructions) in original) {
                val after = now.getValue(method)
                when (method) {
                    olderSignature -> assertEquals("$where: $method only gained the hook", instructions, after.filter { it.second != NEW_MESSAGE_BUTTON })
                    newerSignature -> assertEquals("$where: $method only gained the hook", instructions, after.drop(1))
                    makerSignature -> assertEquals("$where: $method only gained the mark", instructions, after.filter { it.second != PICKER_ROWS })
                    in fillers -> assertEquals("$where: $method only gained its answer", instructions, after.drop(2))
                    else -> assertEquals("$where: $method changed", instructions, after)
                }
            }
        }
        return where
    }

    /** [filler] asks [hook] for its list first, with `this` and every parameter, and keeps the answer in the list's register. */
    private fun checkFiller(where: String, filler: Method, hook: String, handed: Int) {
        val code = filler.visualCode()
        val registers = filler.implementation!!.registerCount
        assertEquals("$where: ${filler.name}'s hook", hook, code[0].visualReference().toString())
        assertEquals("$where: a range call", Opcode.INVOKE_STATIC_RANGE, code[0].opcode)
        assertEquals("$where: handed this and the parameters", (registers - handed until registers).toList(), code[0].namedRegisters())
        assertEquals("$where: the answer", Opcode.MOVE_RESULT_OBJECT, code[1].opcode)
        assertEquals("$where: kept as the list", listOf(registers - 1), code[1].namedRegisters())
        assertEquals("$where: one hook", 1, code.count { it.visualReference().toString() == hook })
    }

    private companion object {
        private val slices = HashMap<File, List<ClassDef>>()

        fun declaredBundles(): List<File> {
            val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
            return versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } }
        }

        fun method(context: BytecodePatchContext, type: String, name: String, parameters: List<String>): Method =
            context.mutableClassDefBy(type).methods.single { it.name == name && it.parameterTypes.map(CharSequence::toString) == parameters }

        fun signature(type: String, method: Method) = "$type->${method.name}(${method.parameterTypes.joinToString("")})${method.returnType}"

        /** Each method's code with NOPs left out and every goto read as one kind, so a widened branch or moved payload doesn't count as a change. */
        fun snapshot(classDef: ClassDef) = classDef.methods.associate { method ->
            signature(classDef.type, method) to method.visualCode().filter { it.opcode != Opcode.NOP }.map {
                val kind = when (it.opcode) {
                    Opcode.GOTO_16, Opcode.GOTO_32 -> Opcode.GOTO
                    else -> it.opcode
                }
                Triple(kind, it.visualReference()?.toString(), if (kind == Opcode.GOTO) emptyList() else it.namedRegisters())
            }
        }

        /**
         * What the patch reads in a build: the ghost patch's two slices, the classes reading the older
         * bar's tap field (the built configuration copying it), the classes reading the built
         * configuration's tap field (the binder), and the classes loading the picker's two strings.
         */
        fun slice(bundle: File): List<ClassDef> = slices.getOrPut(bundle) {
            val kept = LinkedHashMap<String, ClassDef>()
            for (classDef in GhostModeEntryHookTest.slice(bundle) + GhostModeEntryHookTest.actionSlice(bundle)) kept.putIfAbsent(classDef.type, classDef)
            val tap = PatchContexts.of(kept.values.toList()).findGhostEntries().map { it.tap }.distinct().single()
            fun reads(field: Set<String>) = { classDef: ClassDef ->
                classDef.methods.any { method ->
                    method.visualCode().any { it.opcode == Opcode.IGET_OBJECT && (it.visualReference() as? FieldReference)?.let { ref -> "${ref.definingClass}->${ref.name}:${ref.type}" } in field }
                }
            }
            val copiers = FixtureDex.classesLoadingAny(bundle, setOf(COMMUNITY_ROW, PICKER_ADAPTER), reads(setOf(tap)))
            for (classDef in copiers) kept.putIfAbsent(classDef.type, classDef)
            val built = copiers.flatMap { owner ->
                owner.methods.filter { it.name == "<init>" }.flatMap { it.visualCode() }
                    .mapNotNull { (it.visualReference() as? FieldReference)?.takeIf { ref -> ref.definingClass == owner.type && ref.type == "Landroid/view/View\$OnClickListener;" } }
                    .map { "${it.definingClass}->${it.name}:${it.type}" }
            }.toSet()
            val binders = ArrayList<ClassDef>()
            val readsBuilt = reads(built)
            FixtureDex.forEach(bundle) { dex ->
                for (candidate in dex.classes) if (candidate.type !in kept && readsBuilt(candidate)) binders += ImmutableClassDef.of(candidate)
            }
            for (classDef in binders) kept.putIfAbsent(classDef.type, classDef)
            kept.values.toList()
        }
    }
}
