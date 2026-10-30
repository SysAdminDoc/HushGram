/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.font

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
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Font: each Prism Sans Typeface Instagram's font helper keeps goes through Fonts.prismTypeface
 * before it's kept, every IgTextView goes to Fonts.textView right after Instagram sets it up, and
 * anything the patch can't find exactly fails it before a change.
 */
class FontHookTest {
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(FONTS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$PRISM_TYPEFACE is not in the extension: $declared", PRISM_TYPEFACE in declared)
        assertTrue("$TEXT_VIEW is not in the extension: $declared", TEXT_VIEW in declared)
        assertTrue("$RESOURCE_FONT is not in the extension: $declared", RESOURCE_FONT in declared)
        assertTrue("$EMOJI_TYPEFACE is not in the extension: $declared", EMOJI_TYPEFACE in declared)
        assertTrue("$EMOJI_NAME is not in the extension: $declared", EMOJI_NAME in declared)
    }

    @Test
    fun everyKeptTypefaceAndEveryTextViewGoThroughTheExtension() {
        val context = PatchContexts.of(listOf(helper(), textView(), compat(), emoji(), emojiCaller(), igEmoji(), sheets(), extension()))
        val found = context.findFontSites()
        assertEquals(6, found.writes.size)
        assertEquals(3, found.emojiCalls.size)
        context.swapFonts()

        assertWritesSwapped("stand-in", context.mutableClassDefBy(HELPER), 6)
        assertTextViewHandedOn("stand-in", context.mutableClassDefBy(IG_TEXT_VIEW).methods.toList())
        assertGetFontAsksFirst("stand-in", context.mutableClassDefBy(COMPAT).methods.single { it.name == "getFont" })
        assertEmojiAnswersSwapped("stand-in", context.mutableClassDefBy(CALLER).methods.toList(),
            setOf("$EMOJI->CED($STRING)$TYPEFACE", "$EMOJI_FONTS->CED($STRING)$TYPEFACE"), 3)
        assertEquals("the lookup itself is left alone", 2, context.mutableClassDefBy(EMOJI).methods.single { it.name == "CED" }.code().size)
        val emoji = context.mutableClassDefBy(IG_EMOJI).methods
        assertEmojiNameMarked("stand-in", emoji.single { it.parameterTypes.size == 2 })
        assertEmojiNameMarked("stand-in sheet", context.mutableClassDefBy(SHEETS).methods.single { it.returnType == IMAGE_URL })
        assertEquals("the other one left alone", 2, emoji.single { it.parameterTypes.size == 1 }.code().size)
    }

