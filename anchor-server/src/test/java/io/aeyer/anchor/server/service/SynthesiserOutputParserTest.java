package io.aeyer.anchor.server.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class SynthesiserOutputParserTest {

    private SynthesiserOutputParser parser;

    @BeforeEach
    void setUp() {
        parser = new SynthesiserOutputParser(new ObjectMapper());
    }

    @Test
    void canonical_format_with_response_and_grounding_markers() {
        String raw = """
                RESPONSE:
                I am the document. The catalyst is selective.

                GROUNDING:
                {"grounded_in_sections":["Methods"],"confidence":"high"}
                """;

        assertThat(parser.extractResponse(raw))
                .isEqualTo("I am the document. The catalyst is selective.");
        assertThat(parser.extractGrounding(raw))
                .containsEntry("confidence", "high");
    }

    @Test
    void model_omits_response_marker_and_starts_directly_with_prose() {
        String raw = """
                I am the document. The catalyst is selective per Methods.

                GROUNDING:
                {"confidence":"medium"}
                """;

        assertThat(parser.extractResponse(raw))
                .startsWith("I am the document.")
                .doesNotContain("GROUNDING:");
        assertThat(parser.extractGrounding(raw))
                .containsEntry("confidence", "medium");
    }

    @Test
    void model_echoes_trailing_synthesiser_output_label_and_re_emits_response() {
        // Real Gemma 4 E4B output observed against arXiv 2602.00112v1 — the model
        // emits the response, the GROUNDING JSON, then echoes "SYNTHESISER OUTPUT:"
        // and writes the response a second time.
        String raw = """
                I am a document titled "Torse-forming vector field with certain deformations". I claim X.

                GROUNDING:
                {"grounded_in_sections":["1. Introduction"],"confidence":"high","incorporated_critic_challenges":[1,2]}

                SYNTHESISER OUTPUT:
                I am a document titled "Torse-forming vector field with certain deformations". I claim X again.
                """;

        String response = parser.extractResponse(raw);
        assertThat(response)
                .startsWith("I am a document titled")
                .doesNotContain("GROUNDING:")
                .doesNotContain("SYNTHESISER OUTPUT:")
                .doesNotContain("again"); // duplicate echo must not leak in
        assertThat(parser.extractGrounding(raw))
                .containsEntry("confidence", "high");
    }

    @Test
    void code_fenced_grounding_is_unwrapped() {
        String raw = """
                RESPONSE:
                Some prose.

                GROUNDING:
                ```json
                {"confidence":"low"}
                ```
                """;

        assertThat(parser.extractGrounding(raw))
                .containsEntry("confidence", "low");
    }

    @Test
    void unparseable_grounding_returns_raw_output_map_not_null() {
        String raw = """
                RESPONSE:
                Some prose.

                GROUNDING:
                this is not valid JSON {{{
                """;

        var grounding = parser.extractGrounding(raw);
        assertThat(grounding).isNotNull();
        assertThat(grounding).containsKey("raw_output");
        assertThat((String) grounding.get("raw_output")).contains("not valid JSON");
    }

    @Test
    void no_grounding_marker_returns_null_grounding_and_full_text_response() {
        String raw = "Just a free-form answer with no markers at all.";

        assertThat(parser.extractResponse(raw)).isEqualTo(raw);
        assertThat(parser.extractGrounding(raw)).isNull();
    }

    @Test
    void null_input_is_handled_gracefully() {
        assertThat(parser.extractResponse(null)).isEmpty();
        assertThat(parser.extractGrounding(null)).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void unnamed_segment_entries_are_stripped_from_grounding_arrays() {
        // Belt-and-braces: the synthesiser prompt tells the model to skip
        // (unnamed segment) entries, but smaller models sometimes copy the
        // whole bullet anyway when all the relevant evidence is synthetic.
        // The parser scrubs them so downstream tooling can rely on
        // grounding entries being verbatim document-owned titles.
        String raw = """
                RESPONSE:
                I cite both real and unnamed sections.

                GROUNDING:
                {
                  "grounded_in_chapters": ["1. Introduction", "(unnamed segment): summary copied verbatim"],
                  "grounded_in_sections": ["(unnamed segment)", "Methods", "(unnamed segment): another bullet"],
                  "confidence": "medium"
                }
                """;

        var grounding = parser.extractGrounding(raw);
        assertThat(grounding).isNotNull();
        assertThat((java.util.List<String>) grounding.get("grounded_in_chapters"))
                .containsExactly("1. Introduction");
        assertThat((java.util.List<String>) grounding.get("grounded_in_sections"))
                .containsExactly("Methods");
        assertThat(grounding.get("confidence")).isEqualTo("medium");
    }

    @Test
    @SuppressWarnings("unchecked")
    void duplicate_grounding_entries_are_deduplicated_preserving_first_occurrence_order() {
        // Models occasionally list the same section twice when the
        // deliberation pulled multiple chunks from it. Conveys one citation,
        // not N — the array shape shouldn't lie about that.
        String raw = """
                GROUNDING:
                {
                  "grounded_in_chapters": ["1. Introduction", "3. Main results", "1. Introduction"],
                  "grounded_in_sections": ["3.1. Antichains.", "3.1. Antichains.", "3.7. Rainbow.", "3.1. Antichains."],
                  "confidence": "high"
                }
                """;

        var grounding = parser.extractGrounding(raw);
        assertThat((java.util.List<String>) grounding.get("grounded_in_chapters"))
                .containsExactly("1. Introduction", "3. Main results");
        assertThat((java.util.List<String>) grounding.get("grounded_in_sections"))
                .containsExactly("3.1. Antichains.", "3.7. Rainbow.");
    }

    @Test
    @SuppressWarnings("unchecked")
    void scrubber_catches_paraphrased_unnamed_segment_marker_without_parens() {
        // Smaller models occasionally drop the parentheses around the marker
        // when copying it into grounded_in_sections; the scrubber must catch
        // both forms to enforce the "no synthetic markers in grounding"
        // contract regardless of model compliance.
        String raw = """
                GROUNDING:
                {
                  "grounded_in_chapters": ["1. Introduction"],
                  "grounded_in_sections": ["unnamed segment", "Unnamed Segment", "Methods", "(unnamed segment): paraphrase"],
                  "confidence": "high"
                }
                """;

        var grounding = parser.extractGrounding(raw);
        assertThat((java.util.List<String>) grounding.get("grounded_in_sections"))
                .containsExactly("Methods");
    }

    @Test
    @SuppressWarnings("unchecked")
    void all_synthetic_grounding_yields_empty_array_not_a_crash() {
        String raw = """
                GROUNDING:
                {
                  "grounded_in_chapters": ["1. Introduction"],
                  "grounded_in_sections": ["(unnamed segment)", "(unnamed segment): summary"],
                  "confidence": "low"
                }
                """;

        var grounding = parser.extractGrounding(raw);
        assertThat((java.util.List<String>) grounding.get("grounded_in_sections")).isEmpty();
        assertThat((java.util.List<String>) grounding.get("grounded_in_chapters"))
                .containsExactly("1. Introduction");
    }
}
