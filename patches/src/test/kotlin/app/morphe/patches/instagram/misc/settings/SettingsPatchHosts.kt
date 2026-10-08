/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.settings

import app.morphe.patches.instagram.misc.extension.INSTAGRAM_APPLICATION
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableClassDef
import com.android.tools.smali.dexlib2.immutable.ImmutableMethod
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodImplementation
import com.android.tools.smali.dexlib2.immutable.ImmutableMethodParameter
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction10x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11n
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction11x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction12x
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21s
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction21t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction31t
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableInstruction35c
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutablePackedSwitchPayload
import com.android.tools.smali.dexlib2.immutable.instruction.ImmutableSwitchElement
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableMethodReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableStringReference
import com.android.tools.smali.dexlib2.immutable.reference.ImmutableTypeReference

/**
 * Stand-ins for the Instagram classes the settings patch hooks, for a test that runs the whole
 * patch over a few classes instead of an APK: the application with its onCreate, the main
 * activity with its onNewIntent, and the settings screen with its factory and onCreateView.
 */
internal object SettingsPatchHosts {
    const val SETTINGS_SCREEN = "Lfixture/SettingsScreenFragment;"
    const val STATE_HOST = "Lfixture/BaseActivity;"

    fun all(): List<ClassDef> {
        val navigation = NavigationEntryHosts.classes()
        val main = mainActivity()
        val factory = navigation.single { it.type == MAIN_ACTIVITY }
        val combined = ImmutableClassDef(main.type, main.accessFlags, main.superclass, main.interfaces,
            main.sourceFile, main.annotations, main.fields, main.methods + factory.methods)
        return listOf(application(), combined, settingsScreen(), stateHost()) + navigation.filter { it.type != MAIN_ACTIVITY }
    }

