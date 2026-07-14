package com.wharf.backend.security;

import com.wharf.backend.configuration.JwtProperties;
import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.exception.InvalidTokenException;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.UUID;

/**
 * Mints and validates the self-issued HMAC (HS256) JWTs. Both token types embed the
 * user's {@code tokenVersion}; bumping it on the user revokes every outstanding token.
 */
@Service
public class JwtService {

    private static final String CLAIM_TOKEN_TYPE = "tokenType";
    private static final String CLAIM_TOKEN_VERSION = "tokenVersion";
    private static final String CLAIM_EMAIL = "email";

    private final JwtProperties properties;
    private final SecretKey signingKey;

    public JwtService(JwtProperties properties) {
        this.properties = properties;
        this.signingKey = Keys.hmacShaKeyFor(properties.secretKey().getBytes(StandardCharsets.UTF_8));
    }

    public String issueIdentityToken(UserEntity user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getId().toString())
                .issuer(properties.issuer())
                .claim(CLAIM_TOKEN_TYPE, TokenType.IDENTITY.name())
                .claim(CLAIM_TOKEN_VERSION, user.getTokenVersion())
                .claim(CLAIM_EMAIL, user.getEmail())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.identityExpiration())))
                .signWith(signingKey)
                .compact();
    }

    public String issueRefreshToken(UserEntity user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getId().toString())
                .issuer(properties.issuer())
                .claim(CLAIM_TOKEN_TYPE, TokenType.REFRESH.name())
                .claim(CLAIM_TOKEN_VERSION, user.getTokenVersion())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(properties.refreshExpiration())))
                .signWith(signingKey)
                .compact();
    }

    /**
     * Parse and verify a token, asserting it is of the expected type. Throws
     * {@link InvalidTokenException} on any signature, expiry or type mismatch.
     */
    public ParsedToken parse(String token, TokenType expectedType) {
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(signingKey)
                    .requireIssuer(properties.issuer())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException ex) {
            throw new InvalidTokenException("Invalid or expired token");
        }

        String type = claims.get(CLAIM_TOKEN_TYPE, String.class);
        if (type == null || !type.equals(expectedType.name())) {
            throw new InvalidTokenException("Unexpected token type");
        }

        UUID userId;
        try {
            userId = UUID.fromString(claims.getSubject());
        } catch (IllegalArgumentException ex) {
            throw new InvalidTokenException("Malformed token subject");
        }

        Integer tokenVersion = claims.get(CLAIM_TOKEN_VERSION, Integer.class);
        if (tokenVersion == null) {
            throw new InvalidTokenException("Missing token version");
        }

        return new ParsedToken(userId, tokenVersion);
    }

    /** The identity fields carried by a validated token. */
    public record ParsedToken(UUID userId, int tokenVersion) {
    }
}
