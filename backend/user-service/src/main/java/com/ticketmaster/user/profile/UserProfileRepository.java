package com.ticketmaster.user.profile;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

interface UserProfileRepository extends JpaRepository<UserProfile, UUID> {
}
