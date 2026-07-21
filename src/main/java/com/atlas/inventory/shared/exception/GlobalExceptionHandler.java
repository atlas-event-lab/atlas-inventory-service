package com.atlas.inventory.shared.exception;

import com.atlas.inventory.exception.InvalidReservationStateTransitionException;
import com.atlas.inventory.exception.InventoryNotFoundException;
import com.atlas.inventory.exception.ReservationNotFoundException;
import com.atlas.inventory.shared.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * Translates all exceptions into RFC 7807 Problem Details responses (API-005).
 * Content type is always {@code application/problem+json}.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String PROBLEM_TITLE_VALIDATION_ERROR = "Validation Error";

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<ProblemDetail> handleValidation(
            MethodArgumentNotValidException ex, HttpServletRequest request) {

        List<FieldErrorDetail> errors = ex.getBindingResult().getFieldErrors().stream()
                .map(e -> new FieldErrorDetail(e.getField(), e.getDefaultMessage()))
                .toList();

        ProblemDetail problem = problemOf(
                HttpStatus.BAD_REQUEST,
                "Request validation failed",
                ProblemTypes.VALIDATION,
                PROBLEM_TITLE_VALIDATION_ERROR,
                request);
        problem.setProperty("errors", errors);

        return respond(HttpStatus.BAD_REQUEST, problem);
    }

    @ExceptionHandler(MissingRequestHeaderException.class)
    public ResponseEntity<ProblemDetail> handleMissingHeader(
            MissingRequestHeaderException ex, HttpServletRequest request) {

        ProblemDetail problem = problemOf(
                HttpStatus.BAD_REQUEST,
                "Required header '" + ex.getHeaderName() + "' is missing",
                ProblemTypes.VALIDATION,
                PROBLEM_TITLE_VALIDATION_ERROR,
                request);

        return respond(HttpStatus.BAD_REQUEST, problem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<ProblemDetail> handleUnreadableBody(
            HttpMessageNotReadableException ex, HttpServletRequest request) {

        ProblemDetail problem = problemOf(
                HttpStatus.BAD_REQUEST,
                "Malformed request body",
                ProblemTypes.VALIDATION,
                PROBLEM_TITLE_VALIDATION_ERROR,
                request);

        return respond(HttpStatus.BAD_REQUEST, problem);
    }

    @ExceptionHandler(MethodArgumentTypeMismatchException.class)
    public ResponseEntity<ProblemDetail> handleTypeMismatch(
            MethodArgumentTypeMismatchException ex, HttpServletRequest request) {

        ProblemDetail problem = problemOf(
                HttpStatus.BAD_REQUEST,
                "Invalid value for '" + ex.getName() + "'",
                ProblemTypes.VALIDATION,
                PROBLEM_TITLE_VALIDATION_ERROR,
                request);

        return respond(HttpStatus.BAD_REQUEST, problem);
    }

    @ExceptionHandler({InventoryNotFoundException.class, ReservationNotFoundException.class})
    public ResponseEntity<ProblemDetail> handleNotFound(RuntimeException ex, HttpServletRequest request) {

        ProblemDetail problem =
                problemOf(HttpStatus.NOT_FOUND, ex.getMessage(), ProblemTypes.NOT_FOUND, "Not Found", request);

        return respond(HttpStatus.NOT_FOUND, problem);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleIllegalArgument(
            IllegalArgumentException ex, HttpServletRequest request) {

        ProblemDetail problem = problemOf(
                HttpStatus.BAD_REQUEST,
                ex.getMessage(),
                ProblemTypes.VALIDATION,
                PROBLEM_TITLE_VALIDATION_ERROR,
                request);

        return respond(HttpStatus.BAD_REQUEST, problem);
    }

    @ExceptionHandler(InvalidReservationStateTransitionException.class)
    public ResponseEntity<ProblemDetail> handleInvalidStateTransition(
            InvalidReservationStateTransitionException ex, HttpServletRequest request) {

        ProblemDetail problem = problemOf(
                HttpStatus.UNPROCESSABLE_ENTITY,
                ex.getMessage(),
                ProblemTypes.UNPROCESSABLE,
                "Invalid State Transition",
                request);

        return respond(HttpStatus.UNPROCESSABLE_ENTITY, problem);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ResponseEntity<ProblemDetail> handleNoResource(NoResourceFoundException ex, HttpServletRequest request) {

        ProblemDetail problem =
                problemOf(HttpStatus.NOT_FOUND, "Resource not found", ProblemTypes.NOT_FOUND, "Not Found", request);

        return respond(HttpStatus.NOT_FOUND, problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleGeneric(Exception ex, HttpServletRequest request) {
        LOGGER.error("Unexpected error processing request to {}", request.getRequestURI(), ex);

        ProblemDetail problem = problemOf(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "An unexpected error occurred",
                ProblemTypes.INTERNAL_ERROR,
                "Internal Server Error",
                request);

        return respond(HttpStatus.INTERNAL_SERVER_ERROR, problem);
    }

    private ProblemDetail problemOf(
            HttpStatus status, String detail, java.net.URI type, String title, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type);
        problem.setTitle(title);
        problem.setInstance(java.net.URI.create(request.getRequestURI()));
        problem.setProperty("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY));
        return problem;
    }

    private ResponseEntity<ProblemDetail> respond(HttpStatus status, ProblemDetail problem) {
        return ResponseEntity.status(status)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
