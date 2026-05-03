package io.aeyer.anchor.server.ingest;

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

    private static final Pattern NUMBERED_HEADING = Pattern.compile("^\\s*\\d+\\.\\s+[A-Z].{0,80}$");
    private static final Pattern TITLE_CASE_HEADING = Pattern.compile("^\\s*([A-Z][a-zA-Z]*\\s*){1,6}$");

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
            boolean chemistryName = CHEMISTRY_SECTIONS.contains(normalised);
            boolean numbered = NUMBERED_HEADING.matcher(raw).matches();
            boolean titleCase = TITLE_CASE_HEADING.matcher(raw).matches()
                    && trimmed.length() <= 60
                    && nextNonEmptyLooksLikeProse(lines, i + 1, chapterEndLine);

            if (chemistryName || numbered || titleCase) {
                boundaries.add(i);
            }
        }

        if (boundaries.isEmpty()) {
            // Whole chapter body is one synthetic section.
            return List.of(new DetectedSection("Body", 0, false, contentStart, chapterEndLine));
        }

        List<DetectedSection> sections = new ArrayList<>();
        int order = 0;
        for (int i = 0; i < boundaries.size(); i++) {
            int start = boundaries.get(i);
            int end = (i + 1 < boundaries.size()) ? boundaries.get(i + 1) : chapterEndLine;
            String title = lines[start].trim();
            String key = title.toLowerCase(Locale.ROOT).replaceAll("[^a-z\\s]", "").trim();
            if (EXCLUDED_SECTIONS.contains(key)) continue; // references etc. dropped wholesale
            boolean isAbstract = "abstract".equals(key);
            sections.add(new DetectedSection(title, order++, isAbstract, start + 1, end));
        }
        return sections;
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
            int bodyStartLine,
            int endLine) {}
}
