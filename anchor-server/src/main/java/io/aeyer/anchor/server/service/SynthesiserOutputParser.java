package io.aeyer.anchor.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Parses the synthesiser's free-form chat output into a clean prose response
 * + structured grounding map.
 *
 * The synthesiser prompt asks for {@code RESPONSE:\n<text>\n\nGROUNDING:\n<json>}
 * but smaller chat models (e.g. Gemma 4 E4B observed in the first real-paper
 * smoke run) routinely:
 *   1. Omit the "RESPONSE:" prefix and just start the prose.
 *   2. Echo the trailing "SYNTHESISER OUTPUT:" prompt label and re-emit the
 *      prose a second time.
 *   3. Emit code fences around the JSON.
 *
 * Lifting this out of {@link AskService} so the parsing is testable in
 * isolation — the failure modes are all "what does this specific text input
 * produce" and don't need a Spring context.
 */
final class SynthesiserOutputParser {

    /**
     * Marker the prompt-block builders use for sections/chapters that the
     * parser invented (no document-owned title). Listed in the synthesiser
     * prompt as "skip these in grounding arrays" — but smaller models
     * sometimes grab the whole bullet anyway when all the relevant evidence
     * is synthetic and they're reluctant to leave the array empty. This
     * parser strips the resulting entries so the contract is enforced
     * regardless of model compliance.
     *
     * The detection is paren-insensitive on purpose: models routinely
     * paraphrase the marker, dropping the parentheses or capitalising it
     * differently (e.g. {@code "unnamed segment"} or
     * {@code "Unnamed Segment"}). The two-word phrase itself is the
     * enforcement boundary — anything containing it, regardless of
     * surrounding punctuation, is rejected.
     */
    private static final String UNNAMED_MARKER_PHRASE = "unnamed segment";

    private final ObjectMapper mapper;

    SynthesiserOutputParser(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /**
     * The prose answer is the longest non-empty span between recognised
     * section markers, in order of preference: from RESPONSE: (or start of
     * output if absent), terminated by GROUNDING: or the trailing
     * SYNTHESISER OUTPUT: echo, whichever comes first.
     */
    String extractResponse(String raw) {
        if (raw == null) return "";
        int responseIdx = raw.indexOf("RESPONSE:");
        int start = responseIdx >= 0 ? responseIdx + "RESPONSE:".length() : 0;

        int end = raw.length();
        for (String marker : new String[]{"GROUNDING:", "SYNTHESISER OUTPUT:"}) {
            int idx = raw.indexOf(marker, start);
            if (idx >= 0 && idx < end) end = idx;
        }
        return raw.substring(start, end).trim();
    }

    /**
     * Best-effort grounding extraction. If the model didn't emit a GROUNDING:
     * block, returns null. If it did but the JSON is unparseable (fences,
     * trailing junk, half a duplicate output), returns a Map with the raw
     * snippet so callers can still see what the model said without crashing.
     */
    Map<String, Object> extractGrounding(String raw) {
        if (raw == null) return null;
        int groundingIdx = raw.indexOf("GROUNDING:");
        if (groundingIdx < 0) return null;
        int end = raw.length();
        int trailingMarker = raw.indexOf("SYNTHESISER OUTPUT:", groundingIdx);
        if (trailingMarker > 0) end = trailingMarker;
        String jsonPart = stripFences(raw.substring(groundingIdx + "GROUNDING:".length(), end).trim());
        try {
            JsonNode root = mapper.readTree(jsonPart);
            Map<String, Object> grounding = mapper.convertValue(root, new TypeReference<>() {});
            return scrubSyntheticMarkers(grounding);
        } catch (Exception e) {
            return Map.of("raw_output", truncate(jsonPart, 500));
        }
    }

    /**
     * Drop any string entries in {@code grounded_in_chapters} /
     * {@code grounded_in_sections} that contain the unnamed-segment marker,
     * and deduplicate the survivors while preserving first-occurrence order.
     *
     * The model is supposed to skip the marker entries per the synthesiser
     * prompt; this is the belt to that braces, since the contract —
     * "GROUNDING titles are verbatim copies of document-owned titles, never
     * parser-internal markers, never duplicates" — is what downstream
     * tooling depends on. Deduplication is structural too: a model that
     * grounds in the same section three times is conveying one citation,
     * not three, and the array shape shouldn't lie about that.
     *
     * Returns a new map; never mutates the input. Preserves insertion order.
     */
    private Map<String, Object> scrubSyntheticMarkers(Map<String, Object> grounding) {
        if (grounding == null) return null;
        Map<String, Object> cleaned = new LinkedHashMap<>(grounding);
        for (String key : new String[]{"grounded_in_chapters", "grounded_in_sections"}) {
            Object value = cleaned.get(key);
            if (value instanceof List<?> raw) {
                java.util.LinkedHashSet<Object> seen = new java.util.LinkedHashSet<>(raw.size());
                for (Object item : raw) {
                    if (item instanceof String s
                            && s.toLowerCase(java.util.Locale.ROOT).contains(UNNAMED_MARKER_PHRASE)) {
                        continue;
                    }
                    seen.add(item);
                }
                cleaned.put(key, new ArrayList<>(seen));
            }
        }
        return cleaned;
    }

    private static String stripFences(String text) {
        String t = text.trim();
        if (t.startsWith("```")) {
            int firstNewline = t.indexOf('\n');
            if (firstNewline > 0) t = t.substring(firstNewline + 1);
            if (t.endsWith("```")) t = t.substring(0, t.length() - 3);
        }
        return t.trim();
    }

    private static String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() <= max ? s : s.substring(0, max) + "...";
    }
}
