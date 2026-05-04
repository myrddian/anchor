package io.aeyer.anchor.server.persistence.repo;

import io.aeyer.anchor.protocol.ingest.IngestJobStatus;
import io.aeyer.anchor.server.persistence.entity.IngestJobDbo;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface IngestJobRepository extends JpaRepository<IngestJobDbo, UUID> {

    /**
     * Jobs that were running when the server stopped — used at startup to
     * rewrite their status to FAILED so polling clients see a real terminal
     * state instead of stale RUNNING.
     */
    List<IngestJobDbo> findByStatusNotIn(List<IngestJobStatus> terminalStatuses);

    /** Watchdog: terminal rows older than the cutoff. */
    @Modifying
    @Query("DELETE FROM IngestJobDbo j WHERE j.status IN :terminal AND j.completedAt < :cutoff")
    int deleteTerminalOlderThan(@Param("terminal") List<IngestJobStatus> terminal,
                                @Param("cutoff") Instant cutoff);
}
