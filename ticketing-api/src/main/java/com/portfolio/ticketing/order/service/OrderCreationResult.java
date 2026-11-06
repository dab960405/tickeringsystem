package com.portfolio.ticketing.order.service;

import com.portfolio.ticketing.order.api.OrderResponse;

public record OrderCreationResult(OrderResponse response, boolean created) {}
