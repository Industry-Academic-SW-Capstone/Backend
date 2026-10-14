package grit.stockIt.global.auth.repository;

import grit.stockIt.global.auth.entity.KisCredential;
import grit.stockIt.global.auth.entity.KisCredentialType;
import org.springframework.data.jpa.repository.JpaRepository;

public interface KisCredentialRepository extends JpaRepository<KisCredential, KisCredentialType> {
}
