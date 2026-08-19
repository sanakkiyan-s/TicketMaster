package com.ticketmaster.user.preferences;

import jakarta.validation.constraints.NotNull;

public record UpdatePreferencesRequest(

        @NotNull
        Boolean emailOptIn,

        @NotNull
        Boolean smsOptIn
) {
}
