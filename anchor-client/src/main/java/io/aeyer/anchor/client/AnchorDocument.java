package io.aeyer.anchor.client;

import io.aeyer.anchor.client.internal.HttpTransport;
import io.aeyer.anchor.protocol.ask.AskRequest;
import io.aeyer.anchor.protocol.ask.JobAcceptedResponse;
import io.aeyer.anchor.protocol.documents.DocumentDetailResponse;
import io.aeyer.anchor.protocol.retrieve.RetrieveRequest;
import io.aeyer.anchor.protocol.retrieve.RetrieveResponse;
import io.aeyer.anchor.protocol.validate.ValidateQuickRequest;
import io.aeyer.anchor.protocol.validate.ValidateQuickResponse;
import io.aeyer.anchor.protocol.validate.ValidateRequest;
import io.aeyer.anchor.protocol.validate.ValidateResponse;
import java.util.UUID;

/**
 * Document-bound handle. Created by {@link AnchorClient#use(UUID)} or
 * {@link AnchorClient#use(String)} — purely a client-side construct holding
 * the doc id; calls to its methods hit the server scoped to this document.
 */
public final class AnchorDocument {

    private final UUID documentId;
    private final HttpTransport transport;

    AnchorDocument(UUID documentId, HttpTransport transport) {
        this.documentId = documentId;
        this.transport = transport;
    }

    public UUID id() { return documentId; }

    public DocumentDetailResponse describe() {
        return transport.get("/documents/" + documentId, DocumentDetailResponse.class);
    }

    public RetrieveResponse retrieve(String query, int k) {
        return transport.postJson("/retrieve",
                new RetrieveRequest(query, documentId, k), RetrieveResponse.class);
    }

    public ValidateResponse validate(UUID chunkId, String query) {
        return transport.postJson("/validate",
                new ValidateRequest(chunkId, query), ValidateResponse.class);
    }

    /**
     * Vector-only stance approximation — no LLM call. Two embedding round
     * trips, returns a topical relevance + heuristic stance score in
     * [-1, 1]. Use as a pre-filter before {@link #ask(String)}, not as a
     * substitute for the deliberation.
     */
    public ValidateQuickResponse quickValidate(String query) {
        return transport.postJson("/validate/quick",
                new ValidateQuickRequest(documentId, query), ValidateQuickResponse.class);
    }

    /**
     * Submit a deliberation. Returns an {@link AskHandle} the caller can poll,
     * subscribe to, or cancel. The HTTP call returns 202 immediately.
     */
    public AskHandle ask(String query) {
        JobAcceptedResponse accepted = transport.postJson(
                "/documents/" + documentId + "/ask",
                new AskRequest(query),
                JobAcceptedResponse.class);
        return new AskHandle(accepted.jobId(), documentId, transport);
    }
}
