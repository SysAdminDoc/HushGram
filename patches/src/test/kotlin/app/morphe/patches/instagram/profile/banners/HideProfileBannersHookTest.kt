/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.banners

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.instagram.metaai.MUSE_BANNER_EVENTS
import app.morphe.patches.instagram.metaai.findMuseBannerBuilder
import app.morphe.patches.instagram.misc.extension.PatchLogCapture
import app.morphe.patches.instagram.misc.extension.loadsString
import app.morphe.patches.instagram.profile.friendship.USER
import app.morphe.patches.instagram.profile.friendship.VIEW_MODEL
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.FiveRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.OneRegisterInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.instruction.TwoRegisterInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
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

/**
 * Hide profile banners: the row of banners under a profile's bio goes through the extension just
 * before it's wrapped for the header, and the extension's stubs read a banner's kind and the
 * signed-in account's user ID.
 */
class HideProfileBannersHookTest {
    private val header = "Lfixture/ProfileHeader;"
    private val base = "Lfixture/Banner;"
    private val muse = "Lfixture/MuseBanner;"
    private val kindEnum = "Lfixture/BannerKind;"
    private val holder = "Lfixture/HeaderState;"
    private val rowHeader = "Lfixture/RowHeader;"
    private val row = "Lfixture/Row;"
    private val context = "Landroid/content/Context;"
    private val string = "Ljava/lang/String;"
    private val list = "Ljava/util/List;"
    private val museCall = "$header->muse($context${OBJECT_TYPE})$muse"
    private val linksBinder = "Lfixture/LinksBinder;"
    private val headerData = "Lfixture/HeaderData;"
    private val linksState = "Lfixture/LinksState;"
    private val linksModel = "Lfixture/Links;"
    private val linksRow = "Lfixture/LinksRow;"
    private val linksHeader = "Lfixture/LinksHeader;"

    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(PROFILE_BANNERS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        val wanted = listOf(
            FILTER_BANNERS.substringAfter("->"), "$BANNER_TYPE_STUB($OBJECT_TYPE)$string", "$VIEWER_ID_STUB($OBJECT_TYPE)$string",
            FILTER_LINKS.substringAfter("->"), "$LINKS_PROFILE_ID_STUB($OBJECT_TYPE)$string", "$LINKS_VIEWER_ID_STUB($OBJECT_TYPE)$string",
        )
        for (hook in wanted) {
            assertTrue("$hook is not in the extension: $declared", hook in declared)
        }
    }

    /**
     * The binder names the field and the kind of links, the one other read of that field into the
     * same question is where the filter goes, with the header parameter, and the stubs read the
     * profile's user ID and the signed-in one from that header.
     */
    @Test
    fun theLinksGoThroughTheExtensionBeforeTheRowAsks() {
        val patch = PatchContexts.of(linksClasses())
        val found = patch.findProfileLinks()
        assertEquals(linksRow, found.type)
        assertEquals("draw", found.name)
        assertEquals(linksState, found.field.type)
        assertEquals("the header is the third parameter", 5, found.header)
        assertEquals(1, found.links)
        assertEquals(linksHeader, found.headerType)
        assertEquals("$linksHeader->viewModel:$VIEW_MODEL", found.viewModel)
        assertEquals("$linksHeader->session:$BANNER_SESSION", found.session)
        assertEquals("getId", found.userId)
        val before = patch.mutableClassDefBy(linksRow).methods.single { it.name == "draw" }.code()
        assertEquals(Opcode.INSTANCE_OF, before[found.at].opcode)

        patch.hideProfileLinks(found)

        val after = patch.mutableClassDefBy(linksRow).methods.single { it.name == "draw" }.code()
        assertLinksFiltered("synthetic", found, before, after)
        assertLinksStubsFilled(patch, found)
        assertEquals("the binder is left alone", 4, patch.mutableClassDefBy(linksBinder).methods.single().code().size)
    }

