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
    void empty_chapter_returns_synthetic_section_marked_with_sentinel() {
        String[] lines = "Chapter 1".split("\\R", -1);
        List<DetectedSection> sections = detector.detect(lines, 0, lines.length);
        assertThat(sections).hasSize(1);
        // Title is the SyntheticTitles.SECTION sentinel — boundary code is
        // expected to filter it; if a render path leaks it the obviously-
        // internal string is the smoking gun rather than a plausible "Body"
        // surfacing in user-facing output.
        assertThat(sections.get(0).title()).isEqualTo("__SYNTHETIC_HEAP__");
        assertThat(sections.get(0).isSynthetic()).isTrue();
    }

    @Test
    void chapter_with_no_recognised_headings_yields_synthetic_section() {
        String[] lines = "Chapter 1\nJust some prose.\nMore prose.".split("\\R", -1);
        List<DetectedSection> sections = detector.detect(lines, 0, lines.length);
        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).title()).isEqualTo("__SYNTHETIC_HEAP__");
        assertThat(sections.get(0).isSynthetic()).isTrue();
    }

    @Test
    void real_section_titles_are_not_marked_synthetic() {
        String[] lines = """
                Chapter 1
                Introduction
                Some prose here.
                """.split("\\R", -1);
        List<DetectedSection> sections = detector.detect(lines, 0, lines.length);
        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).title()).isEqualTo("Introduction");
        assertThat(sections.get(0).isSynthetic()).isFalse();
    }

    @Test
    void title_case_regex_rejects_latex_math_residue() {
        // Tier 2.5 follow-up: pre-fix, lines like "X Y" (LaTeX math after
        // Tika strips subscripts) matched TITLE_CASE_HEADING and were
        // promoted to section titles, then leaked into LLM prompts. The
        // tightened regex requires ≥2 chars per word AND ≥6 chars total,
        // killing single-letter math residue without losing real
        // short titles like "Methods".
        String[] lines = """
                Chapter 1
                X Y
                Some prose with random math residue above as a 'heading'.
                More prose.
                """.split("\\R", -1);
        List<DetectedSection> sections = detector.detect(lines, 0, lines.length);
        // No real heading -> falls back to one synthetic section, not a
        // "X Y"-titled real section.
        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).isSynthetic()).isTrue();
    }

    @Test
    void lp_keyword_minimize_is_not_a_heading() {
        // Tier 2.5 follow-up: `Minimize` passes the title-case + length
        // checks but is an LP problem-statement keyword, not a heading.
        // The denylist short-circuits it.
        String[] lines = """
                Chapter 1
                Minimize
                x + y subject to Ax = b. The LP solver returns the optimal value.
                """.split("\\R", -1);
        List<DetectedSection> sections = detector.detect(lines, 0, lines.length);
        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).isSynthetic()).isTrue();
    }

    @Test
    void numbered_subsection_titles_are_truncated_when_pdf_extraction_glued_body_prose() {
        // Tika often joins the heading and the first body sentence onto one
        // physical line. The regex correctly matches but the title shouldn't
        // carry the body overflow into DB / API / prompts.
        String[] lines = """
                Chapter 3
                3.1. Antichains of fixed diameter. Define the diameter diam(F) of a family F ⊂ 2[n] as
                Some discussion of antichains here.
                """.split("\\R", -1);
        List<DetectedSection> sections = detector.detect(lines, 0, lines.length);
        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).title()).isEqualTo("3.1. Antichains of fixed diameter.");
    }

    @Test
    void numbered_subsection_titles_without_trailing_period_are_kept_intact() {
        String[] lines = """
                Chapter 3
                3.1 Antichains of fixed diameter
                Some discussion of antichains here.
                """.split("\\R", -1);
        List<DetectedSection> sections = detector.detect(lines, 0, lines.length);
        assertThat(sections).hasSize(1);
        assertThat(sections.get(0).title()).isEqualTo("3.1 Antichains of fixed diameter");
    }

    @Test
    void numbered_subsection_headings_are_detected() {
        // Tier 2.5 follow-up: `3.1. Antichains of fixed diameter` style
        // subsection headings used to fall through (NUMBERED_HEADING only
        // matched single-level `\d+\.`) and the synthesiser fabricated
        // them from chunk text. NUMBERED_SUBSECTION captures them.
        String[] lines = """
                Chapter 3
                3.1. Antichains of fixed diameter
                Some discussion of antichains here.
                3.2. Diversity of intersecting set systems
                Some discussion of diversity here.
                """.split("\\R", -1);
        List<DetectedSection> sections = detector.detect(lines, 0, lines.length);
        assertThat(sections).extracting(DetectedSection::title)
                .containsExactly("3.1. Antichains of fixed diameter",
                                 "3.2. Diversity of intersecting set systems");
        assertThat(sections).allSatisfy(s -> assertThat(s.isSynthetic()).isFalse());
    }

    @Test
    void title_case_regex_still_accepts_short_real_headings() {
        String[] lines = """
                Chapter 1
                Methods
                We did Y.
                """.split("\\R", -1);
        List<DetectedSection> sections = detector.detect(lines, 0, lines.length);
        // "Methods" is in CHEMISTRY_SECTIONS so it would be detected even
        // without TITLE_CASE_HEADING, but this confirms the tightened
        // 6-char minimum doesn't accidentally exclude reasonable titles.
        assertThat(sections).extracting(DetectedSection::title).containsExactly("Methods");
        assertThat(sections.get(0).isSynthetic()).isFalse();
    }
}
