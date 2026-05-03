package io.aeyer.anchor.server.ingest;

import java.util.List;

/**
 * Parser-side records describing the *structure* of a PDF before it enters
 * persistence. These are the shape ANC-6 produces; ANC-3 will introduce the
 * persistent DBO/domain split. Keeping them here (not under {@code domain/})
 * makes that future split visible — these go away once persistence lands.
 */
public final class ParsedTypes {
    private ParsedTypes() {}

    public record ParsedDocument(String title, String sourcePathHash, List<ParsedChapter> chapters) {}

    public record ParsedChapter(
            String title,
            int orderIndex,
            boolean isSynthetic,
            List<ParsedSection> sections) {}

    public record ParsedSection(
            String title,
            int orderIndex,
            boolean isAbstract,
            List<ParsedParagraph> paragraphs) {}

    public record ParsedParagraph(int orderIndex, List<ParsedChunk> chunks) {}

    public record ParsedChunk(int orderIndex, String text, int approxTokens) {}
}