    @Test
    fun anythingItCantFindExactlyFailsThePatch() {
        for ((case, classes) in listOf(
            "no helper" to listOf(textView(), compat(), emoji(), emojiCaller(), igEmoji(), sheets(), extension()),
            "two helpers" to listOf(helper(), helper(type = "Lfixture/OtherPrism;"), textView(), compat(), emoji(), emojiCaller(), igEmoji(), sheets(), extension()),
            "two weights" to listOf(helper(fields = 2), textView(), compat(), emoji(), emojiCaller(), igEmoji(), sheets(), extension()),
            "a weight its getter never loads" to listOf(helper(getters = 2), textView(), compat(), emoji(), emojiCaller(), igEmoji(), sheets(), extension()),
            "a weight written a third time" to listOf(helper(extraWrite = true), textView(), compat(), emoji(), emojiCaller(), igEmoji(), sheets(), extension()),
            "no IgTextView" to listOf(helper(), compat(), emoji(), emojiCaller(), igEmoji(), sheets(), extension()),
            "an IgTextView set up twice" to listOf(helper(), textView(calls = 2), compat(), emoji(), emojiCaller(), igEmoji(), sheets(), extension()),
            "a jump past the set-up" to listOf(helper(), textView(jumpAfter = true), compat(), emoji(), emojiCaller(), igEmoji(), sheets(), extension()),
            "no ResourcesCompat" to listOf(helper(), textView(), emoji(), emojiCaller(), igEmoji(), sheets(), extension()),
            "two font getters" to listOf(helper(), textView(), compat(getters = 2), emoji(), emojiCaller(), igEmoji(), sheets(), extension()),
            "a font getter with no register free" to listOf(helper(), textView(), compat(registers = 2), emoji(), emojiCaller(), igEmoji(), sheets(), extension()),
            "no emoji renderer" to listOf(helper(), textView(), compat(), emojiCaller(), igEmoji(), sheets(), extension()),
            "two emoji renderers" to listOf(helper(), textView(), compat(), emoji(), emoji(type = "Lfixture/OtherEmoji;"), emojiCaller(), igEmoji(), sheets(), extension()),
            "two emoji lookups" to listOf(helper(), textView(), compat(), emoji(lookups = 2), emojiCaller(), igEmoji(), sheets(), extension()),
            "nothing calling the emoji lookup" to listOf(helper(), textView(), compat(), emoji(), igEmoji(), sheets(), extension()),
            "an emoji lookup's answer dropped" to listOf(helper(), textView(), compat(), emoji(), emojiCaller(kept = false), igEmoji(), sheets(), extension()),
            "no Emoji" to listOf(helper(), textView(), compat(), emoji(), emojiCaller(), sheets(), extension()),
            "two emoji picture names" to listOf(helper(), textView(), compat(), emoji(), emojiCaller(), igEmoji(namers = 2), sheets(), extension()),
            "an emoji picture named twice" to listOf(helper(), textView(), compat(), emoji(), emojiCaller(), igEmoji(made = 2), sheets(), extension()),
            "no sheet of emoji" to listOf(helper(), textView(), compat(), emoji(), emojiCaller(), igEmoji(), extension()),
            "a jump to where a sheet is named" to listOf(helper(), textView(), compat(), emoji(), emojiCaller(), igEmoji(), sheets(jump = true), extension()),
            "no hook in the extension" to listOf(helper(), textView(), compat(), emoji(), emojiCaller(), igEmoji(), sheets()),
        )) {
            val failure = assertThrows(case, PatchException::class.java) { PatchContexts.of(classes).findFontSites() }
            assertTrue("$case: ${failure.message}", failure.message!!.startsWith("Font:"))
        }
    }

    /** A refusal comes before any change: the helper keeps every instruction it had. */
    @Test
    fun aRefusalChangesNothing() {
        val stub = helper()
        val context = PatchContexts.of(listOf(stub, textView(calls = 2), compat(), emoji(), emojiCaller(), igEmoji(), sheets(), extension()))
        val before = stub.methods.associate { it.name to it.code().map { instruction -> instruction.opcode } }
        assertThrows(PatchException::class.java) { context.swapFonts() }
        assertEquals(before, context.classDefBy(HELPER).methods.associate { it.name to it.code().map { instruction -> instruction.opcode } })
    }

    /** The declared build: all six writes of Prism Sans and IgTextView's set-up go through the extension. */
    @Test
    fun theDeclaredBuildSwapsEveryKeptFont() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                checkBuild(bundle.name, bundle)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    /** The same in the other builds of 452 (#77, #95), where the helper moves class from build to build. */
    @Test
    fun everyOtherBuildSwapsEveryKeptFont() {
        val checked = Fixtures.otherBuilds().map { bundle ->
            checkBuild(bundle.parentFile.name, bundle)
            bundle.parentFile.name
        }
        assertEquals("the other builds: $checked", Fixtures.OTHER_BUILDS, checked.size)
    }

