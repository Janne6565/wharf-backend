package com.wharf.backend.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;

import java.io.IOException;

/** Returns a 401 Problem Detail when an unauthenticated request hits a protected route. */
@Component
public class RestAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ProblemDetailWriter problemDetailWriter;

    public RestAuthenticationEntryPoint(ProblemDetailWriter problemDetailWriter) {
        this.problemDetailWriter = problemDetailWriter;
    }

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        problemDetailWriter.write(response, HttpStatus.UNAUTHORIZED, "Authentication required");
    }
}
