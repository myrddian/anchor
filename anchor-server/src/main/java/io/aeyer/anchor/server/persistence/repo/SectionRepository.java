package io.aeyer.anchor.server.persistence.repo;

import io.aeyer.anchor.server.persistence.entity.SectionDbo;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SectionRepository extends JpaRepository<SectionDbo, UUID> {
    List<SectionDbo> findByChapterIdOrderByOrdinalAsc(UUID chapterId);
}
