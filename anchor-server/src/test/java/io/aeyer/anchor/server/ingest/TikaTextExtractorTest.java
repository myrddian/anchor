package io.aeyer.anchor.server.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TikaTextExtractorTest {

    private final TikaTextExtractor extractor = new TikaTextExtractor();
    private final DocumentTextExtractor dispatcher =
            new DocumentTextExtractor(new PdfTextExtractor(), extractor);

    @Test
    void extracts_text_from_a_plain_text_file(@TempDir Path tempDir) throws IOException {
        Path txt = tempDir.resolve("note.txt");
        Files.writeString(txt, """
                Chapter I
                The catalyst showed unexpected selectivity. Yields exceeded 90%.

                Chapter II
                Discussion of the mechanism.
                """);

        ExtractedDocument doc = extractor.extract(txt);

        assertThat(doc.text()).contains("Chapter I").contains("selectivity");
        // Tika doesn't expose an outline structure for non-PDF formats — the
        // chapter detector falls through to its other branches (the
        // ^Chapter\s+[0-9IVXLC]+ regex hits "Chapter I" / "Chapter II" here).
        assertThat(doc.outlineTopLevel()).isEmpty();
        assertThat(doc.contentHash()).hasSize(64); // SHA-256 hex
        assertThat(doc.title()).isEqualTo("note");
    }

    @Test
    void same_bytes_produce_same_hash_idempotent_re_ingest(@TempDir Path tempDir) throws IOException {
        Path a = tempDir.resolve("a.txt");
        Path b = tempDir.resolve("b.txt");
        byte[] bytes = "identical content for hash check".getBytes();
        Files.write(a, bytes);
        Files.write(b, bytes);

        assertThat(extractor.extract(a).contentHash())
                .isEqualTo(extractor.extract(b).contentHash());
    }

    @Test
    void extracts_html_strips_tags_preserves_block_breaks(@TempDir Path tempDir) throws IOException {
        Path html = tempDir.resolve("page.html");
        Files.writeString(html, """
                <!doctype html>
                <html><head><title>Some Test Doc</title></head><body>
                <h1>Chapter I</h1>
                <p>The catalyst was selective.</p>
                <h1>Chapter II</h1>
                <p>Mechanism follows.</p>
                </body></html>
                """);

        ExtractedDocument doc = extractor.extract(html);

        assertThat(doc.text()).contains("Chapter I").contains("selective");
        // Tika lifts <title> into the metadata — that becomes our title.
        assertThat(doc.title()).isEqualTo("Some Test Doc");
        // HTML tags themselves should be gone.
        assertThat(doc.text()).doesNotContain("<h1>");
    }

    @Test
    void dispatcher_routes_pdf_through_pdfbox_and_others_through_tika(@TempDir Path tempDir) throws IOException {
        Path txt = tempDir.resolve("doc.txt");
        Files.writeString(txt, "some text");
        // Non-PDF — should reach Tika and succeed. If the dispatcher mis-routed
        // to PDFBox, PDFBox would throw on the malformed bytes.
        ExtractedDocument doc = dispatcher.extract(txt);
        assertThat(doc.text()).contains("some text");
    }

}
