package com.portfolio.ticketing.reservation.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "ticketing.reservation")
public class ReservationProperties {

  @Min(1)
  private long ttlMinutes = 10;

  public long getTtlMinutes() {
    return ttlMinutes;
  }

  public void setTtlMinutes(long ttlMinutes) {
    this.ttlMinutes = ttlMinutes;
  }
}
