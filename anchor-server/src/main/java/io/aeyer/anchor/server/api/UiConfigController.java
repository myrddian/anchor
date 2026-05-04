package io.aeyer.anchor.server.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public bootstrap endpoint for the web UI. Tells the freshly loaded page
 * whether the API gates require a Bearer token (so it should prompt the user
 * for one) and whether the UI is enabled at all (defensive — if the operator
 * disabled the UI, the static assets never load and this endpoint is moot).
 *
 * Exempt from {@link io.aeyer.anchor.server.security.AnchorApiTokenFilter}:
 * the browser hits this before it has any token to send. Returns no secrets,
 * just two booleans.
 */
@RestController
public class UiConfigController {

    private final boolean authRequired;
    private final boolean uiEnabled;

    public UiConfigController(@Value("${anchor.api-token:}") String apiToken,
                              @Value("${anchor.web-ui.enabled:true}") boolean uiEnabled) {
        this.authRequired = apiToken != null && !apiToken.isBlank();
        this.uiEnabled = uiEnabled;
    }

    @GetMapping("/anchor/ui/config")
    public UiConfig get() {
        return new UiConfig(authRequired, uiEnabled);
    }

    public record UiConfig(
            @JsonProperty("auth_required") boolean authRequired,
            @JsonProperty("ui_enabled") boolean uiEnabled) {}
}
