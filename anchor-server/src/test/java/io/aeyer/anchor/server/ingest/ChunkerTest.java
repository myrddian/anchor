package io.aeyer.anchor.server.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import io.aeyer.anchor.server.ingest.Chunker.Chunk;
import java.util.List;
import org.junit.jupiter.api.Test;

class ChunkerTest {

    private final Chunker chunker = new Chunker();

    @Test
    void empty_input_returns_no_chunks() {
        assertThat(chunker.chunk("", 300)).isEmpty();
        assertThat(chunker.chunk(null, 300)).isEmpty();
        assertThat(chunker.chunk("   ", 300)).isEmpty();
    }

    @Test
    void short_paragraph_fits_in_one_chunk() {
        String paragraph = "The catalyst showed unexpected selectivity. Yields exceeded 90 percent.";
        List<Chunk> chunks = chunker.chunk(paragraph, 300);
        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).contains("catalyst").contains("Yields");
    }

    @Test
    void chunks_break_on_sentence_boundaries_not_mid_sentence() {
        // Build text that exceeds the 10-token budget; we want sentence-respect.
        String text = "The first sentence is short. The second sentence is also short. "
                + "Now a much longer third sentence packs many more words to push over budget.";
        List<Chunk> chunks = chunker.chunk(text, 10);

        assertThat(chunks.size()).isGreaterThanOrEqualTo(2);
        for (Chunk c : chunks) {
            // Each chunk should END with sentence punctuation (no mid-sentence cuts).
            assertThat(c.text()).matches(".*[.!?]\\s*$");
        }
    }

    @Test
    void single_oversized_sentence_overflows_rather_than_splits() {
        // One sentence above budget — overflow as standalone chunk.
        String giant = "This single sentence has many many many words designed to exceed the small target token budget on its own.";
        List<Chunk> chunks = chunker.chunk(giant, 5);

        assertThat(chunks).hasSize(1);
        assertThat(chunks.get(0).text()).isEqualTo(giant);
        assertThat(chunks.get(0).approxTokens()).isGreaterThan(5);
    }

    @Test
    void mixes_normal_and_oversized_sentences_correctly() {
        String shortA = "Short A.";
        String shortB = "Short B.";
        String giant = "This particular giant sentence intentionally contains very many words far above any reasonable token budget.";
        String shortC = "Short C.";
        String text = String.join(" ", shortA, shortB, giant, shortC);

        List<Chunk> chunks = chunker.chunk(text, 6);

        // shortA+shortB combined fit under 6 tokens, then giant overflows alone, then shortC.
        assertThat(chunks).hasSize(3);
        assertThat(chunks.get(0).text()).contains("Short A").contains("Short B");
        assertThat(chunks.get(1).text()).isEqualTo(giant);
        assertThat(chunks.get(2).text()).isEqualTo("Short C.");
    }
}
