package io.aeyer.anchor.server.service;

import io.aeyer.anchor.server.llm.Embedding;
import io.aeyer.anchor.server.llm.LMStudioClient;
import io.aeyer.anchor.server.workers.WorkerPools;
import java.util.List;
import java.util.concurrent.ExecutionException;
import org.springframework.stereotype.Service;

/**
 * Embedding pipeline. Batches go through {@link WorkerPools#embeddingPool()}
 * (two slots — nomic-embed-text-v1.5 can process two requests in parallel on
 * the Mac Studio); the calling thread blocks on the resulting Future. The
 * embedding pool is the throughput bottleneck for large ingests, not the
 * orchestration thread, so this is safe.
 */
@Service
public class EmbeddingService {

    private final LMStudioClient llm;
    private final WorkerPools pools;
    private final TokenLedger ledger;

    public EmbeddingService(LMStudioClient llm, WorkerPools pools, TokenLedger ledger) {
        this.llm = llm;
        this.pools = pools;
        this.ledger = ledger;
    }

    public List<Embedding> embedAll(List<String> texts) {
        if (texts.isEmpty()) return List.of();
        try {
            List<Embedding> result = pools.embeddingPool()
                    .submit(() -> llm.embedBatch(texts))
                    .get();
            ledger.addEmbeddingInputs(texts.size());
            return result;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IngestException("Interrupted while embedding", e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException re) throw re;
            throw new IngestException("Embedding call failed", cause);
        }
    }
}
