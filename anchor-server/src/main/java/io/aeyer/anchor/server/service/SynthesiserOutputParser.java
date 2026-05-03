package io.aeyer.anchor.server.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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
            return mapper.convertValue(root, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of("raw_output", truncate(jsonPart, 500));
        }
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
