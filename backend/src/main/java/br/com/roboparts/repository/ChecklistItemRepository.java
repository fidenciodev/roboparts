package br.com.roboparts.repository;
import br.com.roboparts.entity.ChecklistItem;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ChecklistItemRepository extends JpaRepository<ChecklistItem,UUID> {
 List<ChecklistItem> findByChecklistIdOrderByPositionAscIdAsc(UUID checklistId);
}
