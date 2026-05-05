package io.aeyer.anchor.server.domain;

/**
 * Type-enforced boundary between "this structural unit has a real, document-
 * owned title that's safe to show" and "this is a parser-invented unit whose
 * stored title is a sentinel that must never reach the LLM, the API, or the
 * UI."
 *
 * Every render site (prompt assembly, REST DTOs, citation strings) takes a
 * {@link Section} or {@link Chapter} through {@link #ofSection} /
 * {@link #ofChapter}, then switch-expression-matches on the result. The
 * sealed hierarchy makes the compiler complain if a new variant is ever
 * added and a render site forgets to handle it; grep for {@code .title()}
 * outside this file + the persistence mapper finds any boundary that
 * skipped the helper.
 *
 * The synthetic-vs-named split is the *only* leak-prevention contract — what
 * to render in the synthetic case is per-call-site policy (drop the title,
 * substitute the parent's, render a generic placeholder, …). This interface
 * deliberately has no opinion about that.
 */
public sealed interface StructuralRef {

    /** A real title taken verbatim from the source document. Safe to print. */
    record Named(String title) implements StructuralRef {}

    /** Parser-invented unit. Caller decides how to degrade gracefully. */
    record Synthetic() implements StructuralRef {}

    static StructuralRef ofSection(Section section) {
        return section.isSynthetic() ? new Synthetic() : new Named(section.title());
    }

    static StructuralRef ofChapter(Chapter chapter) {
        return chapter.isSynthetic() ? new Synthetic() : new Named(chapter.title());
    }
}
