package io.aeyer.anchor.server.domain;

/**
 * Sentinel strings the parser writes into {@code chapters.title} and
 * {@code sections.title} when it had to invent a structural unit because the
 * source document had no detectable heading at that level.
 *
 * Defense-in-depth pair to {@link StructuralRef}: the rendering boundary
 * filters synthetic units out of all user/LLM-facing output, but if anyone
 * bypasses the helper these obviously-internal strings make the bug visible
 * instantly rather than blending in as a plausible-looking section name like
 * the previous {@code "Body"} / {@code "Document"} fallbacks did.
 */
public final class SyntheticTitles {

    public static final String CHAPTER = "__SYNTHETIC_SEGMENT__";
    public static final String SECTION = "__SYNTHETIC_HEAP__";

    private SyntheticTitles() {}
}
