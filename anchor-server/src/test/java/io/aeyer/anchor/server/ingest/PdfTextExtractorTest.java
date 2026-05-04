package io.aeyer.anchor.server.ingest;

import static org.assertj.core.api.Assertions.assertThat;

import io.aeyer.anchor.server.ingest.ExtractedDocument;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PdfTextExtractorTest {

    private final PdfTextExtractor extractor = new PdfTextExtractor();

    @Test
    void extracts_text_outline_and_hash_from_a_pdf(@TempDir Path tempDir) throws IOException {
        Path pdfPath = tempDir.resolve("paper.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page1 = new PDPage();
            doc.addPage(page1);
            writeText(doc, page1, "Chapter 1 Foundations");
            writeText(doc, page1, "Foundations content here.", 700);

            PDPage page2 = new PDPage();
            doc.addPage(page2);
            writeText(doc, page2, "Chapter 2 Methods");
            writeText(doc, page2, "Methods content here.", 700);

            PDDocumentOutline outline = new PDDocumentOutline();
            doc.getDocumentCatalog().setDocumentOutline(outline);
            PDOutlineItem itemA = new PDOutlineItem();
            itemA.setTitle("Foundations");
            outline.addLast(itemA);
            PDOutlineItem itemB = new PDOutlineItem();
            itemB.setTitle("Methods");
            outline.addLast(itemB);

            doc.save(pdfPath.toFile());
        }

        ExtractedDocument extracted = extractor.extract(pdfPath);

        assertThat(extracted.text()).contains("Foundations").contains("Methods");
        assertThat(extracted.outlineTopLevel()).containsExactly("Foundations", "Methods");
        assertThat(extracted.contentHash()).hasSize(64); // SHA-256 hex
        // Same bytes → same hash (idempotent re-ingest detection in ANC-7)
        Path copy = tempDir.resolve("paper-copy.pdf");
        Files.copy(pdfPath, copy);
        assertThat(extractor.extract(copy).contentHash()).isEqualTo(extracted.contentHash());
    }

    @Test
    void title_falls_back_to_filename_when_pdf_metadata_lacks_one(@TempDir Path tempDir) throws IOException {
        Path pdfPath = tempDir.resolve("my_chemistry_paper.pdf");
        try (PDDocument doc = new PDDocument()) {
            PDPage page = new PDPage();
            doc.addPage(page);
            writeText(doc, page, "Hello");
            doc.save(pdfPath.toFile());
        }

        ExtractedDocument extracted = extractor.extract(pdfPath);

        assertThat(extracted.title()).isEqualTo("my chemistry paper");
    }

    private void writeText(PDDocument doc, PDPage page, String text) throws IOException {
        writeText(doc, page, text, 750);
    }

    private void writeText(PDDocument doc, PDPage page, String text, int y) throws IOException {
        try (PDPageContentStream cs = new PDPageContentStream(doc, page,
                PDPageContentStream.AppendMode.APPEND, true)) {
            cs.beginText();
            cs.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
            cs.newLineAtOffset(72, y);
            cs.showText(text);
            cs.endText();
        }
    }
}
