package br.com.roboparts.repository;
import br.com.roboparts.entity.Checklist;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.Pageable;
public interface ChecklistRepository extends JpaRepository<Checklist,UUID> {
 @Query("select c.robotId from Checklist c where c.id = :id")
 Optional<UUID> findRobotIdById(UUID id);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select c from Checklist c where c.id = :id")
 Optional<Checklist> lockById(UUID id);
 Optional<Checklist> findByRobotIdAndRequestId(UUID robotId,UUID requestId);
 List<Checklist> findAllByOrderByCreatedAtDescIdDesc(Pageable pageable);
 List<Checklist> findByRobotIdOrderByCreatedAtDescIdDesc(UUID robotId,Pageable pageable);
}
