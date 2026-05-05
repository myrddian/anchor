package io.aeyer.anchor.server.ingest;

import java.util.Locale;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Recovers the raw text of a document's references / bibliography section
 * for downstream citation extraction.
 *
 * The structural parser drops references entirely (see
 * {@link SectionDetector#EXCLUDED_SECTIONS}) because they aren't
 * claim-bearing — they don't contribute to summaries at any level. That's
 * the right call for the deliberation pipeline but it makes the citation
 * list invisible to anything downstream. This extractor walks the original
 * extracted text and grabs everything from the first references-style
 * heading to either the next chapter-style heading or end of document.
 *
 * Pure regex over the raw text — no LLM, no JPA. The downstream
 * {@link io.aeyer.anchor.server.service.DocumentMetadataExtractor} runs
 * the per-entry parsing.
 */
@Service
public class ReferencesExtractor {

    private static final Pattern REFERENCES_HEADING = Pattern.compile(
            "^\\s*(\\d+\\.?\\s*)?(references|bibliography)\\s*$",
            Pattern.CASE_INSENSITIVE);

    // Chapter-style heading that would terminate the references block. Mirrors
    // the patterns ChapterDetector uses (a numbered "N. Title" or a Part marker)
    // but kept narrower because we're looking for the *next* major section
    // boundary, not detecting chapters from scratch.
    private static final Pattern CHAPTER_BOUNDARY = Pattern.compile(
            "^\\s*(chapter\\s+\\d+|part\\s+[IVX]+|\\d+\\.\\s+[A-Z][a-zA-Z].{2,80})\\s*$");

    /**
     * Returns the raw text of the references section, joined by newlines,
     * or an empty string if no references heading is found. Excludes the
     * heading line itself.
     */
    public String findReferencesText(String fullText) {
        if (fullText == null || fullText.isBlank()) return "";
        String[] lines = fullText.split("\\R", -1);

        int start = -1;
        for (int i = 0; i < lines.length; i++) {
            if (REFERENCES_HEADING.matcher(lines[i]).matches()) {
                start = i + 1;
                break;
            }
        }
        if (start < 0) return "";

        int end = lines.length;
        for (int i = start; i < lines.length; i++) {
            // Don't terminate on the references heading we just consumed,
            // and require at least a few lines of content first to avoid
            // spurious early termination on a numbered entry the regex
            // would otherwise mistake for a chapter heading.
            if (i - start < 3) continue;
            String trimmed = lines[i].trim();
            if (trimmed.isEmpty()) continue;
            if (looksLikeReferenceEntry(trimmed)) continue;
            if (CHAPTER_BOUNDARY.matcher(lines[i]).matches()) {
                end = i;
                break;
            }
        }

        StringBuilder sb = new StringBuilder();
        for (int i = start; i < end; i++) {
            sb.append(lines[i]);
            if (i < end - 1) sb.append('\n');
        }
        return sb.toString().trim();
    }

    /**
     * Numbered reference entries ({@code "16. Author, Title"} or
     * {@code "[16] Author, Title"}) look like chapter headings to the
     * narrow CHAPTER_BOUNDARY regex above. Skip them so the extractor
     * doesn't truncate mid-references-list.
     */
    private boolean looksLikeReferenceEntry(String trimmed) {
        if (trimmed.startsWith("[")) return true;
        // "16." or "16. " followed by anything — distinguish from "1. Introduction"
        // by checking the line is long (refs entries usually wrap to ~80+ chars
        // even as a one-liner because of author + title + venue).
        int dot = trimmed.indexOf('.');
        if (dot > 0 && dot <= 4) {
            String prefix = trimmed.substring(0, dot);
            try {
                Integer.parseInt(prefix);
                // Numbered start; treat as a reference entry if the line is
                // long enough to be one. 30 chars is a heuristic — short
                // enough to not exclude any real references, long enough to
                // exclude one-word chapter titles like "1. Introduction".
                return trimmed.length() >= 30;
            } catch (NumberFormatException ignored) {
                // Not a numbered prefix
            }
        }
        // Lowercase-start lines are continuation lines from a wrapped reference.
        return !trimmed.isEmpty() && Character.isLowerCase(trimmed.charAt(0));
    }
}
