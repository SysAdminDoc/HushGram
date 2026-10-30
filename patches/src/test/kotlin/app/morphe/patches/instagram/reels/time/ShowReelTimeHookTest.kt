/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.reels.time

import app.morphe.ExtensionDex
import app.morphe.PatchContexts
import app.morphe.patcher.extensions.InstructionExtensions.addInstructionsWithLabels
import app.morphe.patcher.patch.BytecodePatchContext
import app.morphe.patcher.patch.PatchException
import app.morphe.patcher.util.proxy.mutableTypes.MutableMethod
import app.morphe.patches.instagram.download.MEDIA
import app.morphe.patches.instagram.misc.extension.markers
import app.morphe.patches.instagram.reels.suggested.TO_CLIPS_ITEM
import app.morphe.util.namedRegisters
import com.android.tools.smali.dexlib2.AccessFlags
import com.android.tools.smali.dexlib2.Opcode
import com.android.tools.smali.dexlib2.builder.BuilderOffsetInstruction
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.Method
import com.android.tools.smali.dexlib2.iface.instruction.Instruction
import com.android.tools.smali.dexlib2.iface.instruction.NarrowLiteralInstruction
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
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

/** A method's code as a hook beside it must leave it: each instruction's opcode, what it refers to and the registers it names. */
internal typealias ReelTimeCode = List<Triple<Opcode, String?, List<Int>>>

class ShowReelTimeHookTest {
    private val component = "Lfixture/Component;"
    private val title = "Lfixture/Title;"
    private val reel = "Lfixture/Reel;"
    private val item = "Lfixture/ClipsItem;"
    private val state = "Lfixture/State;"
    private val subtitle = "Lfixture/Subtitle;"
    private val iconText = "Lfixture/IconText;"
    private val colors = "Lfixture/Colors;"
    private val column = "Lfixture/Column;"
    private val scope = "Lfixture/Scope;"
    private val converter = "Lfixture/Converter;"
    private val arrayList = "Ljava/util/ArrayList;"
    private val list = "Ljava/util/List;"
    private val string = "Ljava/lang/String;"
    private val mark = "Lfixture/Tracker;->mark(Ljava/lang/String;)V"
    private val iconTextShape = listOf(
        "Landroid/view/View\$OnClickListener;", CONFIG, SESSION, colors, "Ljava/lang/CharSequence;", "Ljava/lang/Integer;",
        "Lfixture/Extra;", "I", "Z",
    )

    // Its switch starts off, so simple mode picks it (DefaultSelectionPolicyTest).
    @Test
    fun simpleModePicksThePatchSinceItsSwitchStartsOff() {
        assertTrue(showReelTimePatch.default)
    }

    /**
     * The two hooks and the three stubs the patch fills are in the ReelPostTime the bundle ships,
     * public and static, and the line's state is a plain class with no interface until the patch
     * gives it Instagram's.
     */
    @Test
    fun theHooksAndStubsAreInTheExtension() {
        val declared = ExtensionDex.classDef(REEL_POST_TIME).methods
            .filter { AccessFlags.PUBLIC.isSet(it.accessFlags) && AccessFlags.STATIC.isSet(it.accessFlags) }
            .map { "${it.name}(${it.parameterTypes.joinToString("")})${it.returnType}" }
        for (wanted in listOf(ADD_TIME_LINE, TIME_LINE_TEXT, NO_CLICK, MEDIA_STUB, TAKEN_AT_STUB, COMPONENT_STUB)) {
            assertTrue("$wanted is not in the extension: $declared", wanted.substringAfter("->") in declared)
        }
        val line = ExtensionDex.classDef(REEL_TIME_LINE)
        assertFalse(
            "the extension can't make its line",
            AccessFlags.INTERFACE.isSet(line.accessFlags) || AccessFlags.ABSTRACT.isSet(line.accessFlags),
        )
        assertEquals("the line is no state of Instagram's as built", emptyList<String>(), line.interfaces.toList())
    }

