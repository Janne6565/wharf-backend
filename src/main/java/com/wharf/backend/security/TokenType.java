package com.wharf.backend.security;

/**
 * Value of the {@code tokenType} JWT claim. Only IDENTITY tokens authenticate a request;
 * only REFRESH tokens may be exchanged for a new pair.
 */
public enum TokenType {
    IDENTITY,
    REFRESH
}
