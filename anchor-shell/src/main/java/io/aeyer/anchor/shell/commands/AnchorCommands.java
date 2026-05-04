package io.aeyer.anchor.shell.commands;

import io.aeyer.anchor.client.AnchorClient;
import io.aeyer.anchor.client.AnchorDocument;
import io.aeyer.anchor.client.AskHandle;
import io.aeyer.anchor.client.IngestHandle;
import io.aeyer.anchor.protocol.ask.AskJobResponse;
import io.aeyer.anchor.protocol.documents.DocumentDetailResponse;
import io.aeyer.anchor.protocol.documents.DocumentSearchHit;
import io.aeyer.anchor.protocol.documents.DocumentSearchResponse;
import io.aeyer.anchor.protocol.documents.DocumentSummaryResponse;
import io.aeyer.anchor.protocol.ingest.IngestJobResponse;
import io.aeyer.anchor.protocol.retrieve.RetrieveResponse;
import io.aeyer.anchor.protocol.validate.AlternativeChunk;
import io.aeyer.anchor.protocol.validate.ValidateQuickResponse;
import io.aeyer.anchor.protocol.validate.ValidateResponse;
import io.aeyer.anchor.shell.ShellState;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.springframework.shell.Availability;
import org.springframework.shell.standard.ShellComponent;
import org.springframework.shell.standard.ShellMethod;
import org.springframework.shell.standard.ShellMethodAvailability;
import org.springframework.shell.standard.ShellOption;

/**
 * Spring Shell surface — the §10.2 demo artefact. Mirrors the SDK ergonomics:
 * {@code use} binds a document, then {@code retrieve / validate / ask} all
 * scope to that document automatically.
 */
@ShellComponent
public class AnchorCommands {

    private final AnchorClient client;
    private final ShellState state;

    public AnchorCommands(AnchorClient client, ShellState state) {
        this.client = client;
        this.state = state;
    }

    @ShellMethod(key = "ingest",
            value = "Ingest a PDF / EPUB / etc. Local file → uploaded; otherwise treated as a server-side path.")
    public String ingest(@ShellOption(help = "Local file path, or a path the server can read") String path) {
        java.nio.file.Path local = java.nio.file.Path.of(path);
        boolean isLocal = java.nio.file.Files.isRegularFile(local);
        IngestHandle handle = isLocal ? client.ingestUpload(local) : client.ingest(path);
        // Long-running on a real book; print a progress line every poll so the
        // chemist can see the % climb instead of staring at a stuck cursor.
        IngestJobResponse result = handle.awaitCompletion(Duration.ofMinutes(30), snap -> {
            String phase = snap.phase() == null ? "" : snap.phase().name().toLowerCase().replace('_', ' ');
            String msg = snap.message() == null ? "" : " — " + snap.message();
            System.out.printf("\r[%3d%%] %s%s%s",
                    snap.percentComplete(), phase, msg, " ".repeat(20));
            System.out.flush();
        });
        System.out.println();
        if (result.status() != io.aeyer.anchor.protocol.ingest.IngestJobStatus.COMPLETED) {
            return "Ingest " + result.status() + ": " + (result.error() == null ? "(no detail)" : result.error());
        }
        var r = result.result();
        return "Ingested " + (isLocal ? "(uploaded) " : "(server-path) ")
                + r.title() + " (id=" + r.documentId()
                + ", chapters=" + r.chapterCount()
                + ", chunks=" + r.chunkCount() + ")";
    }

    @ShellMethod(key = "list", value = "List ingested documents.")
    public String list() {
        List<DocumentSummaryResponse> docs = client.listDocuments();
        if (docs.isEmpty()) return "(no documents ingested yet)";
        StringBuilder sb = new StringBuilder();
        for (DocumentSummaryResponse d : docs) {
            sb.append(d.documentId()).append("  ")
                    .append(d.title())
                    .append("  [").append(d.chapterCount()).append(" chapters, ")
                    .append(d.chunkCount()).append(" chunks]\n");
        }
        return sb.toString().stripTrailing();
    }

