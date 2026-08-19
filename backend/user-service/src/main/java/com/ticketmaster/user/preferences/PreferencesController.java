package com.ticketmaster.user.preferences;

import com.ticketmaster.user.shared.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/users/me/preferences")
@Tag(name = "preferences", description = "The authenticated user's notification preferences")
public class PreferencesController {

    private final PreferencesService preferencesService;
    private final CurrentUserResolver currentUser;

    public PreferencesController(PreferencesService preferencesService, CurrentUserResolver currentUser) {
        this.preferencesService = preferencesService;
        this.currentUser = currentUser;
    }

    @Operation(summary = "Get the authenticated user's preferences",
            description = "Defaults: emailOptIn true, smsOptIn false. Auto-created on first access.")
    @GetMapping
    public PreferencesResponse get(HttpServletRequest request) {
        return PreferencesResponse.from(preferencesService.getOrCreate(currentUser.resolve(request)));
    }

    @Operation(summary = "Update the authenticated user's preferences")
    @PutMapping
    public PreferencesResponse update(@Valid @RequestBody UpdatePreferencesRequest body, HttpServletRequest request) {
        var updated = preferencesService.update(
                currentUser.resolve(request), body.emailOptIn(), body.smsOptIn());
        return PreferencesResponse.from(updated);
    }
}
