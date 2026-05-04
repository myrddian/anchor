package io.aeyer.anchor.server.ingest;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.apache.tika.Tika;
import org.apache.tika.exception.TikaException;
import org.apache.tika.metadata.Metadata;
import org.apache.tika.metadata.TikaCoreProperties;
import org.springframework.stereotype.Service;

/**
 * Tika-based text extractor for everything that isn't PDF — EPUB, DOCX, RTF,
 * HTML, plain text, etc. Tika handles format detection internally; we just
 * hand it the bytes.
 *
 * Tika's plain-text output preserves block-element newlines, so a
 * {@code <h1>Chapter I</h1>} in an EPUB lands as {@code "Chapter I\n"} in
 * the extracted text and the existing {@link ChapterDetector} regex picks
 * it up the same way it does for a PDF. No equivalent to the PDF outline
 * here — the outline list comes back empty and the detector falls through
 * to its other branches if the regex misses.
 */
@Service
public class TikaTextExtractor {

    /** Cap to keep a single document from blowing memory. 5MB of text ≈ 1M tokens. */
    private static final int MAX_EXTRACTED_CHARS = 5_000_000;

    private final Tika tika;

    public TikaTextExtractor() {
        this.tika = new Tika();
        this.tika.setMaxStringLength(MAX_EXTRACTED_CHARS);
    }

    public ExtractedDocument extract(Path file) throws IOException {
        byte[] bytes = Files.readAllBytes(file);
        String hash = sha256(bytes);

        Metadata metadata = new Metadata();
        metadata.set(TikaCoreProperties.RESOURCE_NAME_KEY, file.getFileName().toString());

        String text;
        try (InputStream in = Files.newInputStream(file)) {
            text = tika.parseToString(in, metadata);
        } catch (TikaException e) {
            throw new IOException("Tika could not parse " + file + ": " + e.getMessage(), e);
        }

        String title = derivedTitle(metadata, file);
        return new ExtractedDocument(title, hash, text == null ? "" : text, List.of());
    }

    private String derivedTitle(Metadata metadata, Path path) {
        String fromMetadata = metadata.get(TikaCoreProperties.TITLE);
        if (fromMetadata != null && !fromMetadata.isBlank()) return fromMetadata.trim();
        String filename = path.getFileName().toString();
        int dot = filename.lastIndexOf('.');
        return (dot > 0 ? filename.substring(0, dot) : filename)
                .replace('_', ' ').replace('-', ' ').trim();
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
