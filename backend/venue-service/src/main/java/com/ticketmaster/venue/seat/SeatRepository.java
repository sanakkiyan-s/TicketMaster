package com.ticketmaster.venue.seat;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

interface SeatRepository extends JpaRepository<Seat, UUID> {

    List<Seat> findBySectionId(UUID sectionId);
}
