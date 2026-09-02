package io.memoryos.api.security;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Rejects every unsafe {@code /api/**} request that lacks the same-origin mutation header, so a new
 * mutation endpoint is guarded without remembering a per-handler check.
 */
@Configuration(proxyBeanMethods = false)
class BrowserMutationConfiguration implements WebMvcConfigurer {

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new BrowserMutationInterceptor()).addPathPatterns("/api/**");
    }

    private static final class BrowserMutationInterceptor implements HandlerInterceptor {

        @Override
        public boolean preHandle(
                @NonNull HttpServletRequest request,
                @NonNull HttpServletResponse response,
                @NonNull Object handler
        ) {
            if (!isSafe(request.getMethod())) {
                BrowserMutation.require(request.getHeader(BrowserMutation.HEADER));
            }
            return true;
        }

        private static boolean isSafe(String method) {
            return HttpMethod.GET.matches(method)
                    || HttpMethod.HEAD.matches(method)
                    || HttpMethod.OPTIONS.matches(method);
        }
    }
}
