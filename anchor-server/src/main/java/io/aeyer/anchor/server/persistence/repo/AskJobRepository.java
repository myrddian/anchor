package io.aeyer.anchor.server.persistence.repo;

import io.aeyer.anchor.protocol.ask.JobStatus;
import io.aeyer.anchor.server.persistence.entity.AskJobDbo;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface AskJobRepository extends JpaRepository<AskJobDbo, UUID> {

    /**
     * Jobs that were running when the server stopped — used at startup to
     * rewrite their status to FAILED ("interrupted by server restart") so
     * polling clients see a real terminal state instead of stale RUNNING.
     */
    List<AskJobDbo> findByStatusNotIn(List<JobStatus> terminalStatuses);

    /** Watchdog: terminal rows older than the cutoff. */
    @Modifying
    @Query("DELETE FROM AskJobDbo j WHERE j.status IN :terminal AND j.completedAt < :cutoff")
    int deleteTerminalOlderThan(@Param("terminal") List<JobStatus> terminal,
                                @Param("cutoff") Instant cutoff);
}
