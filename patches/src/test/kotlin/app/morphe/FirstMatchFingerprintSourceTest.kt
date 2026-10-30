/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No patch takes a fingerprint's first match without knowing it's the only one.
 *
 * <p>`SomeFingerprint.method`, `.match()`, `.classDef` and the patcher's other one-answer reads hand
 * back the first method a fingerprint matches and say nothing when a retarget gives it a second, so
 * a patch could go on hooking whichever copy the new build lists first. The patches resolve through
 * `uniqueMethod`, which stops on none or more than one, or `matchAllOrNull`, which returns every
 * match for the patch to choose from. The first-match reads left are in [allowed], each with why it
 * can only ever find one method. Like [MergedReturnSourceTest], it's a net for the shapes spelled
 * out below, not a proof.
 */
class FirstMatchFingerprintSourceTest {
    /** Below these the scan has stopped finding the tree, or the fingerprints in it, and proves nothing. */
    private val fewestCredibleSources = 25
    private val fewestCredibleFingerprints = 50

    /** The patcher's reads that answer with one match. matchAll and matchAllOrNull aren't among them. */
    private val firstMatch = "(?:match|matchOrNull|method|methodOrNull|classDef|classDefOrNull|" +
        "originalMethod|originalMethodOrNull|originalClassDef|originalClassDefOrNull|" +
        "instructionMatches|instructionMatchesOrNull|stringMatches|stringMatchesOrNull)(?!\\w)"

    /** An object or val built as a fingerprint, up to its constructor's opening parenthesis. */
    private val declared = Regex("\\b(?:object|val|var)\\s+(\\w+)\\s*[:=]\\s*Fingerprint\\(")

    /** A parameter or property typed as a fingerprint. */
    private val typed = Regex("\\b(\\w+)\\s*:\\s*Fingerprint\\b(?!\\()")

    private val construction = Regex("\\bFingerprint\\(")

    /** A first-match read straight after a fingerprint's constructor closes. */
    private val readAfter = Regex("^\\s*\\??\\.\\s*($firstMatch)")

    /** The first-match reads that can only find one method, keyed by file and read, with why. */
    private val allowed = mapOf(
        "patches/shared/misc/extension/SharedExtensionPatch.kt: MorpheUtilsPatchesVersionFingerprint.method" to
            "HushGram's own Utils class, named in full, holds one method of that name",
        "patches/shared/misc/extension/SharedExtensionPatch.kt: MorpheUtilsBuildIdentityFingerprint.method" to
            "HushGram's own Utils class, named in full, holds one method of that name",
    )

    @Test
    fun noPatchTakesAFingerprintsFirstMatch() {
        val sources = patchSources()
        assertTrue(
            "only ${sources.size} sources were found, so this case proves nothing",
            sources.size >= fewestCredibleSources,
        )
        val texts = sources.associate { file ->
            file.path.replace('\\', '/').substringAfter("/app/morphe/") to file.readText()
        }
        val names = fingerprintNames(texts.values.map(::bare))
        assertTrue(
            "only ${names.size} fingerprints were found, so this case proves nothing",
            names.size >= fewestCredibleFingerprints,
        )

        val found = firstMatches(texts)
        assertEquals(
            "these take a fingerprint's first match, which turns a retarget that gives it a second " +
                "into a silent wrong hook. Use uniqueMethod, or matchAllOrNull and choose, or allow the " +
                "read here with why it can only find one method",
            emptyList<String>(),
            offenders(found, allowed.keys),
        )
        assertEquals(
            "these allowed reads aren't in the sources any more, so take them off the list",
            emptyList<String>(),
            (allowed.keys - found.toSet()).sorted(),
        )
    }

    /** The reads it has to find, and the comments, strings and checked reads it has to let through. */
    @Test
    fun theScanFindsTheReadsItIsLookingFor() {
        val declares = "internal object FooFingerprint : Fingerprint(\n" +
            "    strings = listOf(\"a (b\"),\n" +
            ")\n" +
            "val barFingerprint =\n" +
            "    Fingerprint(name = \"bar\")\n" +
            "val readers = Fingerprint(name = \"r\").matchAllOrNull().orEmpty()\n" +
            "val hit = Fingerprint(strings = listOf(\"x)\")).matchAllOrNull().orEmpty().single()\n"
        val uses = "/** The one method [FooFingerprint] matches; FooFingerprint.method would take the first. */\n" +
            "fun BytecodePatchContext.hook(fingerprint: Fingerprint) {\n" +
            "    // FooFingerprint.method in a comment isn't a read.\n" +
            "    uniqueMethod(PATCH, \"foo\", FooFingerprint).addInstruction(0, \"nop\")\n" +
            "    FooFingerprint.matchAllOrNull().orEmpty().single().method\n" +
            "    readers.first().method\n" +
            "    hit.method\n" +
            "    val types = \"video/*\"\n" +
            "    FooFingerprint.method.addInstruction(0, \"nop\")\n" +
            "    val styles = \"res/values*/styles.xml\"\n" +
            "    barFingerprint\n" +
            "        .classDefOrNull\n" +
            "    with(this) { fingerprint.originalMethod }\n" +
            "    Fingerprint(filters = listOf(literal(1L))).methodOrNull\n" +
            "    \"FooFingerprint.match in a string\"\n" +
            "}\n"
        assertEquals(
            listOf(
                "Uses.kt: Fingerprint(...).methodOrNull",
                "Uses.kt: FooFingerprint.method",
                "Uses.kt: barFingerprint.classDefOrNull",
                "Uses.kt: fingerprint.originalMethod",
            ),
            firstMatches(mapOf("Fingerprints.kt" to declares, "Uses.kt" to uses)).sorted(),
        )
        // An allowed read covers the one place it was allowed for, not a second copy of it.
        assertEquals(
            listOf("Uses.kt: FooFingerprint.method x2", "Uses.kt: barFingerprint.classDefOrNull x1"),
            offenders(
                listOf("Uses.kt: FooFingerprint.method", "Uses.kt: FooFingerprint.method", "Uses.kt: barFingerprint.classDefOrNull"),
                setOf("Uses.kt: FooFingerprint.method"),
            ),
        )
        assertEquals(emptyList<String>(), offenders(listOf("Uses.kt: FooFingerprint.method"), setOf("Uses.kt: FooFingerprint.method")))
    }

