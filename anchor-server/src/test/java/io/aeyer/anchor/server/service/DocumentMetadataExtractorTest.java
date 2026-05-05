package io.aeyer.anchor.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.aeyer.anchor.server.domain.Citation;
import io.aeyer.anchor.server.llm.ChatCompletion;
import io.aeyer.anchor.server.llm.LMStudioClient;
import io.aeyer.anchor.server.workers.WorkerPoolProperties;
import io.aeyer.anchor.server.workers.WorkerPools;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

class DocumentMetadataExtractorTest {

    private LMStudioClient llm;
    private WorkerPools pools;
    private DocumentMetadataExtractor extractor;

    @BeforeEach
    void setUp() {
        llm = mock(LMStudioClient.class);
        WorkerPoolProperties props = new WorkerPoolProperties();
        pools = new WorkerPools(props);
        pools.init();

        extractor = new DocumentMetadataExtractor(llm, pools, new ObjectMapper());
        ReflectionTestUtils.setField(extractor, "authorsPrompt",
                new ClassPathResource("prompts/extract-authors.txt"));
        ReflectionTestUtils.setField(extractor, "citationsPrompt",
                new ClassPathResource("prompts/extract-citations.txt"));
        extractor.loadPrompts();
    }

    @AfterEach
    void tearDown() {
        pools.shutdown();
    }

    @Test
    void parses_authors_json_into_list_of_strings() {
        when(llm.complete(anyString(), anyString(), eq(0.0)))
                .thenReturn(new ChatCompletion(
                        "{\"authors\": [\"Adam Zsolt Wagner\", \"Co-Author Name\"]}",
                        "stop", null));

        List<String> authors = extractor.extractAuthors("REFUTING CONJECTURES IN EXTREMAL COMBINATORICS\nADAM ZSOLT WAGNER");

        assertThat(authors).containsExactly("Adam Zsolt Wagner", "Co-Author Name");
    }

    @Test
    void code_fenced_authors_json_is_unwrapped() {
        when(llm.complete(any(), anyString(), eq(0.0)))
                .thenReturn(new ChatCompletion(
                        "```json\n{\"authors\": [\"X Author\"]}\n```",
                        "stop", null));
        assertThat(extractor.extractAuthors("front matter")).containsExactly("X Author");
    }

    @Test
    void duplicate_author_names_are_deduplicated() {
        when(llm.complete(any(), anyString(), eq(0.0)))
                .thenReturn(new ChatCompletion(
                        "{\"authors\": [\"Same Person\", \"Other\", \"Same Person\"]}",
                        "stop", null));
        assertThat(extractor.extractAuthors("front matter")).containsExactly("Same Person", "Other");
    }

    @Test
    void garbled_llm_response_yields_empty_authors_list_not_crash() {
        when(llm.complete(any(), anyString(), eq(0.0)))
                .thenReturn(new ChatCompletion("not json at all", "stop", null));
        assertThat(extractor.extractAuthors("front matter")).isEmpty();
    }

    @Test
    void blank_input_returns_empty_authors_list_without_an_llm_call() {
        assertThat(extractor.extractAuthors("")).isEmpty();
        assertThat(extractor.extractAuthors(null)).isEmpty();
        assertThat(extractor.extractAuthors("   ")).isEmpty();
    }

    @Test
    void parses_citations_json_into_list_of_records() {
        when(llm.complete(anyString(), anyString(), eq(0.0)))
                .thenReturn(new ChatCompletion(
                        "{\"citations\": [" +
                        "{\"ref_num\": 16, \"raw\": \"Peter Frankl, Extremal problems...\"}," +
                        "{\"ref_num\": 18, \"raw\": \"Peter Frankl, Antichains...\"}" +
                        "]}",
                        "stop", null));

        List<Citation> citations = extractor.extractCitations("[16] Peter Frankl, ...");

        assertThat(citations).hasSize(2);
        assertThat(citations.get(0).refNum()).isEqualTo(16);
        assertThat(citations.get(0).raw()).contains("Peter Frankl");
        assertThat(citations.get(1).refNum()).isEqualTo(18);
    }

    @Test
    void citations_with_missing_refnum_or_raw_are_dropped_not_thrown() {
        when(llm.complete(any(), anyString(), eq(0.0)))
                .thenReturn(new ChatCompletion(
                        "{\"citations\": [" +
                        "{\"ref_num\": 1, \"raw\": \"keeper\"}," +
                        "{\"ref_num\": \"not-an-int\", \"raw\": \"dropped\"}," +
                        "{\"ref_num\": 3}," +                        // missing raw
                        "{\"raw\": \"missing ref_num\"}" +
                        "]}",
                        "stop", null));
        List<Citation> citations = extractor.extractCitations("references text");
        assertThat(citations).hasSize(1);
        assertThat(citations.get(0).refNum()).isEqualTo(1);
        assertThat(citations.get(0).raw()).isEqualTo("keeper");
    }

    @Test
    void blank_references_text_returns_empty_citations_without_an_llm_call() {
        assertThat(extractor.extractCitations("")).isEmpty();
        assertThat(extractor.extractCitations(null)).isEmpty();
    }

    @Test
    void to_metadata_list_serialises_citations_to_plain_maps_for_jsonb() {
        var meta = DocumentMetadataExtractor.toMetadataList(
                List.of(new Citation(1, "Foo et al."), new Citation(2, "Bar et al.")));
        assertThat(meta).hasSize(2);
        assertThat(meta.get(0)).containsEntry("ref_num", 1).containsEntry("raw", "Foo et al.");
        assertThat(meta.get(1)).containsEntry("ref_num", 2).containsEntry("raw", "Bar et al.");
    }
}
