package br.com.roboparts.repository;

import br.com.roboparts.entity.PasswordReset;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PasswordResetRepository extends JpaRepository<PasswordReset, UUID> { }
