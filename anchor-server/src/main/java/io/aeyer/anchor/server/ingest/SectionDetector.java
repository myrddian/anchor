package io.aeyer.anchor.server.ingest;

import io.aeyer.anchor.server.domain.SyntheticTitles;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Splits a chapter's text into sections per SPEC §4.3.
 *
 * Detection strategy: numbered headings (`^[0-9]+\\.\\s+[A-Z]`), short title-cased
 * lines followed by paragraph text, and explicit chemistry section names. A
 * section recognised as the references list is dropped — references are not
 * load-bearing for claim-based summarisation.
 *
 * Anchor's first audience is chemistry; the chemistry-name set lives here as the
 * canonical list. Other domain corpora can extend this later but the chemistry
 * list is the v0 baseline (SPEC §4.3).
 */
@Service
public class SectionDetector {

    private static final Set<String> CHEMISTRY_SECTIONS = Set.of(
            "abstract", "introduction", "background", "methods", "materials and methods",
            "experimental", "experimental section", "results", "results and discussion",
            "discussion", "conclusion", "conclusions", "references", "acknowledgements",
            "acknowledgments", "supporting information");

    private static final Set<String> EXCLUDED_SECTIONS = Set.of(
            "references", "bibliography", "acknowledgements", "acknowledgments");

    // LP / optimisation / proof-style keywords that look like single-word
    // title-case headings (`Minimize`, `Theorem`, `Lemma`, ...) but are
    // actually inline structural elements of mathematical prose. Tier 2.5
    // follow-up: surfaced by the Wagner LP paper, where `Minimize:` lines
    // intro'd LP problem statements and the parser misclassified them as
    // section headings. Normalised lowercase, dot-stripped — same shape
    // the matcher computes from each candidate line.
    private static final Set<String> MATH_LP_NON_HEADINGS = Set.of(
            "minimize", "maximize", "minimise", "maximise",
            "subject to", "st",
            "proof", "proofs",
            "theorem", "lemma", "corollary", "proposition",
            "definition", "remark", "example", "claim",
            "case", "cases",
            "note", "notation");

    private static final Pattern NUMBERED_HEADING = Pattern.compile("^\\s*\\d+\\.\\s+[A-Z].{0,80}$");

    // Multi-level numbered subsection: `3.1. Antichains of fixed diameter` /
    // `3.1 Foo` / `3.10. Bar`. Tier 2.5 follow-up: the parser previously
    // missed these (NUMBERED_HEADING only matches single-level `\d+\.`) and
    // the synthesiser ended up lifting subsection titles directly out of
    // chunk text into the GROUNDING block — a "helpful hallucination" that
    // still relaxes the contract. Detecting them up front means they're
    // first-class section rows in the DB instead.
    private static final Pattern NUMBERED_SUBSECTION = Pattern.compile(
            "^\\s*\\d+\\.\\d+\\.?\\s+[A-Z].{0,80}$");

    // Tightened from `^([A-Z][a-zA-Z]*\s*){1,6}$` (Tier 2.5 follow-up): the loose
    // form matched LaTeX-flattened math residue like "X Y" (subscripts stripped
    // by Tika), promoting it to a section title that then leaked into the LLM
    // prompt as `[X Y]`. Now: each word must be ≥2 chars (kills single-letter
    // tokens which are almost always math residue, never headings) and the
    // whole heading must be ≥6 chars in addition to the line-length cap below.
    private static final Pattern TITLE_CASE_HEADING = Pattern.compile("^\\s*([A-Z][a-zA-Z]+\\s*){1,6}$");
    private static final int MIN_TITLE_CASE_HEADING_LENGTH = 6;

