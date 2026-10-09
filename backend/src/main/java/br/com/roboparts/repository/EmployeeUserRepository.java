package br.com.roboparts.repository;

import br.com.roboparts.entity.EmployeeUser;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface EmployeeUserRepository extends JpaRepository<EmployeeUser, UUID> {
    Optional<EmployeeUser> findByEmail(String email);
    boolean existsByEmail(String email);
}
