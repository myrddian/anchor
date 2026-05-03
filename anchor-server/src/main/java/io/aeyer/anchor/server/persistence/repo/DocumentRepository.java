package io.aeyer.anchor.server.persistence.repo;

import io.aeyer.anchor.server.persistence.entity.DocumentDbo;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface DocumentRepository extends JpaRepository<DocumentDbo, UUID>, DocumentRepositoryDomain {
}
