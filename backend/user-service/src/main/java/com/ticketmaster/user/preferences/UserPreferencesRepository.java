package com.ticketmaster.user.preferences;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface UserPreferencesRepository extends JpaRepository<UserPreferences, UUID> {
}
