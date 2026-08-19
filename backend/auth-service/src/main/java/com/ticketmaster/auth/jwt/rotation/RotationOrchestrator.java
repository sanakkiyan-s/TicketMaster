package com.ticketmaster.auth.jwt.rotation;

import com.ticketmaster.auth.jwt.KeyStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Drives ADR-012's four-phase key rotation state machine.
 *
 * Always a bean, even when {@code auth.jwt.key-source=ephemeral} - only
 * {@link VaultKeyStore} is conditional on Vault, injected here through an
 * {@link ObjectProvider} so a missing Vault key source is a clean {@link
 * RotationNotSupportedException} at the moment someone tries to rotate,
 * not a failure to start the whole application.
 */
@Service
class RotationOrchestrator {

    private static final Logger log = LoggerFactory.getLogger(RotationOrchestrator.class);

    private final VaultKeyStore vaultKeyStore;
    private final RotationStateRepository states;
    private final Clock clock;

    RotationOrchestrator(ObjectProvider<VaultKeyStore> vaultKeyStore, RotationStateRepository states, Clock clock) {
        this.vaultKeyStore = vaultKeyStore.getIfAvailable();
        this.states = states;
        this.clock = clock;
    }

    /** Starts a rotation now, outside the normal 90-day schedule. ADR-012 phase 1 PUBLISH. */
    @Transactional
    void startRotation() {
        VaultKeyStore store = requireVault();
        RotationState state = currentState();
        if (state.getPhase() != RotationPhase.IDLE) {
            throw new RotationInProgressException(state.getPhase().name());
        }

        List<Map<String, String>> entries = store.readEntries();
        Map<String, String> currentSigning = entries.stream()
                .filter(e -> KeyStatus.SIGNING.name().equals(e.get("status")))
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("no SIGNING key found in Vault"));

        Map<String, String> newKey = store.generateKey(KeyStatus.PUBLISHED);
        entries.add(newKey);
        store.writeEntries(entries);

        Instant now = Instant.now(clock);
        state.transitionTo(RotationPhase.PUBLISH, currentSigning.get("kid"), newKey.get("kid"), now);
        log.info("rotation started: PUBLISH old={} new={}", currentSigning.get("kid"), newKey.get("kid"));
    }

    /**
     * Advances the state machine by one step if the current phase has been
     * held for at least its configured minimum duration. Called by {@link
     * RotationScheduler} on an interval; a no-op while IDLE, so an
     * ephemeral-key-source instance (where {@link #vaultKeyStore} is null
     * and no rotation can ever be started) never touches Vault here.
     */
    @Transactional
    void advanceIfDue(RotationProperties properties) {
        RotationState state = currentState();
        Instant now = Instant.now(clock);

        switch (state.getPhase()) {
            case IDLE -> {
                // Nothing running; nothing to advance.
            }
            case PUBLISH -> {
                if (elapsedSincePhaseStart(state, now).compareTo(properties.publishDuration()) >= 0) {
                    cutover(state, now);
                }
            }
            case CUTOVER -> drain(state, now); // instant, per ADR-012 phase 2
            case DRAIN -> {
                if (elapsedSincePhaseStart(state, now).compareTo(properties.drainDuration()) >= 0) {
                    retire(state, now);
                }
            }
        }
    }

    /** ADR-012 phase 2 CUTOVER: the new key starts signing; the old key stays published. */
    private void cutover(RotationState state, Instant now) {
        VaultKeyStore store = requireVault();
        List<Map<String, String>> entries = store.readEntries();
        for (Map<String, String> entry : entries) {
            if (entry.get("kid").equals(state.getNewKid())) {
                entry.put("status", KeyStatus.SIGNING.name());
            } else if (entry.get("kid").equals(state.getOldKid())) {
                entry.put("status", KeyStatus.PUBLISHED.name());
            }
        }
        store.writeEntries(entries);
        state.transitionTo(RotationPhase.CUTOVER, state.getOldKid(), state.getNewKid(), now);
        log.info("rotation advanced: CUTOVER - now signing with {}", state.getNewKid());
    }

    /** ADR-012 phase 3 DRAIN: pure state transition, waiting for K1 tokens to expire naturally. */
    private void drain(RotationState state, Instant now) {
        state.transitionTo(RotationPhase.DRAIN, state.getOldKid(), state.getNewKid(), now);
        log.info("rotation advanced: DRAIN - waiting for outstanding {} tokens to expire", state.getOldKid());
    }

    /** ADR-012 phase 4 RETIRE: the old key is removed from Vault entirely; back to steady state. */
    private void retire(RotationState state, Instant now) {
        VaultKeyStore store = requireVault();
        List<Map<String, String>> entries = store.readEntries();
        entries.removeIf(entry -> entry.get("kid").equals(state.getOldKid()));
        store.writeEntries(entries);

        String retiredKid = state.getOldKid();
        state.transitionTo(RotationPhase.IDLE, state.getNewKid(), null, now);
        log.info("rotation complete: RETIRE - removed {} from Vault, {} is now the sole signing key",
                retiredKid, state.getOldKid());
    }

    /**
     * Compromise response (ADR-012): skip straight to phase 4. Every key
     * currently in Vault - whatever was signing, and any key mid-rotation -
     * is destroyed and replaced by exactly one freshly generated SIGNING
     * key. Every live access token dies immediately; refresh tokens are
     * opaque and unaffected, so clients silently re-auth via /refresh.
     *
     * A rotation in progress is deliberately abandoned rather than
     * carefully unwound: a suspected compromise is not the moment to
     * reason about which of the two in-flight keys is the safe one to
     * keep.
     */
    @Transactional
    void handleCompromise() {
        VaultKeyStore store = requireVault();
        Map<String, String> replacement = store.generateKey(KeyStatus.SIGNING);
        store.writeEntries(List.of(replacement));

        RotationState state = currentState();
        state.transitionTo(RotationPhase.IDLE, replacement.get("kid"), null, Instant.now(clock));
        log.warn("compromise response executed: all previous signing keys destroyed from Vault, "
                + "new signing kid={}", replacement.get("kid"));
    }

    private Duration elapsedSincePhaseStart(RotationState state, Instant now) {
        return Duration.between(state.getPhaseEnteredAt(), now);
    }

    private RotationState currentState() {
        return states.findSingleton().orElseGet(() -> {
            states.insertIdleIfMissing(Instant.now(clock));
            return states.findSingleton().orElseThrow(
                    () -> new IllegalStateException("rotation_state row missing after insert-if-missing"));
        });
    }

    private VaultKeyStore requireVault() {
        if (vaultKeyStore == null) {
            throw new RotationNotSupportedException();
        }
        return vaultKeyStore;
    }
}
