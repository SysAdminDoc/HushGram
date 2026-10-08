/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.settings

import app.morphe.patcher.extensions.InstructionExtensions.addInstructions
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.immutable.*

/** Two native proxy variants and their common factory. No native identities are needed. */
internal object NavigationEntryHosts {
    const val TAB = "Lfixture/NavigationTab;"
    const val PROXY = "Lfixture/NavigationProxy;"
    const val PLAIN = "Lfixture/PlainNavigationProxy;"
    const val LITHO = "Lfixture/LithoNavigationProxy;"
    const val LISTENER = "Landroid/view/View\$OnLongClickListener;"
    const val VIEW = "Landroid/view/View;"

    fun method(owner: String, name: String, parameters: List<String>, result: String, registers: Int, body: String,
               flags: Int = AccessFlags.PUBLIC.value): MutableMethod = MutableMethod(ImmutableMethod(
        owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, result, flags, null, null,
        ImmutableMethodImplementation(registers, emptyList(), null, null),
    )).apply { addInstructions(0, body) }

    /**
     * With [activityLongPress], the activity also sets a long press of its own straight on a view,
     * the way 450 gives the Profile button its account switcher, behind a branch that jumps to it.
     */
    fun classes(activityLongPress: Boolean = false): List<ClassDef> {
        val tab = type(TAB, "Ljava/lang/Enum;", emptyList(), listOf(method(TAB, "<clinit>", emptyList(), "V", 1, """
            const-string v0, "FEED"
            const-string v0, "CLIPS"
            const-string v0, "clips_viewer_clips_tab"
            return-void
        """, AccessFlags.STATIC.value or AccessFlags.CONSTRUCTOR.value)))
        val proxy = type(PROXY, "Ljava/lang/Object;", listOf(ImmutableField(PROXY, "tab", TAB,
            AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null)), listOf(method(PROXY,
            "<init>", listOf("Landroid/content/Context;", "Lcom/instagram/common/session/UserSession;", "Lfixture/TabData;", TAB),
            "V", 6, "iput-object p4, p0, $PROXY->tab:$TAB\nreturn-void", AccessFlags.PUBLIC.value or AccessFlags.CONSTRUCTOR.value)))
        val variants = listOf(PLAIN to VIEW, LITHO to "Lcom/facebook/litho/LithoView;").map { (owner, viewType) ->
            val field = ImmutableField(owner, "button", viewType, AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, null, null, null)
            type(owner, PROXY, listOf(field), listOf(
                method(owner, "view", emptyList(), VIEW, 2, "iget-object v0, p0, $owner->button:$viewType\nreturn-object v0"),
                method(owner, "longPress", listOf(LISTENER), "V", 3, """
                    iget-object v0, p0, $owner->button:$viewType
                    invoke-virtual {v0, p1}, $VIEW->setOnLongClickListener($LISTENER)V
                    return-void
                """),
            ))
        }
        val factory = method(MAIN_ACTIVITY, "makeTab", listOf("Landroid/view/ViewGroup;", MAIN_ACTIVITY, TAB,
            "Lfixture/TabData;", "Lkotlin/jvm/functions/Function1;", "I", "Z", "Z"), VIEW, 18, """
            move-object/from16 v2, p2
            const-string v0, "$TAB_FACTORY"
            new-instance v0, $PLAIN
            const/4 v1, 0
            ${List(6) { "invoke-virtual {v0, v1}, $PROXY->longPress($LISTENER)V" }.joinToString("\n")}
            invoke-virtual {v0}, $PROXY->view()$VIEW
            move-result-object v1
            return-object v1
        """, AccessFlags.PUBLIC.value or AccessFlags.STATIC.value)
        val switcher = method(MAIN_ACTIVITY, "showSwitcher", listOf(VIEW, LISTENER), "V", 4, """
            if-nez p1, :set
            return-void
            :set
            invoke-virtual {p1, p2}, $VIEW->setOnLongClickListener($LISTENER)V
            const/4 p2, 0
            return-void
        """)
        val activity = if (activityLongPress) listOf(factory, switcher) else listOf(factory)
        return listOf(tab, proxy) + variants + type(MAIN_ACTIVITY, "Landroid/app/Activity;", emptyList(), activity)
    }

    fun type(owner: String, parent: String, fields: List<ImmutableField>, methods: List<com.android.tools.smali.dexlib2.iface.Method>): ClassDef =
        ImmutableClassDef(owner, AccessFlags.PUBLIC.value, parent, null, null, null, fields, methods)
}
