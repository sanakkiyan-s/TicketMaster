package com.ticketmaster.user.profile;

import com.ticketmaster.user.shared.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

/**
 * ADR-034 versions the REST edge at /api/v1 — see auth-service's
 * RegistrationController for why the path is not rewritten between here
 * and the gateway.
 */
@RestController
@RequestMapping("/api/v1/users/me")
@Tag(name = "profile", description = "The authenticated user's profile")
public class ProfileController {

    private final ProfileService profileService;
    private final CurrentUserResolver currentUser;

    public ProfileController(ProfileService profileService, CurrentUserResolver currentUser) {
        this.profileService = profileService;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Get the authenticated user's profile",
            description = "Auto-creates an empty profile row on first access — see ProfileService.getOrCreate.")
    @GetMapping
    public ProfileResponse get(HttpServletRequest request) {
        return ProfileResponse.from(profileService.getOrCreate(currentUser.resolve(request)));
    }

    @Operation(summary = "Update the authenticated user's profile")
    @PutMapping
    public ProfileResponse update(@Valid @RequestBody UpdateProfileRequest body, HttpServletRequest request) {
        var updated = profileService.update(
                currentUser.resolve(request), body.displayName(), body.phoneNumber(), body.avatarUrl());
        return ProfileResponse.from(updated);
    }
}
