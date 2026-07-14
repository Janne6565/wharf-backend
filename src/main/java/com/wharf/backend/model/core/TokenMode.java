package com.wharf.backend.model.core;

/**
 * How token(s) are delivered to the client. Browsers use COOKIE (refresh token set as
 * an httpOnly cookie); non-browser clients (the TUI) use DIRECT (tokens in the body).
 */
public enum TokenMode {
    COOKIE,
    DIRECT
}