    /**
     * The title hands itself and its column's parts to the extension just before it makes the
     * column, the subtitle's pick asks the extension for a line's words before anything else, the
     * title gains a subtitle maker taking the state to show, the line becomes one of Instagram's
     * subtitle states, the stubs read the title's post and its time, and nothing else changes.
     */
    @Test
    fun theTitleGetsALineOfItsOwnAndNothingElseChanges() {
        val input = classes()
        val context = PatchContexts.of(input)
        val before = input.associate { it.type to snapshot(it.methods) }
        val render = input.methodNamed(title, "render")
        val maker = input.methodNamed(title, "maker")
        val pick = input.methodNamed(subtitle, "pick")

        val sites = context.findReelTime()

        assertEquals(title, sites.title)
        assertEquals("the column's constructor", 12, sites.columnAt)
        assertEquals("the column's parts", 5, sites.list)
        assertEquals("the first local nothing reads after the column is made", 0, sites.scratch)
        assertEquals("the maker's marker, its read of the state and the register it fills", listOf(0, 3, 3), listOf(sites.markerAt, sites.stateAt, sites.stateRegister))
        assertEquals(state, sites.state)
        assertEquals(subtitle, sites.subtitle)
        assertEquals(listOf("config", "session"), listOf(sites.config.name, sites.session.name))
        assertEquals("the state is the pick's first parameter and the colors its second", listOf(0, 1), listOf(sites.stateParameter, sites.colorsParameter))
        assertEquals(iconText, sites.iconText)
        assertEquals(iconTextShape, sites.iconTextTakes)
        assertEquals(listOf("reel", "item", "post"), listOf(sites.reel.name, sites.item.name, sites.media.name))
        assertEquals("the getter for taken_at, not the other Long", "takenAt", sites.takenAt.name)
        for (classDef in input) {
            assertEquals("${classDef.type} was edited by the search", before[classDef.type], snapshot(context.mutableClassDefBy(classDef.type).methods))
        }

        context.showReelTime(sites)

        val titleMethods = context.mutableClassDefBy(title).methods
        assertRenderHook("the stand-in", titleMethods.single { it.name == "render" }, render.code(), sites)
        assertTimeLineMaker("the stand-in", titleMethods.filter { it.name == TIME_LINE_MAKER }, maker, sites)
        assertPickHook("the stand-in", context.mutableClassDefBy(subtitle).methods.single { it.name == "pick" }, pick.code(), sites)
        assertStubsFilled("the stand-in", context, sites)
        assertEquals("the line is a subtitle state", listOf(state), context.mutableClassDefBy(REEL_TIME_LINE).interfaces.toList())
        assertNothingElseChanged("the stand-in", before, context, sites)
    }

    /** A build the patch can't read fails at patch time, saying what it found, before anything is written. */
    @Test
    fun aBuildThePatchCantReadFailsBeforeAnythingChanges() {
        val shown = "$title->render($scope)$component"
        val cases = listOf(
            classes(renderMarks = 0) to "expected one method holding the $TITLE_RENDER marker, found none",
            classes(renderMarks = 2) to "expected one method holding the $TITLE_RENDER marker, found Lfixture/",
            classes(returnsTwice = true) to "expected $shown to answer its column in one place, found 2",
            classes(columnTakesList = false) to "expected the column $shown makes to take one list of parts, found [Z]",
            classes(base = 15) to "$shown keeps the column's parts in v16, past what the hook's call can name",
            classes(jumpToColumn = true) to "a jump enters $shown where it makes its column",
            classes(thisReused = true) to "the Reels title: $title->render writes over this",
            classes(makerFlag = "I") to "expected $title->maker(I)$subtitle to be an instance method taking one flag",
            classes(makerBranches = true) to "$title->maker(Z)$subtitle branches, so a copy of it can't be made a line at a time",
            classes(hasTimeLine = true) to "$title already has a $TIME_LINE_MAKER",
            classes(renderMakes = false) to "$shown doesn't make a subtitle",
            classes(makerMarks = 2) to "$title->maker(Z)$subtitle doesn't load its marker exactly once",
            classes(tracked = false) to "doesn't hand its marker to the tracker and leave it at that",
            classes(readsThroughReel = 2) to "to read one object through the title's reel, found 2",
            classes(reelFields = listOf("reel", "next")) to "expected $title to keep one reel of type $reel, found [next, reel]",
            classes(stateMethod = true) to "expected the subtitle's state $state to be a public interface with nothing to implement",
            classes(stateHanded = false) to "doesn't hand the state it read to the $subtitle it makes",
            classes(otherReader = true) to "to read the subtitle's state, found [",
            classes(pickFlag = state) to "to take the state $state once",
            classes(renderPicks = false) to "doesn't pick what it draws through $subtitle->pick(",
            classes(pickLoops = true) to "a jump or exception handler enters $subtitle->pick(",
            classes(pickLocals = 9) to "has 9 local register(s), and the hook needs 10",
            classes(constructors = 2) to "expected $iconText to have one constructor, found 2",
            classes(iconTextTakes = iconTextShape.dropLast(2) + listOf("Z", "I")) to "an object, an icon and a flag, found [",
            classes(iconTextPublic = false) to "$iconText or its constructor isn't public, so the subtitle can't make one",
            classes(pickColors = scope) to "to take the colors $colors once",
            classes(pickMakes = false) to "doesn't make $iconText itself",
            classes(iconTextExtends = OBJECT) to "$iconText isn't a $component, which",
            classes(intFields = listOf("icon", "width")) to "expected $iconText to keep one icon of type I, found [icon, width]",
            classes(iconBranches = false) to "doesn't leave the icon out when it is zero",
            classes(sessionFields = listOf("session", "viewer")) to "expected $subtitle to keep one session of type $SESSION, found [session, viewer]",
            classes(converters = 0) to "Reels item converter holding \"$TO_CLIPS_ITEM\" in this Instagram build, found none",
            classes(itemFields = listOf("item", "next")) to "expected $reel to keep one Reels item of type $item, found [item, next]",
            classes(mediaFields = emptyList()) to "expected $item to keep one post of type $MEDIA, found []",
            classes(reelPublic = false) to "$title->reel isn't public, so the extension can't reach it",
            classes(takenAtField = "taken_at_v2") to "expected one getter on $MEDIA answering $LONG for $TAKEN_AT_FIELD, found none",
            classes(takenAtPublic = false) to "$MEDIA->takenAt isn't public, so the extension can't reach it",
            classes(extension = false) to "the extension has no $REEL_POST_TIME",
            classes(line = false) to "the extension has no $REEL_TIME_LINE",
        )
        for ((input, expected) in cases) {
            val context = PatchContexts.of(input)
            val before = input.associate { it.type to snapshot(it.methods) }
            val failure = assertThrows(expected, PatchException::class.java) { context.showReelTime(context.findReelTime()) }
            assertTrue("$expected: ${failure.message}", failure.message!!.startsWith("Show a reel's exact time: ") && failure.message!!.contains(expected))
            for (classDef in input) {
                assertEquals(
                    "$expected: ${classDef.type} was edited before the refusal",
                    before[classDef.type], snapshot(context.mutableClassDefBy(classDef.type).methods),
                )
            }
            if (input.any { it.type == REEL_TIME_LINE }) {
                assertEquals("$expected: the line was made a state before the refusal", emptyList<String>(), context.mutableClassDefBy(REEL_TIME_LINE).interfaces.toList())
            }
        }
    }

