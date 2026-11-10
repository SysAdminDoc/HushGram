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

    @Test
    fun theHookIsInTheExtension() {
        val declared = ExtensionDex.classDef(PROFILE_BANNERS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (wanted in listOf(FILTER_BANNERS.substringAfter("->"), "$BANNER_TYPE_STUB($OBJECT_TYPE)$string", "$VIEWER_ID_STUB($OBJECT_TYPE)$string")) {
            assertTrue("$wanted is not in the extension: $declared", wanted in declared)
        }
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
