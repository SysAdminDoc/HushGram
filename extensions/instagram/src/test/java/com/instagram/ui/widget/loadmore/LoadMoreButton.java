/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package com.instagram.ui.widget.loadmore;

import android.content.Context;
import android.view.View;
import android.widget.ImageView;
import android.widget.ViewAnimator;

/**
 * Stands in for Instagram 453's row at a chat's old end, by the name it keeps: a ViewAnimator showing
 * one of four children, nothing more to load, a page loading, Load more and Retry, the last two
 * tappable images with Instagram's click listeners on them.
 */
public class LoadMoreButton extends ViewAnimator {
    public final View empty;
    public final View loading;
    public final ImageView loadMore;
    public final ImageView retry;
    public int loadMoreTaps;
    public int retryTaps;

    public LoadMoreButton(Context context) {
        super(context);
        empty = new View(context);
        loading = new View(context);
        loadMore = new ImageView(context);
        retry = new ImageView(context);
        loadMore.setOnClickListener(v -> loadMoreTaps++);
        retry.setOnClickListener(v -> retryTaps++);
        addView(empty);
        addView(loading);
        addView(loadMore);
        addView(retry);
    }
}