    // ---- stand-ins shaped like Instagram 450's -------------------------------------------------

    /**
     * The Reels title, which fills a list with its subtitle and answers the column it makes from the
     * list; its subtitle maker, reading the state to show off the title's reel; the reel with its
     * state and its item; the item with its post; Media with its getters; the state, an interface
     * with nothing in it; the subtitle, whose render hands its state to the pick; the plain subtitle
     * text the pick makes; the converter answering the Reels item; and the extension.
     */
    private fun classes(
        renderMarks: Int = 1,
        returnsTwice: Boolean = false,
        columnTakesList: Boolean = true,
        base: Int = 4,
        jumpToColumn: Boolean = false,
        thisReused: Boolean = false,
        makerFlag: String = "Z",
        makerBranches: Boolean = false,
        hasTimeLine: Boolean = false,
        renderMakes: Boolean = true,
        makerMarks: Int = 1,
        tracked: Boolean = true,
        readsThroughReel: Int = 1,
        reelFields: List<String> = listOf("reel"),
        stateMethod: Boolean = false,
        stateHanded: Boolean = true,
        otherReader: Boolean = false,
        pickFlag: String = "Z",
        renderPicks: Boolean = true,
        pickLoops: Boolean = false,
        pickLocals: Int = 12,
        constructors: Int = 1,
        iconTextTakes: List<String> = iconTextShape,
        iconTextPublic: Boolean = true,
        pickColors: String = colors,
        pickMakes: Boolean = true,
        iconTextExtends: String = component,
        intFields: List<String> = listOf("icon"),
        iconBranches: Boolean = true,
        sessionFields: List<String> = listOf("session"),
        converters: Int = 1,
        itemFields: List<String> = listOf("item"),
        mediaFields: List<String> = listOf("post"),
        reelPublic: Boolean = true,
        takenAtField: String = TAKEN_AT_FIELD,
        takenAtPublic: Boolean = true,
        extension: Boolean = true,
        line: Boolean = true,
    ): List<ClassDef> {
        // The title's render: v3 is the list of parts, v2 the title, and the column is made in
        // v[base] from the list in v[base + 1] and a flag in v[base + 2].
        val renderBody = buildString {
            if (renderMarks > 0) {
                appendLine("const-string v0, \"${marker(TITLE_RENDER)}\"")
                appendLine("invoke-static { v0 }, $mark")
            }
            appendLine("move-object/from16 v2, p0")
            appendLine("new-instance v3, $arrayList")
            appendLine("invoke-direct { v3 }, $arrayList-><init>()V")
            appendLine("const/4 v1, 0x0")
            if (returnsTwice) {
                appendLine("if-eqz v1, :draw")
                appendLine("const/4 v0, 0x0")
                appendLine("return-object v0")
                appendLine(":draw")
            }
            if (thisReused) appendLine("move-object/from16 p0, v3")
            if (renderMakes) {
                appendLine("invoke-direct { v2, v1 }, $title->maker($makerFlag)$subtitle")
                appendLine("move-result-object v0")
                appendLine("invoke-virtual { v3, v0 }, $arrayList->add($OBJECT)Z")
            } else {
                appendLine("const/4 v0, 0x0")
                appendLine("const/4 v0, 0x0")
                appendLine("const/4 v0, 0x0")
            }
            appendLine("new-instance v$base, $column")
            if (columnTakesList) {
                appendLine("move-object/from16 v${base + 1}, v3")
                appendLine("const/16 v${base + 2}, 0x0")
                if (jumpToColumn) {
                    appendLine("if-eqz v1, :make")
                    appendLine("const/16 v${base + 2}, 0x1")
                    appendLine(":make")
                }
                appendLine("invoke-direct/range { v$base .. v${base + 2} }, $column-><init>(${list}Z)V")
            } else {
                appendLine("const/16 v${base + 1}, 0x0")
                appendLine("invoke-direct/range { v$base .. v${base + 1} }, $column-><init>(Z)V")
            }
            appendLine("return-object v$base")
        }
        // The maker: its marker goes to the tracker, the state is read off the title's reel into v3,
        // and the subtitle is made from the title's config and session, the state and the flag.
        val makerBody = buildString {
            repeat(makerMarks) { appendLine("const-string v0, \"${marker(SUBTITLE_MAKER)}\"") }
            if (tracked) appendLine("invoke-static { v0 }, $mark")
            appendLine("iget-object v0, p0, $title->reel:$reel")
            appendLine("iget-object v3, v0, $reel->state:$state")
            if (readsThroughReel > 1) appendLine("iget-object v4, v0, $reel->item:$item")
            appendLine("iget-object v1, p0, $title->config:$CONFIG")
            appendLine("iget-object v2, p0, $title->session:$SESSION")
            if (makerBranches) {
                appendLine("if-eqz p1, :plain")
                appendLine(":plain")
            }
            if (!stateHanded) appendLine("const/4 v4, 0x0")
            appendLine("new-instance v0, $subtitle")
            appendLine("invoke-direct { v0, v1, v2, ${if (stateHanded) "v3" else "v4"}, p1 }, $subtitle-><init>($CONFIG$SESSION$state$makerFlag)V")
            appendLine("return-object v0")
        }
        val titleMethods = listOf(
            method(title, "render", listOf(scope), component, base + 3, body = renderBody),
            method(title, "maker", listOf(makerFlag), subtitle, 6, public = false, body = makerBody),
        ) + if (hasTimeLine) listOf(method(title, TIME_LINE_MAKER, emptyList(), "V", 0, body = "return-void")) else emptyList()
        val titleClass = classDef(
            title, titleMethods, superclass = component, fieldsPublic = reelPublic,
            fields = reelFields.map { it to reel } + listOf("config" to CONFIG, "session" to SESSION),
        )
        val otherTitle = if (renderMarks > 1) listOf(classDef("Lfixture/OtherTitle;", listOf(
            method("Lfixture/OtherTitle;", "render", listOf(scope), component, 1, body = """
                const-string v0, "${marker(TITLE_RENDER)}"
                const/4 v0, 0x0
                return-object v0
            """),
        ))) else emptyList()

        val reelClass = classDef(reel, emptyList(), fields = listOf("state" to state) + itemFields.map { it to item })
        val itemClass = classDef(item, emptyList(), fields = mediaFields.map { it to MEDIA })
        val mediaClass = classDef(MEDIA, listOf(
            getter("takenAt", takenAtField, LONG, public = takenAtPublic),
            getter("deviceTimestamp", "device_timestamp", LONG),
            getter("caption", "caption", string),
        ))
        val stateClass = interfaceDef(state, if (stateMethod) listOf(abstractMethod(state, "kind", emptyList(), "I")) else emptyList())

        val pickTakes = listOf(state, pickColors, pickFlag)
        val pick = "$subtitle->pick(${pickTakes.joinToString("")})$component"
        val made = "$iconText-><init>(${iconTextTakes.joinToString("")})V"
        val subtitleRenderBody = buildString {
            appendLine("const-string v0, \"${marker(SUBTITLE_RENDER)}\"")
            appendLine("invoke-static { v0 }, $mark")
            appendLine("new-instance v1, $colors")
            appendLine("invoke-direct { v1 }, $colors-><init>()V")
            appendLine("iget-object v0, p0, $subtitle->state:$state")
            appendLine("const/4 v2, 0x0")
            if (renderPicks) {
                appendLine("invoke-direct { p0, v0, v1, v2 }, $pick")
                appendLine("move-result-object v0")
            }
            appendLine("return-object v0")
        }
        // The pick: for the one state the stand-in knows, the plain subtitle text, made from the
        // subtitle's config and session, the colors it was handed, the words and an icon.
        val pickBody = buildString {
            if (pickLoops) appendLine(":again")
            appendLine("const-string v0, \"${marker(SUBTITLE_PICK)}\"")
            appendLine("invoke-static { v0 }, $mark")
            appendLine("const/4 v1, 0x0")
            if (pickLoops) appendLine("if-nez v1, :again")
            if (pickMakes) {
                appendLine("iget-object v2, p0, $subtitle->config:$CONFIG")
                appendLine("iget-object v3, p0, $subtitle->session:$SESSION")
                appendLine("move-object/from16 v4, p2")
                appendLine("const-string v5, \"Original audio\"")
                appendLine("const/4 v6, 0x0")
                appendLine("const/4 v7, 0x0")
                appendLine("const/4 v8, 0x1")
                appendLine("const/4 v9, 0x0")
                appendLine("new-instance v0, $iconText")
                appendLine("invoke-direct/range { v0 .. v9 }, $made")
            } else {
                appendLine("const/4 v0, 0x0")
            }
            appendLine("return-object v0")
        }
        val subtitleClass = classDef(
            subtitle,
            listOf(
                method(subtitle, "render", listOf(scope), component, 4, body = subtitleRenderBody),
                method(subtitle, "pick", pickTakes, component, pickLocals, public = false, body = pickBody),
            ),
            superclass = component,
            fields = listOf("state" to state, "config" to CONFIG) + sessionFields.map { it to SESSION },
        )
        val otherReaderClass = if (otherReader) listOf(classDef("Lfixture/Peek;", listOf(
            method("Lfixture/Peek;", "peek", listOf(subtitle), state, 1, static = true, body = """
                iget-object v0, p0, $subtitle->state:$state
                return-object v0
            """),
        ))) else emptyList()

        val iconRenderBody = buildString {
            appendLine("const-string v0, \"${marker(ICON_TEXT_RENDER)}\"")
            appendLine("invoke-static { v0 }, $mark")
            appendLine("iget v0, p0, $iconText->icon:I")
            appendLine("const/4 v1, 0x0")
            if (iconBranches) {
                appendLine("if-eqz v0, :none")
                appendLine("const/4 v1, 0x1")
                appendLine(":none")
            }
            appendLine("return-object p0")
        }
        val iconTextClass = classDef(
            iconText,
            (0 until constructors).map { copy ->
                method(iconText, "<init>", if (copy == 0) iconTextTakes else listOf("I"), "V", 1, body = "return-void")
            } + method(iconText, "render", listOf(scope), component, 2, body = iconRenderBody),
            public = iconTextPublic, superclass = iconTextExtends, fields = intFields.map { it to "I" },
        )
        val componentClass = ImmutableClassDef(
            component, AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, OBJECT, null, null, null, null, emptyList<Method>(),
        )
        val converterClasses = (0 until converters).map {
            classDef(converter, listOf(method(converter, "convert", listOf("Lfixture/ServerItem;", SESSION), item, 1, static = true, body = """
                const-string v0, "$TO_CLIPS_ITEM"
                new-instance v0, $item
                invoke-direct { v0 }, $item-><init>()V
                return-object v0
            """)))
        }
        return listOf(componentClass, titleClass) + otherTitle + reelClass + itemClass + mediaClass + stateClass + subtitleClass +
            otherReaderClass + iconTextClass + converterClasses +
            (if (extension) listOf(ExtensionDex.classDef(REEL_POST_TIME)) else emptyList()) +
            (if (line) listOf(ExtensionDex.classDef(REEL_TIME_LINE)) else emptyList())
    }

