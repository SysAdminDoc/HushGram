package com.instagram.model.direct;

/** Stands in for Instagram's search match on a chat's name. The names of its fields change with each build. */
public final class DirectMessageSearchThread {
    public String A06 = "group";
    public String A07 = "Weekend plans";
    public String A08;
    public long A02 = 5L;

    public DirectMessageSearchThread(String threadId) {
        this.A08 = threadId;
    }
}
