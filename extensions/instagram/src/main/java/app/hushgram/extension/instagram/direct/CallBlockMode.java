/*
 * Copyright 2026 HushGram contributors
 * https://github.com/SysAdminDoc/HushGram
 */
package app.hushgram.extension.instagram.direct;

/** Who can ring your phone with an Instagram call (#578). */
public enum CallBlockMode {
    EVERYONE,
    NOBODY,
    ONLY_LIST,
    EXCEPT_LIST;

    /** Whether this choice reads the list of people. */
    public boolean usesList() {
        return this == ONLY_LIST || this == EXCEPT_LIST;
    }
}
