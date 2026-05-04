package io.aeyer.anchor.server.security;

import static org.assertj.core.api.Assertions.assertThat;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class AnchorApiTokenFilterTest {

    private static final String TOKEN = "s3cret-correct-horse";

    @Test
    void disabled_when_token_blank_passes_through() throws Exception {
        AnchorApiTokenFilter filter = new AnchorApiTokenFilter("");
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/documents");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain chain = recordingChain();

        filter.doFilter(req, res, chain);

        assertThat(filter.isAuthEnabled()).isFalse();
        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(((Recording) chain).called).isTrue();
    }

    @Test
    void enabled_rejects_missing_authorization_with_401() throws Exception {
        AnchorApiTokenFilter filter = new AnchorApiTokenFilter(TOKEN);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/documents");
        MockHttpServletResponse res = new MockHttpServletResponse();
        Recording chain = recordingChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(res.getHeader("WWW-Authenticate")).startsWith("Bearer");
        assertThat(chain.called).isFalse();
    }

    @Test
    void enabled_rejects_wrong_token_with_401() throws Exception {
        AnchorApiTokenFilter filter = new AnchorApiTokenFilter(TOKEN);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/documents");
        req.addHeader("Authorization", "Bearer not-the-token");
        MockHttpServletResponse res = new MockHttpServletResponse();
        Recording chain = recordingChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(chain.called).isFalse();
    }

    @Test
    void enabled_rejects_non_bearer_scheme_with_401() throws Exception {
        AnchorApiTokenFilter filter = new AnchorApiTokenFilter(TOKEN);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/documents");
        req.addHeader("Authorization", "Basic " + java.util.Base64.getEncoder()
                .encodeToString(("user:" + TOKEN).getBytes()));
        MockHttpServletResponse res = new MockHttpServletResponse();
        Recording chain = recordingChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(chain.called).isFalse();
    }

    @Test
    void enabled_accepts_correct_token() throws Exception {
        AnchorApiTokenFilter filter = new AnchorApiTokenFilter(TOKEN);
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/validate");
        req.addHeader("Authorization", "Bearer " + TOKEN);
        MockHttpServletResponse res = new MockHttpServletResponse();
        Recording chain = recordingChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(chain.called).isTrue();
    }

    @Test
    void exempt_paths_bypass_auth_even_when_enabled() throws Exception {
        AnchorApiTokenFilter filter = new AnchorApiTokenFilter(TOKEN);
        for (String exempt : new String[]{
                "/", "/index.html", "/anchor.css", "/anchor.js", "/favicon.ico",
                "/actuator/health", "/actuator/info", "/actuator/info/foo",
                "/anchor/ui/config"}) {
            MockHttpServletRequest req = new MockHttpServletRequest("GET", exempt);
            MockHttpServletResponse res = new MockHttpServletResponse();
            Recording chain = recordingChain();

            filter.doFilter(req, res, chain);

            assertThat(chain.called)
                    .as("exempt path %s must reach next filter without auth", exempt)
                    .isTrue();
            assertThat(res.getStatus()).isEqualTo(200);
        }
    }

    @Test
    void enabled_rejects_non_exempt_actuator_endpoints() throws Exception {
        AnchorApiTokenFilter filter = new AnchorApiTokenFilter(TOKEN);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/actuator/metrics");
        MockHttpServletResponse res = new MockHttpServletResponse();
        Recording chain = recordingChain();

        filter.doFilter(req, res, chain);

        assertThat(res.getStatus()).isEqualTo(401);
        assertThat(chain.called).isFalse();
    }

    private Recording recordingChain() {
        return new Recording();
    }

    private static class Recording implements FilterChain {
        boolean called = false;
        @Override
        public void doFilter(jakarta.servlet.ServletRequest req, jakarta.servlet.ServletResponse res) {
            called = true;
        }
    }
}
