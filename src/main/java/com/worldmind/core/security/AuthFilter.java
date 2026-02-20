package com.worldmind.core.security;

import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;

@Component
@Order(1)
public class AuthFilter implements Filter {

    private static final String SESSION_USER_KEY = "worldmind_user";

    private static final Set<String> PUBLIC_PATHS = Set.of(
            "/api/v1/auth/login",
            "/api/v1/auth/status",
            "/api/v1/health",
            "/actuator/health",
            "/actuator/prometheus"
    );

    private static final Set<String> PUBLIC_PREFIXES = Set.of(
            "/assets/",
            "/favicon.ico",
            "/logo.png",
            "/index.html",
            "/api/internal/"
    );

    private final AuthProperties authProperties;

    public AuthFilter(AuthProperties authProperties) {
        this.authProperties = authProperties;
    }

    @Override
    public void doFilter(ServletRequest request, ServletResponse response, FilterChain chain)
            throws IOException, ServletException {

        HttpServletRequest httpRequest = (HttpServletRequest) request;
        HttpServletResponse httpResponse = (HttpServletResponse) response;

        String path = httpRequest.getRequestURI();

        if (!authProperties.isEnabled() || isPublicPath(path) || isStaticResource(path)) {
            chain.doFilter(request, response);
            return;
        }

        HttpSession session = httpRequest.getSession(false);
        String user = session != null ? (String) session.getAttribute(SESSION_USER_KEY) : null;

        if (user != null) {
            chain.doFilter(request, response);
            return;
        }

        httpResponse.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        httpResponse.setContentType("application/json");
        httpResponse.getWriter().write("{\"error\":\"Authentication required\",\"authenticated\":false}");
    }

    private boolean isPublicPath(String path) {
        return PUBLIC_PATHS.contains(path);
    }

    private boolean isStaticResource(String path) {
        if (path.equals("/") || path.isEmpty()) {
            return true;
        }
        for (String prefix : PUBLIC_PREFIXES) {
            if (path.startsWith(prefix)) {
                return true;
            }
        }
        if (!path.startsWith("/api/") && !path.startsWith("/actuator/")) {
            return true;
        }
        return false;
    }
}
