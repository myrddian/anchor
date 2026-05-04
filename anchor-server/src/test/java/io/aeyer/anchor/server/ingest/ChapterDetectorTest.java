package io.aeyer.anchor.server.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import io.aeyer.anchor.server.ingest.ChapterDetector.DetectedChapter;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChapterDetectorTest {

    private final ChapterDetector detector = new ChapterDetector();

    @Test
    void chapter_regex_takes_precedence() {
        String text = """
                Front matter line.
                Chapter 1 Foundations
                Foundations content here.
                More foundations.
                Chapter 2 Methods
                Methods content here.
                """;
        List<DetectedChapter> chapters = detector.detect(text, List.of("Foundations", "Methods"));

        assertThat(chapters).hasSize(2);
        assertThat(chapters.get(0).title()).startsWith("Chapter 1");
        assertThat(chapters.get(0).synthetic()).isFalse();
        assertThat(chapters.get(1).title()).startsWith("Chapter 2");
        assertThat(chapters.get(1).startLine()).isGreaterThan(chapters.get(0).startLine());
    }

    @Test
    void falls_back_to_pdf_outline_when_no_chapter_regex() {
        String text = """
                Foundations
                Foundations content here.
                Methods
                Methods content here.
                """;
        List<DetectedChapter> chapters = detector.detect(text, List.of("Foundations", "Methods"));

        assertThat(chapters).hasSize(2);
        assertThat(chapters.get(0).title()).isEqualTo("Foundations");
        assertThat(chapters.get(1).title()).isEqualTo("Methods");
    }

    @Test
    void falls_back_to_part_markers_when_no_regex_or_outline() {
        String text = """
                Part I Setup
                Setup content.
                Part II Execution
                Execution content.
                """;
        List<DetectedChapter> chapters = detector.detect(text, List.of());

        assertThat(chapters).hasSize(2);
        assertThat(chapters.get(0).title()).startsWith("Part I");
        assertThat(chapters.get(1).title()).startsWith("Part II");
    }

    @Test
    void synthetic_chapter_when_nothing_matches() {
        String text = "Just some prose without any structure markers.\nA second line.";
        List<DetectedChapter> chapters = detector.detect(text, List.of());

        assertThat(chapters).hasSize(1);
        assertThat(chapters.get(0).synthetic()).isTrue();
        assertThat(chapters.get(0).startLine()).isZero();
    }

    @Test
    void front_and_back_matter_headings_are_excluded_from_chapters() {
        // Mirrors what an academic paper's PDF outline looks like — body
        // sections plus References/Appendix at the end. The deliberation
        // model used to write things like "the References chapter
        // concludes with a counterexample" because the bibliography was
        // promoted to a real chapter; this test pins the fix.
        String text = """
                Chapter 1 Introduction
                Intro content.
                Chapter 2 Methods
                Methods content.
                References
                Bibliography content here.
                Appendix
                Appendix content.
                """;
        List<DetectedChapter> chapters = detector.detect(text, List.of());

        assertThat(chapters)
                .extracting(DetectedChapter::title)
                .doesNotContain("References", "Appendix")
                .hasSize(2);
        assertThat(chapters.get(0).orderIndex()).isZero();
        assertThat(chapters.get(1).orderIndex()).isOne();
    }

    @Test
    void numbered_back_matter_is_also_excluded() {
        // A common variant: "5. References" / "6. Appendix A" — the leading
        // number must not defeat the front/back-matter filter.
        String text = """
                Chapter 1 Body
                Body content.
                5. References
                refs.
                6. Appendix
                appendix.
                """;
        List<DetectedChapter> chapters = detector.detect(text, List.of());

        assertThat(chapters).hasSize(1);
        assertThat(chapters.get(0).title()).isEqualTo("Chapter 1 Body");
    }

    @Test
    void excluded_titles_in_pdf_outline_are_dropped_too() {
        // The other entry path — PDF outline rather than regex.
        String text = """
                Introduction
                content
                Methods
                methods content
                References
                refs content
                """;
        List<DetectedChapter> chapters = detector.detect(text,
                List.of("Introduction", "Methods", "References"));

        assertThat(chapters).extracting(DetectedChapter::title)
                .containsExactly("Introduction", "Methods");
    }
}
