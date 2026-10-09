package br.com.roboparts.repository;
import br.com.roboparts.entity.Movement;
import java.util.*;
import org.springframework.data.repository.Repository;
public interface MovementRepository extends Repository<Movement,UUID> {
 Movement save(Movement movement);
 Optional<Movement> findByChecklistIdAndRequestId(UUID checklistId,UUID requestId);
 List<Movement> findByChecklistIdOrderByCreatedAtAscIdAsc(UUID checklistId);
}
