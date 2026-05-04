package io.aeyer.anchor.server.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Bearer-token gate for HTTP requests.
 *
 * - When {@code anchor.api-token} is empty/unset: filter is a no-op. This is
 *   the dev / single-user-on-localhost default and matches the historical
 *   behaviour before this filter existed.
 * - When set: every request must carry {@code Authorization: Bearer <token>}
 *   matching the configured value, except for paths in {@link #EXEMPT_PATHS}
 *   (the static UI assets, actuator probes). Mismatch / missing → 401, no
 *   body.
 *
 * Comparison is constant-time on the bytes to make the obvious
 * timing-attack story uninteresting.
 *
 * Single shared token, not per-user. v0 deployment story is "you put this
 * behind a reverse proxy and use the token to gate machine clients;
 * multi-user auth is a v1 problem".
 */
public class AnchorApiTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(AnchorApiTokenFilter.class);

    /**
     * Paths that bypass the token check even when auth is enabled. The static
     * UI assets and {@code /anchor/ui/config} are exempt because the browser
     * can't carry a Bearer header on the initial GETs — the UI bootstraps,
     * fetches the config, prompts the user for the token, and then attaches
     * the bearer to every subsequent request (including the deliberation
     * stream, which is fetch-based for that reason). /actuator/health is
     * exempt so liveness probes don't need credentials.
     */
    private static final Set<String> EXEMPT_PATHS = Set.of(
            "/", "/index.html", "/anchor.css", "/anchor.js", "/favicon.ico",
            "/actuator/health",
            "/anchor/ui/config");
    private static final String EXEMPT_PREFIX_ACTUATOR_INFO = "/actuator/info";

    /**
     * Prefixes that bypass auth even when enabled. The OpenAPI spec and Swagger
     * UI describe the API surface (paths, request/response shapes) — the
     * descriptions themselves aren't sensitive, only the data behind them is,
     * and that data still goes through token-gated endpoints. Exempting the
     * docs lets developers explore the API at /swagger-ui/index.html without a
     * token, then paste one into the "Authorize" button to actually call.
     */
    private static final java.util.List<String> EXEMPT_PREFIXES = java.util.List.of(
            "/v3/api-docs",
            "/swagger-ui",
            "/swagger-ui.html");

    private final String expectedToken;
    private final byte[] expectedBytes;

    public AnchorApiTokenFilter(String expectedToken) {
        this.expectedToken = expectedToken == null ? "" : expectedToken;
        this.expectedBytes = this.expectedToken.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    public boolean isAuthEnabled() {
        return !expectedToken.isEmpty();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request,
                                    HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!isAuthEnabled() || isExempt(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }

        String header = request.getHeader("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            unauthorised(response, "missing bearer token");
            return;
        }
        String presented = header.substring("Bearer ".length()).trim();
        if (!constantTimeEquals(presented.getBytes(java.nio.charset.StandardCharsets.UTF_8), expectedBytes)) {
            unauthorised(response, "invalid bearer token");
            return;
        }
        chain.doFilter(request, response);
    }

    private boolean isExempt(String uri) {
        if (uri == null) return false;
        if (EXEMPT_PATHS.contains(uri)) return true;
        if (uri.startsWith(EXEMPT_PREFIX_ACTUATOR_INFO)) return true;
        for (String prefix : EXEMPT_PREFIXES) {
            if (uri.startsWith(prefix)) return true;
        }
        return false;
    }

    private void unauthorised(HttpServletResponse response, String reason) throws IOException {
        log.debug("Rejecting request: {}", reason);
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setHeader("WWW-Authenticate", "Bearer realm=\"anchor\"");
    }

    /**
     * Constant-time byte comparison. Avoids the "compare prefix, return early
     * on mismatch" pattern that leaks token length/content via response
     * timing. java.util.Arrays.equals is non-constant-time; this isn't.
     */
    private static boolean constantTimeEquals(byte[] a, byte[] b) {
        if (a.length != b.length) return false;
        int diff = 0;
        for (int i = 0; i < a.length; i++) diff |= a[i] ^ b[i];
        return diff == 0;
    }
}
