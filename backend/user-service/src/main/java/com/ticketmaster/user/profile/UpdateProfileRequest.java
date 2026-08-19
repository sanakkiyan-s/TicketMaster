package com.ticketmaster.user.profile;

import jakarta.validation.constraints.Size;

public record UpdateProfileRequest(

        @Size(max = 255)
        String displayName,

        @Size(max = 32)
        String phoneNumber,

        @Size(max = 2048)
        String avatarUrl
) {
}
