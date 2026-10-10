package com.instagram.model.direct;

/** Stands in for Instagram's search result of a chat or a person: the thread id is all the extension asks for. */
public class DirectShareTarget {
    public final String threadId;

    public DirectShareTarget(String threadId) {
        this.threadId = threadId;
    }
}
