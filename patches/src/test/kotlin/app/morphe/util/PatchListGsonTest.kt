/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.util

import com.google.gson.JsonParser
import com.google.gson.JsonSyntaxException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

/** What patches-list.json depends on from Gson, so a Gson update can't change it unseen. */
class PatchListGsonTest {
    private val emoji = String(Character.toChars(0x1F600))
    private val text = "<b>Tom & Jerry's</b> = caf" + 0xE9.toChar() + " " + emoji

    @Test fun mapsKeepTheirInsertionOrderAndWriteNulls() {
        val row = linkedMapOf<String, Any?>("z" to 1, "a" to null, "m" to listOf("x"))
        assertEquals("{\n  \"z\": 1,\n  \"a\": null,\n  \"m\": [\n    \"x\"\n  ]\n}", catalogGson().toJson(row))
    }

    @Test fun textIsWrittenAsItIsAndReadsBackTheSame() {
        val written = catalogGson().toJson(mapOf("description" to text))
        assertEquals("{\n  \"description\": \"$text\"\n}", written)
        assertFalse(written.contains("\\u"))
        assertEquals(text, JsonParser.parseString(written).asJsonObject["description"].asString)
    }

    @Test fun aTruncatedCatalogIsRefused() {
        for (broken in listOf("{\"patches\": [", "{\"patches\": [{\"name\": \"a\"}", "{\"version\": \"v1\",")) {
            assertThrows(JsonSyntaxException::class.java) { JsonParser.parseString(broken) }
        }
    }
}
