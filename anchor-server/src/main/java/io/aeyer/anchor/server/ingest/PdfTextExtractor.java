package io.aeyer.anchor.server.ingest;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDDocumentOutline;
import org.apache.pdfbox.pdmodel.interactive.documentnavigation.outline.PDOutlineItem;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

/**
 * Extracts plain text and the PDF outline (if present) from a PDF.
 * The outline informs chapter detection downstream.
 */
@Service
public class PdfTextExtractor {

    public ExtractedPdf extract(Path pdfPath) throws IOException {
        byte[] bytes = Files.readAllBytes(pdfPath);
        String hash = sha256(bytes);
        try (PDDocument doc = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            stripper.setSortByPosition(true);
            String text = stripper.getText(doc);
            List<String> outline = collectOutlineTitles(doc.getDocumentCatalog().getDocumentOutline());
            String title = derivedTitle(doc, pdfPath);
            return new ExtractedPdf(title, hash, text, outline);
        }
    }

    private List<String> collectOutlineTitles(PDDocumentOutline outline) {
        List<String> titles = new ArrayList<>();
        if (outline == null) return titles;
        PDOutlineItem item = outline.getFirstChild();
        while (item != null) {
            String title = item.getTitle();
            if (title != null && !title.isBlank()) titles.add(title.trim());
            item = item.getNextSibling();
        }
        return titles;
    }

    private String derivedTitle(PDDocument doc, Path path) {
        String docTitle = doc.getDocumentInformation() == null
                ? null
                : doc.getDocumentInformation().getTitle();
        if (docTitle != null && !docTitle.isBlank()) return docTitle.trim();
        String filename = path.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        return (dot > 0 ? filename.substring(0, dot) : filename).replace('_', ' ').replace('-', ' ').trim();
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public record ExtractedPdf(String title, String contentHash, String text, List<String> outlineTopLevel) {}
}
