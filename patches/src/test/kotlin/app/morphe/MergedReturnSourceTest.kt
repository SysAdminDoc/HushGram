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
 * No stub the patches fill lets a null check and a field read meet at one `return-object`, unless
 * the stub answers an Object.
 *
 * <p>`if-eqz p0, :none` followed by `iget-object p0, ...` and `:none return-object p0` leaves two
 * types in p0 where the paths meet: the null-checked holder and the field. ART's verifier merges
 * them to their common superclass, Object, and rejects the whole class when the stub declares a
 * narrower answer such as String. ChatLocks.threadId was written that way, and Instagram stopped at
 * startup with a VerifyError on an emulator that had Lock single chats on, while the unit tests and
 * every fixture check passed: nothing off a device runs ART's verifier. The fix is a return of its
 * own on the null path (`const/4 p0, 0x0`), which is what the String stubs do.
 *
 * <p>Stubs that answer an Object may keep the shared return, and say so in a comment just above
 * their smali ("Answers an Object"), which is how this scan tells them apart. Like
 * [ConstantReturnSourceTest], it's a net for the shape spelled out below, not a proof.
 */
class MergedReturnSourceTest {
    /** Below this the scan has stopped finding the tree and the case proves nothing. */
    private val fewestCredibleSources = 25

    /** Every block string, wherever it's handed to the patcher from. */
    private val block = Regex("\"\"\"(.*?)\"\"\"", RegexOption.DOT_MATCHES_ALL)

    /** A null check that jumps to a label. */
    private val nullBranch = Regex("^if-eqz ([vp]\\d+), :(\\w+)$", RegexOption.MULTILINE)

    /** The note that marks a stub answering an Object, written above its smali. */
    private val answersObject = Regex("[Aa]nswers? an Object")

    @Test
    fun noStubMergesANullCheckIntoANarrowerReturn() {
        val sources = patchSources()
        assertTrue(
            "only ${sources.size} sources were found, so this case proves nothing",
            sources.size >= fewestCredibleSources,
        )

        val offenders = sources.flatMap { file ->
            mergedReturns(file.readText()).map {
                file.path.replace('\\', '/').substringAfter("/app/morphe/") + ": " + it
            }
        }

        assertEquals(
            "these stubs let a null check and a later read of the same register meet at one " +
                "return-object, which ART rejects unless the stub answers an Object. Give the null " +
                "path its own const/4 and return, or say \"Answers an Object\" above the smali when " +
                "it does: " + offenders,
            emptyList<String>(),
            offenders,
        )
    }

    /** The shape that crashed, the fixed one, and the two that have to go on being allowed. */
    @Test
    fun theScanFindsTheShapeItIsLookingFor() {
        val triple = "\"\"\""
        val crashed = "    val bridgeBody = " + triple + "\n" +
            "        check-cast p0, LX/screen;\n" +
            "        invoke-virtual { p0 }, LX/screen;->key()LX/key;\n" +
            "        move-result-object p0\n" +
            "        if-eqz p0, :none\n" +
            "        iget-object p0, p0, LX/key;->id:Ljava/lang/String;\n" +
            "        :none\n" +
            "        return-object p0\n" +
            "    " + triple + ".trimIndent()\n"
        assertEquals(listOf("p0 at :none"), mergedReturns(crashed))

        val fixed = "    val bridgeBody = " + triple + "\n" +
            "        move-result-object p0\n" +
            "        if-nez p0, :key\n" +
            "        const/4 p0, 0x0\n" +
            "        return-object p0\n" +
            "        :key\n" +
            "        iget-object p0, p0, LX/key;->id:Ljava/lang/String;\n" +
            "        return-object p0\n" +
            "    " + triple + ".trimIndent()\n"
        assertEquals(emptyList<String>(), mergedReturns(fixed))

        val objectStub = "        // Answers an Object, so its ways out may meet at one return.\n" +
            "        stub.addInstructionsWithLabels(\n" +
            "            0,\n" +
            "            " + triple + "\n" +
            "                iget-object p0, p0, LX/holder;->data:LX/data;\n" +
            "                if-eqz p0, :none\n" +
            "                iget-object p0, p0, LX/data;->kind:LX/kind;\n" +
            "                :none\n" +
            "                return-object p0\n" +
            "            " + triple + ",\n" +
            "        )\n"
        assertEquals(emptyList<String>(), mergedReturns(objectStub))

        // A register that starts out null and is only ever written with the answer meets one type.
        val presetNull = "            " + triple + "\n" +
            "                const/4 v0, 0x0\n" +
            "                if-eqz p0, :none\n" +
            "                iget-object v0, p0, LX/data;->kind:LX/kind;\n" +
            "                :none\n" +
            "                return-object v0\n" +
            "            " + triple + ",\n"
        assertEquals(emptyList<String>(), mergedReturns(presetNull))

        // The same crash, reached through a call rather than a field read.
        val throughACall = "            " + triple + "\n" +
            "                if-eqz p0, :none\n" +
            "                invoke-virtual { p0 }, LX/session;->id()Ljava/lang/String;\n" +
            "                move-result-object p0\n" +
            "                :none\n" +
            "                return-object p0\n" +
            "            " + triple + ",\n"
        assertEquals(listOf("p0 at :none"), mergedReturns(throughACall))
    }

    /** Each null check in [source]'s smali whose register is written again before it meets the return. */
    private fun mergedReturns(source: String): List<String> {
        val found = mutableListOf<String>()
        var previousEnd = 0
        for (match in block.findAll(source)) {
            val lead = source.substring(maxOf(previousEnd, match.range.first - 600), match.range.first)
            previousEnd = match.range.last + 1
            if (answersObject.containsMatchIn(lead)) continue
            val body = normalise(match.groupValues[1])
            for (branch in nullBranch.findAll(body)) {
                val register = branch.groupValues[1]
                val label = branch.groupValues[2]
                val merge = body.indexOf(":$label\nreturn-object $register")
                if (merge < branch.range.last) continue
                val between = body.substring(branch.range.last, merge)
                val rewritten = Regex(
                    "^(?:(?:iget|sget|aget)-object $register,.*|move-result-object $register)$",
                    RegexOption.MULTILINE,
                )
                if (rewritten.containsMatchIn(between)) found += "$register at :$label"
            }
        }
        return found
    }

    /** The smali as the patcher sees it, without indentation, blank lines or smali's own comments. */
    private fun normalise(body: String): String = body
        .replace("\\n", "\n")
        .replace("\r\n", "\n")
        .lineSequence()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .joinToString("\n")

    private fun patchSources(): List<File> {
        val root = listOf(File("src/main/kotlin"), File("patches/src/main/kotlin"))
            .firstOrNull { it.isDirectory }
        assertTrue("no patch source tree was found from ${File(".").absolutePath}", root != null)
        return root!!.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .sortedBy { it.path }.toList()
    }
}
