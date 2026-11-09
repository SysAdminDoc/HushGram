/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/** A video's picture strip (#567), read under the names in Instagram's own JSON. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class SeekSheetTest {
    private static final String ONE = "https://scontent.cdninstagram.com/one.jpg";
    private static final String TWO = "https://scontent.cdninstagram.com/two.jpg";

    /** Two sprites of 25 tiles, five to a row, one tile every 2 seconds. */
    private static Map<String, Object> info() {
        Map<String, Object> info = new HashMap<>();
        info.put("sprite_urls", Arrays.asList(ONE, TWO));
        info.put("thumbnail_width", 100);
        info.put("thumbnail_height", 178);
        info.put("thumbnails_per_row", 5);
        info.put("max_thumbnails_per_sprite", 25);
        info.put("thumbnail_duration", 2.0d);
        return info;
    }

    private static void assertTile(SeekSheet.Tile tile, String url, int x, int y) {
        assertEquals(url, tile.url);
        assertEquals(x, tile.x);
        assertEquals(y, tile.y);
        assertEquals(100, tile.width);
        assertEquals(178, tile.height);
    }

    @Test
    public void aTileIsFoundAcrossThenDownThenInTheNextSprite() {
        SeekSheet sheet = SeekSheet.of(info());
        assertNotNull(sheet);
        assertTile(sheet.tileAt(0), ONE, 0, 0);
        assertTile(sheet.tileAt(1999), ONE, 0, 0);
        assertTile(sheet.tileAt(2000), ONE, 100, 0);
        assertTile(sheet.tileAt(10000), ONE, 0, 178);
        assertTile(sheet.tileAt(48000), ONE, 400, 712);
        assertTile(sheet.tileAt(50000), TWO, 0, 0);
    }

    @Test
    public void pastTheEndIsTheLastPicture() {
        assertTile(SeekSheet.of(info()).tileAt(2_000_000), TWO, 400, 712);
    }

    @Test
    public void beforeTheStartIsTheFirstPicture() {
        assertTile(SeekSheet.of(info()).tileAt(-5), ONE, 0, 0);
    }

    @Test
    public void theJsonsLongsAndDoublesAreNumbersToo() {
        Map<String, Object> info = info();
        info.put("thumbnail_width", 100L);
        info.put("thumbnail_duration", 2);
        assertTile(SeekSheet.of(info).tileAt(2000), ONE, 100, 0);
    }

    @Test
    public void anyPartMissingMeansNoSheet() {
        for (String key : info().keySet()) {
            Map<String, Object> info = info();
            info.remove(key);
            assertNull(key, SeekSheet.of(info));
        }
    }

    @Test
    public void partsThatMakeNoSenseMeanNoSheet() {
        for (Map.Entry<String, Object> bad : Arrays.<Map.Entry<String, Object>>asList(
                entry("sprite_urls", Collections.emptyList()),
                entry("sprite_urls", "one.jpg"),
                entry("thumbnail_width", 0),
                entry("thumbnail_height", -1),
                entry("thumbnails_per_row", "5"),
                entry("max_thumbnails_per_sprite", 0),
                entry("thumbnail_duration", 0d))) {
            Map<String, Object> info = info();
            info.put(bad.getKey(), bad.getValue());
            assertNull(bad.toString(), SeekSheet.of(info));
        }
    }

    @Test
    public void nothingToReadIsNoSheet() {
        assertNull(SeekSheet.of(null));
        assertNull(SeekSheet.ofMedia(null));
        assertNull("an unpatched build's stub answers nothing", SeekSheet.ofMedia(new Object()));
    }

    private static Map.Entry<String, Object> entry(String key, Object value) {
        return new java.util.AbstractMap.SimpleEntry<>(key, value);
    }
}
