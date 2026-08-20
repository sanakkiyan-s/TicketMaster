package com.ticketmaster.user.profile;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
class ProfileService {

    private final UserProfileRepository profiles;
    private final Clock clock;

    ProfileService(UserProfileRepository profiles, Clock clock) {
        this.profiles = profiles;
        this.clock = clock;
    }

    /**
     * A profile logically exists the moment an account does, even with
     * every field blank — so a first GET for a userId with no row yet
     * auto-creates an empty one rather than 404ing. That keeps GET
     * idempotent from the caller's point of view: it never needs to know
     * whether this is the user's first visit.
     */
    @Transactional
    UserProfile getOrCreate(UUID userId) {
        return profiles.findById(userId)
                .orElseGet(() -> profiles.saveAndFlush(new UserProfile(userId, Instant.now(clock))));
    }

    @Transactional
    UserProfile update(UUID userId, String displayName, String phoneNumber, String avatarUrl) {
        UserProfile profile = getOrCreate(userId);
        profile.update(displayName, phoneNumber, avatarUrl, Instant.now(clock));
        return profile;
    }
}
