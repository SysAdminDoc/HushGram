/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.extension

import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [classesLoadingString] finds a pooled string's askers through the patcher's literal index when the
 * pool answers the string for one number (#173). Every class that asks has to come back, however it
 * loads the number, in the app's order, and nothing that only looks like an ask.
 */
class ClassesLoadingStringTest {
    @Test
    fun everyClassAskingThePoolForTheOneNumberIsFoundInTheAppsOrder() {
        val context = PatchContexts.of(classes())
        assertEquals(
            listOf(POOL, HOLDER, ASKER, ADD_LITERAL, WIDER_CONST),
            context.classesLoadingString("wanted").map { it.type },
        )
    }

    @Test
    fun aStringThePoolAnswersForTwoNumbersFindsTheAskersOfEach() {
        val context = PatchContexts.of(classes())
        assertEquals(listOf(POOL, OTHER_NUMBER, THIRD_NUMBER), context.classesLoadingString("twice").map { it.type })
    }

    @Test
    fun aStringNoClassLoadsFindsNothing() {
        assertEquals(emptyList<String>(), PatchContexts.of(classes()).classesLoadingString("missing").map { it.type })
    }

    private companion object {
        const val POOL = "Lfixture/Pool;"
        const val OTHER_POOL = "Lfixture/OtherPool;"
        const val HOLDER = "Lfixture/Holder;"
        const val ASKER = "Lfixture/Asker;"
        const val OTHER_NUMBER = "Lfixture/OtherNumber;"
        const val LITERAL_ONLY = "Lfixture/LiteralOnly;"
        const val ADD_LITERAL = "Lfixture/AddLiteral;"
        const val APART = "Lfixture/Apart;"
        const val WIDER_CONST = "Lfixture/WiderConst;"
        const val THIRD_NUMBER = "Lfixture/ThirdNumber;"
        const val OBJECT = "Ljava/lang/Object;"
        const val STRING = "Ljava/lang/String;"

        fun method(owner: String, name: String, parameters: List<String>, returns: String, smali: String): ImmutableMethod {
            val method = MutableMethod(
                ImmutableMethod(
                    owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
                    AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
                    ImmutableMethodImplementation(3, emptyList(), null, null),
                ),
            )
            method.addInstructionsWithLabels(0, smali)
            return ImmutableMethod.of(method)
        }

        fun type(type: String, vararg methods: ImmutableMethod): ClassDef =
            ImmutableClassDef(type, AccessFlags.PUBLIC.value, OBJECT, null, null, null, null, methods.toList())

        /** A Redex pool: 0 "zero", 1 "wanted", 2 and 3 "twice". */
        fun pool(owner: String) = type(
            owner,
            method(
                owner, "A00", listOf("I"), STRING,
                """
                    packed-switch p0, :cases
                    const/4 v0, 0x0
                    return-object v0
                    :zero
                    const-string v0, "zero"
                    return-object v0
                    :wanted
                    const-string v0, "wanted"
                    return-object v0
                    :twice
                    const-string v0, "twice"
                    return-object v0
                    :cases
                    .packed-switch 0x0
                        :zero
                        :wanted
                        :twice
                        :twice
                    .end packed-switch
                """,
            ),
        )

        fun caller(owner: String, body: String) = type(owner, method(owner, "run", emptyList(), "V", body + "\nreturn-void"))

        fun ask(load: String, pool: String = POOL) = """
            $load
            invoke-static { v0 }, $pool->A00(I)$STRING
            move-result-object v0
        """

        fun classes(): List<ClassDef> = listOf(
            pool(POOL),
            type(OTHER_POOL, method(OTHER_POOL, "A00", listOf("I"), STRING, "const-string v0, \"elsewhere\"\nreturn-object v0")),
            caller(HOLDER, "const-string v0, \"wanted\""),
            caller(ASKER, ask("const/4 v0, 0x1")),
            caller(OTHER_NUMBER, ask("const/4 v0, 0x2")),
            // The number, but asked of another pool.
            caller(LITERAL_ONLY, ask("const/4 v0, 0x1", OTHER_POOL)),
            // Any instruction leaving the number in the call's register right before it counts.
            caller(ADD_LITERAL, ask("add-int/lit8 v0, v1, 0x1")),
            // The number loaded, then another one right before the call.
            caller(APART, "const/4 v0, 0x1\n" + ask("const/4 v1, 0x0")),
            caller(WIDER_CONST, ask("const/16 v0, 0x1")),
            caller(THIRD_NUMBER, ask("const/4 v0, 0x3")),
        )
    }
}
