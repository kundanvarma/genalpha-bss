package com.bss.ordering.exception;

import jakarta.validation.ConstraintViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(VerifiedIdentityRequiredException.class)
    public ResponseEntity<ErrorResponse> handleVerifiedIdentity(VerifiedIdentityRequiredException ex) {
        // 403 with a machine-readable code + the WWW-Authenticate-style hint,
        // so the channel launches a BankID step-up rather than showing an error.
        ErrorResponse body = new ErrorResponse(
                "VERIFIED_IDENTITY_REQUIRED", "Forbidden", ex.getMessage(), "403");
        return ResponseEntity.status(HttpStatus.FORBIDDEN)
                .header("X-Step-Up", "verified-identity")
                .body(body);
    }

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<ErrorResponse> handleNotFound(NotFoundException ex) {
        ErrorResponse body = new ErrorResponse(
                "404",
                "Not Found",
                ex.getMessage(),
                "404");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(body);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .map(fe -> fe.getField() + ": " + fe.getDefaultMessage())
                .findFirst()
                .orElse("Validation failed");
        ErrorResponse body = new ErrorResponse(
                "400",
                "Bad Request",
                message,
                "400");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<ErrorResponse> handleConstraintViolation(ConstraintViolationException ex) {
        String message = ex.getConstraintViolations().stream()
                .map(v -> v.getPropertyPath() + ": " + v.getMessage())
                .findFirst()
                .orElse("Validation failed");
        ErrorResponse body = new ErrorResponse(
                "400",
                "Bad Request",
                message,
                "400");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
        ErrorResponse body = new ErrorResponse(
                "400",
                "Bad Request",
                ex.getName() + ": invalid value",
                "400");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(OrderValidationException.class)
    public ResponseEntity<ErrorResponse> handleOrderValidation(OrderValidationException ex) {
        ErrorResponse body = new ErrorResponse(
                "400",
                "Bad Request",
                ex.getMessage(),
                "400");
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(body);
    }

    @ExceptionHandler(DownstreamException.class)
    public ResponseEntity<ErrorResponse> handleDownstream(DownstreamException ex) {
        ErrorResponse body = new ErrorResponse(
                "502",
                "Bad Gateway",
                ex.getMessage(),
                "502");
        return ResponseEntity.status(HttpStatus.BAD_GATEWAY).body(body);
    }
}
