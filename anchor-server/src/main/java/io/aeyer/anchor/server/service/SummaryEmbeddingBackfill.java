package io.aeyer.anchor.server.service;

import io.aeyer.anchor.server.llm.Embedding;
import io.aeyer.anchor.server.persistence.entity.DocumentDbo;
import io.aeyer.anchor.server.persistence.repo.DocumentRepository;
import io.aeyer.anchor.server.workers.WorkerPools;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * One-time backfill of {@code summary_embedding} for any document that
 * predates the V2 migration. Runs at startup on the embedding pool so it
 * doesn't hold up server boot, and silently swallows failures (LM Studio
 * down, etc.) — next startup will retry.
 *
 * Once every doc has a summary embedding the runner is a no-op.
 */
@Component
public class SummaryEmbeddingBackfill implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(SummaryEmbeddingBackfill.class);
    private static final int BATCH_SIZE = 16;

    private final DocumentRepository documents;
    private final EmbeddingService embedder;
    private final WorkerPools pools;

    @PersistenceContext
    private EntityManager em;

    public SummaryEmbeddingBackfill(DocumentRepository documents,
                                    EmbeddingService embedder,
                                    WorkerPools pools) {
        this.documents = documents;
        this.embedder = embedder;
        this.pools = pools;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Fire and forget on the embedding pool — boot continues immediately.
        pools.embeddingPool().execute(this::tryBackfill);
    }

    private void tryBackfill() {
        try {
            int total = backfillAll();
            if (total > 0) log.info("Backfilled summary_embedding for {} document(s).", total);
        } catch (Exception e) {
            log.warn("Summary-embedding backfill failed (will retry next startup): {}", e.getMessage());
        }
    }

    @Transactional
    protected int backfillAll() {
        List<DocumentDbo> missing = em.createQuery(
                "SELECT d FROM DocumentDbo d WHERE d.summaryEmbedding IS NULL", DocumentDbo.class)
                .getResultList();
        if (missing.isEmpty()) return 0;

        log.info("Backfilling summary_embedding for {} document(s)…", missing.size());
        int done = 0;
        for (int start = 0; start < missing.size(); start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, missing.size());
            List<DocumentDbo> batch = missing.subList(start, end);
            List<String> summaries = batch.stream().map(DocumentDbo::getDocSummary).toList();
            List<Embedding> embeddings = embedder.embedAll(summaries);
            if (embeddings.size() != batch.size()) {
                log.warn("Embedding count mismatch on backfill batch: expected {}, got {} — skipping batch.",
                        batch.size(), embeddings.size());
                continue;
            }
            for (int i = 0; i < batch.size(); i++) {
                batch.get(i).setSummaryEmbedding(embeddings.get(i).vector());
            }
            done += batch.size();
        }
        return done;
    }
}