    fun stateHost(): ClassDef {
        val remove = ImmutableMethodReference("Landroid/os/BaseBundle;", "remove", listOf("Ljava/lang/String;"), "V")
        return ImmutableClassDef(
            STATE_HOST, AccessFlags.PUBLIC.value, "Landroid/app/Activity;", null, null, null, null,
            listOf(ImmutableMethod(
                STATE_HOST, "createInternal", listOf(ImmutableMethodParameter("Landroid/os/Bundle;", null, null)),
                "V", AccessFlags.PUBLIC.value, null, null,
                ImmutableMethodImplementation(4, listOf(
                    ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("IgFragmentActivity.internalOnCreate")),
                    ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(".internalOnCreate")),
                    ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("android:fragments")),
                    ImmutableInstruction12x(Opcode.MOVE_OBJECT, 1, 3),
                    ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 1, 0, 0, 0, 0, remove),
                    ImmutableInstruction35c(Opcode.INVOKE_VIRTUAL, 2, 1, 0, 0, 0, 0, remove),
                    ImmutableInstruction10x(Opcode.RETURN_VOID),
                ), null, null),
            )),
        )
    }

    const val KEY_POOL = "Lfixture/Strings;"
    const val SCREEN_KEY = 5
    const val SESSION_KEY = 6
    val POOL_CALL = ImmutableMethodReference(KEY_POOL, "A00", listOf("I"), "Ljava/lang/String;")

    /**
     * A pool of shared strings as 450's Redex writes it: a static (int)String switch answering
     * "screen_id" for [SCREEN_KEY] and [session] for [SESSION_KEY]. p0 is v1; the nop puts the
     * payload on an even address.
     */
    fun keyPool(session: String = "new_settings_session"): ClassDef = ImmutableClassDef(
        KEY_POOL, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Ljava/lang/Object;", null, null, null, null,
        listOf(ImmutableMethod(
            KEY_POOL, POOL_CALL.name, listOf(ImmutableMethodParameter("I", null, null)), "Ljava/lang/String;",
            AccessFlags.PUBLIC.value or AccessFlags.STATIC.value, null, null,
            ImmutableMethodImplementation(2, listOf(
                ImmutableInstruction31t(Opcode.PACKED_SWITCH, 1, 12),
                ImmutableInstruction11n(Opcode.CONST_4, 0, 0),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("screen_id")),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference(session)),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                ImmutableInstruction10x(Opcode.NOP),
                ImmutablePackedSwitchPayload(listOf(ImmutableSwitchElement(SCREEN_KEY, 5), ImmutableSwitchElement(SESSION_KEY, 8))),
            ), null, null),
        )),
    )

    /**
     * A settings screen shaped like Instagram 449's: a static factory that puts the two keys in the
     * arguments and answers an instance, and an onCreateView answering a view from v1, reached
     * from a branch at 1 and falling through from 2 when [branched], or straight after it's made.
     * With [pooledKeys] the factory asks [keyPool] for both keys, as 450's 385611400 does.
     */
    fun settingsScreen(branched: Boolean = false, pooledKeys: Boolean = false): ClassDef {
        val view = ImmutableTypeReference("Landroid/view/View;")
        val keys = if (pooledKeys) {
            listOf(SCREEN_KEY, SESSION_KEY).flatMap { number ->
                listOf(
                    ImmutableInstruction21s(Opcode.CONST_16, 0, number),
                    ImmutableInstruction35c(Opcode.INVOKE_STATIC, 1, 0, 0, 0, 0, 0, POOL_CALL),
                    ImmutableInstruction11x(Opcode.MOVE_RESULT_OBJECT, 0),
                )
            }
        } else {
            listOf(
                ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("screen_id")),
                ImmutableInstruction21c(Opcode.CONST_STRING, 0, ImmutableStringReference("new_settings_session")),
            )
        }
        val createView = if (branched) {
            listOf(
                ImmutableInstruction21c(Opcode.NEW_INSTANCE, 1, view),
                ImmutableInstruction21t(Opcode.IF_EQZ, 4, 4),
                ImmutableInstruction21c(Opcode.NEW_INSTANCE, 1, view),
                ImmutableInstruction11x(Opcode.RETURN_OBJECT, 1),
            )
        } else {
            listOf(ImmutableInstruction21c(Opcode.NEW_INSTANCE, 1, view), ImmutableInstruction11x(Opcode.RETURN_OBJECT, 1))
        }
        return ImmutableClassDef(
            SETTINGS_SCREEN, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, "Landroidx/fragment/app/Fragment;",
            null, null, null, null,
            listOf(
                ImmutableMethod(
                    SETTINGS_SCREEN, "A01",
                    listOf(ImmutableMethodParameter("LX/0PKz;", null, null), ImmutableMethodParameter("Ljava/lang/String;", null, null)),
                    SETTINGS_SCREEN, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value or AccessFlags.FINAL.value, null, null,
                    ImmutableMethodImplementation(
                        3,
                        keys + listOf(
                            ImmutableInstruction21c(Opcode.NEW_INSTANCE, 0, ImmutableTypeReference(SETTINGS_SCREEN)),
                            ImmutableInstruction11x(Opcode.RETURN_OBJECT, 0),
                        ),
                        null, null,
                    ),
                ),
                ImmutableMethod(
                    SETTINGS_SCREEN, "onCreateView",
                    listOf("Landroid/view/LayoutInflater;", "Landroid/view/ViewGroup;", "Landroid/os/Bundle;")
                        .map { ImmutableMethodParameter(it, null, null) },
                    "Landroid/view/View;", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                    ImmutableMethodImplementation(6, createView, null, null),
                ),
            ),
        )
    }

    private fun application(): ClassDef {
        val returns = ImmutableInstruction10x(Opcode.RETURN_VOID)
        return ImmutableClassDef(
            INSTAGRAM_APPLICATION, AccessFlags.PUBLIC.value, "Landroid/app/Application;", null, null, null, null,
            listOf(
                ImmutableMethod(
                    INSTAGRAM_APPLICATION, "onCreate", emptyList(), "V", AccessFlags.PUBLIC.value, null, null,
                    ImmutableMethodImplementation(1, listOf(returns), null, null),
                ),
            ),
        )
    }

    private fun mainActivity(): ClassDef {
        val returns = ImmutableInstruction10x(Opcode.RETURN_VOID)
        return ImmutableClassDef(
            MAIN_ACTIVITY, AccessFlags.PUBLIC.value, STATE_HOST, null, null, null, null,
            listOf(
                ImmutableMethod(
                    MAIN_ACTIVITY, "onNewIntent", listOf(ImmutableMethodParameter("Landroid/content/Intent;", null, null)),
                    "V", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null,
                    ImmutableMethodImplementation(2, listOf(returns), null, null),
                ),
            ),
        )
    }
}
