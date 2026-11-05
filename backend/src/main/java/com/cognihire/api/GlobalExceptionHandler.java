package com.cognihire.api;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import java.time.Instant;

class ResourceNotFoundException extends RuntimeException {
  ResourceNotFoundException(String message) { super(message); }
}

@RestControllerAdvice
class GlobalExceptionHandler {
  @ExceptionHandler(ResponseStatusException.class)
  ResponseEntity<ApiErrorResponse> statusException(ResponseStatusException ex, HttpServletRequest request) {
    var status = HttpStatus.valueOf(ex.getStatusCode().value());
    var message = ex.getReason() == null ? status.getReasonPhrase() : ex.getReason();
    if (ex.getStatusCode().value() == 404 && (ex.getReason() == null || ex.getReason().equalsIgnoreCase(status.getReasonPhrase()))) {
      message = status.getReasonPhrase() + " for " + request.getRequestURI();
    }
    return response(status, message, request);
  }

  @ExceptionHandler(ResourceNotFoundException.class)
  ResponseEntity<ApiErrorResponse> notFound(ResourceNotFoundException ex, HttpServletRequest request) {
    return response(HttpStatus.NOT_FOUND, ex.getMessage(), request);
  }

  @ExceptionHandler(BadCredentialsException.class)
  ResponseEntity<ApiErrorResponse> badCredentials(BadCredentialsException ex, HttpServletRequest request) {
    return response(HttpStatus.UNAUTHORIZED, "Email or password is incorrect", request);
  }

  @ExceptionHandler(IllegalStateException.class)
  ResponseEntity<ApiErrorResponse> illegalState(IllegalStateException ex, HttpServletRequest request) {
    return response(HttpStatus.BAD_REQUEST, ex.getMessage(), request);
  }

  @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
  ResponseEntity<ApiErrorResponse> badRequest(Exception ex, HttpServletRequest request) {
    return response(HttpStatus.BAD_REQUEST, ex instanceof IllegalArgumentException ? ex.getMessage() : "Request body is invalid", request);
  }

  @ExceptionHandler(MethodArgumentNotValidException.class)
  ResponseEntity<ApiErrorResponse> invalidBody(MethodArgumentNotValidException ex, HttpServletRequest request) {
    var message = ex.getBindingResult().getFieldErrors().stream()
        .map(error -> error.getField() + ": " + error.getDefaultMessage()).findFirst().orElse("Request validation failed");
    return response(HttpStatus.BAD_REQUEST, message, request);
  }

  @ExceptionHandler(AccessDeniedException.class)
  ResponseEntity<ApiErrorResponse> forbidden(AccessDeniedException ex, HttpServletRequest request) {
    return response(HttpStatus.FORBIDDEN, "You are not allowed to access this resource", request);
  }

  @ExceptionHandler(DataIntegrityViolationException.class)
  ResponseEntity<ApiErrorResponse> conflict(DataIntegrityViolationException ex, HttpServletRequest request) {
    return response(HttpStatus.CONFLICT, "The request conflicts with existing data", request);
  }

  private ResponseEntity<ApiErrorResponse> response(HttpStatus status, String message, HttpServletRequest request) {
    return ResponseEntity.status(status).body(new ApiErrorResponse(Instant.now(), status.value(), status.getReasonPhrase(), message, request.getRequestURI()));
  }
}
