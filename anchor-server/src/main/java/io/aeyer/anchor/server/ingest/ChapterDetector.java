package io.aeyer.anchor.server.ingest;

import java.util.ArrayList;
import java.util.List;
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

        return List.of(new DetectedChapter("Document", 0, 0, lines.length, true));
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
        for (int i = 0; i < starts.size(); i++) {
            int start = starts.get(i);
            int end = (i + 1 < starts.size()) ? starts.get(i + 1) : lines.length;
            String title = lines[start].trim();
            chapters.add(new DetectedChapter(title, i, start, end, synthetic));
        }
        return chapters;
    }

    /** Range {@code [startLine, endLine)} — the chapter heading is line {@code startLine}. */
    public record DetectedChapter(String title, int orderIndex, int startLine, int endLine, boolean synthetic) {}
}
