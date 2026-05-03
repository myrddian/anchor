package io.aeyer.anchor.server.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.stereotype.Component;

/**
 * Per-process counters for ingest LLM cost. Mirrors the values into
 * Micrometer counters (visible via the actuator metrics endpoint) and tracks
 * a per-ingest snapshot that the response reports back to the caller.
 */
@Component
public class TokenLedger {

    private final AtomicLong summaryInput = new AtomicLong();
    private final AtomicLong summaryOutput = new AtomicLong();
    private final AtomicLong embeddingInputs = new AtomicLong();

    private final Counter summaryInputCounter;
    private final Counter summaryOutputCounter;
    private final Counter embeddingInputsCounter;

    public TokenLedger(MeterRegistry meters) {
        this.summaryInputCounter = Counter.builder("anchor.ingest.summary.input.tokens")
                .description("Total prompt tokens sent to the chat model during summarisation")
                .register(meters);
        this.summaryOutputCounter = Counter.builder("anchor.ingest.summary.output.tokens")
                .description("Total completion tokens received from the chat model during summarisation")
                .register(meters);
        this.embeddingInputsCounter = Counter.builder("anchor.ingest.embedding.inputs")
                .description("Number of strings sent to the embedding model")
                .register(meters);
    }

    public void addSummaryInput(long n) {
        summaryInput.addAndGet(n);
        summaryInputCounter.increment(n);
    }

    public void addSummaryOutput(long n) {
        summaryOutput.addAndGet(n);
        summaryOutputCounter.increment(n);
    }

    public void addEmbeddingInputs(long n) {
        embeddingInputs.addAndGet(n);
        embeddingInputsCounter.increment(n);
    }

    public Snapshot snapshotAndReset() {
        return new Snapshot(summaryInput.getAndSet(0),
                summaryOutput.getAndSet(0),
                embeddingInputs.getAndSet(0));
    }

    public record Snapshot(long summaryInput, long summaryOutput, long embeddingInputs) {}
}
