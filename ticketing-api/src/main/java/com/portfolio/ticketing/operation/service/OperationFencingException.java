package com.portfolio.ticketing.operation.service;

class OperationFencingException extends RuntimeException {

  OperationFencingException(String message) {
    super(message);
  }
}
