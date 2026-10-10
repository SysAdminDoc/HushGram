/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package instagram.features.feed.mainfeed.actionbar;

import android.content.Context;
import android.widget.FrameLayout;

/** Stands in for Instagram's Home header in tests: the extension finds it by this class name. */
public final class MainFeedActionBar extends FrameLayout {
    public MainFeedActionBar(Context context) {
        super(context);
    }
}
