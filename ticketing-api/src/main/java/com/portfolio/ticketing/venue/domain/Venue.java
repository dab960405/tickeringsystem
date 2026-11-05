package com.portfolio.ticketing.venue.domain;

import com.portfolio.ticketing.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.util.Objects;

@Entity
@Table(name = "venues")
public class Venue extends BaseEntity {

  @Column(nullable = false, length = 150)
  private String name;

  @Column(nullable = false, length = 100)
  private String city;

  @Column(nullable = false)
  private String address;

  @Column(nullable = false)
  private int capacity;

  protected Venue() {}

  public Venue(String name, String city, String address, int capacity) {
    update(name, city, address, capacity);
  }

  public void update(String name, String city, String address, int capacity) {
    if (capacity <= 0) {
      throw new IllegalArgumentException("Venue capacity must be positive");
    }
    this.name = Objects.requireNonNull(name, "name");
    this.city = Objects.requireNonNull(city, "city");
    this.address = Objects.requireNonNull(address, "address");
    this.capacity = capacity;
  }

  public String getName() {
    return name;
  }

  public String getCity() {
    return city;
  }

  public String getAddress() {
    return address;
  }

  public int getCapacity() {
    return capacity;
  }
}
