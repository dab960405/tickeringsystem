package com.portfolio.ticketing.tickettype.repository;

import com.portfolio.ticketing.tickettype.domain.TicketType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface TicketTypeRepository extends JpaRepository<TicketType, UUID> {

  List<TicketType> findByEventIdOrderByPriceAsc(UUID eventId);

  @Query(
      """
      SELECT t.id AS id, t.availableQuantity AS availableQuantity
      FROM TicketType t
      WHERE t.event.id = :eventId
      ORDER BY t.price ASC
      """)
  List<TicketTypeAvailabilityProjection> findAvailabilityByEventId(
      @Param("eventId") UUID eventId);

  boolean existsByEventId(UUID eventId);

  boolean existsByEventIdAndNameIgnoreCase(UUID eventId, String name);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE ticket_types
          SET available_quantity = available_quantity - :quantity, version = version + 1
          WHERE id = :id AND available_quantity >= :quantity
          """,
      nativeQuery = true)
  int reserveStock(@Param("id") UUID id, @Param("quantity") int quantity);

  @Modifying(flushAutomatically = true, clearAutomatically = true)
  @Query(
      value =
          """
          UPDATE ticket_types
          SET available_quantity = available_quantity + :quantity, version = version + 1
          WHERE id = :id AND available_quantity + :quantity <= total_quantity
          """,
      nativeQuery = true)
  int restoreStock(@Param("id") UUID id, @Param("quantity") int quantity);
}
