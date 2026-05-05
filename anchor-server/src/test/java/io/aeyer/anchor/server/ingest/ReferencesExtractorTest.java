package io.aeyer.anchor.server.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ReferencesExtractorTest {

    private final ReferencesExtractor extractor = new ReferencesExtractor();

    @Test
    void returns_empty_string_when_no_references_heading_present() {
        String text = """
                Chapter 1
                Some prose about catalysts.
                Chapter 2
                More prose.
                """;
        assertThat(extractor.findReferencesText(text)).isEmpty();
    }

    @Test
    void captures_lines_after_a_references_heading() {
        String text = """
                Chapter 1
                Some prose.

                References
                [1] Foo et al., A paper, Journal X (2020).
                [2] Bar et al., Another paper, Journal Y (2021).
                [3] Baz, Yet another, Journal Z (2022).
                """;
        String refs = extractor.findReferencesText(text);
        assertThat(refs).contains("[1] Foo et al.");
        assertThat(refs).contains("[2] Bar et al.");
        assertThat(refs).contains("[3] Baz");
        assertThat(refs).doesNotContain("Some prose");
        assertThat(refs).doesNotContain("References");
    }

    @Test
    void captures_numbered_references_format_too() {
        String text = """
                References

                1. Adam Author, A long enough title to look like a reference, Journal Foo (2020).
                2. Bea Coauthor, Another long-enough reference title, Journal Bar (2021).
                3. Charlie Other, Yet another long-enough one, Journal Baz (2022).
                """;
        String refs = extractor.findReferencesText(text);
        assertThat(refs).contains("1. Adam Author");
        assertThat(refs).contains("2. Bea Coauthor");
        assertThat(refs).contains("3. Charlie Other");
    }

    @Test
    void bibliography_heading_works_too() {
        String text = """
                Bibliography
                [1] Foo et al., A paper.
                [2] Bar et al., Another.
                [3] Baz, Yet another.
                """;
        assertThat(extractor.findReferencesText(text)).contains("[1] Foo et al.");
    }

    @Test
    void case_insensitive_heading_match() {
        String text = """
                REFERENCES
                [1] Foo, A paper.
                [2] Bar, Another paper.
                [3] Baz, Yet one more paper.
                """;
        assertThat(extractor.findReferencesText(text)).contains("[1] Foo");
    }

    @Test
    void numbered_reference_entries_are_not_mistaken_for_chapter_boundaries() {
        // The line "16. Peter Frankl, Extremal..." would match a naive
        // numbered-chapter regex; the extractor's looksLikeReferenceEntry
        // heuristic distinguishes it from a real chapter heading by line
        // length + lowercase-continuation detection. This test pins that
        // we don't truncate mid-reference-list.
        String text = """
                References
                15. First Author, A reasonably-long reference one, Journal A (2019).
                16. Peter Frankl, Extremal problems for finite sets, Bolyai Society (2018).
                17. Second Author, Another reasonably-long reference, Journal B (2020).
                18. Third Author, Final reasonably-long reference, Journal C (2021).
                """;
        String refs = extractor.findReferencesText(text);
        assertThat(refs).contains("15. First Author");
        assertThat(refs).contains("16. Peter Frankl");
        assertThat(refs).contains("17. Second Author");
        assertThat(refs).contains("18. Third Author");
    }
}
