package com.ticketmaster.inventory.seat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface PriceTierRepository extends JpaRepository<PriceTier, UUID> {
    List<PriceTier> findBySessionId(UUID sessionId);
}
