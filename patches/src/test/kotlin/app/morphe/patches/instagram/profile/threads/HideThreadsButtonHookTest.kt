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
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.RegisterRangeInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class HideThreadsButtonHookTest {
    private val builder = "Lfixture/Builder;"
    private val button = "Lfixture/Button;"
    private val source = "Lfixture/Source;"
    private val build = "$builder->build($BAR_GROUP${BAR_GROUP}JLjava/util/List;)V"
    private val getter = "invoke-virtual { p1 }, $source->buttons()Ljava/util/List;"

    /** The hook and the stub the patch writes are in the ThreadsButton the bundle ships, public and static. */
    @Test
    fun theHookAndTheStubAreInTheExtension() {
        val declared = ExtensionDex.classDef(THREADS_BUTTON).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$BUTTONS is not in the extension: $declared", BUTTONS.substringAfter("->") in declared)
        assertTrue("the $ICON_STUB stub is not in the extension: $declared", "$ICON_STUB(Ljava/lang/Object;)I" in declared)
        assertTrue("$HIDE_BANNER is not in the extension: $declared", HIDE_BANNER.substringAfter("->") in declared)
        assertTrue("$HIDE_CREATE is not in the extension: $declared", HIDE_CREATE.substringAfter("->") in declared)
    }

    /** Share profile's hook and the stub the patch writes are in the ShareProfileButton the bundle ships, public and static. */
    @Test
    fun theShareHookAndStubAreInTheExtension() {
        val declared = ExtensionDex.classDef(SHARE_BUTTON).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        assertTrue("$SHARE_HOOK is not in the extension: $declared", SHARE_HOOK.substringAfter("->") in declared)
        assertTrue("the $SHARE_STUB stub is not in the extension: $declared", "$SHARE_STUB(Ljava/lang/Object;)Z" in declared)
    }

    /**
     * #253: the stub tells Share profile's model, and each builder hands its button and model over
     * right before its return, with the branches that went to the return going through the hook.
     */
    @Test
    fun eachRowButtonGoesToTheExtensionBeforeItsReturned() {
        val context = PatchContexts.of(shareClasses())
        val site = context.findShareButton()
        val before = site.builders.map { context.shareMethod(it).code().map { i -> i.describe() } }

        context.hideShareButton(site)

        assertShareHooked("the stand-in", context, site, before)
        val text = site.builders.first()
        assertTrue("the stand-in: the branch to the return goes through the hook", text.returnAt in context.shareMethod(text).jumpTargets())
    }

    /** A Share profile button the patch can't place fails at patch time, saying what it found. */
    @Test
    fun aShareButtonThePatchCantPlaceFails() {
        val cases = listOf(
            shareClasses(models = 0) to "expected one Share profile button loading \"$SHARE_URL\" and \"$SHARE_TRAY\" when tapped, found 0",
            shareClasses(kinds = 2) to "expected Share profile's $shareModel to be one kind of action button, found 2 interfaces",
            shareClasses(icons = 0) to "expected one method building Share profile as an icon from a $kind, found 0",
            shareClasses(texts = 0) to "expected one method in $rowButtons building the action row's text buttons from a $kind, found 0",
            shareClasses(texts = 2) to "expected one method in $rowButtons building the action row's text buttons from a $kind, found 2",
            shareClasses(writesModel = true) to "$rowButtons->A01 writes over its model in v2",
            shareClasses(extension = null) to "the extension has no $SHARE_BUTTON",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.findShareButton() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
        }
    }

    /** In each declared build both of the row's builders hand Share profile over once, before their return. */
    @Test
    fun eachDeclaredBuildHandsShareProfileToTheExtension() {
        forEachFixture { share(it, it.name) }
    }

    /** The same on every other build of the version. */
    @Test
    fun eachOtherBuildHandsShareProfileToTheExtensionToo() {
        for (base in Fixtures.otherBuilds()) share(base, base.parentFile.name)
    }

    private fun share(bundle: File, label: String) {
        val loading = (FixtureDex.classesLoading(bundle, SHARE_URL) + FixtureDex.classesLoading(bundle, SHARE_ICON_TAG)).distinctBy { it.type }
        val context = PatchContexts.of(FixtureDex.withStringPools(bundle, loading) + ExtensionDex.classDef(SHARE_BUTTON))
        val site = context.findShareButton()
        val before = site.builders.map { context.shareMethod(it).code().map { i -> i.describe() } }

        context.hideShareButton(site)

        assertShareHooked(label, context, site, before)
    }

    /** The stub's check, then for each builder one hook right before its return, Instagram's own code otherwise untouched. */
    private fun assertShareHooked(what: String, context: BytecodePatchContext, site: ShareButtonSite, before: List<List<String>>) {
        val stub = context.mutableClassDefBy(SHARE_BUTTON).methods.single { it.name == SHARE_STUB }.code()
        assertEquals("$what: the stub", listOf(Opcode.INSTANCE_OF, Opcode.RETURN), stub.map { it.opcode })
        assertEquals("$what: the stub's type", site.model, (stub[0].reference() as TypeReference).type)
        assertEquals("$what: the builders", 2, site.builders.size)
        for ((index, builder) in site.builders.withIndex()) {
            val method = context.shareMethod(builder)
            val code = method.code()
            val where = "$what: ${builder.type}->${builder.name}"
            assertEquals("$where: hooks", listOf(builder.returnAt), code.indices.filter { code[it].referenceText() == SHARE_HOOK })
            assertEquals("$where: hands over the button and its model", listOf(builder.view, builder.from), code[builder.returnAt].arguments())
            assertEquals("$where: the return after it", Opcode.RETURN_OBJECT, code[builder.returnAt + 1].opcode)
            assertEquals("$where: the button returned", builder.view, (code[builder.returnAt + 1] as OneRegisterInstruction).registerA)
            assertTrue("$where: a jump skips the hook", builder.returnAt + 1 !in method.jumpTargets())
            val expected = before[index].toMutableList().apply { add(builder.returnAt, code[builder.returnAt].describe()) }
            assertEquals("$where: Instagram's own code", expected, code.map { it.describe() })
        }
    }

    private fun BytecodePatchContext.shareMethod(builder: ShareBuilder): MutableMethod =
        mutableClassDefBy(builder.type).methods.single { it.name == builder.name && it.parameterTypes.map(CharSequence::toString) == builder.parameters }

    private val kind = "Lfixture/ActionButton;"
    private val shareModel = "Lfixture/ShareModel;"
    private val rowButtons = "Lfixture/RowButtons;"

    /**
     * Share profile's model, an action button whose onClick loads the share link and the tray it
     * opens; the class building the row's buttons, the text ones (with a branch to the return, as
     * 451's has) and Share profile as an icon; and the extension.
     */
    private fun shareClasses(
        models: Int = 1,
        kinds: Int = 1,
        icons: Int = 1,
        texts: Int = 1,
        writesModel: Boolean = false,
        extension: ClassDef? = ExtensionDex.classDef(SHARE_BUTTON),
    ): List<ClassDef> {
        val view = "Landroid/view/View;"
        val classes = mutableListOf<ClassDef>()
        if (models == 1) {
            val onClick = method(shareModel, "onClick", listOf(view), "V", 1, body = "const-string v0, \"$SHARE_URL\"\nconst-string v0, \"$SHARE_TRAY\"\nreturn-void")
            val interfaces = List(kinds) { if (it == 0) kind else "Lfixture/Other$it;" }
            classes += ImmutableClassDef(shareModel, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;",
                interfaces, null, null, emptyList(), listOf(onClick))
        }
        val builders = mutableListOf<Method>()
        builders += (0 until texts).map {
            method(rowButtons, "A0${it + 1}", listOf(kind, "Ljava/lang/Integer;", "I"), view, 2, static = true, body = """
                const-string v1, "$TEXT_TAG"
                ${if (writesModel) "const/4 p0, 0x0" else ""}
                const/4 v0, 0x0
                if-eqz p2, :done
                invoke-static {}, Lfixture/Views;->make()$view
                move-result-object v0
                :done
                return-object v0
            """)
        }
        builders += (0 until icons).map {
            method(rowButtons, "B0$it", listOf(kind), view, 2, static = true, body = """
                const-string v1, "$SHARE_ICON_TAG"
                const/4 v0, 0x0
                return-object v0
            """)
        }
        // The share link loaded beside the row's builders by a method that isn't Share's onClick.
        builders += method(rowButtons, "onClick", listOf(view), "V", 1, body = "const-string v0, \"$SHARE_URL\"\nreturn-void")
        classes += classDef(rowButtons, builders)
        if (extension != null) classes += extension
        return classes
    }

    /** The method adding the Threads chip asks the extension first and returns before the chip is made when told to. */
    @Test
    fun theThreadsChipIsSkippedWhenTheExtensionSaysSo() {
        val context = PatchContexts.of(bannerClasses() + ExtensionDex.classDef(THREADS_BUTTON))
        val site = context.findThreadsBanner()
        val before = context.adder(site).code().map { it.describe() }

        context.hideThreadsBanner(site)

        assertEquals("the chip", chip, site.banner)
        assertBannerHooked("the stand-in", context, site, before)
    }

    /** A chip or an adder the patch can't place fails at patch time, saying what it found. */
    @Test
    fun aThreadsChipThePatchCantPlaceFails() {
        val cases = listOf(
            bannerClasses(impression = false) to "expected one Threads chip loading \"$BANNER_TAP\" and \"$BANNER_IMPRESSION\", found 0",
            bannerClasses(adders = 0) to "expected one method adding the Threads chip $chip to a profile's banners, found 0",
            bannerClasses(adders = 2) to "expected one method adding the Threads chip $chip to a profile's banners, found 2",
            bannerClasses(session = "Ljava/lang/Object;") to "expected one method adding the Threads chip $chip to a profile's banners, found 0",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes + ExtensionDex.classDef(THREADS_BUTTON))
            val failure = assertThrows(expected, PatchException::class.java) { context.findThreadsBanner() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
        }
    }

    /** In each declared build the one method adding the Threads chip under a profile's bio asks the extension first. */
    @Test
    fun eachDeclaredBuildAsksBeforeAddingTheThreadsChip() {
        forEachFixture { banner(it, it.name) }
    }

    /** The same on every other build of the version. */
    @Test
    fun eachOtherBuildAsksBeforeAddingTheThreadsChipToo() {
        for (base in Fixtures.otherBuilds()) banner(base, base.parentFile.name)
    }

    private fun banner(bundle: File, label: String) {
        val loading = (FixtureDex.classesLoading(bundle, BANNER_TAP) + FixtureDex.classesLoading(bundle, BANNER_IMPRESSION)).distinctBy { it.type }
        val candidates = loading.map { it.type }.toSet()
        val creators = FixtureDex.methodsWhere(bundle, { dex -> dex.typeSection.any { it in candidates } }) { method ->
            method.code().any { it.opcode == Opcode.NEW_INSTANCE && (it.reference() as? TypeReference)?.type in candidates }
        }.map { it.definingClass }.toSet()
        val classes = (loading + FixtureDex.classes(bundle, creators).values).distinctBy { it.type }
        val context = PatchContexts.of(FixtureDex.withStringPools(bundle, classes) + ExtensionDex.classDef(THREADS_BUTTON))
        val site = context.findThreadsBanner()
        val before = context.adder(site).code().map { it.describe() }

        context.hideThreadsBanner(site)

        assertBannerHooked(label, context, site, before)
    }

    /** The ask, its answer, the skip and Instagram's own code after it, untouched. */
    private fun assertBannerHooked(what: String, context: BytecodePatchContext, site: ThreadsBannerSite, before: List<String>) {
        val adder = context.adder(site)
        val code = adder.code()
        assertEquals("$what: the ask", HIDE_BANNER, code[0].referenceText())
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[1].opcode)
        assertEquals("$what: the branch", Opcode.IF_EQZ, code[2].opcode)
        assertEquals("$what: the skip", Opcode.RETURN_VOID, code[3].opcode)
        assertEquals("$what: Instagram's code after it", before, code.drop(4).map { it.describe() })
        assertTrue("$what: the branch goes to Instagram's first instruction", 4 in adder.jumpTargets())
        assertEquals("$what: hooks", 1, code.count { it.referenceText() == HIDE_BANNER })
    }

    private fun BytecodePatchContext.adder(site: ThreadsBannerSite): MutableMethod =
        mutableClassDefBy(site.type).methods.single { it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters }

    private val chip = "Lfixture/ThreadsChip;"
    private val banners = "Lfixture/Banners;"

    /** A chip whose constructor loads its two events, and the static method that makes one and adds it to the list. */
    private fun bannerClasses(adders: Int = 1, impression: Boolean = true, session: String = USER_SESSION): List<ClassDef> {
        val events = "const-string v0, \"$BANNER_TAP\"" + if (impression) "\nconst-string v0, \"$BANNER_IMPRESSION\"" else ""
        val init = method(chip, "<init>", emptyList(), "V", 1, body = "$events\ninvoke-direct { p0 }, Ljava/lang/Object;-><init>()V\nreturn-void")
        val adds = (0 until adders).map { index ->
            method(banners, "A0$index", listOf(session, "Ljava/lang/Object;", "Ljava/lang/Object;", "Ljava/util/List;"), "V", 1, static = true, body = """
                new-instance v0, $chip
                invoke-direct { v0 }, $chip-><init>()V
                invoke-interface { p3, v0 }, Ljava/util/List;->add(Ljava/lang/Object;)Z
                return-void
            """)
        }
        return listOf(classDef(chip, listOf(init)), classDef(banners, adds))
    }

    /**
     * #254: right after the guard lets your own profile through, the action bar asks the extension,
     * jumps where the guard jumps for someone else's profile when told to, and otherwise puts the
     * flag back and builds the plus as before.
     */
    @Test
    fun theCreateButtonIsSkippedWhenTheExtensionSaysSo() {
        val context = PatchContexts.of(createClasses() + ExtensionDex.classDef(THREADS_BUTTON))
        val site = context.findCreateInBar()
        val original = NeutralNativePath(context.createAdder(site))

        context.hideCreateInBar(site)

        assertEquals("the guard", 2, site.guard)
        assertEquals("the flag's register", 0, site.register)
        assertCreateHooked("the stand-in", context, site, original)
    }

    /** A Create button the patch can't place fails at patch time, saying what it found. */
    @Test
    fun aCreateButtonThePatchCantPlaceFails() {
        val notOne = "expected one Create button stored with its id 0x${CREATE_BUTTON_ID.toString(16)}, found"
        val guarded = "$profileBar->Ady guards the Create button with something other than a flag of $USER_DETAIL"
        val cases = listOf(
            createClasses(stores = 0) to "$notOne 0",
            createClasses(stores = 2) to "$notOne 2",
            createClasses(guard = null) to "$profileBar->Ady adds the Create button with no guard before it",
            createClasses(guard = "if-nez") to guarded,
            createClasses(flagOwner = "Lfixture/Other;") to guarded,
            createClasses(jumpIn = true) to "something jumps in between $profileBar->Ady's guard and the Create button",
            createClasses(extension = fakeExtension()) to "the extension has no public static $HIDE_CREATE",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.findCreateInBar() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
        }
    }

    /** In each declared build the profile screen's action bar asks the extension once before adding the plus on your own profile. */
    @Test
    fun eachDeclaredBuildAsksBeforeAddingTheCreateButton() {
        forEachFixture { create(it, it.name) }
    }

    /** The same on every other build of the version. */
    @Test
    fun eachOtherBuildAsksBeforeAddingTheCreateButtonToo() {
        for (base in Fixtures.otherBuilds()) create(base, base.parentFile.name)
    }

    private fun create(bundle: File, label: String) {
        // Every dex, so another method storing the id would show up here as it would when patching.
        val owners = FixtureDex.methodsWhere(bundle, { true }) { method ->
            method.code().any { (it as? NarrowLiteralInstruction)?.narrowLiteral == CREATE_BUTTON_ID }
        }.map { it.definingClass }.toSet()
        val context = PatchContexts.of(FixtureDex.classes(bundle, owners).values + ExtensionDex.classDef(THREADS_BUTTON))
        val site = context.findCreateInBar()
        val original = NeutralNativePath(context.createAdder(site))

        context.hideCreateInBar(site)

        assertCreateHooked(label, context, site, original)
    }

    /** The guard, the ask, its answer, the jump to where the guard goes, the flag put back, and Instagram's own code untouched. */
    private fun assertCreateHooked(what: String, context: BytecodePatchContext, site: CreateInBarSite, original: NeutralNativePath) {
        val adder = context.createAdder(site)
        val code = adder.code()
        val written = adder.implementation!!.instructions.toList()
        val at = site.guard + 1
        val skip = site.skip + 4
        assertEquals("$what: the guard", Opcode.IF_EQZ, code[site.guard].opcode)
        assertEquals("$what: the guard still goes past the button", skip, (written[site.guard] as BuilderOffsetInstruction).target.location.index)
        assertEquals("$what: the ask", HIDE_CREATE, code[at].referenceText())
        assertEquals("$what: the answer", Opcode.MOVE_RESULT, code[at + 1].opcode)
        assertEquals("$what: the answer's register", site.register, (code[at + 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: the branch", Opcode.IF_NEZ, code[at + 2].opcode)
        assertEquals("$what: the branch's register", site.register, (code[at + 2] as OneRegisterInstruction).registerA)
        assertEquals("$what: the branch goes where the guard does", skip, (written[at + 2] as BuilderOffsetInstruction).target.location.index)
        assertEquals("$what: the flag put back", Opcode.CONST_4, code[at + 3].opcode)
        assertEquals("$what: the flag's register", site.register, (code[at + 3] as OneRegisterInstruction).registerA)
        assertEquals("$what: the flag's value", 1, (code[at + 3] as NarrowLiteralInstruction).narrowLiteral)
        assertTrue("$what: a jump lands on the hook", (at..at + 3).none { it in adder.jumpTargets() })
        assertEquals("$what: hooks", 1, code.count { it.referenceText() == HIDE_CREATE })
        original.assertPreserved(what, adder, (at..at + 3).toSet())
    }

    private fun BytecodePatchContext.createAdder(site: CreateInBarSite): MutableMethod =
        mutableClassDefBy(site.type).methods.single {
            it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters && it.returnType == site.returnType
        }

    private val profileBar = "Lfixture/ProfileBar;"
    private val barButton = "Lfixture/BarButton;"

    /**
     * The profile screen's action bar, shaped like 451's: a flag of the profile screen, true on your
     * own profile, guards the build of the plus, whose builder gets the button's view id first.
     * [stores] is how many methods store the id; 0 leaves one that only loads it.
     */
    private fun createClasses(
        stores: Int = 1,
        guard: String? = "if-eqz",
        flagOwner: String = USER_DETAIL,
        jumpIn: Boolean = false,
        extension: ClassDef = ExtensionDex.classDef(THREADS_BUTTON),
    ): List<ClassDef> {
        val store = if (stores == 0) "invoke-virtual { v1, v2 }, $barButton->setId(I)V" else "iput v2, v1, $barButton->A05:I"
        val body = """
            ${if (jumpIn) "if-nez p1, :inside" else ""}
            const/4 v1, 0x0
            iget-boolean v0, p1, $flagOwner->A2V:Z
            ${if (guard == null) "" else "$guard v0, :after"}
            new-instance v1, $barButton
            :inside
            invoke-direct { v1 }, $barButton-><init>()V
            const v2, 0x${CREATE_BUTTON_ID.toString(16)}
            $store
            :after
            return-void
        """
        val names = if (stores == 2) listOf("Ady", "A00") else listOf("Ady")
        val methods = names.map { method(profileBar, it, listOf(USER_DETAIL), "V", 3, body = body) }
        return listOf(classDef(profileBar, methods), extension)
    }

    /**
     * The list goes to the extension right after it's read, past a long the builder takes before
     * it, and the answer goes back in the same register, so the null check and the build use it.
     * The stub then reads the icon off the button type the builder casts to.
     */
    @Test
    fun theListGoesToTheExtensionAndTheStubReadsTheIcon() {
        val context = PatchContexts.of(classes())

        context.hide()

        assertHooked("the stand-in", context.binder(), register = 0)
        assertStub("the stand-in", context, button, "$button->icon:I")
    }

    /** Another list the builder walks, like 450's list of Booleans, isn't taken for the buttons. */
    @Test
    fun anotherListTheBuilderWalksIsLeftOut() {
        val context = PatchContexts.of(classes(otherWalk = true))

        context.hide()

        assertHooked("the stand-in with a second walk", context.binder(), register = 0)
        assertStub("the stand-in with a second walk", context, button, "$button->icon:I")
    }

    /** A branch inside the stretch from the read to the build, like 450's, is fine: every way in still passes the hook. */
    @Test
    fun aBranchInsideTheStretchIsFine() {
        val context = PatchContexts.of(classes(between = "if-nez v1, :skip\nconst/4 v4, 0x1\n:skip"))

        context.hide()

        assertHooked("the branching stand-in", context.binder(), register = 0)
    }

    /** A build the patch can't read fails at patch time, saying what it found, and nothing is changed. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val bar = PROFILE_ACTION_BAR
        val cases = listOf(
            classes(bar = false) to "this Instagram build has no $bar",
            classes(builds = 0) to "expected one call building the buttons in $bar, found 0",
            classes(builds = 2) to "expected one call building the buttons in $bar, found 2",
            classes(read = "invoke-virtual { p1, v1 }, $source->buttons(I)Ljava/util/List;") to "expected one list of buttons read into v0 in $bar->A00, found 0",
            classes(read = "invoke-virtual { p1 }, $source->buttons()Ljava/util/Collection;") to "expected one list of buttons read into v0 in $bar->A00, found 0",
            classes(reads = 2) to "expected one list of buttons read into v0 in $bar->A00, found 2",
            classes(readAfter = true) to "$bar->A00 reads its list of buttons after building them",
            classes(jumpIn = true) to "something jumps in between $bar->A00's list of buttons and the call building them",
            classes(between = "const/4 v0, 0x0") to "$bar->A00 writes over v0 between its list of buttons",
            classes(builder = false) to "the buttons' builder $builder isn't in the app",
            classes(builderName = "other") to "$builder has no build",
            classes(loops = 0) to "expected $builder->build to go through its buttons once, found 0",
            classes(loops = 2) to "expected $builder->build to go through its buttons once, found 2",
            classes(button = false) to "the button type $button isn't in the app",
            classes(buttonPublic = false) to "$button isn't public, so the extension can't read its icon",
            classes(iconFields = 0) to "expected one int field on $button, its icon, found 0",
            classes(iconFields = 2) to "expected one int field on $button, its icon, found 2",
            classes(iconPublic = false) to "$button's icon icon isn't public, so the extension can't read it",
            classes(extension = null) to "the extension has no $THREADS_BUTTON",
            classes(extension = fakeExtension(buttons = false)) to "the extension has no public static $BUTTONS",
            classes(extension = fakeExtension(icon = false)) to "$THREADS_BUTTON has no public static I $ICON_STUB(Ljava/lang/Object;)",
        )
        for ((classes, expected) in cases) {
            val context = PatchContexts.of(classes)
            val failure = assertThrows(expected, PatchException::class.java) { context.hide() }
            assertTrue("$expected: ${failure.message}", failure.message!!.contains(expected))
            for (original in classes) {
                val now = context.mutableClassDefBy(original.type).methods.associateBy { it.key() }
                for (method in original.methods) {
                    val after = now.getValue(method.key())
                    assertEquals("$expected: ${original.type}->${method.name} changed", method.code().map { it.describe() }, after.code().map { it.describe() })
                }
            }
        }
    }

    /**
     * In each declared build the bar's list goes to the extension once, right after it's read, with
     * Instagram's own code otherwise untouched, and the stub reads the int field of the type every
     * button extends.
     */
    @Test
    fun eachDeclaredBuildHandsTheBarsListToTheExtension() {
        forEachFixture { builds(it, it.name) }
    }

    /** The same checks on every other build of the version, each compiled on its own. */
    @Test
    fun eachOtherBuildHandsTheBarsListToTheExtensionToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val bar = FixtureDex.classes(bundle, setOf(PROFILE_ACTION_BAR)).values.single()
        val builders = bar.methods.flatMap { it.code() }.mapNotNull { it.reference() as? MethodReference }
            .filter { call -> call.parameterTypes.count { it.toString() == BAR_GROUP } == 2 }.map { it.definingClass }.toSet()
        val builderClasses = FixtureDex.classes(bundle, builders).values
        val casts = builderClasses.flatMap { it.methods }.flatMap { it.code() }
            .filter { it.opcode == Opcode.CHECK_CAST }.map { (it.reference() as TypeReference).type }.toSet()
        val buttons = FixtureDex.classes(bundle, casts).values
        val context = PatchContexts.of((listOf(bar) + builderClasses + buttons).distinctBy { it.type } + ExtensionDex.classDef(THREADS_BUTTON))
        val site = context.findThreadsButton()
        val original = NeutralNativePath(context.binder(site))

        context.hideThreadsButton(site)

        val binder = context.binder(site)
        assertHooked(label, binder, site.register)
        original.assertPreserved(label, binder, setOf(site.hook, site.hook + 1))
        assertStub(label, context, site.button, site.icon)
        assertTrue("$label: no button the builder takes extends ${site.button}", buttons.any { it.superclass == site.button })
    }

    private fun forEachFixture(check: (java.io.File) -> Unit) {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                check(bundle)
                checked += version
            }
        }
        assertEquals("a declared build has no fixture", versions, checked)
    }

    /** The list read, then the hook on its register and its answer back into it, once, with nothing jumping in. */
    private fun assertHooked(what: String, binder: MutableMethod, register: Int) {
        val code = binder.code()
        val hooks = code.indices.filter { code[it].referenceText() == BUTTONS }
        assertEquals("$what: hooks", 1, hooks.size)
        val hook = hooks.single()
        assertEquals("$what: right after the list is read", Opcode.MOVE_RESULT_OBJECT, code[hook - 1].opcode)
        assertEquals("$what: the read's register", register, (code[hook - 1] as OneRegisterInstruction).registerA)
        assertEquals("$what: hands the list over", listOf(register), code[hook].arguments())
        assertEquals("$what: the answer", Opcode.MOVE_RESULT_OBJECT, code[hook + 1].opcode)
        assertEquals("$what: the answer's register", register, (code[hook + 1] as OneRegisterInstruction).registerA)
        assertTrue("$what: a jump lands on the hook or its answer", hook !in binder.jumpTargets() && hook + 1 !in binder.jumpTargets())
    }

    /** The stub casts its button, reads [icon] off it and answers that. */
    private fun assertStub(what: String, context: BytecodePatchContext, type: String, icon: String) {
        val stub = context.mutableClassDefBy(THREADS_BUTTON).methods.single { it.name == ICON_STUB }.code()
        assertEquals("$what: the stub", listOf(Opcode.CHECK_CAST, Opcode.IGET, Opcode.RETURN), stub.map { it.opcode })
        assertEquals("$what: the stub's cast", type, (stub[0].reference() as TypeReference).type)
        assertEquals("$what: the stub's field", icon, stub[1].referenceText())
    }

    private fun BytecodePatchContext.hide() = hideThreadsButton(findThreadsButton())

    private fun BytecodePatchContext.binder(site: ThreadsButtonSite? = null): MutableMethod =
        mutableClassDefBy(PROFILE_ACTION_BAR).methods.single {
            if (site == null) it.name == "A00" else it.name == site.name && it.parameterTypes.map(CharSequence::toString) == site.parameters
        }

    // ---- stand-ins shaped like Instagram 450's -------------------------------------------------

    /**
     * The bar, whose binder reads the list of buttons, skips the build on null and hands it to the
     * builder with the bar's two groups and a long; the builder, which casts each item to the
     * button type; the button type, public with its icon in a public int; and the extension.
     */
    private fun classes(
        bar: Boolean = true,
        builds: Int = 1,
        read: String = getter,
        reads: Int = 1,
        readAfter: Boolean = false,
        jumpIn: Boolean = false,
        between: String = "",
        builder: Boolean = true,
        builderName: String = "build",
        loops: Int = 1,
        otherWalk: Boolean = false,
        button: Boolean = true,
        buttonPublic: Boolean = true,
        iconFields: Int = 1,
        iconPublic: Boolean = true,
        extension: ClassDef? = ExtensionDex.classDef(THREADS_BUTTON),
    ): List<ClassDef> {
        val classes = mutableListOf<ClassDef>()
        if (bar) {
            val call = if (builds == 0) "invoke-static { v1, v2, v3, v0 }, ${this.builder}->build(${BAR_GROUP}JLjava/util/List;)V"
            else "invoke-static { v1, v1, v2, v3, v0 }, $build"
            val reading = List(reads) { "$read\nmove-result-object v0" }.joinToString("\n")
            val body = """
                const/4 v1, 0x0
                const-wide/16 v2, 0x0
                ${if (jumpIn) "if-nez p1, :inside" else ""}
                ${if (readAfter) "" else reading}
                if-eqz v0, :done
                $between
                :inside
                $call
                ${if (readAfter) reading else ""}
                :done
                return-void
            """
            val methods = mutableListOf(method(PROFILE_ACTION_BAR, "A00", listOf(source), "V", 6, body = body))
            if (builds == 2) {
                methods += method(PROFILE_ACTION_BAR, "A01", listOf(source), "V", 6, body = """
                    $getter
                    move-result-object v0
                    const/4 v1, 0x0
                    const-wide/16 v2, 0x0
                    $call
                    return-void
                """)
            }
            classes += classDef(PROFILE_ACTION_BAR, methods, superclass = "Landroid/widget/FrameLayout;")
        }
        if (builder) {
            val step = "invoke-interface { v0 }, Ljava/util/Iterator;->next()Ljava/lang/Object;\nmove-result-object v1\ncheck-cast v1, ${this.button}"
            // 450's builder also walks a list of Booleans, through an iterator of its own.
            val other = """
                invoke-static {}, Ljava/util/Collections;->emptyList()Ljava/util/List;
                move-result-object v1
                invoke-interface { v1 }, Ljava/util/List;->iterator()Ljava/util/Iterator;
                move-result-object v1
                invoke-interface { v1 }, Ljava/util/Iterator;->next()Ljava/lang/Object;
                move-result-object v1
                check-cast v1, Ljava/lang/Boolean;
            """.takeIf { otherWalk }.orEmpty()
            val body = """
                $other
                invoke-interface { p4 }, Ljava/util/List;->iterator()Ljava/util/Iterator;
                move-result-object v0
                :loop
                invoke-interface { v0 }, Ljava/util/Iterator;->hasNext()Z
                move-result v1
                if-eqz v1, :done
                ${List(loops) { step }.joinToString("\n")}
                goto :loop
                :done
                return-void
            """
            val parameters = listOf(BAR_GROUP, BAR_GROUP, "J", "Ljava/util/List;")
            classes += classDef(this.builder, listOf(method(this.builder, builderName, parameters, "V", 2, static = true, body = body)))
        }
        if (button) {
            val access = AccessFlags.PUBLIC.value.takeIf { iconPublic } ?: AccessFlags.PRIVATE.value
            val fields = (0 until iconFields).map { ImmutableField(this.button, if (it == 0) "icon" else "size", "I", access, null, null, null) } +
                ImmutableField(this.button, "label", "Ljava/lang/String;", AccessFlags.PUBLIC.value, null, null, null)
            val flags = (if (buttonPublic) AccessFlags.PUBLIC.value else 0) or AccessFlags.ABSTRACT.value
            classes += ImmutableClassDef(this.button, flags, "Ljava/lang/Object;", null, null, null, fields, emptyList())
        }
        if (extension != null) classes += extension
        return classes
    }

    /** A ThreadsButton missing the hook or the stub. */
    private fun fakeExtension(buttons: Boolean = true, icon: Boolean = true): ClassDef {
        val methods = mutableListOf<Method>()
        if (buttons) methods += method(THREADS_BUTTON, "buttons", listOf("Ljava/util/List;"), "Ljava/util/List;", 0, static = true, body = "return-object p0")
        if (icon) methods += method(THREADS_BUTTON, ICON_STUB, listOf("Ljava/lang/Object;"), "I", 1, static = true, body = "const/4 v0, 0x0\nreturn v0")
        return classDef(THREADS_BUTTON, methods)
    }

    private fun method(
        owner: String,
        name: String,
        parameters: List<String>,
        returns: String,
        registers: Int,
        static: Boolean = false,
        body: String,
    ): Method {
        var flags = AccessFlags.PUBLIC.value
        if (static) flags = flags or AccessFlags.STATIC.value
        val total = registers + (if (static) 0 else 1) + parameters.sumOf { if (it == "J" || it == "D") 2 else 1 }
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, methods: List<Method>, superclass: String = "Ljava/lang/Object;"): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, superclass, null, null, null, emptyList(), methods)

    private fun MutableMethod.jumpTargets(): Set<Int> =
        implementation!!.instructions.filterIsInstance<com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction>()
            .map { it.target.location.index }.toSet()

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Method.key(): String = name + parameterTypes.joinToString(prefix = "(", postfix = ")")

    private fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

    private fun Instruction.referenceText(): String? = reference()?.toString()

    private fun Instruction.arguments(): List<Int> = when (this) {
        is FiveRegisterInstruction -> listOf(registerC, registerD, registerE, registerF, registerG).take(registerCount)
        is RegisterRangeInstruction -> (startRegister until startRegister + registerCount).toList()
        else -> emptyList()
    }

    /** An instruction as text: its opcode, registers, literal and reference, enough to see a change. */
    private fun Instruction.describe(): String = buildString {
        append(opcode.name)
        if (this@describe is OneRegisterInstruction) append(" v$registerA")
        if (this@describe is TwoRegisterInstruction) append(" v$registerB")
        append(arguments().joinToString(prefix = " {", postfix = "}") { "v$it" })
        if (this@describe is NarrowLiteralInstruction) append(" #$narrowLiteral")
        referenceText()?.let { append(" $it") }
    }
}
