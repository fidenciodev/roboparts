package br.com.roboparts.repository;

import br.com.roboparts.entity.ApplicationMetadata;
import java.util.Optional;
import org.springframework.data.repository.Repository;

public interface ApplicationMetadataRepository extends Repository<ApplicationMetadata, Short> {
    Optional<ApplicationMetadata> findById(Short id);
}
