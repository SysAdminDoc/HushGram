/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.morphe.patches.instagram.misc.extension

import app.morphe.patcher.patch.PatchException
import app.morphe.patches.shared.compat.AppCompatibilities
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class RuntimeFloorTest {
    private fun manifest(body: String) = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(
        ByteArrayInputStream(("<manifest xmlns:android=\"http://schemas.android.com/apk/res/android\">" +
            body + "<application android:label=\"Instagram\"/></manifest>").toByteArray()),
    )

    @Test
    fun `lower vendor floors rise while higher floors and target SDK stay unchanged`() {
        for (vendor in listOf(26, 28, 31)) {
            val document = manifest("<uses-sdk android:minSdkVersion=\"$vendor\" android:targetSdkVersion=\"37\"/>")
            enforceInstagramRuntimeFloor(document)
            val sdk = document.getElementsByTagName("uses-sdk").item(0) as org.w3c.dom.Element
            assertEquals(maxOf(vendor, AppCompatibilities.INSTAGRAM_RUNTIME_MIN_SDK).toString(), sdk.getAttribute("android:minSdkVersion"))
            assertEquals("37", sdk.getAttribute("android:targetSdkVersion"))
            assertEquals(1, document.getElementsByTagName("application").length)
        }
    }

    @Test
    fun `missing duplicate and nested SDK declarations refuse patching`() {
        for (body in listOf("", "<uses-sdk/><uses-sdk/>", "<application><uses-sdk android:minSdkVersion=\"26\"/></application>")) {
            assertThrows(PatchException::class.java) { enforceInstagramRuntimeFloor(manifest(body)) }
        }
    }

    @Test
    fun `unknown or invalid vendor minimums refuse patching`() {
        for (minimum in listOf("", "FutureAndroid", "-1", "0")) {
            assertThrows(PatchException::class.java) {
                enforceInstagramRuntimeFloor(manifest("<uses-sdk android:minSdkVersion=\"$minimum\"/>"))
            }
        }
    }

    @Test
    fun `running the floor edit twice preserves the same declaration`() {
        val document = manifest("<uses-sdk android:minSdkVersion=\"26\" android:targetSdkVersion=\"37\"/>")
        enforceInstagramRuntimeFloor(document)
        enforceInstagramRuntimeFloor(document)
        assertEquals(1, document.getElementsByTagName("uses-sdk").length)
        assertEquals("28", (document.getElementsByTagName("uses-sdk").item(0) as org.w3c.dom.Element).getAttribute("android:minSdkVersion"))
    }
}
