/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.feed;

import androidx.annotation.Nullable;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import app.hushgram.extension.shared.Logger;

/**
 * The strip of small pictures Instagram keeps for a video to scrub through (#567, #592): one or more
 * sprite images, each a grid of equal tiles, one tile for every few seconds of the video. It comes
 * with the post, under the names Instagram's own JSON gives them, and most videos have one.
 */
public final class SeekSheet {
    /** One picture in a sprite: where it is in which sprite image. */
    static final class Tile {
        final String url;
        final int x;
        final int y;
        final int width;
        final int height;

        Tile(String url, int x, int y, int width, int height) {
            this.url = url;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        /** Tells two tiles apart. */
        String key() {
            return url + "#" + x + "," + y;
        }
    }

    final List<String> urls;
    final int tileWidth;
    final int tileHeight;
    final int perRow;
    final int perSprite;
    final double secondsPerTile;

    private static WeakReference<Object> lastMedia = new WeakReference<>(null);
    @Nullable private static SeekSheet last;

    private SeekSheet(List<String> urls, int tileWidth, int tileHeight, int perRow, int perSprite, double secondsPerTile) {
        this.urls = urls;
        this.tileWidth = tileWidth;
        this.tileHeight = tileHeight;
        this.perRow = perRow;
        this.perSprite = perSprite;
        this.secondsPerTile = secondsPerTile;
    }

    /** The sheet of the post [media], or null when it has none or it can't be read. The last one is kept, as this runs on every move of a drag. */
    @Nullable
    public static SeekSheet ofMedia(@Nullable Object media) {
        if (media == null) return null;
        if (lastMedia.get() == media) return last;
        SeekSheet found = null;
        try {
            // Reels keep their strip with the cover pictures; feed videos may have it on the post itself.
            found = of(FeedSeekReader.scrubberSheet(media));
            if (found == null) found = of(FeedSeekReader.sheet(media));
        } catch (Throwable failure) {
            Logger.printException(() -> "Seek preview: could not read the video's picture strip", failure);
        }
        lastMedia = new WeakReference<>(media);
        last = found;
        return found;
    }

    /** Reads the sheet from [info], by the names in Instagram's JSON. Null unless every part it needs is there and makes sense. */
    @Nullable
    static SeekSheet of(@Nullable Map<?, ?> info) {
        if (info == null) return null;
        Object listed = info.get("sprite_urls");
        if (!(listed instanceof List)) return null;
        List<String> urls = new ArrayList<>();
        for (Object url : (List<?>) listed) {
            if (url instanceof CharSequence && ((CharSequence) url).length() > 0) urls.add(url.toString());
        }
        int width = whole(info.get("thumbnail_width"));
        int height = whole(info.get("thumbnail_height"));
        int perRow = whole(info.get("thumbnails_per_row"));
        int perSprite = whole(info.get("max_thumbnails_per_sprite"));
        double seconds = info.get("thumbnail_duration") instanceof Number ? ((Number) info.get("thumbnail_duration")).doubleValue() : 0d;
        if (urls.isEmpty() || width <= 0 || height <= 0 || perRow <= 0 || perSprite <= 0 || seconds <= 0d) return null;
        return new SeekSheet(Collections.unmodifiableList(urls), width, height, perRow, perSprite, seconds);
    }

    private static int whole(@Nullable Object value) {
        return value instanceof Number ? ((Number) value).intValue() : 0;
    }

    /** The picture for [positionMs] into the video. Past the last sprite it's the last picture there is. */
    Tile tileAt(int positionMs) {
        int index = (int) Math.floor(Math.max(0, positionMs) / 1000d / secondsPerTile);
        int sprite = Math.min(urls.size() - 1, index / perSprite);
        int within = Math.min(perSprite - 1, index - sprite * perSprite);
        return new Tile(urls.get(sprite), (within % perRow) * tileWidth, (within / perRow) * tileHeight, tileWidth, tileHeight);
    }
}
