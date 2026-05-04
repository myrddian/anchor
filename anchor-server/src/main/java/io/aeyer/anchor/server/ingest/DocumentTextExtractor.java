package io.aeyer.anchor.server.ingest;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * Format dispatcher. PDFs go through {@link PdfTextExtractor} so we can
 * extract the embedded outline (used by {@link ChapterDetector} as a fallback
 * when the {@code ^Chapter N} regex misses). Everything else — EPUB, DOCX,
 * RTF, HTML, plain text — goes through {@link TikaTextExtractor}.
 *
 * Both produce the same {@link ExtractedDocument} shape so downstream
 * (chapter / section / paragraph / chunk decomposition) is format-agnostic.
 */
@Service
public class DocumentTextExtractor {

    private final PdfTextExtractor pdfExtractor;
    private final TikaTextExtractor tikaExtractor;

    public DocumentTextExtractor(PdfTextExtractor pdfExtractor, TikaTextExtractor tikaExtractor) {
        this.pdfExtractor = pdfExtractor;
        this.tikaExtractor = tikaExtractor;
    }

    public ExtractedDocument extract(Path file) throws IOException {
        return isPdf(file) ? pdfExtractor.extract(file) : tikaExtractor.extract(file);
    }

    private boolean isPdf(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return name.endsWith(".pdf");
    }
}
