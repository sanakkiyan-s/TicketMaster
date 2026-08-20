package com.ticketmaster.user.paymentmethods;

import com.ticketmaster.user.shared.CurrentUserResolver;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.net.URI;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users/me/payment-methods")
@Tag(name = "payment-methods", description = "The authenticated user's saved payment method references")
public class PaymentMethodController {

    private final PaymentMethodService paymentMethodService;
    private final CurrentUserResolver currentUser;

    public PaymentMethodController(PaymentMethodService paymentMethodService, CurrentUserResolver currentUser) {
        this.paymentMethodService = paymentMethodService;
        this.currentUser = currentUser;
    }

    @Operation(summary = "List the authenticated user's saved payment methods")
    @GetMapping
    public List<PaymentMethodResponse> list(HttpServletRequest request) {
        return paymentMethodService.list(currentUser.resolve(request)).stream()
                .map(PaymentMethodResponse::from)
                .toList();
    }

    @Operation(summary = "Save a new payment method reference",
            description = "Persists a provider-issued reference as given — see AddPaymentMethodRequest's javadoc "
                    + "for why no validation against payment-service happens here.")
    @PostMapping
    public ResponseEntity<PaymentMethodResponse> add(
            @Valid @RequestBody AddPaymentMethodRequest body, HttpServletRequest request) {
        var saved = paymentMethodService.add(
                currentUser.resolve(request), body.provider(), body.providerToken(), body.brand(), body.last4());

        return ResponseEntity
                .created(URI.create("/api/v1/users/me/payment-methods/" + saved.getId()))
                .body(PaymentMethodResponse.from(saved));
    }

    @Operation(summary = "Delete a saved payment method",
            description = "Returns 404 for both an unknown id and one belonging to another user — see "
                    + "PaymentMethodNotFoundException's javadoc.")
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id, HttpServletRequest request) {
        paymentMethodService.delete(currentUser.resolve(request), id);
        return ResponseEntity.noContent().build();
    }
}
