package io.aeyer.anchor.server.ingest;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Splits raw extracted text into chapters per SPEC §4.2.
 *
 * Precedence: explicit `^Chapter N` regex → PDF outline top-level entries
 * → `^Part I/II` markers → single synthetic chapter as last resort. The
 * font-size-aligned heading detector is deferred — it requires per-page
 * positioning data we don't carry through {@link PdfTextExtractor} yet.
 *
 * Chapter ranges are emitted as {@code [startLine, endLine)} so the section
 * detector can operate on a slice without losing line indices.
 */
@Service
public class ChapterDetector {

    private static final Pattern CHAPTER_REGEX =
            Pattern.compile("^\\s*Chapter\\s+[0-9IVXLC]+\\b.*", Pattern.CASE_INSENSITIVE);
    private static final Pattern PART_REGEX =
            Pattern.compile("^\\s*Part\\s+[0-9IVXLC]+\\b.*", Pattern.CASE_INSENSITIVE);

    /**
     * Top-level headings that look chapter-shaped to the regex/outline
     * detector but are non-content (front matter, back matter, navigational
     * scaffolding). When these get promoted to chapters, the per-chapter
     * summarizer dutifully writes a "References" or "Appendix" summary,
     * which the deliberation prompt then surfaces as authoritative content
     * — and the model writes things like "the References chapter concludes
     * with a counterexample," fragment-stitching that reads as fabrication.
     *
     * Filter is run after stripping leading numeric prefixes (e.g.
     * "5. References" → "references") so numbered bibliographies are
     * caught alongside unnumbered ones.
     */
    private static final Set<String> EXCLUDED_CHAPTER_TITLES = Set.of(
            "references", "bibliography", "works cited",
            "acknowledgements", "acknowledgments",
            "appendix", "appendices",
            "supporting information", "supplementary material",
            "table of contents", "contents", "index",
            "abstract");

    /**
     * What the source document calls its top-level groupings. Used by the
     * deliberation prompts (see {@code AskService}) so we don't tell the
     * model "YOUR CHAPTERS" when the paper itself uses "Section". Mismatch
     * was the root cause of deliberation outputs that read like
     * fabrication: the model was correctly citing our internal labels but
     * those labels contradicted the document's own self-references.
     */
    public enum Vocabulary {
        CHAPTER, SECTION, PART;

        public String singular()    { return name().toLowerCase(Locale.ROOT); }
        public String plural()      { return singular() + "s"; }
        public String singularCap() { return name().charAt(0) + singular().substring(1); }
        public String pluralCap()   { return singularCap() + "s"; }

        /**
         * What the document calls the level *below* its top-level groupings.
         * Books: chapter→section. Academic papers: section→subsection (so the
         * prompt can distinguish "section 2" from "subsection 2.3").
         * Parts: section, since we don't model the part→chapter→section
         * hierarchy fully — the part-document's middle level collapses to
         * 'section' in our two-level-only schema.
         */
        public String midLevel() {
            return switch (this) {
                case CHAPTER, PART -> "section";
                case SECTION -> "subsection";
            };
        }
        public String midLevelPlural()    { return midLevel() + "s"; }
        public String midLevelPluralCap() {
            return Character.toUpperCase(midLevel().charAt(0)) + midLevelPlural().substring(1);
        }
    }

    /**
     * Best-effort detection of the source's preferred terminology. Counts
     * heading-shaped lines that look like {@code Chapter N}, {@code Part N},
     * or {@code N. Title} (academic-paper section style). The format
     * with the most matches wins; "section" is the default because untagged
     * academic papers dominate Anchor's corpus.
     */
    public Vocabulary detectVocabulary(String fullText) {
        if (fullText == null || fullText.isBlank()) return Vocabulary.SECTION;
        String[] lines = fullText.split("\\R", -1);
        int chapterHits = 0, partHits = 0, sectionHits = 0;
        Pattern numbered = Pattern.compile("^\\s*\\d+\\.\\s+[A-Z].{0,80}$");
        for (String line : lines) {
            if (CHAPTER_REGEX.matcher(line).matches()) chapterHits++;
            else if (PART_REGEX.matcher(line).matches()) partHits++;
            else if (numbered.matcher(line).matches()) sectionHits++;
        }
        if (chapterHits >= 2 && chapterHits >= partHits && chapterHits >= sectionHits) {
            return Vocabulary.CHAPTER;
        }
        if (partHits >= 2 && partHits > sectionHits) {
            return Vocabulary.PART;
        }
        return Vocabulary.SECTION;
    }

    public List<DetectedChapter> detect(String fullText, List<String> pdfOutlineTopLevel) {
        if (fullText == null || fullText.isBlank()) return List.of();
        String[] lines = fullText.split("\\R", -1);

        List<Integer> chapterStarts = findRegexBoundaries(lines, CHAPTER_REGEX);
        if (!chapterStarts.isEmpty()) return materialise(lines, chapterStarts, false);

        if (pdfOutlineTopLevel != null && !pdfOutlineTopLevel.isEmpty()) {
            List<Integer> outlineStarts = findOutlineBoundaries(lines, pdfOutlineTopLevel);
            if (!outlineStarts.isEmpty()) return materialise(lines, outlineStarts, false);
        }

        List<Integer> partStarts = findRegexBoundaries(lines, PART_REGEX);
        if (!partStarts.isEmpty()) return materialise(lines, partStarts, false);

        return List.of(new DetectedChapter(
                io.aeyer.anchor.server.domain.SyntheticTitles.CHAPTER, 0, 0, lines.length, true));
    }

    private List<Integer> findRegexBoundaries(String[] lines, Pattern pattern) {
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            if (pattern.matcher(lines[i]).matches()) starts.add(i);
        }
        return starts;
    }

    private List<Integer> findOutlineBoundaries(String[] lines, List<String> outline) {
        Set<String> wanted = outline.stream().map(s -> s.toLowerCase().trim()).collect(java.util.stream.Collectors.toSet());
        List<Integer> starts = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String trimmed = lines[i].trim().toLowerCase();
            if (!trimmed.isEmpty() && wanted.contains(trimmed)) starts.add(i);
        }
        return starts;
    }

    private List<DetectedChapter> materialise(String[] lines, List<Integer> starts, boolean synthetic) {
        List<DetectedChapter> chapters = new ArrayList<>();
        int order = 0;
        for (int i = 0; i < starts.size(); i++) {
            int start = starts.get(i);
            int end = (i + 1 < starts.size()) ? starts.get(i + 1) : lines.length;
            String title = lines[start].trim();
            if (isExcludedTitle(title)) continue;  // skip front/back-matter
            chapters.add(new DetectedChapter(title, order++, start, end, synthetic));
        }
        return chapters;
    }

    /**
     * True when the heading is non-content scaffolding we don't want
     * promoted to a first-class chapter — see {@link #EXCLUDED_CHAPTER_TITLES}.
     * Strips leading numbering (e.g. "5. References") + non-letter chars
     * before matching so "5. References", "References", and
     * "REFERENCES" all collapse to the same key.
     */
    private static boolean isExcludedTitle(String title) {
        if (title == null) return false;
        String key = title.toLowerCase(Locale.ROOT)
                .replaceAll("^[\\d\\.\\s]+", "")    // strip leading numbering
                .replaceAll("[^a-z\\s]", "")
                .trim();
        return EXCLUDED_CHAPTER_TITLES.contains(key);
    }

    /** Range {@code [startLine, endLine)} — the chapter heading is line {@code startLine}. */
    public record DetectedChapter(String title, int orderIndex, int startLine, int endLine, boolean synthetic) {}
}