    /**
     * Patches the slice of [bundle] the finder reads and checks each change. Before the change,
     * each of the six writes keeps the Typeface a call returned just before it, so each hook
     * stands between Instagram's font loader and the field, and the helper's three fixed fonts
     * ("sans-serif" and "sans-serif-medium", which aren't Prism Sans) are left alone.
     */
    private fun checkBuild(label: String, bundle: java.io.File) {
        val holders = FixtureDex.classesHolding(bundle, PRISM_MARKER)
        assertEquals("$label: classes holding the marker", 1, holders.size)
        val views = FixtureDex.classes(bundle, setOf(IG_TEXT_VIEW)).values
        assertEquals("$label: IgTextView", 1, views.size)
        val compats = FixtureDex.classesHolding(bundle, FONT_RESOURCE_MARKER)
        assertEquals("$label: classes holding the font resource marker", 1, compats.size)
        val renderers = FixtureDex.classesHolding(bundle, EMOJI_MARKER)
        assertEquals("$label: classes holding the emoji marker", 1, renderers.size)
        val renderer = renderers.single()
        val subclasses = FixtureDex.subclasses(bundle, setOf(renderer.type))
        val family = listOf(renderer) + subclasses
        val lookupName = family.flatMap { it.methods }.single {
            !AccessFlags.STATIC.isSet(it.accessFlags) && it.returnType == TYPEFACE && it.parameterTypes.map(Any::toString) == listOf(STRING)
        }.name
        val lookups = family.flatMap { listOf(it.type) + it.interfaces }.map { "$it->$lookupName($STRING)$TYPEFACE" }.toSet()
        val callerTypes = FixtureDex.methodsWhere(bundle, { dex -> dex.methodSection.any { it.toString() in lookups } }) { method ->
            method.code().any { it.methodCalled() in lookups }
        }.map { it.definingClass }.toSet()
        val callers = FixtureDex.classes(bundle, callerTypes).values
        val lookupCalls = callers.sumOf { caller -> caller.methods.sumOf { method -> method.code().count { it.methodCalled() in lookups } } }
        assertTrue("$label: calls to the emoji lookup", lookupCalls > 0)
        val emoji = FixtureDex.classes(bundle, setOf(IG_EMOJI)).values
        assertEquals("$label: Instagram's Emoji", 1, emoji.size)
        val sheets = FixtureDex.withStringPools(bundle, FixtureDex.classesLoading(bundle, EMOJI_SHEET_MARKER))
        val context = PatchContexts.of((holders + views + compats + renderers + subclasses + callers + emoji + sheets).distinctBy { it.type } + extension())

        val found = context.findFontSites()
        assertEquals("$label: writes", 6, found.writes.size)
        assertEquals("$label: emoji lookup calls", lookupCalls, found.emojiCalls.size)
        for (write in found.writes) {
            val code = write.method.code()
            val loaded = code[write.at - 1]
            assertEquals("$label ${write.method.name}@${write.at}: a loaded font is kept", Opcode.MOVE_RESULT_OBJECT, loaded.opcode)
            assertEquals("$label ${write.method.name}@${write.at}: the loaded font is the one kept", write.register,
                (loaded as OneRegisterInstruction).registerA)
            val load = (code[write.at - 2] as ReferenceInstruction).reference as MethodReference
            assertEquals("$label ${write.method.name}@${write.at}: loaded by a call answering a Typeface", TYPEFACE, load.returnType)
        }
        assertEquals("$label: the set-up hands on the view first", 0, found.initializeAt)
        val helperType = holders.single().type
        context.swapFonts()

        val helper = context.mutableClassDefBy(helperType)
        assertWritesSwapped(label, helper, 6)
        val fixed = helper.staticFields.filter { it.type == TYPEFACE && AccessFlags.FINAL.isSet(it.accessFlags) }.map { it.name }.toSet()
        assertEquals("$label: the fixed fonts", 3, fixed.size)
        for (method in helper.methods) {
            val code = method.code()
            for (at in code.indices) {
                if (code[at].opcode != Opcode.SPUT_OBJECT || code[at].field().name !in fixed) continue
                assertFalse("$label ${method.name}@$at: a fixed font goes through the hook", at >= 2 && code[at - 2].isHook(PRISM_TYPEFACE))
            }
        }
        assertTextViewHandedOn(label, context.mutableClassDefBy(IG_TEXT_VIEW).methods.toList())
        val getFont = found.getFont
        assertGetFontAsksFirst(label, context.mutableClassDefBy(getFont.definingClass).methods.single {
            it.name == getFont.name && it.parameterTypes.map(Any::toString) == listOf(CONTEXT, "I")
        })
        assertEmojiAnswersSwapped(label, callerTypes.flatMap { context.mutableClassDefBy(it).methods }, lookups, lookupCalls)
        assertEquals("$label: emoji picture names", 2, found.emojiNames.size)
        val (single, sheet) = found.emojiNames.map { it.method }
        assertEquals("$label: a single emoji's name is Emoji's", IG_EMOJI, single.definingClass)
        assertTrue("$label: a single emoji's name starts with \"emoji:/\"", single.code().any { it.loads("emoji:/") })
        assertTrue("$label: the sheet's name is built from Emoji", IG_EMOJI in sheet.parameterTypes.map(Any::toString))
        for (namer in listOf(single, sheet)) {
            assertEmojiNameMarked("$label ${namer.definingClass}->${namer.name}", context.mutableClassDefBy(namer.definingClass).methods.single {
                it.name == namer.name && it.parameterTypes.map(Any::toString) == namer.parameterTypes.map(Any::toString)
            })
        }
    }

