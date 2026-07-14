package com.wharf.backend.security;

import com.wharf.backend.entity.UserEntity;
import com.wharf.backend.model.exception.InvalidTokenException;
import com.wharf.backend.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Optional;

/**
 * Authenticates requests bearing a valid IDENTITY token. A missing or invalid token
 * leaves the context unauthenticated — the filter chain then rejects protected routes
 * via the entry point. Token validation never lives in business logic.
 */
@Component
@RequiredArgsConstructor
public class JwtFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(JwtFilter.class);
    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {

        Optional<String> bearer = resolveBearerToken(request);
        if (bearer.isPresent() && SecurityContextHolder.getContext().getAuthentication() == null) {
            authenticate(bearer.get());
        }
        filterChain.doFilter(request, response);
    }

    private void authenticate(String token) {
        try {
            JwtService.ParsedToken parsed = jwtService.parse(token, TokenType.IDENTITY);
            UserEntity user = userRepository.findById(parsed.userId()).orElse(null);
            if (user == null) {
                log.debug("Identity token references unknown user {}", parsed.userId());
                return;
            }
            if (user.getTokenVersion() != parsed.tokenVersion()) {
                log.debug("Rejecting stale token for user {} (token v{}, current v{})",
                        user.getId(), parsed.tokenVersion(), user.getTokenVersion());
                return;
            }
            SecurityContextHolder.getContext().setAuthentication(new WharfAuthenticationToken(user));
        } catch (InvalidTokenException ex) {
            log.debug("Rejecting request with invalid identity token: {}", ex.getDetail());
        }
    }

    private Optional<String> resolveBearerToken(HttpServletRequest request) {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)) {
            return Optional.of(header.substring(BEARER_PREFIX.length()).trim());
        }
        return Optional.empty();
    }
}
