package io.memoryos.api.audit;

import io.memoryos.audit.AuditRequestContext;
import jakarta.servlet.http.HttpServletRequest;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

/**
 * The request an audited change arrived on. The client address is the one the forwarded-header filter resolved
 * ({@code server.forward-headers-strategy: framework}), so behind the ingress it is the browser's, not the proxy's.
 */
@Component
class HttpAuditRequestContext implements AuditRequestContext {

    @Override
    public @Nullable String endpoint() {
        var request = current();
        return request == null ? null : request.getMethod() + " " + request.getRequestURI();
    }

    @Override
    public @Nullable String sourceIp() {
        var request = current();
        return request == null ? null : request.getRemoteAddr();
    }

    private static @Nullable HttpServletRequest current() {
        return RequestContextHolder.getRequestAttributes() instanceof ServletRequestAttributes attributes
                ? attributes.getRequest() : null;
    }
}
