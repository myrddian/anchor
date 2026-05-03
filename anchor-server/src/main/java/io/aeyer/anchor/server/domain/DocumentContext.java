package io.aeyer.anchor.server.domain;

import java.util.List;

/**
 * Full document hierarchy materialised for one deliberation run. Built once at
 * the start of /ask (SPEC §7.5) and passed by value into proposer / critic /
 * synthesiser via the chat pool — never re-fetched mid-deliberation.
 */
public record DocumentContext(
        Document document,
        List<ChapterContext> chapters) {

    public record ChapterContext(Chapter chapter, List<SectionContext> sections) {}
    public record SectionContext(Section section, List<ParagraphContext> paragraphs) {}
    public record ParagraphContext(Paragraph paragraph, List<Chunk> chunks) {}
}