    /** The one image name the method makes takes its String from the hook, which was handed that String. */
    private fun assertEmojiNameMarked(what: String, namer: MutableMethod) {
        val code = namer.code()
        val made = code.indices.filter { code[it].methodCalled() == IMAGE_URL_INIT }
        assertEquals("$what: image names made", 1, made.size)
        val at = made.single()
        val name = (code[at] as FiveRegisterInstruction).registerD
        assertTrue("$what: the hook right before", code[at - 2].isHook(EMOJI_NAME))
        assertEquals("$what: handed the name", name, (code[at - 2] as RegisterRangeInstruction).startRegister)
        assertEquals("$what: alone", 1, (code[at - 2] as RegisterRangeInstruction).registerCount)
        assertEquals("$what: its answer", Opcode.MOVE_RESULT_OBJECT, code[at - 1].opcode)
        assertEquals("$what: in the name's place", name, (code[at - 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: one hook", 1, code.count { it.isHook(EMOJI_NAME) })
    }

    private fun Instruction.loads(string: String): Boolean =
        (opcode == Opcode.CONST_STRING || opcode == Opcode.CONST_STRING_JUMBO) &&
            ((this as ReferenceInstruction).reference as StringReference).string == string

    /**
     * Each of the [count] calls to the emoji lookup moves its answer into a register, which goes
     * through the hook right away and takes the hook's answer in its place.
     */
    private fun assertEmojiAnswersSwapped(what: String, methods: List<Method>, lookups: Set<String>, count: Int) {
        var calls = 0
        for (method in methods) {
            val code = method.code()
            for (at in code.indices) {
                if (code[at].methodCalled() !in lookups) continue
                calls++
                val where = "$what ${method.name}@$at"
                assertEquals("$where: the answer is kept", Opcode.MOVE_RESULT_OBJECT, code[at + 1].opcode)
                val register = (code[at + 1] as OneRegisterInstruction).registerA
                val hook = code[at + 2]
                assertTrue("$where: and goes through the hook", hook.isHook(EMOJI_TYPEFACE))
                assertEquals("$where: the answer passed", register, (hook as RegisterRangeInstruction).startRegister)
                assertEquals("$where: the answer alone", 1, hook.registerCount)
                assertEquals("$where: the hook's answer", Opcode.MOVE_RESULT_OBJECT, code[at + 3].opcode)
                assertEquals("$where: kept in its place", register, (code[at + 3] as OneRegisterInstruction).registerA)
            }
        }
        assertEquals("$what: emoji lookup calls", count, calls)
        assertEquals("$what: emoji hooks", count, methods.sumOf { method -> method.code().count { it.isHook(EMOJI_TYPEFACE) } })
    }

    /**
     * ResourcesCompat.getFont hands its Context and resource id to the extension first, answers what
     * comes back when it isn't null, and otherwise jumps to what it did before.
     */
    private fun assertGetFontAsksFirst(what: String, getFont: MutableMethod) {
        val code = getFont.code()
        val parameters = getFont.implementation!!.registerCount - 2
        val ask = code[0]
        assertEquals("$what: the extension is asked first", Opcode.INVOKE_STATIC, ask.opcode)
        assertEquals("$what: the hook", RESOURCE_FONT, (ask as ReferenceInstruction).reference.toString())
        assertEquals("$what: handed the Context", parameters, (ask as FiveRegisterInstruction).registerC)
        assertEquals("$what: and the id", parameters + 1, ask.registerD)
        assertEquals("$what: its answer", Opcode.MOVE_RESULT_OBJECT, code[1].opcode)
        val answer = (code[1] as OneRegisterInstruction).registerA
        assertEquals("$what: null goes on", Opcode.IF_EQZ, code[2].opcode)
        assertEquals("$what: to what getFont did before", 4, (code[2] as BuilderOffsetInstruction).target.location.index)
        assertEquals("$what: a font is answered", Opcode.RETURN_OBJECT, code[3].opcode)
        assertEquals("$what: the extension's", answer, (code[3] as OneRegisterInstruction).registerA)
        assertTrue("$what: the answer's register is no parameter", answer < parameters)
    }

    /** [count] writes of a Typeface field that can change, each right after the hook on its own register, none jumped past. */
    private fun assertWritesSwapped(what: String, helper: ClassDef, count: Int) {
        val changeable = helper.staticFields.filter { it.type == TYPEFACE && !AccessFlags.FINAL.isSet(it.accessFlags) }
            .map { it.name }.toSet()
        val methods = helper.methods.map { it as MutableMethod }
        var writes = 0
        for (method in methods) {
            val code = method.code()
            val targets = method.jumpTargets()
            for (at in code.indices) {
                val write = code[at]
                if (write.opcode != Opcode.SPUT_OBJECT) continue
                if (write.field().definingClass != helper.type || write.field().name !in changeable) continue
                writes++
                val register = (write as OneRegisterInstruction).registerA
                val call = code[at - 2]
                assertTrue("$what ${method.name}@$at: the hook", call.isHook(PRISM_TYPEFACE))
                assertEquals("$what ${method.name}@$at: the font passed", register, (call as RegisterRangeInstruction).startRegister)
                assertEquals("$what ${method.name}@$at: the font alone", 1, call.registerCount)
                assertEquals("$what ${method.name}@$at: the answer", Opcode.MOVE_RESULT_OBJECT, code[at - 1].opcode)
                assertEquals("$what ${method.name}@$at: the answer is kept", register, (code[at - 1] as OneRegisterInstruction).registerA)
                assertFalse("$what ${method.name}@$at: a jump lands past the hook", at in targets || at - 1 in targets)
            }
        }
        assertEquals("$what: writes", count, writes)
        assertEquals("$what: hooks", count, methods.sumOf { method -> method.code().count { it.isHook(PRISM_TYPEFACE) } })
    }

    /** One hook right after the one call to IgTextViewUtils.initialize, handed the view that call was given. */
    private fun assertTextViewHandedOn(what: String, methods: List<MutableMethod>) {
        val hooks = methods.flatMap { method -> method.code().withIndex().filter { it.value.isHook(TEXT_VIEW) }.map { method to it.index } }
        assertEquals("$what: text view hooks", 1, hooks.size)
        val (method, at) = hooks.single()
        val code = method.code()
        val initialize = code[at - 1]
        assertEquals("$what: right after the set-up", INITIALIZE, (initialize as ReferenceInstruction).reference.toString())
        assertEquals("$what: the view the set-up was given", (initialize as FiveRegisterInstruction).registerC,
            (code[at] as RegisterRangeInstruction).startRegister)
        assertEquals("$what: the view alone", 1, (code[at] as RegisterRangeInstruction).registerCount)
        assertFalse("$what: a jump lands past the hook", at + 1 in method.jumpTargets())
    }

    private fun Instruction.isHook(hook: String) =
        opcode == Opcode.INVOKE_STATIC_RANGE && (this as ReferenceInstruction).reference.toString() == hook

    private fun Instruction.field(): FieldReference = (this as ReferenceInstruction).reference as FieldReference

    private fun Instruction.methodCalled(): String? = ((this as? ReferenceInstruction)?.reference as? MethodReference)?.toString()

    private fun MutableMethod.jumpTargets(): Set<Int> =
        implementation!!.instructions.filterIsInstance<BuilderOffsetInstruction>().map { it.target.location.index }.toSet()

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private companion object {
        const val HELPER = "Lfixture/PrismFonts;"
        const val REPOSITORY = "Lfixture/Typefaces;"
        const val TYPEFACE = "Landroid/graphics/Typeface;"
        const val CONTEXT = "Landroid/content/Context;"
        val WEIGHTS = listOf("A00", "A01", "A02")

        const val COMPAT = "Lfixture/ResourcesCompat;"
        const val EMOJI = "Lfixture/FacebookEmoji;"
        const val EMOJI_FONTS = "Lfixture/EmojiFonts;"
        const val CALLER = "Lfixture/EmojiBitmaps;"
        const val SHEETS = "Lfixture/EmojiSheets;"
        const val STRING = "Ljava/lang/String;"

        fun extension(): ClassDef = ExtensionDex.classDef(FONTS)

        /**
         * A stand-in shaped like 451's emoji renderer: the marker in the span it builds, and [lookups]
         * instance (String) Typeface lookups, which belong to the interface it shares with the class
         * that answers nothing.
         */
        fun emoji(type: String = EMOJI, lookups: Int = 1): ClassDef {
            val flags = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
            val methods = mutableListOf(
                method(
                    type, "A02", emptyList(), "Landroid/text/style/TypefaceSpan;", flags, 3,
                    """
                        new-instance v0, Landroid/text/style/TypefaceSpan;
                        const-string v1, "$EMOJI_MARKER"
                        invoke-direct { v0, v1 }, Landroid/text/style/TypefaceSpan;-><init>($STRING)V
                        return-object v0
                    """,
                ),
            )
            repeat(lookups) { i ->
                methods += method(
                    type, if (i == 0) "CED" else "CED$i", listOf(STRING), TYPEFACE, flags, 3,
                    """
                        const/4 v0, 0x0
                        return-object v0
                    """,
                )
            }
            return ImmutableClassDef(type, flags, "Ljava/lang/Object;", listOf(EMOJI_FONTS), null, null, null, methods)
        }

        /**
         * A stand-in for Instagram's Emoji: [namers] static (String, String) image names, each making
         * its image name [made] times, and one (String) one that hands its emoji to the first.
         */
        fun igEmoji(namers: Int = 1, made: Int = 1): ClassDef {
            val flags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value
            val methods = mutableListOf(
                method(
                    IG_EMOJI, "A00", listOf(STRING), IMAGE_URL, flags, 2,
                    """
                        const/4 v0, 0x0
                        return-object v0
                    """,
                ),
            )
            repeat(namers) { i ->
                methods += method(
                    IG_EMOJI, "A0${i + 1}", listOf(STRING, STRING), IMAGE_URL, flags, 4,
                    buildString {
                        appendLine("const-string v1, \"emoji:/\"")
                        repeat(made) {
                            appendLine("new-instance v0, $IMAGE_URL")
                            appendLine("invoke-direct { v0, v1 }, $IMAGE_URL_INIT")
                        }
                        appendLine("return-object v0")
                    },
                )
            }
            return ImmutableClassDef(IG_EMOJI, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, methods)
        }

        /**
         * A stand-in for the class naming a sheet of six emoji pictures: the marker, then one image
         * name made from a String, and a jump to that call if asked.
         */
        fun sheets(jump: Boolean = false): ClassDef {
            val flags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value
            val body = buildString {
                appendLine("const-string v1, \"$EMOJI_SHEET_MARKER\"")
                appendLine("new-instance v0, $IMAGE_URL")
                if (jump) appendLine("if-eqz p3, :make")
                appendLine(":make")
                appendLine("invoke-direct { v0, v1 }, $IMAGE_URL_INIT")
                appendLine("return-object v0")
            }
            return ImmutableClassDef(
                SHEETS, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
                listOf(
                    method(
                        SHEETS, "A00", listOf("Lcom/instagram/common/session/UserSession;", IG_EMOJI, "[$IG_EMOJI", "I"), IMAGE_URL,
                        flags, 6, body,
                    ),
                ),
            )
        }

        /**
         * A stand-in for the code that draws Instagram's emoji: two calls to the lookup through its
         * interface, by range from registers past 15, and one by the renderer's own name. Each moves
         * its answer into a register, but for the second when [kept] is false.
         */
        fun emojiCaller(kept: Boolean = true): ClassDef {
            val lookup = "$EMOJI_FONTS->CED($STRING)$TYPEFACE"
            val flags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
            return ImmutableClassDef(
                CALLER, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
                listOf(
                    method(
                        CALLER, "draw", listOf(EMOJI_FONTS, STRING), TYPEFACE, flags, 20,
                        """
                            invoke-interface/range { p0 .. p1 }, $lookup
                            move-result-object v16
                            invoke-interface/range { p0 .. p1 }, $lookup
                            ${if (kept) "move-result-object v3" else "const/4 v3, 0x0"}
                            return-object v16
                        """,
                    ),
                    method(
                        CALLER, "drawDirect", listOf(EMOJI, STRING), TYPEFACE, flags, 3,
                        """
                            invoke-virtual { p0, p1 }, $EMOJI->CED($STRING)$TYPEFACE
                            move-result-object v0
                            return-object v0
                        """,
                    ),
                ),
            )
        }

        /**
         * A stand-in for AndroidX's ResourcesCompat: the marker in its loader, and [getters] static
         * (Context, int) Typeface getters with [registers] registers, two of them the parameters.
         */
        fun compat(getters: Int = 1, registers: Int = 8): ClassDef {
            val flags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
            val methods = mutableListOf(
                method(
                    COMPAT, "loadFont", listOf(CONTEXT, "I"), "V", flags, 3,
                    """
                        const-string v0, "$FONT_RESOURCE_MARKER"
                        return-void
                    """,
                ),
            )
            repeat(getters) { i ->
                methods += method(
                    COMPAT, if (i == 0) "getFont" else "getFont$i", listOf(CONTEXT, "I"), TYPEFACE, flags, registers,
                    if (registers > 2) {
                        """
                            move-object v1, p0
                            const/4 v0, 0x0
                            return-object v0
                        """
                    } else {
                        """
                            const/4 p0, 0x0
                            return-object p0
                        """
                    },
                )
            }
            return ImmutableClassDef(COMPAT, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null, methods)
        }

        /**
         * A stand-in shaped like 451's helper: it holds the marker, keeps [fields] weights in
         * static fields that can change and one fixed font in a final one, loads each weight as
         * it starts (with the empty path jumping to the write with null, as 451's does) and again
         * in a getter of its own for the first [getters] of them.
         */
        fun helper(type: String = HELPER, fields: Int = 3, getters: Int = 3, extraWrite: Boolean = false): ClassDef {
            val weights = WEIGHTS.take(fields)
            val start = buildString {
                appendLine("const-string v1, \"sans-serif\"")
                appendLine("const/4 v0, 0x0")
                appendLine("invoke-static { v1, v0 }, $TYPEFACE->create(Ljava/lang/String;I)$TYPEFACE")
                appendLine("move-result-object v0")
                appendLine("sput-object v0, $type->A07:$TYPEFACE")
                weights.forEachIndexed { i, weight ->
                    appendLine("sget-object v0, $REPOSITORY->A04:$REPOSITORY")
                    appendLine("if-eqz v0, :empty$i")
                    appendLine("invoke-virtual { v0 }, $REPOSITORY->load$i()$TYPEFACE")
                    appendLine("move-result-object v0")
                    appendLine(":write$i")
                    appendLine("sput-object v0, $type->$weight:$TYPEFACE")
                }
                if (extraWrite) appendLine("sput-object v0, $type->A00:$TYPEFACE")
                appendLine("return-void")
                weights.indices.forEach { i ->
                    appendLine(":empty$i")
                    appendLine("const/4 v0, 0x0")
                    appendLine("goto :write$i")
                }
            }
            val methods = mutableListOf(
                method(type, "<clinit>", emptyList(), "V", AccessFlags.STATIC.value or AccessFlags.CONSTRUCTOR.value, 2, start),
                method(
                    type, "A04", listOf("Landroid/widget/TextView;"), "V", AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, 2,
                    """
                        const-string v0, "$PRISM_MARKER"
                        return-void
                    """,
                ),
            )
            weights.take(getters).forEachIndexed { i, weight ->
                methods += method(
                    type, "A0${5 + i}", listOf(CONTEXT), TYPEFACE, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, 3,
                    """
                        sget-object v0, $type->$weight:$TYPEFACE
                        if-nez v0, :done
                        invoke-static { p1 }, $REPOSITORY->get$i($CONTEXT)$TYPEFACE
                        move-result-object v0
                        sput-object v0, $type->$weight:$TYPEFACE
                        :done
                        return-object v0
                    """,
                )
            }
            val flags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
            val staticFields = weights.map { ImmutableField(type, it, TYPEFACE, flags, null, null, null) } +
                ImmutableField(type, "A07", TYPEFACE, flags or AccessFlags.FINAL.value, null, null, null)
            return ImmutableClassDef(
                type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;",
                null, null, null, staticFields, methods,
            )
        }

        /** IgTextView with its set-up called first in init, [calls] times, and a jump to just after it if asked. */
        fun textView(calls: Int = 1, jumpAfter: Boolean = false): ClassDef {
            val body = buildString {
                repeat(calls) { appendLine("invoke-static { p0, p1, p2 }, $INITIALIZE") }
                appendLine(":after")
                appendLine("const/4 v0, 0x0")
                if (jumpAfter) appendLine("if-eqz p3, :after")
                appendLine("return-void")
            }
            return ImmutableClassDef(
                IG_TEXT_VIEW, AccessFlags.PUBLIC.value, "Landroid/widget/TextView;", null, null, null, null,
                listOf(
                    method(
                        IG_TEXT_VIEW, "init", listOf(CONTEXT, "Landroid/util/AttributeSet;", "I", "I"), "V",
                        AccessFlags.PRIVATE.value or AccessFlags.FINAL.value, 6, body,
                    ),
                ),
            )
        }

        fun method(type: String, name: String, parameters: List<String>, returns: String, flags: Int, registers: Int, body: String): Method =
            ImmutableMethod.of(
                MutableMethod(
                    ImmutableMethod(
                        type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                        ImmutableMethodImplementation(registers, emptyList(), null, null),
                    ),
                ).apply { addInstructionsWithLabels(0, body.trimIndent().lines().filter { it.isNotBlank() }.joinToString("\n")) },
            )
    }
}
