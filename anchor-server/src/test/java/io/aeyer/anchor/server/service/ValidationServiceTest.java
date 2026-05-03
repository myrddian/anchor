package io.aeyer.anchor.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.anchor.protocol.validate.ArgumentativeRole;
import io.aeyer.anchor.protocol.validate.DocumentStance;
import io.aeyer.anchor.server.domain.Chapter;
import io.aeyer.anchor.server.domain.Chunk;
import io.aeyer.anchor.server.domain.ChunkWithAncestors;
import io.aeyer.anchor.server.domain.DocSummarySource;
import io.aeyer.anchor.server.domain.Document;
import io.aeyer.anchor.server.domain.Paragraph;
import io.aeyer.anchor.server.domain.Section;
import io.aeyer.anchor.server.domain.ValidationResult;
import io.aeyer.anchor.server.llm.ChatCompletion;
import io.aeyer.anchor.server.llm.LMStudioClient;
import io.aeyer.anchor.server.workers.WorkerPoolProperties;
import io.aeyer.anchor.server.workers.WorkerPools;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

class ValidationServiceTest {

    private LMStudioClient llm;
    private WorkerPools pools;
    private ValidationService validator;

    @BeforeEach
    void setUp() {
        llm = mock(LMStudioClient.class);
        pools = new WorkerPools(new WorkerPoolProperties());
        pools.init();
        validator = new ValidationService(llm, pools, new ObjectMapper());
        ReflectionTestUtils.setField(validator, "validationPrompt",
                new ClassPathResource("prompts/validation.txt"));
        validator.loadPrompt();
    }

    @AfterEach
    void tearDown() { pools.shutdown(); }

    @Test
    void parses_valid_json_into_validation_result() {
        when(llm.complete(any(), anyString(), any(Double.class)))
                .thenReturn(new ChatCompletion("""
                        {"is_load_bearing": true,
                         "argumentative_role": "AUTHOR_POSITION",
                         "document_stance_on_query": "SUPPORTS",
                         "qualifying_context": "",
                         "reasoning": "Chunk states the central claim of the document."}
                        """, "stop", null));

        ValidationResult result = validator.validate(buildEvidence(), "does X work?");

        assertThat(result.isLoadBearing()).isTrue();
        assertThat(result.argumentativeRole()).isEqualTo(ArgumentativeRole.AUTHOR_POSITION);
        assertThat(result.documentStanceOnQuery()).isEqualTo(DocumentStance.SUPPORTS);
        assertThat(result.reasoning()).contains("central claim");
    }

    @Test
    void unknown_enum_values_fall_back_safely_without_crashing() {
        when(llm.complete(any(), anyString(), any(Double.class)))
                .thenReturn(new ChatCompletion("""
                        {"is_load_bearing": false,
                         "argumentative_role": "GIBBERISH_VALUE",
                         "document_stance_on_query": "ALSO_GIBBERISH",
                         "qualifying_context": "",
                         "reasoning": "ok"}
                        """, "stop", null));

        ValidationResult result = validator.validate(buildEvidence(), "q");

        assertThat(result.argumentativeRole()).isEqualTo(ArgumentativeRole.UNCLEAR);
        assertThat(result.documentStanceOnQuery()).isEqualTo(DocumentStance.OFF_TOPIC);
    }

    @Test
    void invalid_json_triggers_retry_at_temperature_zero() {
        when(llm.complete(any(), anyString(), eq(0.1)))
                .thenReturn(new ChatCompletion("not json at all { broken", "stop", null));
        when(llm.complete(any(), anyString(), eq(0.0)))
                .thenReturn(new ChatCompletion("""
                        {"is_load_bearing": true,
                         "argumentative_role": "AUTHOR_POSITION",
                         "document_stance_on_query": "SUPPORTS",
                         "qualifying_context": "",
                         "reasoning": "recovered"}
                        """, "stop", null));

        ValidationResult result = validator.validate(buildEvidence(), "q");

        assertThat(result.argumentativeRole()).isEqualTo(ArgumentativeRole.AUTHOR_POSITION);
        verify(llm, times(1)).complete(any(), anyString(), eq(0.1));
        verify(llm, times(1)).complete(any(), anyString(), eq(0.0));
    }

    @Test
    void two_invalid_responses_yield_unclear_with_raw_in_reasoning() {
        when(llm.complete(any(), anyString(), any(Double.class)))
                .thenReturn(new ChatCompletion("totally not json", "stop", null));

        ValidationResult result = validator.validate(buildEvidence(), "q");

        assertThat(result.argumentativeRole()).isEqualTo(ArgumentativeRole.UNCLEAR);
        assertThat(result.reasoning()).contains("Raw model output");
        assertThat(result.reasoning()).contains("totally not json");
        assertThat(result.alternativeChunks()).isEmpty();
    }

    @Test
    void code_fenced_json_is_unwrapped_before_parsing() {
        when(llm.complete(any(), anyString(), any(Double.class)))
                .thenReturn(new ChatCompletion("""
                        ```json
                        {"is_load_bearing": true,
                         "argumentative_role": "AUTHOR_POSITION",
                         "document_stance_on_query": "SUPPORTS",
                         "qualifying_context": "",
                         "reasoning": "fenced"}
                        ```""", "stop", null));

        ValidationResult result = validator.validate(buildEvidence(), "q");

        assertThat(result.argumentativeRole()).isEqualTo(ArgumentativeRole.AUTHOR_POSITION);
        assertThat(result.reasoning()).isEqualTo("fenced");
    }

    private ChunkWithAncestors buildEvidence() {
        UUID docId = UUID.randomUUID();
        UUID chapterId = UUID.randomUUID();
        UUID sectionId = UUID.randomUUID();
        UUID paragraphId = UUID.randomUUID();
        UUID chunkId = UUID.randomUUID();
        return new ChunkWithAncestors(
                new Chunk(chunkId, paragraphId, 0, "chunk text", new float[]{0.1f}),
                new Paragraph(paragraphId, sectionId, 0, "raw paragraph text", "paragraph claim"),
                new Section(sectionId, chapterId, 0, "Methods", "section claim"),
                new Chapter(chapterId, docId, 0, "Chapter 1", "chapter claim", false),
                new Document(docId, "Test paper", "/tmp/x", "doc claim",
                        DocSummarySource.GENERATED, Instant.now(), Map.of()));
    }
}
