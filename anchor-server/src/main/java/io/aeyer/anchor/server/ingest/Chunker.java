package io.aeyer.anchor.server.ingest;

import java.text.BreakIterator;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

/**
 * Sentence-aware chunker per SPEC §4.4.
 *
 * Pulls full sentences into a chunk until the running token estimate exceeds
 * {@code chunkTargetTokens}; any sentence longer than the budget on its own
 * overflows as a standalone chunk rather than being split mid-sentence
 * (preserves citation grounding — never quote half a claim).
 *
 * Token estimate is whitespace-split words. That's coarser than a real BPE
 * count but adequate for chunking decisions in v0; the LLM tokeniser would
 * count slightly more on average, so chunks land a touch under target — fine.
 */
@Service
public class Chunker {

    public List<Chunk> chunk(String paragraphText, int chunkTargetTokens) {
        if (paragraphText == null || paragraphText.isBlank()) return List.of();
        List<String> sentences = splitSentences(paragraphText.trim());
        if (sentences.isEmpty()) return List.of();

        List<Chunk> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int currentTokens = 0;

        for (String sentence : sentences) {
            int sentenceTokens = approxTokens(sentence);

            if (sentenceTokens > chunkTargetTokens) {
                flush(current, currentTokens, chunks);
                current.setLength(0);
                currentTokens = 0;
                chunks.add(new Chunk(sentence, sentenceTokens));
                continue;
            }

            if (currentTokens + sentenceTokens > chunkTargetTokens && currentTokens > 0) {
                flush(current, currentTokens, chunks);
                current.setLength(0);
                currentTokens = 0;
            }

            if (current.length() > 0) current.append(' ');
            current.append(sentence);
            currentTokens += sentenceTokens;
        }
        flush(current, currentTokens, chunks);
        return chunks;
    }

    private void flush(StringBuilder buf, int tokens, List<Chunk> chunks) {
        if (buf.length() == 0) return;
        chunks.add(new Chunk(buf.toString().trim(), tokens));
    }

    private List<String> splitSentences(String text) {
        List<String> out = new ArrayList<>();
        BreakIterator it = BreakIterator.getSentenceInstance(Locale.US);
        it.setText(text);
        int start = it.first();
        for (int end = it.next(); end != BreakIterator.DONE; start = end, end = it.next()) {
            String sentence = text.substring(start, end).trim();
            if (!sentence.isEmpty()) out.add(sentence);
        }
        return out;
    }

    private int approxTokens(String text) {
        if (text == null || text.isBlank()) return 0;
        return text.trim().split("\\s+").length;
    }

    public record Chunk(String text, int approxTokens) {}
}
