package io.aeyer.anchor.server.api;

import io.aeyer.anchor.protocol.documents.ChapterDetail;
import io.aeyer.anchor.protocol.documents.DocumentDetailResponse;
import io.aeyer.anchor.protocol.documents.DocumentListResponse;
import io.aeyer.anchor.protocol.documents.DocumentSummaryResponse;
import io.aeyer.anchor.protocol.documents.SectionDetail;
import io.aeyer.anchor.server.domain.Document;
import io.aeyer.anchor.server.domain.DocumentContext;
import io.aeyer.anchor.server.persistence.repo.DocumentRepository;
import io.aeyer.anchor.server.persistence.repo.DocumentRepositoryDomain.DocumentCounts;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/documents")
public class DocumentController {

    private static final int DEFAULT_LIMIT = 50;

    private final DocumentRepository documents;

    public DocumentController(DocumentRepository documents) {
        this.documents = documents;
    }

    @GetMapping
    public DocumentListResponse list(
            @RequestParam(value = "limit", defaultValue = "50") int limit,
            @RequestParam(value = "offset", defaultValue = "0") int offset,
            @RequestParam(value = "q", required = false) String q) {
        int safeLimit = limit <= 0 ? DEFAULT_LIMIT : limit;
        int safeOffset = Math.max(0, offset);
        long total = documents.countMatching(q);
        List<Document> page = documents.findPageAsDomain(safeLimit, safeOffset, q);
        List<DocumentSummaryResponse> summaries = page.stream().map(this::toSummary).toList();
        return new DocumentListResponse(summaries, total, safeLimit, safeOffset);
    }

    @GetMapping("/{id}")
    public ResponseEntity<DocumentDetailResponse> detail(@PathVariable UUID id) {
        DocumentContext ctx = documents.findDocumentContextAsDomain(id).orElse(null);
        if (ctx == null) return ResponseEntity.status(HttpStatus.NOT_FOUND).build();

        List<ChapterDetail> chapterDetails = ctx.chapters().stream().map(c -> new ChapterDetail(
                c.chapter().id(),
                c.chapter().ordinal(),
                c.chapter().title(),
                c.chapter().summary(),
                c.chapter().isSynthetic(),
                c.sections().stream().map(s -> new SectionDetail(
                        s.section().id(),
                        s.section().ordinal(),
                        s.section().title(),
                        s.section().summary())).toList())).toList();

        return ResponseEntity.ok(new DocumentDetailResponse(
                ctx.document().id(),
                ctx.document().title(),
                ctx.document().sourcePath(),
                ctx.document().docSummary(),
                ctx.document().ingestedAt(),
                chapterDetails));
    }

    private DocumentSummaryResponse toSummary(Document d) {
        DocumentCounts counts = documents.countsFor(d.id());
        return new DocumentSummaryResponse(
                d.id(),
                d.title(),
                d.sourcePath(),
                d.docSummary(),
                d.ingestedAt(),
                counts.chapters(),
                counts.sections(),
                counts.chunks());
    }
}