    /** The reads in [found] that aren't allowed, or are allowed but turn up more than once, with their counts. */
    private fun offenders(found: List<String>, allowedReads: Set<String>): List<String> =
        found.groupingBy { it }.eachCount()
            .filter { (read, count) -> read !in allowedReads || count > 1 }
            .map { (read, count) -> "$read x$count" }
            .sorted()

    /** Each first-match read in [sources], as "file: receiver.read", file by file. */
    private fun firstMatches(sources: Map<String, String>): List<String> {
        val bareSources = sources.mapValues { bare(it.value) }
        val names = fingerprintNames(bareSources.values)
        if (names.isEmpty()) return emptyList()
        val read = Regex("\\b(" + names.joinToString("|") + ")\\s*\\??\\.\\s*($firstMatch)")
        return bareSources.flatMap { (path, text) ->
            read.findAll(text).map { "$path: ${it.groupValues[1]}.${it.groupValues[2]}" } +
                construction.findAll(text).mapNotNull { built ->
                    val after = minOf(closing(text, built.range.last) + 1, text.length)
                    readAfter.find(text.substring(after))?.let { "$path: Fingerprint(...).${it.groupValues[1]}" }
                }
        }
    }

    /** The names declared or typed as fingerprints, leaving out vals that only keep a match. */
    private fun fingerprintNames(bareSources: Collection<String>): Set<String> = bareSources.flatMap { text ->
        declared.findAll(text)
            .filter { !followedByDot(text, closing(text, it.range.last)) }
            .map { it.groupValues[1] } +
            typed.findAll(text).map { it.groupValues[1] }
    }.toSet()

    private fun followedByDot(text: String, close: Int): Boolean {
        var at = close + 1
        while (at < text.length && text[at].isWhitespace()) at++
        return at < text.length && (text[at] == '.' || text.startsWith("?.", at))
    }

    /** Where the parenthesis opened at [open] closes in a [bare] source, or its end if it never does. */
    private fun closing(text: String, open: Int): Int {
        var depth = 0
        for (at in open until text.length) {
            when (text[at]) {
                '(' -> depth++
                ')' -> if (--depth == 0) return at
            }
        }
        return text.length
    }

    /**
     * [source] with its comments gone and every string and char literal emptied, so neither a KDoc
     * nor an error message counts as a read, and a glob in a string such as the MIME type for any
     * video doesn't open a comment.
     */
    private fun bare(source: String): String {
        val out = StringBuilder(source.length)
        var at = 0
        while (at < source.length) {
            when {
                source.startsWith("/*", at) -> {
                    out.append(' ')
                    at = source.indexOf("*/", at + 2).let { if (it < 0) source.length else it + 2 }
                }
                source.startsWith("//", at) -> at = source.indexOf('\n', at).let { if (it < 0) source.length else it }
                source[at] == '"' || source[at] == '\'' -> {
                    out.append("\"\"")
                    at = literalEnd(source, at)
                }
                else -> out.append(source[at++])
            }
        }
        return out.toString()
    }

    /** Where the string or char literal opening at [at] ends. */
    private fun literalEnd(text: String, at: Int): Int {
        if (text.startsWith("\"\"\"", at)) {
            return text.indexOf("\"\"\"", at + 3).let { if (it < 0) text.length else it + 3 }
        }
        val quote = text[at]
        var end = at + 1
        while (end < text.length && text[end] != quote && text[end] != '\n') {
            end += if (text[end] == '\\') 2 else 1
        }
        return minOf(end + 1, text.length)
    }

    private fun patchSources(): List<File> {
        val root = listOf(File("src/main/kotlin"), File("patches/src/main/kotlin"))
            .firstOrNull { it.isDirectory }
        assertTrue("no patch source tree was found from ${File(".").absolutePath}", root != null)
        return root!!.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }.toList()
    }
}
