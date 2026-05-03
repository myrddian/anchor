package io.aeyer.anchor.server.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.anchor.protocol.validate.ArgumentativeRole;
import io.aeyer.anchor.protocol.validate.DocumentStance;
import io.aeyer.anchor.server.domain.ChunkWithAncestors;
import io.aeyer.anchor.server.domain.ValidationResult;
import io.aeyer.anchor.server.llm.ChatCompletion;
import io.aeyer.anchor.server.llm.LMStudioClient;
import io.aeyer.anchor.server.workers.WorkerPools;
import jakarta.annotation.PostConstruct;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutionException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

/**
 * /validate's brain — fills the locked validation prompt (SPEC §6.5), routes
 * the chat call through {@link WorkerPools#chatPool()}, parses the model's
 * JSON, and returns a {@link ValidationResult}. Alternative-chunk discovery
 * (§7.4) is layered on top by {@code ValidateController} since it requires
 * the embedding pool and the chunk repository, which the controller already
 * has on hand.
 *
 * Failure handling per ANC-9 / SPEC §4.7:
 * - Invalid JSON → retry once at temperature 0
 * - Still invalid → return UNCLEAR with the raw model output stuffed into
 *   {@code reasoning} so the caller can see what happened. Never crash.
 */
@Service
public class ValidationService {

    private static final Logger log = LoggerFactory.getLogger(ValidationService.class);
    private static final double VALIDATE_TEMPERATURE = 0.1;
    private static final String VALIDATE_SYSTEM = "";

    private final LMStudioClient llm;
    private final WorkerPools pools;
    private final ObjectMapper mapper;

    @Value("classpath:prompts/validation.txt") Resource validationPrompt;

    private String tpl;

    public ValidationService(LMStudioClient llm, WorkerPools pools, ObjectMapper mapper) {
        this.llm = llm;
        this.pools = pools;
        this.mapper = mapper;
    }

    @PostConstruct
    void loadPrompt() {
        try {
            tpl = new String(validationPrompt.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load validation prompt", e);
        }
    }

    public ValidationResult validate(ChunkWithAncestors evidence, String query) {
        String prompt = fillTemplate(evidence, query);

        String raw = chat(prompt, VALIDATE_TEMPERATURE);
        ParsedJudgment parsed = tryParse(raw);
        if (parsed == null) {
            log.warn("Validator returned non-parseable JSON; retrying at temperature 0");
            raw = chat(prompt, 0.0);
            parsed = tryParse(raw);
        }

        if (parsed == null) {
            // SPEC §4.7: never crash on bad model output. Surface the raw text in reasoning
            // so the caller can decide what to do.
            return new ValidationResult(
                    evidence.chunk().id(),
                    evidence.document().id(),
                    query,
                    false,
                    ArgumentativeRole.UNCLEAR,
                    DocumentStance.OFF_TOPIC,
                    "",
                    "Validator JSON unparseable after retry. Raw model output: " + truncate(raw, 500),
                    List.of());
        }

        return new ValidationResult(
                evidence.chunk().id(),
                evidence.document().id(),
                query,
                parsed.isLoadBearing,
                parsed.role,
                parsed.stance,
                parsed.qualifyingContext,
                parsed.reasoning,
                List.of());
    }

    private String chat(String userPrompt, double temperature) {
        try {
            ChatCompletion completion = pools.chatPool()
                    .submit(() -> llm.complete(VALIDATE_SYSTEM, userPrompt, temperature))
                    .get();
            return completion.content() == null ? "" : completion.content().trim();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IngestException("Interrupted during validation", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new IngestException("Validation chat call failed", cause);
        }
    }

    private ParsedJudgment tryParse(String raw) {
        if (raw == null || raw.isBlank()) return null;
        // Some models wrap JSON in fences despite the prompt forbidding it.
        String cleaned = stripFences(raw).trim();
        try {
            JsonNode root = mapper.readTree(cleaned);
            ArgumentativeRole role = enumOrUnclear(root.path("argumentative_role").asText(""), ArgumentativeRole.class, ArgumentativeRole.UNCLEAR);
            DocumentStance stance = enumOrUnclear(root.path("document_stance_on_query").asText(""), DocumentStance.class, DocumentStance.OFF_TOPIC);
            return new ParsedJudgment(
                    root.path("is_load_bearing").asBoolean(false),
                    role,
                    stance,
                    root.path("qualifying_context").asText(""),
                    root.path("reasoning").asText(""));
        } catch (JsonProcessingException e) {
            return null;
        }
    }

    private <E extends Enum<E>> E enumOrUnclear(String value, Class<E> type, E fallback) {
        if (value == null || value.isBlank()) return fallback;
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException e) {
            return fallback;
        }
    }

    private String stripFences(String text) {
        String t = text.trim();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            if (firstNewline > 0) t = t.substring(firstNewline + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        return t;
    }

    private String fillTemplate(ChunkWithAncestors evidence, String query) {
        return tpl
                .replace("{query}", nullSafe(query))
                .replace("{chunk_text}", nullSafe(evidence.chunk().text()))
                .replace("{paragraph_summary}", nullSafe(evidence.paragraph().summary()))
                .replace("{section_title}", nullSafe(evidence.section().title()))
                .replace("{section_summary}", nullSafe(evidence.section().summary()))
                .replace("{chapter_title}", nullSafe(evidence.chapter().title()))
                .replace("{chapter_summary}", nullSafe(evidence.chapter().summary()))
                .replace("{document_title}", nullSafe(evidence.document().title()))
                .replace("{doc_summary}", nullSafe(evidence.document().docSummary()));
    }

    private String nullSafe(String s) { return s == null ? "" : s; }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }

    private record ParsedJudgment(
            boolean isLoadBearing,
            ArgumentativeRole role,
            DocumentStance stance,
            String qualifyingContext,
            String reasoning) {}
}
