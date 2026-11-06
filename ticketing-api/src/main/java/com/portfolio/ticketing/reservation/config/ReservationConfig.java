package com.portfolio.ticketing.reservation.config;

import java.time.Clock;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(ReservationProperties.class)
public class ReservationConfig {

  @Bean
  Clock clock() {
    return Clock.systemUTC();
  }
}