    @ShellMethod(key = "search",
            value = "Semantic search across documents — ranks by query-vs-summary cosine.")
    public String search(@ShellOption(help = "Topic / claim to search for") String query,
                         @ShellOption(value = {"--k"}, defaultValue = "10") int k) {
        DocumentSearchResponse response = client.searchDocuments(query, k);
        if (response.hits() == null || response.hits().isEmpty()) {
            return "(no matches — try a broader query, or `list` to see everything)";
        }
        StringBuilder sb = new StringBuilder();
        for (DocumentSearchHit hit : response.hits()) {
            sb.append(String.format("%.3f  ", hit.score()))
                    .append(hit.documentId()).append("  ")
                    .append(hit.title()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    @ShellMethod(key = "use", value = "Bind the shell to a document by UUID or title substring.")
    public String use(@ShellOption(help = "Document UUID or title substring") String identifier) {
        AnchorDocument doc = looksLikeUuid(identifier)
                ? client.use(UUID.fromString(identifier))
                : client.use(identifier);
        state.bind(doc);
        return "Bound to " + doc.id() + " — try `describe`, `retrieve <q>`, or `ask <q>`";
    }

    @ShellMethod(key = "exit-doc", value = "Unbind the shell from the current document.")
    @ShellMethodAvailability("requireDocumentBound")
    public String exitDoc() {
        state.clear();
        return "Document unbound";
    }

    @ShellMethod(key = "describe", value = "Show structure of the bound document.")
    @ShellMethodAvailability("requireDocumentBound")
    public String describe() {
        DocumentDetailResponse detail = state.bound().describe();
        StringBuilder sb = new StringBuilder();
        sb.append("Title:   ").append(detail.title()).append('\n');
        sb.append("Source:  ").append(detail.sourcePath()).append('\n');
        sb.append("Summary: ").append(detail.docSummary()).append("\n\n");
        for (var ch : detail.chapters()) {
            sb.append("# ").append(ch.title())
                    .append(ch.isSynthetic() ? " (synthetic)" : "").append('\n');
            for (var sec : ch.sections()) {
                sb.append("  - ").append(sec.title()).append('\n');
            }
        }
        return sb.toString().stripTrailing();
    }

    @ShellMethod(key = "retrieve", value = "Semantic retrieval within the bound document.")
    @ShellMethodAvailability("requireDocumentBound")
    public String retrieve(@ShellOption(help = "Search query") String query,
                           @ShellOption(value = {"--k"}, defaultValue = "5") int k) {
        RetrieveResponse response = state.bound().retrieve(query, k);
        if (response.hits() == null || response.hits().isEmpty()) return "(no hits)";
        StringBuilder sb = new StringBuilder();
        for (var hit : response.hits()) {
            sb.append(String.format("%.3f  ", hit.score()))
                    .append("[").append(hit.sectionTitle()).append("] ")
                    .append(truncate(hit.text(), 120)).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    @ShellMethod(key = "validate", value = "Validate a chunk against a query.")
    @ShellMethodAvailability("requireDocumentBound")
    public String validate(@ShellOption(help = "Chunk UUID") String chunkId,
                           @ShellOption(help = "Caller's query") String query) {
        ValidateResponse response = state.bound().validate(UUID.fromString(chunkId), query);
        StringBuilder sb = new StringBuilder();
        sb.append("Load-bearing:  ").append(response.isLoadBearing()).append('\n');
        sb.append("Role:          ").append(response.argumentativeRole()).append('\n');
        sb.append("Doc stance:    ").append(response.documentStanceOnQuery()).append('\n');
        if (response.qualifyingContext() != null && !response.qualifyingContext().isEmpty()) {
            sb.append("Qualified by:  ").append(response.qualifyingContext()).append('\n');
        }
        sb.append("Reasoning:     ").append(response.reasoning()).append('\n');
        if (response.alternativeChunks() != null && !response.alternativeChunks().isEmpty()) {
            sb.append("Alternative chunks (refutation):\n");
            for (AlternativeChunk alt : response.alternativeChunks()) {
                sb.append("  - [").append(alt.sectionTitle()).append("] ")
                        .append(truncate(alt.text(), 120)).append('\n');
            }
        }
        return sb.toString().stripTrailing();
    }

    @ShellMethod(key = "quick",
            value = "Vector-only stance check against the bound document. No LLM call. Heuristic.")
    @ShellMethodAvailability("requireDocumentBound")
    public String quick(@ShellOption(help = "Claim to score") String query) {
        ValidateQuickResponse response = state.bound().quickValidate(query);
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Topical:  %+.3f   (cosine of query vs doc-summary)%n",
                response.topicalRelevance()));
        sb.append(String.format("Stance:   %+.3f   (positive = doc agrees, negative = disagrees)%n",
                response.stanceScore()));
        sb.append("Mode:     ").append(response.mode())
                .append("   (no deliberation; use `ask` for the full reasoning)");
        return sb.toString();
    }

    @ShellMethod(key = "ask", value = "Ask a question via three-agent deliberation.")
    @ShellMethodAvailability("requireDocumentBound")
    public String ask(@ShellOption(help = "Reader's question") String query) {
        AskHandle handle = state.bound().ask(query);
        AskJobResponse result = handle.await(Duration.ofMinutes(5));
        StringBuilder sb = new StringBuilder();
        sb.append("Job:    ").append(handle.jobId()).append('\n');
        sb.append("Status: ").append(result.status()).append("\n\n");
        if (result.proposer() != null && result.proposer().response() != null) {
            sb.append("PROPOSER:\n").append(result.proposer().response()).append("\n\n");
        }
        if (result.critic() != null) {
            sb.append("CRITIC:\n");
            if (result.critic().challenges() != null) {
                for (String c : result.critic().challenges()) sb.append("  - ").append(c).append('\n');
            }
            sb.append('\n');
        }
        if (result.finalResponse() != null) {
            sb.append("FINAL (synthesiser):\n").append(result.finalResponse()).append('\n');
        }
        return sb.toString().stripTrailing();
    }

    @ShellMethod(key = "demo",
            value = "§10.2 demo: side-by-side comparison of /retrieve vs /ask for the same query.")
    @ShellMethodAvailability("requireDocumentBound")
    public String demo(@ShellOption(help = "Reader's question") String query) {
        StringBuilder sb = new StringBuilder();
        sb.append("=== /retrieve (Shape 1, top 3) ===\n");
        sb.append(retrieve(query, 3)).append("\n\n");
        sb.append("=== /ask (three-agent deliberation) ===\n");
        sb.append(ask(query));
        return sb.toString();
    }

    public Availability requireDocumentBound() {
        return state.isBound()
                ? Availability.available()
                : Availability.unavailable("no document bound — call `use <id-or-title>` first");
    }

    private boolean looksLikeUuid(String s) {
        try { UUID.fromString(s); return true; } catch (IllegalArgumentException e) { return false; }
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
