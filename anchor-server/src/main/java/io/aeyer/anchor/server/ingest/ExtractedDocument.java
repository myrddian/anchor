package io.aeyer.anchor.server.ingest;

import java.util.List;

/**
 * Format-agnostic extraction result. Produced by either {@link PdfTextExtractor}
 * (PDFBox path, exposes the PDF outline for the chapter detector to fall back
 * on) or {@link TikaTextExtractor} (Tika path for EPUB / DOCX / HTML / TXT
 * where there's no equivalent outline structure to lift).
 *
 * Downstream — chapter / section / chunk detection and persistence — works the
 * same shape regardless of which path produced it.
 */
public record ExtractedDocument(
        String title,
        String contentHash,
        String text,
        List<String> outlineTopLevel) {}
