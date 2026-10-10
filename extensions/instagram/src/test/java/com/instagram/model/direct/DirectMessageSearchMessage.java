package com.instagram.model.direct;

/** Stands in for Instagram's search match on a message. The thread id sits in a different field than on a thread match. */
public final class DirectMessageSearchMessage {
    public String A07 = "hello there";
    public String A09;
    public String A0A = "one_to_one";
    public static String A0B = "static text";

    public DirectMessageSearchMessage(String threadId) {
        this.A09 = threadId;
    }
}
