package io.aeyer.anchor.server.health;

import io.aeyer.anchor.server.llm.LMStudioProperties;
import java.time.Duration;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Surfaces LM Studio reachability under {@code /actuator/health}. Pings the
 * {@code /models} endpoint with a short timeout — a healthy daemon answers in
 * a few ms; if it doesn't, the operator sees the connection failure
 * immediately rather than discovering it on the first ingest.
 *
 * Configured chat + embedding model names are echoed in the details so a
 * misconfiguration (typo, wrong model loaded) is obvious without reading the
 * server logs.
 */
@Component
public class LMStudioHealthIndicator implements HealthIndicator {

    private static final Duration PROBE_TIMEOUT = Duration.ofSeconds(3);

    private final LMStudioProperties props;
    private final OkHttpClient probeClient;

    public LMStudioHealthIndicator(LMStudioProperties props) {
        this.props = props;
        this.probeClient = new OkHttpClient.Builder()
                .connectTimeout(PROBE_TIMEOUT)
                .readTimeout(PROBE_TIMEOUT)
                .callTimeout(PROBE_TIMEOUT)
                .build();
    }

    @Override
    public Health health() {
        Health.Builder builder = Health.up()
                .withDetail("base-url", props.getBaseUrl())
                .withDetail("chat-model", props.getChatModel())
                .withDetail("embedding-model", props.getEmbeddingModel())
                .withDetail("embedding-dim-expected", props.getEmbeddingDim());

        Request request = new Request.Builder()
                .url(props.getBaseUrl() + "/models")
                .get()
                .build();
        try (Response response = probeClient.newCall(request).execute()) {
            if (response.isSuccessful()) {
                return builder.withDetail("probe", "ok").build();
            }
            return builder.down()
                    .withDetail("probe", "http-" + response.code())
                    .withDetail("hint", "LM Studio reached but /models returned non-2xx — is OpenAI-compat mode enabled?")
                    .build();
        } catch (Exception e) {
            return builder.down()
                    .withDetail("probe", "unreachable")
                    .withDetail("error", e.getClass().getSimpleName() + ": " + e.getMessage())
                    .withDetail("hint", "Check LM Studio is running and "
                            + "lmstudio.base-url (" + props.getBaseUrl() + ") is reachable.")
                    .build();
        }
    }

}
