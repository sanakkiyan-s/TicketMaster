package com.ticketmaster.user.preferences;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

@Service
class PreferencesService {

    private final UserPreferencesRepository preferences;
    private final Clock clock;

    PreferencesService(UserPreferencesRepository preferences, Clock clock) {
        this.preferences = preferences;
        this.clock = clock;
    }

    /** Same auto-create-on-first-access reasoning as ProfileService.getOrCreate. */
    @Transactional
    UserPreferences getOrCreate(UUID userId) {
        return preferences.findById(userId)
                .orElseGet(() -> preferences.saveAndFlush(new UserPreferences(userId, Instant.now(clock))));
    }

    @Transactional
    UserPreferences update(UUID userId, boolean emailOptIn, boolean smsOptIn) {
        UserPreferences prefs = getOrCreate(userId);
        prefs.update(emailOptIn, smsOptIn, Instant.now(clock));
        return prefs;
    }
}
