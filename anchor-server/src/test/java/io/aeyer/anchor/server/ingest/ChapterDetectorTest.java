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
}
