package com.portfolio.ticketing.common.exception;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/** Single place where exceptions are translated to RFC 7807 problem details. */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
  private static final String TYPE_PREFIX = "https://ticketing.portfolio.com/problems/";

  @ExceptionHandler(BusinessException.class)
  ProblemDetail handleBusiness(BusinessException exception) {
    HttpStatus status =
        switch (exception) {
          case ResourceNotFoundException ignored -> HttpStatus.NOT_FOUND;
          case InsufficientStockException ignored -> HttpStatus.CONFLICT;
          case InvalidStateTransitionException ignored -> HttpStatus.CONFLICT;
          case DuplicateResourceException ignored -> HttpStatus.CONFLICT;
          case BusinessRuleViolationException ignored -> HttpStatus.UNPROCESSABLE_ENTITY;
        };
    return problem(status, exception.getMessage(), typeOf(exception));
  }

  @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
  ProblemDetail handleOptimisticLock(ObjectOptimisticLockingFailureException exception) {
    return problem(
        HttpStatus.CONFLICT,
        "The resource was modified concurrently, please retry",
        "concurrent-modification");
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ProblemDetail handleDataIntegrity(DataIntegrityViolationException exception) {
    // The database message may leak schema details, so it is logged but never returned.
    log.warn("Data integrity violation: {}", exception.getMostSpecificCause().getMessage());
    return problem(
        HttpStatus.CONFLICT, "The request conflicts with existing data", "data-conflict");
  }

  @ExceptionHandler(Exception.class)
  ProblemDetail handleUnexpected(Exception exception) {
    log.error("Unexpected error", exception);
    return problem(HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred", "internal");
  }

  @Override
  protected ResponseEntity<Object> handleMethodArgumentNotValid(
      MethodArgumentNotValidException exception,
      HttpHeaders headers,
      HttpStatusCode status,
      WebRequest request) {
    Map<String, String> errors = new LinkedHashMap<>();
    for (FieldError error : exception.getBindingResult().getFieldErrors()) {
      errors.putIfAbsent(error.getField(), error.getDefaultMessage());
    }
    ProblemDetail body =
        problem(HttpStatus.BAD_REQUEST, "Request validation failed", "validation-error");
    body.setProperty("errors", errors);
    return ResponseEntity.badRequest().body(body);
  }

  private static ProblemDetail problem(HttpStatus status, String detail, String type) {
    ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
    problem.setType(URI.create(TYPE_PREFIX + type));
    return problem;
  }

  private static String typeOf(BusinessException exception) {
    return exception
        .getClass()
        .getSimpleName()
        .replace("Exception", "")
        .replaceAll("([a-z])([A-Z])", "$1-$2")
        .toLowerCase();
  }
}
