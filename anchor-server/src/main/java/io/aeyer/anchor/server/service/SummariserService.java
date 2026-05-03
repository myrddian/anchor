package io.aeyer.anchor.server.service;

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
 * Four-level claim-bearing summarisation per SPEC §4.5 / §6.1-6.4.
 *
 * Critical invariant: raw text only enters paragraph-level summaries. Section,
 * chapter, and document summaries see ONLY the summaries below them. This is
 * what gives the four levels independent compression — duplicating raw text up
 * the stack would defeat the purpose.
 *
 * Empty-summary defence (SPEC §4.7): if the model returns blank, retry once at
 * temperature 0. If the second attempt is also blank, fail loudly — silent
 * empty summaries poison every downstream layer.
 */
@Service
public class SummariserService {

    private static final Logger log = LoggerFactory.getLogger(SummariserService.class);

    private static final double SUMMARY_TEMPERATURE = 0.2;
    private static final String SUMMARISER_SYSTEM = ""; // prompts are self-contained

    private final LMStudioClient llm;
    private final WorkerPools pools;
    private final TokenLedger ledger;

    @Value("classpath:prompts/paragraph-summary.txt") Resource paragraphPrompt;
    @Value("classpath:prompts/section-summary.txt") Resource sectionPrompt;
    @Value("classpath:prompts/chapter-summary.txt") Resource chapterPrompt;
    @Value("classpath:prompts/doc-summary.txt") Resource docPrompt;

    private String paragraphTpl;
    private String sectionTpl;
    private String chapterTpl;
    private String docTpl;

    public SummariserService(LMStudioClient llm, WorkerPools pools, TokenLedger ledger) {
        this.llm = llm;
        this.pools = pools;
        this.ledger = ledger;
    }

    @PostConstruct
    void loadPrompts() {
        paragraphTpl = read(paragraphPrompt);
        sectionTpl = read(sectionPrompt);
        chapterTpl = read(chapterPrompt);
        docTpl = read(docPrompt);
    }

    public String summariseParagraph(String paragraphText) {
        return submitChat(paragraphTpl.replace("{paragraph_text}", paragraphText));
    }

    public String summariseSection(String sectionTitle, List<String> paragraphSummaries) {
        String filled = sectionTpl
                .replace("{section_title}", nullSafe(sectionTitle))
                .replace("{concatenated_paragraph_summaries}", joinNumbered(paragraphSummaries));
        return submitChat(filled);
    }

    public String summariseChapter(String chapterTitle, List<String> sectionSummaries) {
        String filled = chapterTpl
                .replace("{chapter_title}", nullSafe(chapterTitle))
                .replace("{concatenated_section_summaries}", joinNumbered(sectionSummaries));
        return submitChat(filled);
    }

    public String summariseDocument(String documentTitle, List<String> chapterSummaries) {
        String filled = docTpl
                .replace("{document_title}", nullSafe(documentTitle))
                .replace("{concatenated_chapter_summaries}", joinNumbered(chapterSummaries));
        return submitChat(filled);
    }

    private String submitChat(String prompt) {
        try {
            return pools.chatPool().submit(() -> runWithEmptyRetry(prompt)).get();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new SummariserException("Interrupted while summarising", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new SummariserException("Summary call failed", cause);
        }
    }

    private String runWithEmptyRetry(String prompt) {
        ChatCompletion first = llm.complete(SUMMARISER_SYSTEM, prompt, SUMMARY_TEMPERATURE);
        recordUsage(first);
        if (!isBlank(first.content())) return first.content().trim();

        log.warn("Summary returned empty content; retrying once at temperature 0");
        ChatCompletion second = llm.complete(SUMMARISER_SYSTEM, prompt, 0.0);
        recordUsage(second);
        if (!isBlank(second.content())) return second.content().trim();

        throw new SummariserException("Summariser returned empty content twice in a row");
    }

    private void recordUsage(ChatCompletion completion) {
        if (completion.usage() == null) return;
        if (completion.usage().promptTokens() != null) {
            ledger.addSummaryInput(completion.usage().promptTokens());
        }
        if (completion.usage().completionTokens() != null) {
            ledger.addSummaryOutput(completion.usage().completionTokens());
        }
    }

    private String joinNumbered(List<String> items) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < items.size(); i++) {
            sb.append(i + 1).append(". ").append(items.get(i));
            if (i < items.size() - 1) sb.append('\n');
        }
        return sb.toString();
    }

    private String nullSafe(String s) { return s == null ? "" : s; }
    private boolean isBlank(String s) { return s == null || s.isBlank(); }

    private String read(Resource resource) {
        try {
            return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to load prompt: " + resource, e);
        }
    }
}
