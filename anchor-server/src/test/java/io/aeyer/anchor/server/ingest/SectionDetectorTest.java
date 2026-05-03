package io.aeyer.anchor.server.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import io.aeyer.anchor.server.ingest.SectionDetector.DetectedSection;
import java.util.List;
import org.junit.jupiter.api.Test;

class SectionDetectorTest {

    private final SectionDetector detector = new SectionDetector();

    @Test
    void chemistry_section_names_are_detected() {
        String[] lines = """
                Chapter 1
                Abstract
                We report a new catalyst.
                Introduction
                Prior work used X.
                Methods
                We did Y.
                Results
                Yields were 95%.
                Discussion
                This matters.
                References
                [1] Some paper.
                """.split("\\R", -1);

        List<DetectedSection> sections = detector.detect(lines, 0, lines.length);

        // Abstract, Introduction, Methods, Results, Discussion — references is dropped.
        assertThat(sections).extracting(DetectedSection::title)
                .containsExactly("Abstract", "Introduction", "Methods", "Results", "Discussion");
        assertThat(sections.get(0).isAbstract()).isTrue();
        assertThat(sections.get(1).isAbstract()).isFalse();
    }

    @Test
    void numbered_headings_are_detected() {
        String[] lines = """
                Chapter 1
                1. Introduction
                Some text.
                2. Methods
                More text.
                3. Conclusions
                Final text.
                """.split("\\R", -1);

        List<DetectedSection> sections = detector.detect(lines, 0, lines.length);

        assertThat(sections).extracting(DetectedSection::title)
                .containsExactly("1. Introduction", "2. Methods", "3. Conclusions");
    }

    @Test
    void references_and_acknowledgements_are_excluded() {
        String[] lines = """
                Chapter 1
                Introduction
                Some intro.
                References
                [1] Foo et al.
                Acknowledgements
                We thank Bob.
                """.split("\\R", -1);

        List<DetectedSection> sections = detector.detect(lines, 0, lines.length);

        assertThat(sections).extracting(DetectedSection::title).containsExactly("Introduction");
    }

    @Test
    void empty_chapter_returns_no_sections() {
        String[] lines = "Chapter 1".split("\\R", -1);
        List<DetectedSection> sections = detector.detect(lines, 0, lines.length);
        assertThat(sections).hasSize(1); // synthetic Body
        assertThat(sections.get(0).title()).isEqualTo("Body");
    }

    @Test
    void chapter_with_no_recognised_headings_yields_synthetic_body_section() {
        String[] lines = "Chapter 1\nJust some prose.\nMore prose.".split("\\R", -1);
        List<DetectedSection> sections = detector.detect(lines, 0, lines.length);
        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).title()).isEqualTo("Body");
    }
}