    @Test
    fun anUnfamiliarLinksRowIsRefused() {
        val refusals = mapOf(
            "no binder" to linksClasses(binder = false),
            "two readers" to linksClasses(readers = 2),
            "a jump onto the question" to linksClasses(jumpOnto = true),
            "no session on the header" to linksClasses(session = false),
            "a private header" to linksClasses(publicHeader = false),
            "the header written first" to linksClasses(headerWritten = true),
            "no ID getter in hashCode" to linksClasses(hashed = false),
        )
        for ((case, classes) in refusals) {
            assertThrows(case, PatchException::class.java) { PatchContexts.of(classes).findProfileLinks() }
        }
    }

    /** A links row that has moved leaves Hide links out with a warning, and the banners still go in. */
    @Test
    fun aMovedLinksRowLeavesHideLinksOut() {
        val patch = PatchContexts.of(linksClasses(binder = false))
        var asked = false
        var found: ProfileLinks? = null
        val warnings = PatchLogCapture.warnings {
            found = patch.profileLinksOrWarn()
            asked = true
        }
        assertTrue(asked)
        assertEquals(null, found)
        assertEquals(warnings.toString(), 1, warnings.size)
        assertTrue(warnings.single(), warnings.single().endsWith("goes in without Hide links."))
    }

    /**
     * The kind is the enum name the Muse banner's builder hands its banner's constructor, followed up
     * to the common class's field, and the list is filtered right before the new-instance that wraps it.
     */
    @Test
    fun theRowGoesThroughTheExtensionBeforeItsWrapped() {
        val patch = PatchContexts.of(classes())
        val found = patch.findMuseBannerBuilder()
        val kind = patch.findBannerKind(found)
        assertEquals(base, kind.base)
        assertEquals("kind", kind.field)

        val wrapped = patch.findBannerRow(found)
        assertEquals(header, wrapped.type)
        assertEquals("row", wrapped.name)
        val before = patch.mutableClassDefBy(header).methods.single { it.name == "row" }.code()
        assertEquals(Opcode.NEW_INSTANCE, before[wrapped.at].opcode)

        patch.hideProfileBanners(kind, wrapped)

        val after = patch.mutableClassDefBy(header).methods.single { it.name == "row" }.code()
        assertFiltered("synthetic", wrapped, before, after)
        assertEquals("the session comes from the holder's field", "session", wrapped.session.name)
        assertStubsFilled(patch, kind)
        assertEquals("Edit featured's ordering is left alone", 5, patch.mutableClassDefBy(header).methods.single { it.name == "featured" }.code().size)
    }

    /** A builder that calls the common class's constructor itself is followed straight to the field. */
    @Test
    fun aBuilderCallingTheCommonConstructorIsFollowedToo() {
        val patch = PatchContexts.of(classes(direct = true))
        assertEquals("kind", patch.findBannerKind(patch.findMuseBannerBuilder()).field)
    }

    @Test
    fun anUnfamiliarRowFailsThePatch() {
        val refusals = mapOf(
            "two rows" to classes(rows = 2),
            "no wrapper" to classes(wrapped = false),
            "no enum kind" to classes(kindFromEnum = false),
            "private banners" to classes(publicBase = false),
            "no session" to classes(session = false),
        )
        for ((case, classes) in refusals) {
            val patch = PatchContexts.of(classes)
            val found = patch.findMuseBannerBuilder()
            assertThrows(case, PatchException::class.java) {
                patch.hideProfileBanners(patch.findBannerKind(found), patch.findBannerRow(found))
            }
        }
    }