    private fun marker(name: String) = "android_purge_26_q3_$name"

    private fun List<ClassDef>.methodNamed(type: String, name: String): Method = single { it.type == type }.methods.single { it.name == name }

    /** A Media getter reading [field] the way 450's do: by the hash of its name, loaded as a literal. */
    private fun getter(name: String, field: String, returns: String, public: Boolean = true): Method =
        method(MEDIA, name, emptyList(), returns, 1, public = public, body = """
            const v0, ${field.hashCode()}
            const/4 v0, 0x0
            return-object v0
        """)

    private fun method(
        owner: String,
        name: String,
        parameters: List<String>,
        returns: String,
        registers: Int,
        static: Boolean = false,
        public: Boolean = true,
        body: String,
    ): Method {
        var flags = if (public) AccessFlags.PUBLIC.value else AccessFlags.PRIVATE.value
        if (static) flags = flags or AccessFlags.STATIC.value
        if (name == "<init>") flags = flags or AccessFlags.CONSTRUCTOR.value
        val total = registers + (if (static) 0 else 1) + parameters.size
        val mutable = MutableMethod(
            ImmutableMethod(
                owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns, flags, null, null,
                ImmutableMethodImplementation(total, emptyList(), null, null),
            ),
        )
        mutable.addInstructionsWithLabels(0, body.trimIndent())
        return ImmutableMethod.of(mutable)
    }

