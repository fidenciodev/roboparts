package br.com.roboparts.repository;

import br.com.roboparts.entity.EmployeeUser;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import jakarta.persistence.LockModeType;

public interface EmployeeUserRepository extends JpaRepository<EmployeeUser, UUID> {
    Optional<EmployeeUser> findByEmail(String email);
    boolean existsByEmail(String email);
    @Query("select u.id from EmployeeUser u where u.email = :email")
    Optional<UUID> findIdByEmail(String email);
    @Query("select u.passwordVersion from EmployeeUser u where u.id = :id")
    Optional<Long> findPasswordVersionById(UUID id);
    @Query("select u.active as active, u.passwordVersion as passwordVersion from EmployeeUser u where u.id = :id")
    Optional<SessionState> findSessionStateById(UUID id);
    interface SessionState { boolean getActive(); long getPasswordVersion(); }
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from EmployeeUser u where u.id = :id")
    Optional<EmployeeUser> lockById(UUID id);
}
