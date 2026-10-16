package br.com.roboparts.repository;
import br.com.roboparts.entity.TeamInvitation;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
public interface TeamInvitationRepository extends JpaRepository<TeamInvitation, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select i from TeamInvitation i where i.tokenHash=:hash")
    Optional<TeamInvitation> lockByHash(String hash);
    List<TeamInvitation> findTop100ByOrderByCreatedAtDesc();
}
