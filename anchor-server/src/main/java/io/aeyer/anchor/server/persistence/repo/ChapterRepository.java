package io.aeyer.anchor.server.persistence.repo;

import io.aeyer.anchor.server.persistence.entity.ChapterDbo;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChapterRepository extends JpaRepository<ChapterDbo, UUID> {
    List<ChapterDbo> findByDocumentIdOrderByOrdinalAsc(UUID documentId);
}
