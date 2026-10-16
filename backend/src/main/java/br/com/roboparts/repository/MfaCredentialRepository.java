package br.com.roboparts.repository;
import br.com.roboparts.entity.MfaCredential;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
public interface MfaCredentialRepository extends JpaRepository<MfaCredential,UUID> { }
