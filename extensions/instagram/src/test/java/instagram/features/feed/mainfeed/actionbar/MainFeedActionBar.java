/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package instagram.features.feed.mainfeed.actionbar;

import android.content.Context;
import android.view.View;
import android.widget.FrameLayout;

/**
 * Stands in for Instagram's Home header in tests: the extension finds it by this class name, and
 * asks it for the Messages button by the getter's name, as it does Instagram's.
 */
public final class MainFeedActionBar extends FrameLayout {
    private View directInbox;

    public MainFeedActionBar(Context context) {
        super(context);
    }

    public void setDirectInbox(View view) {
        directInbox = view;
    }

    public View getDirectInboxView() {
        return directInbox;
    }
}
