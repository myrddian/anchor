package io.aeyer.anchor.server.persistence.repo;

import io.aeyer.anchor.server.persistence.entity.ParagraphDbo;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ParagraphRepository extends JpaRepository<ParagraphDbo, UUID> {
    List<ParagraphDbo> findBySectionIdOrderByOrdinalAsc(UUID sectionId);
}
