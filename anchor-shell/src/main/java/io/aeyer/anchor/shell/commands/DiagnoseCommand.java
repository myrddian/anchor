package io.aeyer.anchor.shell.commands;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;

/**
 * Pre-flight checks for a fresh setup. Hits the server's
 * {@code /actuator/health} and surfaces the LM Studio + database probes so
 * the operator can tell — in one command — whether all three pieces of the
 * local stack (server, LM Studio, pgvector) are talking to each other before
 * they ingest a paper and discover a broken link the slow way.
 *
 * Uses JDK {@link HttpClient} + tiny regex extraction rather than pulling
 * Jackson into the shell module — the diagnostic output is the destination,
 * structured parsing isn't needed.
 */
@ShellComponent
public class DiagnoseCommand {

    private static final Pattern STATUS = Pattern.compile("\"status\"\\s*:\\s*\"([A-Z_]+)\"");
    private static final Pattern COMPONENT_BLOCK =
            Pattern.compile("\"([a-zA-Z]+)\"\\s*:\\s*\\{[^{}]*\"status\"\\s*:\\s*\"([A-Z_]+)\"[^{}]*\\}");

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final String baseUrl;

    public DiagnoseCommand(@Value("${anchor.base-url:http://localhost:8090}") String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    @ShellMethod(key = "diagnose", value = "Run pre-flight checks: server, LM Studio, database.")
    public String diagnose() {
        StringBuilder sb = new StringBuilder();
        sb.append("Server (").append(baseUrl).append(")\n");

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + "/actuator/health"))
                .timeout(Duration.ofSeconds(10))
                .GET()
                .build();
        HttpResponse<String> response;
        try {
            response = http.send(request, HttpResponse.BodyHandlers.ofString());
        } catch (Exception e) {
            sb.append("  ✗ unreachable: ").append(e.getMessage()).append('\n');
            sb.append("  hint: start the server with `./gradlew :anchor-server:bootRun`\n");
            return sb.toString().stripTrailing();
        }
        if (response.statusCode() / 100 != 2) {
            sb.append("  ✗ /actuator/health returned HTTP ").append(response.statusCode()).append('\n');
            return sb.toString().stripTrailing();
        }

        String body = response.body();
        Matcher overall = STATUS.matcher(body);
        if (overall.find()) {
            sb.append("  ✓ reachable, status=").append(overall.group(1)).append("\n\n");
        }

        Matcher components = COMPONENT_BLOCK.matcher(body);
        boolean any = false;
        while (components.find()) {
            String name = components.group(1);
            String status = components.group(2);
            if ("status".equals(name)) continue; // top-level status field
            boolean up = "UP".equals(status);
            sb.append(prettyName(name)).append("\n  ")
                    .append(up ? "✓" : "✗").append(" status=").append(status).append('\n');
            any = true;
        }
        if (!any) {
            sb.append("(no per-component details — show-details may be off; ")
                    .append("set management.endpoint.health.show-details=always)\n");
        }

        sb.append("\nIf any check shows ✗, fix the underlying issue before ingesting:\n");
        sb.append("  • LM Studio down → start it on the configured base-url, enable OpenAI-compat mode\n");
        sb.append("  • Database down  → `docker compose up -d postgres`\n");
        return sb.toString().stripTrailing();
    }

    private String prettyName(String key) {
        return switch (key) {
            // The bean LMStudioHealthIndicator → JSON key "LMStudio" (Spring's
            // Introspector.decapitalize preserves both caps when the first two
            // chars are uppercase). Accept both forms in case Spring's naming
            // convention shifts in a future version.
            case "LMStudio", "lMStudio" -> "LM Studio";
            case "db" -> "Database";
            case "diskSpace" -> "Disk";
            case "ping" -> "Ping";
            default -> key;
        };
    }
}
