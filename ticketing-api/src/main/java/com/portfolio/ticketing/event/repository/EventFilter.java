package com.portfolio.ticketing.event.repository;

import java.time.Instant;

public record EventFilter(String q, String category, String city, Instant from, Instant to) {}
