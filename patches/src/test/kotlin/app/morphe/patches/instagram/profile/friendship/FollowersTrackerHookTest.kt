/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.profile.friendship

import app.morphe.ExtensionDex
import app.morphe.Fixtures
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.FixtureDex
import app.morphe.patches.shared.compat.AppCompatibilities
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableField
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableFieldReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Recent unfollowers (#208): the follow list's pager, its owner and the user's count and username, read with no request. */
class FollowersTrackerHookTest {
    @Test
    fun theStubsAreInTheExtension() {
        val declared = ExtensionDex.classDef(UNFOLLOWERS).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        val wanted = listOf(
            "seen(Ljava/lang/Object;Ljava/lang/Object;)V",
            "listHasMore(Ljava/lang/Object;)Ljava/lang/Boolean;",
            "listOwner(Ljava/lang/Object;)Ljava/lang/Object;",
            "followerCount(Ljava/lang/Object;)Ljava/lang/Integer;",
            "username(Ljava/lang/Object;)Ljava/lang/String;",
        )
        for (method in wanted) assertTrue("$method is not in the extension: $declared", method in declared)
    }

    @Test
    fun thePartsAreTheBuildersPagerAndOwner() {
        val context = PatchContexts.of(standIns())
        val found = context.findFollowersParts(order())
        assertEquals("$BUILDER->pager:$PAGER", found.pager.toString())
        assertEquals("hasMore", found.hasMore)
        assertEquals("$BUILDER->owner:$USER", found.owner.toString())
        assertEquals("count", found.followerCount)
        assertEquals("handle", found.username)

        context.followersStubs().fill(found)
        val extension = context.mutableClassDefBy(UNFOLLOWERS).methods
        fun reads(stub: String) = extension.single { it.name == stub }.implementation!!.instructions
            .mapNotNull { (it as? ReferenceInstruction)?.reference?.toString() }
        assertTrue("$BUILDER->pager:$PAGER" in reads("listHasMore"))
        assertTrue("$PAGER->hasMore()Z" in reads("listHasMore"))
        assertTrue("$BUILDER->owner:$USER" in reads("listOwner"))
        assertTrue("$USER->count()Ljava/lang/Integer;" in reads("followerCount"))
        assertTrue("$USER->handle()Ljava/lang/String;" in reads("username"))
    }

    @Test
    fun noPagerFailsTheParts() = assertRefused(standIns(pagerLoads = false), "one public pager")

    @Test
    fun twoQuestionsOfThePagerFailTheParts() = assertRefused(standIns(asksFailed = true), "one question")

    @Test
    fun noOwnerFailsTheParts() = assertRefused(standIns(ownerField = false), "one public $USER")

    @Test
    fun noFollowerCountFailsTheParts() = assertRefused(standIns(countKey = "following_count"), FOLLOWER_COUNT)

    /** On each declared build the parts are found in the row builder's own class, and the stubs fill. */
    @Test
    fun eachDeclaredBuildFindsThePager() {
        val versions = AppCompatibilities.instagram().single().targets.mapNotNull { it.version }.toSet()
        var checked = 0
        for (version in versions) {
            for (bundle in Fixtures.files { it.extension == "apks" && it.name.contains("-$version-") }) {
                builds(bundle, bundle.name)
                checked++
            }
        }
        assertTrue("no fixture of a declared build", checked > 0)
    }

    @Test
    fun eachOtherBuildFindsThePagerToo() {
        for (base in Fixtures.otherBuilds()) builds(base, base.parentFile.name)
    }

    private fun builds(bundle: File, label: String) {
        val builders = FixtureDex.classesLoading(bundle, FOLLOW_LIST_ADDED)
        val builderClass = builders.single { classDef -> classDef.methods.any { it.loads(FOLLOW_LIST_ADDED) } }
        val builder = builderClass.methods.single { it.loads(FOLLOW_LIST_ADDED) }
        val wanted = builderClass.fields.map { it.type }.filter { it.startsWith("L") }.toSet() + USER
        val classes = (listOf(builderClass) + FixtureDex.classes(bundle, wanted).values + ExtensionDex.classDef(UNFOLLOWERS))
            .map { ImmutableClassDef.of(it) }.distinctBy { it.type }
        val context = PatchContexts.of(classes)
        val order = FollowOrder(builderClass.type, builder.name, builder.parameterTypes.map(CharSequence::toString), 0, 0, 0,
            ImmutableFieldReference(builderClass.type, "binder", OBJECT))

        val found = context.findFollowersParts(order)
        val pager = classes.single { it.type == found.pager.type }
        assertTrue("$label: the pager is an interface", AccessFlags.INTERFACE.isSet(pager.accessFlags))
        assertTrue("$label: it declares what's asked", pager.methods.any { it.name == found.hasMore && it.returnType == "Z" })
        assertNotEquals("$label: whether there's more, not whether it's loading", IS_LOADING, found.hasMore)
        assertEquals("$label: the owner", USER, found.owner.type)
        context.followersStubs().fill(found)
    }

    private fun Method.loads(value: String) = implementation?.instructions?.any {
        ((it as? ReferenceInstruction)?.reference as? StringReference)?.string == value
    } == true

    private fun assertRefused(classes: List<ClassDef>, why: String) {
        val context = PatchContexts.of(classes)
        val refusal = assertThrows(PatchException::class.java) { context.findFollowersParts(order()) }
        assertTrue(refusal.message, refusal.message!!.startsWith("$PATCH: ") && why in refusal.message!!)
        assertEquals(null, context.followersPartsOrWarn(order()))
    }

    private companion object {
        const val BUILDER = "Lfixture/ListBuilder;"
        const val PAGER = "Lfixture/Pager;"

        fun order() = FollowOrder(BUILDER, "build", listOf("Z", "Z"), 0, 0, 0, ImmutableFieldReference(BUILDER, "binder", OBJECT))

        /** Shaped as on 451: the row builder asks its pager whether there's more before a Load more row. */
        fun standIns(
            pagerLoads: Boolean = true,
            asksFailed: Boolean = false,
            ownerField: Boolean = true,
            countKey: String = FOLLOWER_COUNT,
        ): List<ClassDef> {
            val build = method(
                BUILDER, "build", listOf("Z", "Z"), "V", 4,
                """
                    iget-object v0, p0, $BUILDER->pager:$PAGER
                    invoke-interface { v0 }, $PAGER->hasMore()Z
                    move-result v1
                    ${if (asksFailed) "invoke-interface { v0 }, $PAGER->failed()Z" else ""}
                    invoke-interface { v0 }, $PAGER->isLoading()Z
                    return-void
                """,
                static = false,
            )
            val pagerMethods = listOfNotNull(
                if (pagerLoads) abstract(PAGER, IS_LOADING) else null,
                abstract(PAGER, "hasMore"),
                abstract(PAGER, "failed"),
            )
            val fields = listOfNotNull(
                field(BUILDER, "users", "Ljava/util/List;"),
                field(BUILDER, "pager", PAGER),
                if (ownerField) field(BUILDER, "owner", USER) else null,
            )
            return listOf(
                ImmutableClassDef(BUILDER, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, OBJECT, null, null, null, fields, listOf(build)),
                ImmutableClassDef(
                    PAGER, AccessFlags.PUBLIC.value or AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value, OBJECT,
                    null, null, null, null, pagerMethods,
                ),
                ImmutableClassDef(
                    USER, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, OBJECT, null, null, null, null,
                    listOf(keyed("count", countKey, "Ljava/lang/Integer;"), keyed("handle", USERNAME, STRING)),
                ),
                ExtensionDex.classDef(UNFOLLOWERS),
            )
        }

        /** A user model getter reading the server's [key] by its hash, as Instagram's do. */
        private fun keyed(name: String, key: String, returns: String) = method(
            USER, name, emptyList(), returns, 2,
            """
                const v0, ${key.hashCode()}
                const/4 v0, 0x0
                return-object v0
            """,
            static = false,
        )

        private fun abstract(type: String, name: String) = ImmutableMethod(
            type, name, emptyList(), "Z", AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, null, null, null,
        )

        private fun method(
            type: String, name: String, parameters: List<String>, returns: String, registers: Int, body: String,
            static: Boolean = true,
        ): Method {
            val flags = AccessFlags.PUBLIC.value or (if (static) AccessFlags.STATIC.value else AccessFlags.FINAL.value)
            return ImmutableMethod.of(
                MutableMethod(
                    ImmutableMethod(
                        type, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                        ImmutableMethodImplementation(registers, emptyList(), null, null),
                    ),
                ).apply { addInstructionsWithLabels(0, body.trimIndent()) },
            )
        }

        private fun field(type: String, name: String, of: String) =
            ImmutableField(type, name, of, AccessFlags.PUBLIC.value, null, null, null)
    }
}
