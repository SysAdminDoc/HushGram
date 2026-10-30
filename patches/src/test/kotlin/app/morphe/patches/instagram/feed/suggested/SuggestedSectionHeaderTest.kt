/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.feed.suggested

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction22c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Suggested for you header after Home's end of feed card (#104): the hook is in the extension,
 * the title's setText is told from the Older posts link's by where its section comes from, and in
 * every declared 450 build the title goes past FeedSuggestions.sectionTitle with the section's
 * items stub filled.
 */
class SuggestedSectionHeaderTest {
    @Test
    fun theHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(FEED_SUGGESTIONS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "$FEED_SUGGESTIONS->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$SECTION_TITLE is not in the extension: $declared", SECTION_TITLE in declared)
        assertTrue("the items stub is not in the extension: $declared", "$FEED_SUGGESTIONS->$SECTION_ITEMS_STUB(Ljava/lang/Object;)Ljava/util/List;" in declared)
    }

    /** The title is the setText whose section comes out of the walk over the sections, not the link's lookup. */
    @Test
    fun theTitleIsFound() {
        val found = PatchContexts.of(classes()).findSuggestedSectionHeader()

        assertEquals(18, found.titleAt)
        assertEquals(0, found.text)
        assertEquals(2, found.section)
        assertEquals(SECTION, found.sectionType)
        assertEquals("items", found.items.name)
    }

    @Test
    fun theTitleGoesPastTheExtension() {
        val context = PatchContexts.of(classes())
        val found = context.findSuggestedSectionHeader()

        found.write(context)

        assertHooked("stand-in", context, found)
    }

    /** A build without the header's binder keeps Hide suggested posts as it was and leaves the stub alone. */
    @Test
    fun aBuildWithoutTheHeaderChangesNothing() {
        val extension = ExtensionDex.classDef(FEED_SUGGESTIONS)
        val context = PatchContexts.of(listOf(extension))
        val before = extension.methods.associate { it.name to it.code().size }

        assertNull(context.suggestedSectionHeaderOrWarn())

        val after = context.classDefBy(FEED_SUGGESTIONS).methods.associate { it.name to it.code().size }
        assertEquals(before, after)
    }

    @Test
    fun noBinderFailsTheFind() = refused("isn't in this build", classes().filter { it.type != GROUP_SET_BINDER })

    @Test
    fun aTitleNotFromTheWalkFailsTheFind() = refused("found 0", classes(walked = false))

    @Test
    fun aSectionThatIsntPublicFailsTheFind() = refused("isn't public", classes(sectionFlags = AccessFlags.FINAL.value))

    @Test
    fun twoListsInTheSectionFailTheFind() = refused("found 2", classes(lists = 2))

    @Test
    fun eachDeclaredBuildHooksTheTitle() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                hooksTheTitle(bundle, bundle.name)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    @Test
    fun eachOtherBuildHooksTheTitle() {
        var checked = 0
        for (apk in Fixtures.otherBuilds()) {
            hooksTheTitle(apk, apk.parentFile.name)
            checked++
        }
        assertTrue("no other build", checked > 0)
    }

    /** The binder, every class its bindView casts to (the section among them) and the extension. */
    private fun hooksTheTitle(bundle: File, label: String) {
        val binder = FixtureDex.classes(bundle, setOf(GROUP_SET_BINDER))[GROUP_SET_BINDER]
        requireNotNull(binder) { "$label: $GROUP_SET_BINDER isn't in the build" }
        val casts = binder.methods.flatMap { it.code() }.filter { it.opcode == Opcode.CHECK_CAST }
            .map { ((it as ReferenceInstruction).reference as TypeReference).type }.toSet()
        val context = PatchContexts.of(listOf(binder, ExtensionDex.classDef(FEED_SUGGESTIONS)) + FixtureDex.classes(bundle, casts).values)

        val found = context.findSuggestedSectionHeader()
        found.write(context)

        assertHooked("$label ${found.sectionType}", context, found)
    }

    /**
     * Right before the title's setText: the title and its section go to the extension and its answer
     * takes the title's place, once in bindView. The title was read from the section's name just
     * before, and the stub reads the section's list.
     */
    private fun assertHooked(what: String, context: BytecodePatchContext, found: SuggestedSectionHeader) {
        val code = context.mutableClassDefBy(GROUP_SET_BINDER).methods.single {
            it.name == "bindView" && it.parameterTypes.map(CharSequence::toString) == BIND_VIEW
        }.code()
        val calls = code.indices.filter { code[it].referenceText() == SECTION_TITLE }
        assertEquals("$what: one hook, where the title's setText was", listOf(found.titleAt), calls)
        val call = code[found.titleAt] as FiveRegisterInstruction
        assertEquals("$what: a static call", Opcode.INVOKE_STATIC, code[found.titleAt].opcode)
        assertEquals("$what: with the title and the section", listOf(2, found.text, found.section), listOf(call.registerCount, call.registerC, call.registerD))
        val read = code[found.titleAt - 1]
        assertEquals("$what: the title was just read", Opcode.IGET_OBJECT, read.opcode)
        assertEquals("$what: into its register", found.text, (read as TwoRegisterInstruction).registerA)
        assertEquals("$what: from the section", found.section, read.registerB)
        assertEquals("$what: from the section's class", found.sectionType, ((read as ReferenceInstruction).reference as FieldReference).definingClass)
        val answer = code[found.titleAt + 1]
        assertEquals("$what: the extension's answer", Opcode.MOVE_RESULT_OBJECT, answer.opcode)
        assertEquals("$what: in the title's register", found.text, (answer as OneRegisterInstruction).registerA)
        val set = code[found.titleAt + 2] as FiveRegisterInstruction
        assertEquals("$what: then the title is set", "Landroid/widget/TextView;->setText(Ljava/lang/CharSequence;)V", code[found.titleAt + 2].referenceText())
        assertEquals("$what: from that register", found.text, set.registerD)

        val stub = context.mutableClassDefBy(FEED_SUGGESTIONS).methods.single { it.name == SECTION_ITEMS_STUB }.code()
        assertEquals("$what: the stub casts to the section", Opcode.CHECK_CAST, stub[0].opcode)
        assertEquals("$what: the section's class", found.sectionType, stub[0].referenceText())
        assertEquals("$what: reads its list", Opcode.IGET_OBJECT, stub[1].opcode)
        assertEquals("$what: the items field", found.items.toString(), stub[1].referenceText())
        assertEquals("$what: and answers it", Opcode.RETURN_OBJECT, stub[2].opcode)
    }

    private fun refused(why: String, classes: List<ClassDef>) {
        val failure = assertThrows(PatchException::class.java) { PatchContexts.of(classes).findSuggestedSectionHeader() }
        assertTrue(failure.message!!, failure.message!!.contains(why))
    }

    private fun com.android.tools.smali.dexlib2.iface.Method.code(): List<Instruction> =
        implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private companion object {
        const val GROUP_SET = "Lfixture/GroupSet;"
        const val SECTION = "Lfixture/Section;"
        const val HOLDER = "Lfixture/Holder;"
        const val TEXT_VIEW = "Landroid/widget/TextView;"
        const val STRING = "Ljava/lang/String;"
        const val LIST = "Ljava/util/List;"
        const val PUBLIC_FINAL = 0x11
        val BIND_VIEW = listOf("I", "Landroid/view/View;", "Ljava/lang/Object;", "Ljava/lang/Object;")

        fun field(owner: String, name: String, type: String) = ImmutableFieldReference(owner, name, type)

        fun invoke(opcode: Opcode, count: Int, c: Int, d: Int, owner: String, name: String, parameters: List<String>, returns: String) =
            ImmutableInstruction35c(opcode, count, c, d, 0, 0, 0, ImmutableMethodReference(owner, name, parameters, returns))

        /**
         * Shaped like 450's bindView: the link's text from the section looked up by id, then a walk over
         * the sections setting the title from the one it lands on. Registers 13, the parameters in v8 to v12.
         *   0 const-string   2 lookup   5 move-result   6 iget link   8 iget name   10 setText
         *   13 sections()   16 move-result   17 iterator()   20 move-result   21 hasNext()   24 move-result
         *   25 if-eqz +16   27 next()   30 move-result   31 check-cast   33 iget title   35 iget name
         *   37 setText   40 goto -19   41 return-void
         */
        fun classes(walked: Boolean = true, sectionFlags: Int = PUBLIC_FINAL, lists: Int = 1): List<ClassDef> {
            val name = field(SECTION, "name", STRING)
            val code = listOf<Instruction>(
                ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("past_posts")),
                invoke(Opcode.INVOKE_VIRTUAL, 2, 11, 0, GROUP_SET, "section", listOf(STRING), SECTION),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 4),
                ImmutableInstruction22c(Opcode.IGET_OBJECT, 2, 12, field(HOLDER, "link", TEXT_VIEW)),
                ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 4, name),
                invoke(Opcode.INVOKE_VIRTUAL, 2, 2, 0, TEXT_VIEW, "setText", listOf("Ljava/lang/CharSequence;"), "V"),
                invoke(Opcode.INVOKE_VIRTUAL, 1, 11, 0, GROUP_SET, "sections", emptyList(), LIST),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                invoke(Opcode.INVOKE_INTERFACE, 1, 0, 0, LIST, "iterator", emptyList(), "Ljava/util/Iterator;"),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 4),
                invoke(Opcode.INVOKE_INTERFACE, 1, 4, 0, "Ljava/util/Iterator;", "hasNext", emptyList(), "Z"),
                ImmutableInstruction11x(Opcode.MOVE_RESULT, 0),
                ImmutableInstruction21t(Opcode.IF_EQZ, 0, 16),
                invoke(Opcode.INVOKE_INTERFACE, 1, 4, 0, "Ljava/util/Iterator;", "next", emptyList(), "Ljava/lang/Object;"),
                ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 2),
                // Without the walk the cast is of another register, and the section comes straight from next().
                ImmutableInstruction21c(Opcode.CHECK_CAST, if (walked) 2 else 3, ImmutableTypeReference(SECTION)),
                ImmutableInstruction22c(Opcode.IGET_OBJECT, 1, 12, field(HOLDER, "title", TEXT_VIEW)),
                ImmutableInstruction22c(Opcode.IGET_OBJECT, 0, 2, name),
                invoke(Opcode.INVOKE_VIRTUAL, 2, 1, 0, TEXT_VIEW, "setText", listOf("Ljava/lang/CharSequence;"), "V"),
                ImmutableInstruction10t(Opcode.GOTO, -19),
                ImmutableInstruction10x(Opcode.RETURN_VOID),
            )
            val binder = ImmutableClassDef(
                GROUP_SET_BINDER, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null, null,
                listOf(
                    ImmutableMethod(
                        GROUP_SET_BINDER, "bindView", BIND_VIEW.map { ImmutableMethodParameter(it, null, null) },
                        "V", AccessFlags.PUBLIC.value, null, null, ImmutableMethodImplementation(13, code, null, null),
                    ),
                ),
            )
            val section = ImmutableClassDef(
                SECTION, sectionFlags, "Ljava/lang/Object;", null, null, null,
                listOf(ImmutableField(SECTION, "name", STRING, PUBLIC_FINAL, null, null, null)) +
                    (1..lists).map { ImmutableField(SECTION, if (it == 1) "items" else "items$it", LIST, PUBLIC_FINAL, null, null, null) },
                null,
            )
            return listOf(binder, section, ExtensionDex.classDef(FEED_SUGGESTIONS))
        }
    }
}
