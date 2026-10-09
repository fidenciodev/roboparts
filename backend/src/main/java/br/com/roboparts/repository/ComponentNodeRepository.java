package br.com.roboparts.repository;
import br.com.roboparts.entity.ComponentNode;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
public interface ComponentNodeRepository extends JpaRepository<ComponentNode,UUID> {
 List<ComponentNode> findByRobotIdOrderByPositionAscIdAsc(UUID robotId);
}
