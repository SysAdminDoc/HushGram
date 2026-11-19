/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.threads

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.NeutralNativePath
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
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Hide the empty profile prompt (#762): each profile grid adapter fills its "Create your first post"
 * config with one builder call, and the config goes to ThreadsButton.firstPostPrompt right after.
 * Where the adapter tests the config for null before adding its empty row, the config goes through
 * ThreadsButton.emptyRow first, so a null takes Instagram's own way that adds no row.
 */
class EmptyProfilePromptHookTest {
    @Test
    fun bothHooksAreInTheExtension() {
        val declared = ExtensionDex.classDef(THREADS_BUTTON).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.definingClass}->${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (wanted in listOf(FIRST_POST_PROMPT, EMPTY_ROW)) assertTrue("$wanted is not in the extension: $declared", wanted in declared)
    }

    @Test
    fun eachGridsPromptAndNullTestAreFound() {
        val sites = PatchContexts.of(PromptFixture.classes()).findEmptyProfilePrompt()

        assertEquals(listOf(PromptFixture.GRID, PromptFixture.MEDIA), sites.map { it.owner })
        for (site in sites) {
            assertEquals("A12", site.name)
            assertEquals(emptyList<String>(), site.parameters)
            assertEquals(PromptFixture.builder("A01"), site.builder)
            assertEquals(PromptFixture.CONFIG, site.configType)
            assertEquals("${site.owner}: the builder call", PromptFixture.BUILD, site.build)
            assertEquals("${site.owner}: past the loading branch that writes the config", PromptFixture.TEST, site.test)
        }
        assertEquals("the config's register in each grid", listOf(3, 4), sites.map { it.config })
    }

    /** The prompt goes to the extension after it's filled and the test reads the extension's answer; the rest is Instagram's own. */
    @Test
    fun theConfigGoesThroughTheExtension() {
        val context = PatchContexts.of(PromptFixture.classes())
        val sites = context.findEmptyProfilePrompt()
        val originals = sites.map { NeutralNativePath(context.adapter(it)) }

        context.hideEmptyProfilePrompt(sites)

        sites.forEachIndexed { at, site ->
            val filter = assertHooked("the stand-in ${site.owner}", context, site, originals[at])
            assertTrue("${site.owner}: the loading check now lands on the filter", filter in context.adapter(site).jumpTargets())
        }
    }

    /** Anything the patch can't read the same way fails at patch time, saying what it found. */
    @Test
    fun aPromptThePatchCantPlaceFails() {
        val grid = "${PromptFixture.GRID}->A12"
        val gridTrace = EMPTY_STATE_UPDATES[0]
        val cases = listOf(
            PromptFixture.classes(media = false) to "expected one method logging ${EMPTY_STATE_UPDATES[1]} to fill a prompt, found 0",
            PromptFixture.classes(builds = 0) to "expected one method logging $gridTrace to fill a prompt, found 0",
            PromptFixture.classes(twice = true) to "expected one method logging $gridTrace to fill a prompt, found 2",
            PromptFixture.classes(builds = 2) to "expected $grid to fill one prompt with a static (Context, config, Integer) call, found 2",
            PromptFixture.classes(jumpIn = true) to "something jumps in right after $grid fills its prompt",
            PromptFixture.classes(test = false) to "expected $grid to test its prompt for null once on the way on, found 0",
            PromptFixture.classes(loadingWrites = false) to "expected $grid to test its prompt for null once on the way on, found 2",
            PromptFixture.classes(mediaBuilder = "A02") to "the grids fill their prompt with different calls",
            PromptFixture.classes(extension = PromptFixture.fakeExtension(mark = false)) to "the extension has no public static $FIRST_POST_PROMPT",
            PromptFixture.classes(extension = PromptFixture.fakeExtension(filter = false)) to "the extension has no public static $EMPTY_ROW",
        )
        for ((classes, expected) in cases) {
            val failure = assertThrows(expected, PatchException::class.java) { PatchContexts.of(classes).findEmptyProfilePrompt() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            assertTrue(failure.message!!, failure.message!!.startsWith("Hide the Threads button: the empty profile prompt: "))
        }
    }

    /** On every build of the declared versions and every other build, both grids hand their prompt to the extension. */
    @Test
    fun eachBuildHandsBothGridsPromptsToTheExtension() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val bundles = versions.flatMap { version -> Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") } } +
            Fixtures.otherBuilds()
        var checked = 0
        for (bundle in bundles) {
            val label = "${bundle.parentFile.name}/${bundle.name}"
            val context = PatchContexts.of(slice(bundle))
            val sites = context.findEmptyProfilePrompt()
            assertEquals("$label: both grids", EMPTY_STATE_UPDATES.size, sites.map { it.owner }.toSet().size)
            val originals = sites.map { NeutralNativePath(context.adapter(it)) }

            context.hideEmptyProfilePrompt(sites)

            sites.forEachIndexed { at, site -> assertHooked("$label ${site.owner}", context, site, originals[at]) }
            checked++
        }
        assertEquals("every build is read", Fixtures.BUILDS, checked)
    }

    /** The classes loading either grid's name, and the extension, as the finder reads them. */
    private fun slice(bundle: File): List<ClassDef> {
        val classes = EMPTY_STATE_UPDATES.flatMap { FixtureDex.classesLoading(bundle, it) }.distinctBy { it.type }
        assertTrue("${bundle.name}: nothing loads the grids' names", classes.isNotEmpty())
        return classes + ExtensionDex.classDef(THREADS_BUTTON)
    }

    /**
     * The builder call and the mark right after it, the filter, its answer and cast back into the
     * config's register right before the test, once each, with nothing jumping past either hook;
     * the rest untouched. Answers where the filter starts.
     */
    private fun assertHooked(what: String, context: BytecodePatchContext, site: EmptyRowSite, original: NeutralNativePath): Int {
        val method = context.adapter(site)
        val code = method.code()
        val targets = method.jumpTargets()
        assertEquals("$what: the builder", site.builder, code[site.build].referenceText())
        assertEquals("$what: the marks", listOf(site.build + 1), code.indices.filter { code[it].referenceText() == FIRST_POST_PROMPT })
        assertEquals("$what: the mark's call", Opcode.INVOKE_STATIC_RANGE, code[site.build + 1].opcode)
        assertEquals("$what: the mark's register", site.config to 1,
            (code[site.build + 1] as RegisterRangeInstruction).let { it.startRegister to it.registerCount })
        assertTrue("$what: a jump lands past the mark", site.build + 1 !in targets && site.build + 2 !in targets)

        val filters = code.indices.filter { code[it].referenceText() == EMPTY_ROW }
        assertEquals("$what: the filters, on the test moved past the mark", listOf(site.test + 1), filters)
        val filter = filters.single()
        assertEquals("$what: the filter's register", site.config to 1,
            (code[filter] as RegisterRangeInstruction).let { it.startRegister to it.registerCount })
        assertEquals("$what: the answer", Opcode.MOVE_RESULT_OBJECT, code[filter + 1].opcode)
        assertEquals("$what: the answer's register", site.config, (code[filter + 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: the cast", Opcode.CHECK_CAST, code[filter + 2].opcode)
        assertEquals("$what: the cast's register", site.config, (code[filter + 2] as OneRegisterInstruction).registerA)
        assertEquals("$what: the cast's type", site.configType, ((code[filter + 2] as ReferenceInstruction).reference as TypeReference).type)
        assertTrue("$what: the test", code[filter + 3].opcode == Opcode.IF_EQZ || code[filter + 3].opcode == Opcode.IF_NEZ)
        assertEquals("$what: the test's register", site.config, (code[filter + 3] as OneRegisterInstruction).registerA)
        assertTrue("$what: a jump lands past the filter", (filter + 1..filter + 3).none { it in targets })

        original.assertPreserved(what, method, setOf(site.build + 1, filter, filter + 1, filter + 2))
        return filter
    }

    private fun BytecodePatchContext.adapter(site: EmptyRowSite): MutableMethod =
        mutableClassDefBy(site.owner).methods.single {
            it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters && it.returnType == site.returns
        }

    private fun MutableMethod.jumpTargets(): Set<Int> =
        implementation!!.instructions.filterIsInstance<BuilderOffsetInstruction>().map { it.target.location.index }.toSet()

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()
}

/**
 * Stand-ins for 456's two profile grid adapters (0IQM.A12 and 0IMn.A12): each logs its name, fills a
 * fresh config as the prompt, skips the empty row while loading with another value in the config's
 * register, and otherwise adds the row only for a config that isn't null.
 */
internal object PromptFixture {
    const val GRID = "Lfixture/ProfileGridItemTabAdapter;"
    const val MEDIA = "Lfixture/ProfileMediaTabAdapter;"
    const val CONFIG = "Lfixture/EmptyStateConfig;"
    private const val PROMPTS = "Lfixture/EmptyPrompts;"
    private const val LOADING = "Lfixture/LoadingRow;"
    private const val ROW = "Lfixture/EmptyStateRow;"
    private const val CONTEXT = "Landroid/content/Context;"
    private const val OBJECT = "Ljava/lang/Object;"

    /** Where each stand-in fills its prompt, and where it tests the config. */
    const val BUILD = 6
    const val TEST = 13

    fun builder(name: String) = "$PROMPTS->$name($CONTEXT${CONFIG}Ljava/lang/Integer;)V"

    private fun method(owner: String, name: String, parameters: List<String>, returns: String, flags: Int, registers: Int, smali: String): Method {
        val method = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        )
        method.addInstructionsWithLabels(0, smali.trimIndent())
        return ImmutableMethod.of(method)
    }

    /** One adapter's A12, the config in [config] and p0 in v5. */
    private fun update(
        owner: String, name: String, trace: String, config: Int, builder: String, builds: Int, jumpIn: Boolean, test: Boolean,
        loadingWrites: Boolean,
    ): Method {
        val cfg = "v$config"
        val loading = if (loadingWrites) {
            "new-instance $cfg, $LOADING\ninvoke-direct { $cfg }, $LOADING-><init>()V\ngoto :end"
        } else {
            "if-eqz $cfg, :end\nreturn-void"
        }
        val body = """
            const-string v0, "$trace"
            invoke-static { v0 }, Lfixture/Systrace;->begin(Ljava/lang/String;)V
            iget-object v1, p0, $owner->context:$CONTEXT
            new-instance $cfg, $CONFIG
            invoke-direct { $cfg }, $CONFIG-><init>()V
            ${if (jumpIn) "if-eqz v1, :after" else "const/4 v2, 0x0"}
            ${List(builds) { "invoke-static { v1, $cfg, v2 }, ${builder(builder)}" }.joinToString("\n").ifEmpty { "nop" }}
            :after
            const/4 v2, 0x2
            iget-boolean v0, p0, $owner->A0K:Z
            if-eqz v0, :test
            $loading
            :test
            ${if (test) "if-eqz $cfg, :end" else "nop"}
            iget-object v0, p0, $owner->A0J:$ROW
            invoke-virtual { p0, v0, $cfg }, $owner->A0j($OBJECT$OBJECT)I
            :end
            return-void
        """
        return method(owner, name, emptyList(), "V", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, 6, body)
    }

    private fun adapter(type: String, methods: List<Method>): ClassDef {
        val fields = listOf(
            ImmutableField(type, "context", CONTEXT, AccessFlags.PRIVATE.value, null, null, null),
            ImmutableField(type, "A0K", "Z", AccessFlags.PRIVATE.value, null, null, null),
            ImmutableField(type, "A0J", ROW, AccessFlags.PRIVATE.value, null, null, null),
        )
        return ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, OBJECT, null, null, null, fields, methods)
    }

    /** ThreadsButton with only some of what the patch calls. */
    fun fakeExtension(mark: Boolean = true, filter: Boolean = true): ClassDef {
        val flags = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
        val methods = mutableListOf<Method>()
        if (mark) methods += method(THREADS_BUTTON, "firstPostPrompt", listOf(OBJECT), "V", flags, 1, "return-void")
        if (filter) methods += method(THREADS_BUTTON, "emptyRow", listOf(OBJECT), OBJECT, flags, 1, "return-object p0")
        return ImmutableClassDef(THREADS_BUTTON, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, OBJECT, null, null, null, emptyList(), methods)
    }

    fun classes(
        media: Boolean = true,
        builds: Int = 1,
        twice: Boolean = false,
        jumpIn: Boolean = false,
        test: Boolean = true,
        loadingWrites: Boolean = true,
        mediaBuilder: String = "A01",
        extension: ClassDef = ExtensionDex.classDef(THREADS_BUTTON),
    ): List<ClassDef> {
        val gridTrace = EMPTY_STATE_UPDATES[0]
        val gridMethods = listOf("A12", "A13").take(if (twice) 2 else 1).map {
            update(GRID, it, gridTrace, 3, "A01", builds, jumpIn, test, loadingWrites)
        }
        val classes = mutableListOf(adapter(GRID, gridMethods))
        if (media) {
            classes += adapter(MEDIA, listOf(update(MEDIA, "A12", EMPTY_STATE_UPDATES[1], 4, mediaBuilder, 1, false, true, true)))
        }
        return classes + extension
    }
}
