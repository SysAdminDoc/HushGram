/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package com.instagram.common.session;

/**
 * Stands in for Instagram's signed-in account, by the name it keeps: the token its link screens are
 * handed, read through the getToken() it keeps too.
 */
public final class UserSession {
    private final String token;

    public UserSession(String token) {
        this.token = token;
    }

    public String getToken() {
        return token;
    }
}
