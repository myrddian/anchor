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
}
