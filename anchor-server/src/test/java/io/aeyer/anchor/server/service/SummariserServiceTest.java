package io.aeyer.anchor.server.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.aeyer.anchor.server.llm.ChatCompletion;
import io.aeyer.anchor.server.llm.LMStudioClient;
import io.aeyer.anchor.server.llm.TokenUsage;
import io.aeyer.anchor.server.workers.WorkerPoolProperties;
import io.aeyer.anchor.server.workers.WorkerPools;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

class SummariserServiceTest {

    private LMStudioClient llm;
    private WorkerPools pools;
    private TokenLedger ledger;
    private SummariserService summariser;

    @BeforeEach
    void setUp() {
        llm = mock(LMStudioClient.class);
        WorkerPoolProperties props = new WorkerPoolProperties();
        pools = new WorkerPools(props);
        pools.init();
        ledger = new TokenLedger(new SimpleMeterRegistry());

        summariser = new SummariserService(llm, pools, ledger);
        ReflectionTestUtils.setField(summariser, "paragraphPrompt",
                new ClassPathResource("prompts/paragraph-summary.txt"));
        ReflectionTestUtils.setField(summariser, "sectionPrompt",
                new ClassPathResource("prompts/section-summary.txt"));
        ReflectionTestUtils.setField(summariser, "chapterPrompt",
                new ClassPathResource("prompts/chapter-summary.txt"));
        ReflectionTestUtils.setField(summariser, "docPrompt",
                new ClassPathResource("prompts/doc-summary.txt"));
        summariser.loadPrompts();
    }

    @AfterEach
    void tearDown() {
        pools.shutdown();
    }

    @Test
    void paragraph_summary_substitutes_text_into_template() {
        when(llm.complete(anyString(), anyString(), eq(0.2)))
                .thenReturn(new ChatCompletion("paragraph claim", "stop", null));

        String result = summariser.summariseParagraph("The catalyst yielded 95%.");

        assertThat(result).isEqualTo("paragraph claim");
        ArgumentCaptor<String> userPrompt = ArgumentCaptor.forClass(String.class);
        verify(llm).complete(any(), userPrompt.capture(), eq(0.2));
        assertThat(userPrompt.getValue()).contains("The catalyst yielded 95%.");
        assertThat(userPrompt.getValue()).doesNotContain("{paragraph_text}");
    }

    @Test
    void section_summary_only_sees_paragraph_summaries_not_raw_text() {
        when(llm.complete(any(), anyString(), eq(0.2)))
                .thenReturn(new ChatCompletion("section claim", "stop", null));

        summariser.summariseSection("Methods", List.of("para 1 claim", "para 2 claim"));

        ArgumentCaptor<String> prompt = ArgumentCaptor.forClass(String.class);
        verify(llm).complete(any(), prompt.capture(), eq(0.2));
        assertThat(prompt.getValue()).contains("Methods");
        assertThat(prompt.getValue()).contains("1. para 1 claim");
        assertThat(prompt.getValue()).contains("2. para 2 claim");
        assertThat(prompt.getValue()).doesNotContain("{concatenated_paragraph_summaries}");
    }

    @Test
    void empty_summary_triggers_retry_at_temperature_zero() {
        when(llm.complete(any(), anyString(), eq(0.2)))
                .thenReturn(new ChatCompletion("", null, null));
        when(llm.complete(any(), anyString(), eq(0.0)))
                .thenReturn(new ChatCompletion("recovered claim", "stop", null));

        String result = summariser.summariseParagraph("some text");

        assertThat(result).isEqualTo("recovered claim");
        verify(llm).complete(any(), anyString(), eq(0.2));
        verify(llm).complete(any(), anyString(), eq(0.0));
    }

    @Test
    void two_consecutive_empty_summaries_fail_loudly() {
        when(llm.complete(any(), anyString(), any(Double.class)))
                .thenReturn(new ChatCompletion("", null, null));

        assertThatThrownBy(() -> summariser.summariseParagraph("x"))
                .isInstanceOf(SummariserException.class)
                .hasMessageContaining("empty content twice");
    }

    @Test
    void token_usage_is_recorded_in_the_ledger() {
        when(llm.complete(any(), anyString(), any(Double.class)))
                .thenReturn(new ChatCompletion("done", "stop", new TokenUsage(100, 20, 120)));

        summariser.summariseParagraph("text");

        TokenLedger.Snapshot snap = ledger.snapshotAndReset();
        assertThat(snap.summaryInput()).isEqualTo(100);
        assertThat(snap.summaryOutput()).isEqualTo(20);
    }
}
