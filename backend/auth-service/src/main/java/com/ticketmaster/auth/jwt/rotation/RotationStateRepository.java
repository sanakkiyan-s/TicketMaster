package com.ticketmaster.auth.jwt.rotation;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;

interface RotationStateRepository extends JpaRepository<RotationState, Short> {

    default Optional<RotationState> findSingleton() {
        return findById(RotationState.SINGLETON_ID);
    }

    /**
     * `ON CONFLICT DO NOTHING` rather than a plain INSERT, because the
     * scheduler tick and an admin request can race to create the singleton
     * row the first time either one is called (both run inside their own
     * @Transactional, so a caught exception cannot recover the aborted
     * transaction the way it could outside one - the database itself has
     * to be the arbiter, the same reasoning VaultSigningKeyProvider's CAS
     * write already applies to bootstrapping the first signing key).
     */
    @Modifying
    @Query(value = "INSERT INTO rotation_state (id, phase, old_kid, new_kid, phase_entered_at, updated_at) "
            + "VALUES (1, 'IDLE', NULL, NULL, :now, :now) ON CONFLICT (id) DO NOTHING", nativeQuery = true)
    void insertIdleIfMissing(@Param("now") Instant now);
}