    public List<DetectedSection> detect(String[] lines, int chapterStartLine, int chapterEndLine) {
        if (chapterStartLine >= chapterEndLine) return List.of();

        // Skip the chapter heading line itself when scanning for sections.
        int contentStart = chapterStartLine + 1;
        List<Integer> starts = new LinkedHashSet<Integer>().stream().toList();
        List<Integer> boundaries = new ArrayList<>();

        for (int i = contentStart; i < chapterEndLine; i++) {
            String raw = lines[i];
            String trimmed = raw.trim();
            if (trimmed.isEmpty()) continue;

            String normalised = trimmed.toLowerCase(Locale.ROOT).replaceAll("[^a-z\\s]", "").trim();

            // LP / proof keywords always lose, even if they'd otherwise pass
            // the chemistry-name or title-case checks. `Minimize` matches
            // TITLE_CASE_HEADING and is ≥6 chars; without this guard it ends
            // up as a section title in any LP paper.
            if (MATH_LP_NON_HEADINGS.contains(normalised)) continue;

            boolean chemistryName = CHEMISTRY_SECTIONS.contains(normalised);
            boolean numbered = NUMBERED_HEADING.matcher(raw).matches();
            boolean numberedSubsection = NUMBERED_SUBSECTION.matcher(raw).matches();
            boolean titleCase = TITLE_CASE_HEADING.matcher(raw).matches()
                    && trimmed.length() >= MIN_TITLE_CASE_HEADING_LENGTH
                    && trimmed.length() <= 60
                    && nextNonEmptyLooksLikeProse(lines, i + 1, chapterEndLine);

            if (chemistryName || numbered || numberedSubsection || titleCase) {
                boundaries.add(i);
            }
        }

        if (boundaries.isEmpty()) {
            // Whole chapter body is one synthetic section. Title is the
            // sentinel from SyntheticTitles — the rendering boundary
            // (StructuralRef + helpers) drops it before any user/LLM-facing
            // surface, and the obvious-internal string makes a missed
            // boundary visible in logs.
            return List.of(new DetectedSection(
                    SyntheticTitles.SECTION, 0, false, true, contentStart, chapterEndLine));
        }

        List<DetectedSection> sections = new ArrayList<>();
        int order = 0;
        for (int i = 0; i < boundaries.size(); i++) {
            int start = boundaries.get(i);
            int end = (i + 1 < boundaries.size()) ? boundaries.get(i + 1) : chapterEndLine;
            String rawLine = lines[start].trim();
            String title = NUMBERED_SUBSECTION.matcher(rawLine).matches()
                    ? trimSubsectionTitle(rawLine)
                    : rawLine;
            String key = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z\\s]", "").trim();
            if (EXCLUDED_SECTIONS.contains(key)) continue; // references etc. dropped wholesale
            boolean isAbstract = "abstract".equals(key);
            sections.add(new DetectedSection(title, order++, isAbstract, false, start + 1, end));
        }
        return sections;
    }

    /**
     * Tika often joins a numbered subsection heading onto the same physical
     * line as the first sentence of body prose: {@code "3.1. Antichains of
     * fixed diameter. Define the diameter diam(F) of a family F ⊂ 2[n] as"}.
     * The regex match is correct (it IS a subsection), but the captured
     * title shouldn't carry the body-text overflow into the DB / API / LLM
     * prompts. Truncate at the first period after the section-number
     * prefix — that's almost always the title-terminating period before
     * sentence-case prose begins.
     */
    private String trimSubsectionTitle(String line) {
        int i = 0;
        // section-number digits + first period (`3`)
        while (i < line.length() && Character.isDigit(line.charAt(i))) i++;
        if (i < line.length() && line.charAt(i) == '.') i++;
        // subsection-number digits + optional second period (`1.`)
        while (i < line.length() && Character.isDigit(line.charAt(i))) i++;
        if (i < line.length() && line.charAt(i) == '.') i++;
        // whitespace before the title proper
        while (i < line.length() && Character.isWhitespace(line.charAt(i))) i++;

        // First period from here on terminates the title. If absent (the
        // whole line IS the title), keep the line as-is.
        int endOfTitle = line.indexOf('.', i);
        return endOfTitle < 0 ? line : line.substring(0, endOfTitle + 1);
    }

    private boolean nextNonEmptyLooksLikeProse(String[] lines, int from, int end) {
        for (int j = from; j < end; j++) {
            String t = lines[j].trim();
            if (t.isEmpty()) continue;
            // Prose: at least one space and ends without being all-uppercase
            return t.contains(" ") && !t.equals(t.toUpperCase(Locale.ROOT));
        }
        return false;
    }

    /** Body range {@code [bodyStartLine, endLine)} excludes the heading line itself. */
    public record DetectedSection(
            String title,
            int orderIndex,
            boolean isAbstract,
            boolean isSynthetic,
            int bodyStartLine,
            int endLine) {}
}
