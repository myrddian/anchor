package io.aeyer.anchor.server;

import io.aeyer.anchor.server.llm.LMStudioProperties;
import io.aeyer.anchor.server.workers.WorkerPoolProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({WorkerPoolProperties.class, LMStudioProperties.class})
public class AnchorServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(AnchorServerApplication.class, args);
    }
}
