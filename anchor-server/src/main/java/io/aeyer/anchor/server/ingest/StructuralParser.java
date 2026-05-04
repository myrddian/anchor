package io.aeyer.anchor.server.ingest;

import io.aeyer.anchor.server.ingest.ChapterDetector.DetectedChapter;
import io.aeyer.anchor.server.ingest.Chunker.Chunk;
import io.aeyer.anchor.server.ingest.ParsedTypes.ParsedChapter;
import io.aeyer.anchor.server.ingest.ParsedTypes.ParsedChunk;
import io.aeyer.anchor.server.ingest.ParsedTypes.ParsedDocument;
import io.aeyer.anchor.server.ingest.ParsedTypes.ParsedParagraph;
import io.aeyer.anchor.server.ingest.ParsedTypes.ParsedSection;
import io.aeyer.anchor.server.ingest.ExtractedDocument;
import io.aeyer.anchor.server.ingest.SectionDetector.DetectedSection;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

/**
 * Composes the structural pipeline (chapter → section → paragraph → chunk)
 * over an already-extracted PDF. Pure CPU work; the file I/O lives in
 * {@link PdfTextExtractor} and the LLM-driven summarisation lives downstream
 * in the (forthcoming) summarisation service per ANC-7.
 */
@Service
public class StructuralParser {

    private final ChapterDetector chapterDetector;
    private final SectionDetector sectionDetector;
    private final Chunker chunker;
    private final IngestProperties props;

    public StructuralParser(ChapterDetector chapterDetector, SectionDetector sectionDetector,
                            Chunker chunker, IngestProperties props) {
        this.chapterDetector = chapterDetector;
        this.sectionDetector = sectionDetector;
        this.chunker = chunker;
        this.props = props;
    }

    public ParsedDocument parse(ExtractedDocument extracted) {
        String[] lines = extracted.text().split("\\R", -1);
        ChapterDetector.Vocabulary vocab = chapterDetector.detectVocabulary(extracted.text());
        List<DetectedChapter> chapters = chapterDetector.detect(extracted.text(), extracted.outlineTopLevel());

        List<ParsedChapter> parsedChapters = new ArrayList<>(chapters.size());
        for (DetectedChapter chapter : chapters) {
            List<DetectedSection> sections =
                    sectionDetector.detect(lines, chapter.startLine(), chapter.endLine());
            List<ParsedSection> parsedSections = new ArrayList<>(sections.size());
            for (DetectedSection section : sections) {
                parsedSections.add(buildSection(lines, section));
            }
            parsedChapters.add(new ParsedChapter(
                    chapter.title(), chapter.orderIndex(), chapter.synthetic(), parsedSections));
        }
        return new ParsedDocument(extracted.title(), extracted.contentHash(), vocab, parsedChapters);
    }

    private ParsedSection buildSection(String[] lines, DetectedSection section) {
        List<String> paragraphTexts = splitParagraphs(lines, section.bodyStartLine(), section.endLine());
        List<ParsedParagraph> paragraphs = new ArrayList<>(paragraphTexts.size());
        for (int i = 0; i < paragraphTexts.size(); i++) {
            List<Chunk> chunks = chunker.chunk(paragraphTexts.get(i), props.getChunkTargetTokens());
            List<ParsedChunk> parsedChunks = new ArrayList<>(chunks.size());
            for (int j = 0; j < chunks.size(); j++) {
                Chunk c = chunks.get(j);
                parsedChunks.add(new ParsedChunk(j, c.text(), c.approxTokens()));
            }
            paragraphs.add(new ParsedParagraph(i, parsedChunks));
        }
        return new ParsedSection(section.title(), section.orderIndex(), section.isAbstract(), paragraphs);
    }

    /**
     * Treat one or more blank lines as a paragraph break. Re-flow contiguous
     * non-empty lines into a single string so PDF line wrapping doesn't
     * fragment sentences — the chunker re-splits on sentence boundaries.
     */
    private List<String> splitParagraphs(String[] lines, int from, int to) {
        List<String> out = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (int i = from; i < to; i++) {
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty()) {
                if (current.length() > 0) {
                    out.add(current.toString().trim());
                    current.setLength(0);
                }
                continue;
            }
            if (current.length() > 0) current.append(' ');
            current.append(trimmed);
        }
        if (current.length() > 0) out.add(current.toString().trim());
        return out;
    }
}
