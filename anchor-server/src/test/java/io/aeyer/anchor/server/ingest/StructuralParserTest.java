package io.aeyer.anchor.server.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import io.aeyer.anchor.server.ingest.ParsedTypes.ParsedDocument;
import io.aeyer.anchor.server.ingest.ParsedTypes.ParsedSection;
import io.aeyer.anchor.server.ingest.ExtractedDocument;
import java.util.List;
import org.junit.jupiter.api.Test;

class StructuralParserTest {

    private final StructuralParser parser = newParser(300);

    private static StructuralParser newParser(int chunkTarget) {
        IngestProperties props = new IngestProperties();
        props.setChunkTargetTokens(chunkTarget);
        return new StructuralParser(new ChapterDetector(), new SectionDetector(), new Chunker(), props);
    }

    @Test
    void parses_a_synthesised_chemistry_paper_into_full_hierarchy() {
        String text = """
                Chapter 1 Synthesis Of A Novel Catalyst

                Abstract
                We report a new ruthenium catalyst that achieves 95% yield in cross-coupling reactions. The selectivity exceeds prior art by an order of magnitude.

                Introduction
                Cross-coupling reactions are fundamental to organic synthesis. Prior catalysts have suffered from low selectivity. In this work we address this gap.

                Methods
                Reactions were carried out under inert atmosphere at 60 degrees Celsius. NMR spectroscopy was used to confirm product identity.

                Results
                Yields exceeded 95% across the substrate scope. Selectivity ratios were 19 to 1 favouring the desired product.

                Discussion
                The high selectivity arises from steric shielding by the bulky ligand. This finding suggests a general design principle.

                References
                [1] Some prior work.
                """;
        ExtractedDocument extracted = new ExtractedDocument("Test Paper", "abc", text, List.of());

        ParsedDocument parsed = parser.parse(extracted);

        assertThat(parsed.title()).isEqualTo("Test Paper");
        assertThat(parsed.chapters()).hasSize(1);
        assertThat(parsed.chapters().get(0).isSynthetic()).isFalse();

        List<ParsedSection> sections = parsed.chapters().get(0).sections();
        assertThat(sections).extracting(ParsedSection::title)
                .containsExactly("Abstract", "Introduction", "Methods", "Results", "Discussion");
        assertThat(sections.get(0).isAbstract()).isTrue();

        // Each section should have at least one paragraph with at least one chunk.
        for (ParsedSection section : sections) {
            assertThat(section.paragraphs()).isNotEmpty();
            assertThat(section.paragraphs().get(0).chunks()).isNotEmpty();
        }
    }

    @Test
    void document_with_no_chapter_markers_gets_one_synthetic_chapter() {
        String text = "Just one big blob of prose. No chapters. No sections.";
        ExtractedDocument extracted = new ExtractedDocument("Plain", "h", text, List.of());

        ParsedDocument parsed = parser.parse(extracted);

        assertThat(parsed.chapters()).hasSize(1);
        assertThat(parsed.chapters().get(0).isSynthetic()).isTrue();
        // Synthetic chapter title is the SyntheticTitles.CHAPTER sentinel —
        // boundary code drops it; a leak surfaces as the obvious internal
        // string rather than a plausible "Document".
        assertThat(parsed.chapters().get(0).title()).isEqualTo("__SYNTHETIC_SEGMENT__");
        assertThat(parsed.chapters().get(0).sections()).hasSize(1);
        assertThat(parsed.chapters().get(0).sections().get(0).title()).isEqualTo("__SYNTHETIC_HEAP__");
        assertThat(parsed.chapters().get(0).sections().get(0).isSynthetic()).isTrue();
    }

    @Test
    void chunk_target_tokens_setting_is_honoured() {
        StructuralParser tightParser = newParser(5);
        String text = """
                Chapter 1
                Body
                Sentence one is here. Sentence two is here. Sentence three is here. Sentence four is here.
                """;
        ExtractedDocument extracted = new ExtractedDocument("X", "h", text, List.of());

        ParsedDocument parsed = tightParser.parse(extracted);
        var chunks = parsed.chapters().get(0).sections().get(0).paragraphs().get(0).chunks();
        // 4 sentences × ~5 tokens each at budget=5 → multiple chunks
        assertThat(chunks.size()).isGreaterThanOrEqualTo(2);
    }
}
