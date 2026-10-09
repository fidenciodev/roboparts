package br.com.roboparts.repository;
import br.com.roboparts.entity.Robot;
import jakarta.persistence.LockModeType;
import java.util.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.domain.Pageable;
public interface RobotRepository extends JpaRepository<Robot,UUID> {
 List<Robot> findAllByOrderByNameAscIdAsc(Pageable pageable);
 @Lock(LockModeType.PESSIMISTIC_WRITE) @Query("select r from Robot r where r.id = :id")
 Optional<Robot> lockById(UUID id);
}