    private fun abstractMethod(owner: String, name: String, parameters: List<String>, returns: String): Method =
        ImmutableMethod(
            owner, name, parameters.map { ImmutableMethodParameter(it, null, null) }, returns,
            AccessFlags.PUBLIC.value or AccessFlags.ABSTRACT.value, null, null, null,
        )

    private fun classDef(
        type: String,
        methods: List<Method>,
        public: Boolean = true,
        fields: List<Pair<String, String>> = emptyList(),
        fieldsPublic: Boolean = true,
        superclass: String = OBJECT,
    ): ClassDef = ImmutableClassDef(
        type, (if (public) AccessFlags.PUBLIC.value else 0) or AccessFlags.FINAL.value, superclass, null, null, null,
        fields.map { (name, fieldType) ->
            ImmutableField(type, name, fieldType, if (fieldsPublic) AccessFlags.PUBLIC.value else AccessFlags.PRIVATE.value, null, null, null)
        },
        methods,
    )

    private fun interfaceDef(type: String, methods: List<Method>): ClassDef = ImmutableClassDef(
        type, AccessFlags.PUBLIC.value or AccessFlags.INTERFACE.value or AccessFlags.ABSTRACT.value, OBJECT,
        null, null, null, null, methods,
    )

    companion object {
        private const val OBJECT = "Ljava/lang/Object;"
        private const val LONG = "Ljava/lang/Long;"
        private const val SESSION = "Lcom/instagram/common/session/UserSession;"
        private const val CONFIG = "Lcom/instagram/clips/intf/ClipsViewerConfig;"

        /** The extension's three stubs, whose bodies the patch writes. */
        private const val MEDIA_STUB = "$REEL_POST_TIME->media($OBJECT)$OBJECT"
        private const val TAKEN_AT_STUB = "$REEL_POST_TIME->takenAt($OBJECT)$LONG"
        private const val COMPONENT_STUB = "$REEL_POST_TIME->component($OBJECT$OBJECT)$OBJECT"

        /** How many instructions the hook puts in front of the pick. */
        private const val PICK_HOOK = 17

        /**
         * In front of the column's constructor the title goes into the scratch local, and it and
         * the list of the column's parts go to the extension: two instructions, with Instagram's
         * [original] code on both sides of them as it was, the column made from the same list next.
         */
        internal fun assertRenderHook(what: String, method: Method, original: List<Instruction>, sites: ReelTimeSites) {
            val code = method.code()
            val at = sites.columnAt
            assertEquals("$what: one call of the hook", 1, code.count { it.referenceText() == ADD_TIME_LINE })
            assertEquals("$what: the hook at $at", listOf("move-object/from16", "invoke-static $ADD_TIME_LINE"), code.subList(at, at + 2).map { it.text() })
            assertEquals("$what: the title into the scratch local", listOf(sites.scratch, method.self()), code[at].namedRegisters())
            assertEquals("$what: the title and the list of parts", listOf(sites.scratch, sites.list), code[at + 1].namedRegisters())
            assertEquals("$what: two instructions go in, nothing else", original.size + 2, code.size)
            assertEquals("$what: the code in front of the hook", shape(original.take(at)), shape(code.take(at)))
            assertEquals("$what: the code behind the hook", shape(original.drop(at)), shape(code.drop(at + 2)))
            val column = code[at + 2]
            assertEquals("$what: the column is made next", "<init>", (column.reference() as MethodReference).name)
            assertTrue("$what: from the list the hook was handed", sites.list in column.namedRegisters())
            assertTrue(
                "$what: the scratch v${sites.scratch} is a register the call can name that the column doesn't read",
                sites.scratch <= 15 && sites.list <= 15 && sites.scratch !in column.namedRegisters(),
            )
            assertEquals("$what: and answered", Opcode.RETURN_OBJECT, code[at + 3].opcode)
            assertEquals("$what: the column is what's answered", column.namedRegisters().first(), code[at + 3].namedRegisters().single())
        }

        /**
         * The one maker the patch adds to the title: public, taking the flag and the state, in one
         * more register than Instagram's [maker], with that maker's code less its marker and the
         * state moved in from the new parameter where the maker read it off the reel.
         */
        internal fun assertTimeLineMaker(what: String, added: List<Method>, maker: Method, sites: ReelTimeSites) {
            assertEquals("$what: one maker is added", 1, added.size)
            val method = added.single()
            assertEquals("$what: the added maker's flags", AccessFlags.PUBLIC.value or AccessFlags.FINAL.value, method.accessFlags)
            assertEquals("$what: it takes the flag and the state", listOf("Z", sites.state), method.parameterTypes.map(Any::toString))
            assertEquals("$what: it answers the subtitle", sites.subtitle, method.returnType)
            val registers = maker.implementation!!.registerCount
            assertEquals("$what: one more register, where the state lands", registers + 1, method.implementation!!.registerCount)
            val kept = maker.code().filterIndexed { at, _ -> at != sites.markerAt && at != sites.markerAt + 1 }
            val read = sites.stateAt - if (sites.stateAt > sites.markerAt) 2 else 0
            val code = method.code()
            assertEquals("$what: the maker's code without its marker and the tracker's call", kept.size, code.size)
            assertEquals("$what: the copy holds no marker", emptyList<String>(), method.markers())
            assertEquals("$what: the state is moved in", Opcode.MOVE_OBJECT_FROM16, code[read].opcode)
            assertEquals("$what: from the new parameter into the register the read filled", listOf(sites.stateRegister, registers), code[read].namedRegisters())
            assertEquals("$what: where the maker read it", Opcode.IGET_OBJECT, kept[read].opcode)
            assertEquals("$what: into the same register", sites.stateRegister, kept[read].namedRegisters().first())
            assertEquals(
                "$what: the rest is the maker's own",
                shape(kept.filterIndexed { at, _ -> at != read }), shape(code.filterIndexed { at, _ -> at != read }),
            )
        }

        /**
         * In front of everything in the pick, the state goes to the extension. No words is a state of
         * Instagram's and goes on to Instagram's [original] code. Words make Instagram's subtitle
         * text from the listener, the subtitle's own config and session, the colors the pick was
         * handed, the words, no width, no extra object, no icon and the flag off, which is answered.
         */
        internal fun assertPickHook(what: String, method: Method, original: List<Instruction>, sites: ReelTimeSites) {
            val code = method.code()
            val self = method.self()
            val takes = method.parameterTypes.map(Any::toString)
            fun parameter(index: Int) = self + 1 + takes.take(index).sumOf { it.width() }
            assertEquals(
                "$what: the hook",
                listOf(
                    "move-object/from16", "invoke-static $TIME_LINE_TEXT", "move-result-object", "if-eqz",
                    "invoke-static $NO_CLICK", "move-result-object", "move-object/from16",
                    "iget-object ${sites.subtitle}->${sites.config.name}:$CONFIG",
                    "iget-object ${sites.subtitle}->${sites.session.name}:$SESSION",
                    "move-object/from16", "const/4", "const/4", "const/4", "const/4", "new-instance ${sites.iconText}",
                    "invoke-direct/range ${sites.iconText}-><init>(${sites.iconTextTakes.joinToString("")})V", "return-object",
                ),
                code.take(PICK_HOOK).map { it.text() },
            )
            assertEquals("$what: the state into v0", listOf(0, parameter(sites.stateParameter)), code[0].namedRegisters())
            assertEquals("$what: the state is what the pick takes there", sites.state, takes[sites.stateParameter])
            assertEquals("$what: the state to the extension", listOf(0), code[1].namedRegisters())
            assertEquals("$what: the words into v5", listOf(5), code[2].namedRegisters())
            assertEquals("$what: the words are checked", listOf(5), code[3].namedRegisters())
            assertEquals("$what: no words goes to Instagram's first instruction", PICK_HOOK, (code[3] as BuilderOffsetInstruction).target.location.index)
            assertEquals("$what: the listener's call takes nothing", emptyList<Int>(), code[4].namedRegisters())
            assertEquals("$what: the listener into v1", listOf(1), code[5].namedRegisters())
            assertEquals("$what: the subtitle into v3", listOf(3, self), code[6].namedRegisters())
            assertEquals("$what: its config into v2", listOf(2, 3), code[7].namedRegisters())
            assertEquals("$what: its session into v3", listOf(3, 3), code[8].namedRegisters())
            assertEquals("$what: the colors into v4", listOf(4, parameter(sites.colorsParameter)), code[9].namedRegisters())
            assertEquals("$what: the colors are what the text takes fourth", sites.iconTextTakes[3], takes[sites.colorsParameter])
            for (at in 10..13) {
                assertEquals("$what: v${at - 4} is filled", listOf(at - 4), code[at].namedRegisters())
                assertEquals("$what: v${at - 4} is nothing", 0, (code[at] as NarrowLiteralInstruction).narrowLiteral)
            }
            assertEquals("$what: the text into v0", listOf(0), code[14].namedRegisters())
            assertEquals("$what: the text and its nine arguments, in order", (0..9).toList(), code[15].namedRegisters())
            assertEquals("$what: the text is answered", listOf(0), code[16].namedRegisters())
            assertEquals("$what: one call for the words", 1, code.count { it.referenceText() == TIME_LINE_TEXT })
            assertEquals("$what: one call for the listener", 1, code.count { it.referenceText() == NO_CLICK })
            assertEquals("$what: seventeen instructions go in front, nothing else", original.size + PICK_HOOK, code.size)
            assertEquals("$what: Instagram's code behind the hook", shape(original), shape(code.drop(PICK_HOOK)))
        }

        /**
         * The media stub answers the post of the title's reel, null for anything else and for a title
         * with no reel or item. The time stub calls the post's getter, and the component stub calls
         * the maker the patch added with the flag off.
         */
        internal fun assertStubsFilled(what: String, context: BytecodePatchContext, sites: ReelTimeSites) {
            val methods = context.mutableClassDefBy(REEL_POST_TIME).methods
            val reel = sites.reel.type
            val item = sites.item.type
            val media = methods.single { it.toString() == MEDIA_STUB }
            assertEquals("$what: the media stub's registers", 2, media.implementation!!.registerCount)
            assertEquals(
                "$what: the media stub",
                listOf(
                    "instance-of ${sites.title}", "if-nez", "const/4", "return-object", "check-cast ${sites.title}",
                    "iget-object ${sites.title}->${sites.reel.name}:$reel", "if-eqz",
                    "iget-object $reel->${sites.item.name}:$item", "if-eqz",
                    "iget-object $item->${sites.media.name}:$MEDIA", "return-object",
                ),
                media.code().map { it.text() },
            )
            assertEquals(
                "$what: a title goes on to its reel, and a missing reel or item to the one return",
                listOf(4, 10, 10),
                listOf(1, 6, 8).map { (media.code()[it] as BuilderOffsetInstruction).target.location.index },
            )
            val takenAt = methods.single { it.toString() == TAKEN_AT_STUB }
            assertEquals("$what: the time stub's registers", 1, takenAt.implementation!!.registerCount)
            assertEquals(
                "$what: the time stub",
                listOf("check-cast $MEDIA", "invoke-virtual $MEDIA->${sites.takenAt.name}()$LONG", "move-result-object", "return-object"),
                takenAt.code().map { it.text() },
            )
            val component = methods.single { it.toString() == COMPONENT_STUB }
            assertEquals("$what: the component stub's registers", 3, component.implementation!!.registerCount)
            assertEquals(
                "$what: the component stub",
                listOf(
                    "check-cast ${sites.title}", "check-cast ${sites.state}", "const/4",
                    "invoke-virtual ${sites.title}->$TIME_LINE_MAKER(Z${sites.state})${sites.subtitle}", "move-result-object", "return-object",
                ),
                component.code().map { it.text() },
            )
            assertEquals("$what: the title, the flag off and the line", listOf(1, 0, 2), component.code()[3].namedRegisters())
        }

        /**
         * Every method of the classes in [before] is as it was, but for the title's render, the
         * subtitle's pick and the extension's three stubs, and the one method there is more of is the
         * maker the title gained.
         */
        internal fun assertNothingElseChanged(what: String, before: Map<String, List<Pair<String, ReelTimeCode>>>, context: BytecodePatchContext, sites: ReelTimeSites) {
            val changed = setOf(sites.render.toString(), sites.pick.toString(), MEDIA_STUB, TAKEN_AT_STUB, COMPONENT_STUB)
            val added = "${sites.title}->$TIME_LINE_MAKER(Z${sites.state})${sites.subtitle}"
            for ((type, methods) in before) {
                val after = snapshot(context.mutableClassDefBy(type).methods)
                assertEquals(
                    "$what: $type's methods",
                    methods.map { it.first }.toSet() + if (type == sites.title) setOf(added) else emptySet(),
                    after.map { it.first }.toSet(),
                )
                assertEquals(
                    "$what: $type changed",
                    methods.filter { it.first !in changed }.toMap(),
                    after.filter { it.first !in changed && it.first != added }.toMap(),
                )
            }
        }

        internal fun snapshot(methods: Iterable<Method>): List<Pair<String, ReelTimeCode>> = methods.map { it.toString() to shape(it.code()) }

        internal fun shape(code: List<Instruction>): ReelTimeCode = code.map { Triple(it.opcode, it.reference()?.toString(), it.namedRegisters()) }

        internal fun Method.code(): List<Instruction> = implementation?.instructions?.toList().orEmpty()

        internal fun Instruction.reference() = (this as? ReferenceInstruction)?.reference

        /** An instruction as smali names it, with the type, field or method it refers to. */
        private fun Instruction.text() = listOfNotNull(opcode.name, referenceText()).joinToString(" ")

        private fun Instruction.referenceText(): String? = when (val reference = reference()) {
            is TypeReference -> reference.type
            is FieldReference, is MethodReference -> reference.toString()
            else -> null
        }

        /** The register an instance method keeps this in. */
        private fun Method.self() = implementation!!.registerCount - 1 - parameterTypes.sumOf { it.toString().width() }

        private fun String.width(): Int = if (this == "J" || this == "D") 2 else 1
    }
}
