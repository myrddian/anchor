package io.aeyer.anchor.server.persistence.repo;

import io.aeyer.anchor.server.persistence.entity.ChunkDbo;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ChunkRepository extends JpaRepository<ChunkDbo, UUID> {
    List<ChunkDbo> findByParagraphIdOrderByOrdinalAsc(UUID paragraphId);
}