    /**
     * In each declared build, and in every other build of a declared version, the Muse banner's
     * builder names the kind's field, one static method wraps the row, and the filter goes in ahead
     * of the wrapping without changing the rest.
     */
    @Test
    fun eachBuildHasOneProfileBannerRow() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        val declared = versions.flatMap { version ->
            Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }.map { version to it }
        }
        for ((version, bundle) in declared) {
            builds(bundle, bundle.name)
            checked += version
        }
        assertEquals("a declared build has no fixture", versions, checked)
        for (other in Fixtures.otherBuilds()) builds(other, other.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val holders = MUSE_BANNER_EVENTS.flatMap { FixtureDex.classesHolding(bundle, it) }.distinctBy { it.type }
        val found = PatchContexts.of(FixtureDex.withStringPools(bundle, holders)).findMuseBannerBuilder()
        val banner = FixtureDex.classes(bundle, setOf(found.returnType)).values.single()
        val staged = FixtureDex.classes(bundle, setOf(found.returnType, banner.superclass!!, BANNER_SESSION)).values
        val patch = PatchContexts.of(
            (FixtureDex.withStringPools(bundle, holders + staged) + ExtensionDex.classDef(PROFILE_BANNERS)).distinctBy { it.type },
        )

        val kind = patch.findBannerKind(found)
        assertEquals("$label: the kind is kept by the Muse banner's class", banner.superclass, kind.base)
        val wrapped = patch.findBannerRow(found)
        val before = holders.single { it.type == wrapped.type }.methods.single {
            it.name == wrapped.name && it.parameterTypes.map(CharSequence::toString) == wrapped.parameters
        }.code()

        patch.hideProfileBanners(kind, wrapped)

        val after = patch.mutableClassDefBy(wrapped.type).methods.single {
            it.name == wrapped.name && it.parameterTypes.map(CharSequence::toString) == wrapped.parameters
        }.code()
        assertFiltered(label, wrapped, before, after)
        assertStubsFilled(patch, kind)
    }

    /**
     * In each declared build, and in every other build of a declared version, the links binder names
     * one field, one place reads it into the question whether there are links, and the filter goes in
     * ahead of that question with the header, without changing the rest.
     */
    @Test
    fun eachBuildHasOneProfileLinksRow() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        val checked = mutableSetOf<String>()
        val declared = versions.flatMap { version ->
            Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }.map { version to it }
        }
        for ((version, bundle) in declared) {
            linksBuild(bundle, bundle.name)
            checked += version
        }
        assertEquals("a declared build has no fixture", versions, checked)
        for (other in Fixtures.otherBuilds()) linksBuild(other, other.parentFile.name)
    }

    private fun linksBuild(bundle: File, label: String) {
        // Some builds load the binder's section name through a string pool.
        val binders = FixtureDex.withStringPools(bundle, FixtureDex.classesLoading(bundle, BIND_LINKS))
        val binding = PatchContexts.of(binders)
        val (field, model) = binding.findLinksField()
        val binder = binders.flatMap { classDef -> classDef.methods.filter { binding.loadsString(it, BIND_LINKS) } }
            .mapTo(HashSet()) { "${it.definingClass}->${it.name}${it.parameterTypes.joinToString("")}" }
        val readers = mutableListOf<ClassDef>()
        FixtureDex.forEach(bundle) { dex ->
            if (dex.fieldSection.none { it.definingClass == field.definingClass && it.name == field.name }) return@forEach
            for (classDef in dex.classes) {
                if (classDef.methods.any { linksReadsIn(it, field, model).isNotEmpty() }) readers += ImmutableClassDef.of(classDef)
            }
        }
        val reading = readers.flatMap { it.methods }
            .filter { "${it.definingClass}->${it.name}${it.parameterTypes.joinToString("")}" !in binder }
            .filter { linksReadsIn(it, field, model).isNotEmpty() }
        assertEquals("$label: places asking whether $field holds $model", 1, reading.sumOf { linksReadsIn(it, field, model).size })
        val wanted = reading.single().parameterTypes.map(CharSequence::toString).toSet() + setOf(VIEW_MODEL, USER, BANNER_SESSION)
        val staged = FixtureDex.withStringPools(bundle, binders + readers + profileUserClasses(bundle, wanted))
        val patch = PatchContexts.of((staged + ExtensionDex.classDef(PROFILE_BANNERS)).distinctBy { it.type })

        val found = patch.findProfileLinks()
        assertEquals("$label: the field the binder asks", field.toString(), found.field.toString())
        val before = reading.single().code()

        patch.hideProfileLinks(found)

        val after = patch.mutableClassDefBy(found.type).methods.single {
            it.name == found.name && it.parameterTypes.map(CharSequence::toString) == found.parameters
        }.code()
        assertLinksFiltered(label, found, before, after)
        assertLinksStubsFilled(patch, found)
    }

    /**
     * [start] from the bundle, then in three rounds the classes they extend and implement, and for
     * the view model the types of its fields and what its user setter reads: the live data, its
     * interfaces and the holder the user sits in on newer builds.
     */
    private fun profileUserClasses(bundle: File, start: Set<String>): Collection<ClassDef> {
        val found = FixtureDex.classes(bundle, start).toMutableMap()
        repeat(3) {
            val wanted = found.values.flatMap { classDef ->
                val above = listOfNotNull(classDef.superclass) + classDef.interfaces.map(CharSequence::toString)
                if (classDef.type != VIEW_MODEL) return@flatMap above
                val setter = classDef.methods.filter { it.parameterTypes.map(CharSequence::toString) == listOf(VIEW_MODEL, USER) }
                    .flatMap { it.code() }
                    .flatMap { instruction ->
                        when (val reference = (instruction as? ReferenceInstruction)?.reference) {
                            is FieldReference -> listOf(reference.definingClass, reference.type)
                            is MethodReference -> listOf(reference.definingClass)
                            else -> emptyList()
                        }
                    }
                above + classDef.fields.map { it.type } + setter
            }.filter { it.startsWith("L") && it !in found }.toSet()
            if (wanted.isNotEmpty()) found += FixtureDex.classes(bundle, wanted)
        }
        return found.values
    }

    /** The header moved into a free local, the filter over the links and the header, its answer cast back, then the question. */
    private fun assertLinksFiltered(what: String, found: ProfileLinks, before: List<Instruction>, after: List<Instruction>) {
        val at = found.at
        val move = after[at]
        assertEquals("$what: the header moved", Opcode.MOVE_OBJECT_FROM16, move.opcode)
        assertEquals("$what: from the header parameter", found.header, (move as TwoRegisterInstruction).registerB)
        val scratch = (move as OneRegisterInstruction).registerA
        assertTrue("$what: v$scratch is a free local", scratch <= 15 && scratch != found.links)
        val call = after[at + 1]
        assertEquals("$what: the filter", FILTER_LINKS, call.referenceText())
        val arguments = (call as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD, it.registerE).take(it.registerCount) }
        assertEquals("$what: links, header", listOf(found.links, scratch), arguments)
        assertEquals("$what: answer", Opcode.MOVE_RESULT_OBJECT, after[at + 2].opcode)
        assertEquals("$what: into the links' register", found.links, (after[at + 2] as OneRegisterInstruction).registerA)
        assertEquals("$what: cast back", Opcode.CHECK_CAST, after[at + 3].opcode)
        assertEquals("$what: to the field's type", found.field.type, after[at + 3].referenceText())
        assertEquals("$what: the links cast", found.links, (after[at + 3] as OneRegisterInstruction).registerA)
        assertEquals("$what: then the question", Opcode.INSTANCE_OF, after[at + 4].opcode)
        assertEquals("$what: one filter", 1, after.count { it.referenceText() == FILTER_LINKS })
        val stock = before.filter { it.opcode != Opcode.NOP }.map { it.kind() }
        val patched = (after.take(at) + after.drop(at + 4)).filter { it.opcode != Opcode.NOP }.map { it.kind() }
        assertEquals("$what: the rest is as it was", stock, patched)
    }

    /** The profile stub walks from the header through the view model to the user's ID getter, the viewer stub asks the session. */
    private fun assertLinksStubsFilled(patch: BytecodePatchContext, found: ProfileLinks) {
        val stubs = patch.mutableClassDefBy(PROFILE_BANNERS).methods
        for ((name, kept, asked) in listOf(
            Triple(LINKS_PROFILE_ID_STUB, found.viewModel, "$USER->${found.userId}()$string"),
            Triple(LINKS_VIEWER_ID_STUB, found.session, "$BANNER_SESSION->getUserId()$string"),
        )) {
            val code = stubs.single { it.name == name }.code()
            assertEquals("$name: the header", Opcode.CHECK_CAST, code[0].opcode)
            assertEquals("$name: the header", found.headerType, code[0].referenceText())
            assertEquals("$name: read", kept, code[1].referenceText())
            assertTrue("$name: $asked", code.any { it.referenceText() == asked })
            assertTrue("$name: each way out returns", code.count { it.opcode == Opcode.RETURN_OBJECT } >= 2)
        }
    }

    /** The session read again, the filter over the list, its answer back in the list's register, then the stock code. */
    private fun assertFiltered(what: String, wrapped: BannerRow, before: List<Instruction>, after: List<Instruction>) {
        val at = wrapped.at
        val read = after[at]
        assertEquals("$what: session read", Opcode.IGET_OBJECT, read.opcode)
        assertEquals("$what: from the holder", wrapped.holder, (read as TwoRegisterInstruction).registerB)
        assertEquals("$what: the session field", wrapped.session.toString(), (read as ReferenceInstruction).reference.toString())
        val scratch = (read as OneRegisterInstruction).registerA
        val call = after[at + 1]
        assertEquals("$what: the filter", FILTER_BANNERS, call.referenceText())
        val arguments = (call as FiveRegisterInstruction).let { listOf(it.registerC, it.registerD, it.registerE).take(it.registerCount) }
        assertEquals("$what: list, profile ID, session", listOf(wrapped.list, wrapped.profileId, scratch), arguments)
        assertEquals("$what: answer", Opcode.MOVE_RESULT_OBJECT, after[at + 2].opcode)
        assertEquals("$what: into the list's register", wrapped.list, (after[at + 2] as OneRegisterInstruction).registerA)
        assertEquals("$what: then the wrapping", Opcode.NEW_INSTANCE, after[at + 3].opcode)
        assertEquals("$what: one filter", 1, after.count { it.referenceText() == FILTER_BANNERS })
        val stock = before.filter { it.opcode != Opcode.NOP }.map { it.kind() }
        val patched = (after.take(at) + after.drop(at + 3)).filter { it.opcode != Opcode.NOP }.map { it.kind() }
        assertEquals("$what: the rest is as it was", stock, patched)
    }

    /** An opcode, with a goto the insert widened read as the goto it was. */
    private fun Instruction.kind(): Opcode = when (opcode) {
        Opcode.GOTO_16, Opcode.GOTO_32 -> Opcode.GOTO
        else -> opcode
    }

    /** The kind stub reads the common class's field, and the viewer stub asks the session for its user ID. */
    private fun assertStubsFilled(patch: BytecodePatchContext, kind: BannerKind) {
        val stubs = patch.mutableClassDefBy(PROFILE_BANNERS).methods
        val type = stubs.single { it.name == BANNER_TYPE_STUB }.code()
        assertEquals(Opcode.CHECK_CAST, type[0].opcode)
        assertEquals(kind.base, type[0].referenceText())
        assertEquals(Opcode.IGET_OBJECT, type[1].opcode)
        val field = (type[1] as ReferenceInstruction).reference as FieldReference
        assertEquals(kind.reference, "${field.definingClass}->${field.name}:${field.type}")
        assertEquals(Opcode.RETURN_OBJECT, type[2].opcode)
        val viewer = stubs.single { it.name == VIEWER_ID_STUB }.code()
        assertEquals(BANNER_SESSION, viewer[0].referenceText())
        assertEquals("$BANNER_SESSION->getUserId()$string", viewer[1].referenceText())
        assertEquals(Opcode.MOVE_RESULT_OBJECT, viewer[2].opcode)
        assertEquals(Opcode.RETURN_OBJECT, viewer[3].opcode)
    }

    private fun classes(
        rows: Int = 1,
        wrapped: Boolean = true,
        kindFromEnum: Boolean = true,
        publicBase: Boolean = true,
        registers: Int = 8,
        session: Boolean = true,
        direct: Boolean = false,
    ): List<ClassDef> {
        val static = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
        val constructor = AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value
        val instance = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
        val kind = if (kindFromEnum) {
            """
                sget-object v1, $kindEnum->MUSE:$kindEnum
                invoke-virtual { v1 }, Ljava/lang/Object;->toString()$string
                move-result-object v1
            """
        } else {
            "const-string v1, \"MUSE\""
        }
        val made = if (direct) base else muse
        val builder = """
            const-string v0, "${MUSE_BANNER_EVENTS[0]}"
            const-string v0, "${MUSE_BANNER_EVENTS[1]}"
            const-string v0, "1234"
            $kind
            new-instance v2, $muse
            invoke-direct { v2, v0, v1 }, $made-><init>($string$string)V
            return-object v2
        """
        val sessionRead = if (session) {
            "iget-object v1, p1, $holder->session:$BANNER_SESSION"
        } else {
            "const/4 v1, 0x0"
        }
        val ending = if (wrapped) {
            """
                new-instance v1, $row
                invoke-direct { v1, v2, p2 }, $row-><init>($rowHeader$list)V
                return-object v1
            """
        } else {
            "return-object p2"
        }
        val rowBody = """
            const/4 v0, 0x0
            invoke-static { p0, v0 }, $museCall
            move-result-object v0
            if-eqz v0, :skip
            invoke-interface { p2, v0 }, $list->add($OBJECT_TYPE)Z
            :skip
            $sessionRead
            const-string v3, "2002"
            new-instance v2, $rowHeader
            invoke-direct { v2, v1, v3 }, $rowHeader-><init>($OBJECT_TYPE$string)V
            $ending
        """
        val rowParameters = listOf(context, holder, list)
        val rowsMade = (0 until rows).map { copy ->
            method(header, if (copy == 0) "row" else "row$copy", rowParameters, if (wrapped) row else list, registers, rowBody, static)
        }
        val featured = """
            const/4 v0, 0x0
            invoke-static { p0, v0 }, $museCall
            move-result-object v0
            new-instance v1, Ljava/util/ArrayList;
            return-object v1
        """
        val headerClass = classDef(header, "Ljava/lang/Object;", rowsMade + listOf(
            method(header, "muse", listOf(context, OBJECT_TYPE), muse, 5, builder, static),
            method(header, "featured", listOf(context), "Ljava/util/ArrayList;", 3, featured, static),
        ))
        val baseFlags = (if (publicBase) AccessFlags.PUBLIC.value else 0) or AccessFlags.ABSTRACT.value
        val baseClass = ImmutableClassDef(
            base, baseFlags, "Ljava/lang/Object;", null, null, null,
            listOf(ImmutableField(base, "id", string, instance, null, null, null), ImmutableField(base, "kind", string, instance, null, null, null)),
            listOf(method(base, "<init>", listOf(string, string), "V", 3, """
                invoke-direct { p0 }, Ljava/lang/Object;-><init>()V
                iput-object p1, p0, $base->id:$string
                iput-object p2, p0, $base->kind:$string
                return-void
            """, constructor)),
        )
        val museClass = classDef(muse, base, listOf(method(muse, "<init>", listOf(string, string), "V", 3, """
            invoke-direct { p0, p1, p2 }, $base-><init>($string$string)V
            return-void
        """, constructor)))
        val sessionClass = classDef(BANNER_SESSION, "Ljava/lang/Object;", listOf(
            method(BANNER_SESSION, "getUserId", emptyList(), string, 2, "const/4 v0, 0x0\nreturn-object v0", AccessFlags.PUBLIC.value),
        ))
        val holderClass = ImmutableClassDef(
            holder, AccessFlags.PUBLIC.value, "Ljava/lang/Object;", null, null, null,
            listOf(ImmutableField(holder, "session", BANNER_SESSION, instance, null, null, null)), emptyList(),
        )
        return listOf(headerClass, baseClass, museClass, sessionClass, holderClass, ExtensionDex.classDef(PROFILE_BANNERS))
    }

    /**
     * A links binder naming its trace section and asking the header data's field for its links, the
     * Compose row asking the same field the same question with the profile screen's header as its
     * third parameter, that header keeping the view model and the session, the view model keeping
     * the user, and the user's hashCode asking its ID getter.
     */
    private fun linksClasses(
        binder: Boolean = true,
        readers: Int = 1,
        jumpOnto: Boolean = false,
        session: Boolean = true,
        publicHeader: Boolean = true,
        headerWritten: Boolean = false,
        hashed: Boolean = true,
    ): List<ClassDef> {
        val static = AccessFlags.PUBLIC.value or AccessFlags.STATIC.value
        val instance = AccessFlags.PUBLIC.value or AccessFlags.FINAL.value
        val read = "iget-object v1, p0, $headerData->links:$linksState"
        val named = if (binder) BIND_LINKS else "ProfileLinksViewBinder.bindLinks"
        val binderClass = classDef(linksBinder, OBJECT_TYPE, listOf(method(linksBinder, "bind", listOf(headerData), "V", 3, """
            const-string v0, "$named"
            $read
            instance-of v0, v1, $linksModel
            return-void
        """, static)))
        // v0 asks, v1 holds the links, v2 the cast links; p0 is the data, p1 a flag, p2 (v5) the header.
        val rowBody = """
            const/4 v2, 0x0
            ${if (headerWritten) "const/4 v5, 0x0" else ""}
            $read
            ${if (jumpOnto) ":ask" else ""}
            instance-of v0, v1, $linksModel
            if-eqz v0, ${if (jumpOnto) ":ask" else ":none"}
            move-object v2, v1
            check-cast v2, $linksModel
            :none
            if-eqz p1, :end
            if-eqz v2, :end
            invoke-virtual { v2 }, $OBJECT_TYPE->toString()$string
            :end
            return-void
        """
        val rowClass = classDef(linksRow, OBJECT_TYPE, (0 until readers).map { copy ->
            method(linksRow, if (copy == 0) "draw" else "draw$copy", listOf(headerData, "Z", linksHeader), "V", 6, rowBody, static)
        })
        val headerClass = ImmutableClassDef(
            linksHeader, (if (publicHeader) AccessFlags.PUBLIC.value else 0) or AccessFlags.FINAL.value, OBJECT_TYPE, null, null, null,
            listOfNotNull(
                ImmutableField(linksHeader, "viewModel", VIEW_MODEL, instance, null, null, null),
                if (session) ImmutableField(linksHeader, "session", BANNER_SESSION, instance, null, null, null) else null,
            ),
            emptyList(),
        )
        val dataClass = ImmutableClassDef(
            headerData, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, OBJECT_TYPE, null, null, null,
            listOf(ImmutableField(headerData, "links", linksState, instance, null, null, null)), emptyList(),
        )
        val viewModelClass = ImmutableClassDef(
            VIEW_MODEL, AccessFlags.PUBLIC.value, OBJECT_TYPE, null, null, null,
            listOf(ImmutableField(VIEW_MODEL, "user", USER, instance, null, null, null)), emptyList(),
        )
        val hashCode = if (hashed) {
            """
                invoke-virtual { p0 }, $USER->getId()$string
                move-result-object v0
                invoke-virtual { v0 }, $string->hashCode()I
                move-result v0
                return v0
            """
        } else {
            """
                const/4 v0, 0x0
                return v0
            """
        }
        val userClass = ImmutableClassDef(
            USER, AccessFlags.PUBLIC.value, OBJECT_TYPE, null, null, null,
            listOf(ImmutableField(USER, "id", string, AccessFlags.PRIVATE.value, null, null, null)),
            listOf(
                method(USER, "getId", emptyList(), string, 2, "iget-object v0, p0, $USER->id:$string\nreturn-object v0", AccessFlags.PUBLIC.value),
                method(USER, "hashCode", emptyList(), "I", 2, hashCode, AccessFlags.PUBLIC.value),
            ),
        )
        val sessionClass = classDef(BANNER_SESSION, OBJECT_TYPE, listOf(
            method(BANNER_SESSION, "getUserId", emptyList(), string, 2, "const/4 v0, 0x0\nreturn-object v0", AccessFlags.PUBLIC.value),
        ))
        return listOf(
            binderClass, rowClass, headerClass, dataClass, classDef(linksState, OBJECT_TYPE, emptyList()),
            classDef(linksModel, linksState, emptyList()), viewModelClass, userClass, sessionClass, ExtensionDex.classDef(PROFILE_BANNERS),
        )
    }

    private fun method(
        owner: String,
        name: String,
        parameters: List<String>,
        returns: String,
        registers: Int,
        body: String,
        flags: Int,
    ): Method {
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(registers, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun classDef(type: String, superclass: String, methods: List<Method>): ClassDef =
        ImmutableClassDef(type, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, superclass, null, null, null, emptyList(), methods)

    private fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

    private fun Instruction.referenceText(): String? = (this as? ReferenceInstruction)?.reference?.toString()

    private companion object {
        const val OBJECT_TYPE = "Ljava/lang/Object;"
    }
}
