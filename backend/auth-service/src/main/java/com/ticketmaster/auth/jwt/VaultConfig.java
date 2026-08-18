package com.ticketmaster.auth.jwt;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.vault.authentication.TokenAuthentication;
import org.springframework.vault.client.VaultEndpoint;
import org.springframework.vault.core.VaultTemplate;

import java.net.URI;

/**
 * Only built when the Vault key source is selected, so a developer running the
 * ephemeral provider does not need Vault running at all.
 *
 * TokenAuthentication is the local-development path. ADR-010 requires AppRole
 * with a response-wrapped secret_id, moving to the Kubernetes auth method on
 * k8s. The point of response wrapping is that if anyone unwraps the secret_id
 * before the service does, the unwrap fails and you LEARN you were
 * compromised. A static token gives up that signal entirely, which is why this
 * bean is scoped to dev rather than being the general answer.
 */
@Configuration
@ConditionalOnProperty(name = "auth.jwt.key-source", havingValue = "vault")
class VaultConfig {

    @Bean
    VaultTemplate vaultTemplate(VaultKeyProperties properties) {
        return new VaultTemplate(
                VaultEndpoint.from(URI.create(properties.uri())),
                new TokenAuthentication(properties.token()));
    }
}
