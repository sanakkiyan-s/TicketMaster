package com.ticketmaster.user.profile;

import java.time.Instant;
import java.util.UUID;

public record ProfileResponse(
        UUID userId,
        String displayName,
        String phoneNumber,
        String avatarUrl,
        Instant createdAt,
        Instant updatedAt
) {
    static ProfileResponse from(UserProfile profile) {
        return new ProfileResponse(
                profile.getUserId(),
                profile.getDisplayName(),
                profile.getPhoneNumber(),
                profile.getAvatarUrl(),
                profile.getCreatedAt(),
                profile.getUpdatedAt());
    }
}
