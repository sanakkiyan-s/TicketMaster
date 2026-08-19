package com.ticketmaster.auth.jwt;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * @param uri              Vault address.
 * @param token            Token auth, for local development. ADR-010 requires
 *                         AppRole with a response-wrapped secret_id in
 *                         production, and the Kubernetes auth method once on
 *                         k8s. A static token in production is a credential
 *                         that never expires and cannot be traced back to an
 *                         instance, which is what that ADR exists to avoid.
 * @param backend          KV v2 mount, normally `secret`.
 * @param path             Where the key set lives inside the mount.
 * @param refreshInterval  How often the key set is re-read. This is what lets
 *                         a rotation reach a running service without a
 *                         restart, and it must stay well under ADR-012's
 *                         phase-1 window (~15 min), or a cutover could begin
 *                         before every replica has seen the new key.
 * @param bootstrapIfEmpty Generate and store a first key when the path is
 *                         empty. DEV ONLY, defaults to false: it means the
 *                         service will mint its own signing key, so anyone who
 *                         can empty the path can make it generate a fresh one.
 */
@ConfigurationProperties(prefix = "auth.jwt.vault")
@Validated
public record VaultKeyProperties(
        @NotBlank String uri,
        @NotBlank String token,
        @NotBlank String backend,
        @NotBlank String path,
        @NotNull Duration refreshInterval,
        boolean bootstrapIfEmpty
) {
}
