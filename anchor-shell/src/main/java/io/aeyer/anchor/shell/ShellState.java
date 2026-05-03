package io.aeyer.anchor.shell;

import io.aeyer.anchor.client.AnchorDocument;
import org.springframework.stereotype.Component;

/**
 * In-memory bound-document state for the interactive shell. The "select a doc
 * then query" UX (SPEC §15) is implemented client-side; this is its v0
 * single-session container.
 */
@Component
public class ShellState {

    private volatile AnchorDocument boundDocument;

    public void bind(AnchorDocument doc) { this.boundDocument = doc; }
    public void clear() { this.boundDocument = null; }
    public AnchorDocument bound() { return boundDocument; }
    public boolean isBound() { return boundDocument != null; }
}
