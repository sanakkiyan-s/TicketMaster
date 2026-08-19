package com.ticketmaster.user.preferences;

public record PreferencesResponse(boolean emailOptIn, boolean smsOptIn) {
    static PreferencesResponse from(UserPreferences preferences) {
        return new PreferencesResponse(preferences.isEmailOptIn(), preferences.isSmsOptIn());
    }
}
