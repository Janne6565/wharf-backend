package com.wharf.backend.services.mail;

import com.wharf.backend.security.ClientIpResolver;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.time.Instant;

/**
 * Reads the current request's IP and client into an {@link ActorContext}.
 *
 * <p><strong>Must be called on the request thread.</strong> Notifications are delivered
 * asynchronously, and the request is long gone by the time the mail executor picks the job
 * up — so services resolve the context first and pass it into the {@code @Async} method as
 * an argument, rather than the notification service reaching for it later and silently
 * finding nothing.
 *
 * <p>Using {@link RequestContextHolder} rather than threading {@code HttpServletRequest}
 * through controller and service signatures keeps the request out of the domain layer: only
 * this one component knows notifications are triggered by HTTP at all, which is also what
 * lets the same services be driven from tests with no request bound (the context degrades to
 * a bare timestamp and the affected rows drop out of the mail).
 */
@Component
public class ActorContextResolver {

    private final ClientIpResolver clientIpResolver;

    public ActorContextResolver(ClientIpResolver clientIpResolver) {
        this.clientIpResolver = clientIpResolver;
    }

    public ActorContext resolve() {
        Instant now = Instant.now();
        RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
        if (!(attributes instanceof ServletRequestAttributes servletAttributes)) {
            return ActorContext.unknown(now);
        }
        HttpServletRequest request = servletAttributes.getRequest();
        return new ActorContext(now,
                clientIpResolver.resolve(request),
                UserAgentSummary.describe(request.getHeader("User-Agent")));
    }
}
