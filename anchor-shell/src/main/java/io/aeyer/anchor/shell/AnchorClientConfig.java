package io.aeyer.anchor.shell;

import io.aeyer.anchor.client.AnchorClient;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class AnchorClientConfig {

    @Bean
    public AnchorClient anchorClient(
            @Value("${anchor.base-url:http://localhost:8090}") String baseUrl,
            @Value("${anchor.timeout-seconds:120}") long timeoutSeconds,
            @Value("${anchor.api-token:}") String apiToken) {
        return AnchorClient.builder()
                .baseUrl(baseUrl)
                .timeout(Duration.ofSeconds(timeoutSeconds))
                .apiToken(apiToken)
                .build();
    }
}
