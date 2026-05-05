package io.aeyer.anchor.server.domain;

/**
 * A single entry from a document's references / bibliography list, captured
 * as the bracketed-or-numbered marker plus the raw line of text that follows
 * it. No BibTeX-grade parsing — by design, see Tier 2.5 follow-up. The raw
 * string is what the model gets to read; downstream consumers can apply
 * stricter parsers if/when needed.
 *
 * Stored inside {@code Document.metadata.citations} as a list of plain maps;
 * the {@code refNum} is the in-text citation marker (e.g. {@code [16]} →
 * {@code 16}) so the deliberation prompt can cross-reference body text like
 * "Conjecture 3.3 ([16])" against the named author.
 */
public record Citation(int refNum, String raw) {}
