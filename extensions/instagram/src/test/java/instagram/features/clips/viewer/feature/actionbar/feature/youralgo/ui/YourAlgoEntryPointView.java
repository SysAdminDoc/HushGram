/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package instagram.features.clips.viewer.feature.actionbar.feature.youralgo.ui;

import android.content.Context;
import android.widget.FrameLayout;

/**
 * Stands in for the Your algorithm button in the Reels viewer's top bar in tests. The extension
 * finds it by this class name, as it does Instagram's, a frame holding the button's icon and badge.
 */
public final class YourAlgoEntryPointView extends FrameLayout {
    public YourAlgoEntryPointView(Context context) {
        super(context);
    }
}
