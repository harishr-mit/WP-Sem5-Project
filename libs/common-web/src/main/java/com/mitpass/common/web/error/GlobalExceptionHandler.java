package com.mitpass.common.web.error;

import com.mitpass.common.web.correlation.CorrelationIdFilter;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.Path;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.TypeMismatchException;
import org.springframework.context.MessageSourceResolvable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.core.PropertyReferenceException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.validation.method.ParameterErrors;
import org.springframework.validation.method.ParameterValidationResult;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Turns every error into an RFC 9457 {@link ProblemDetail} ({@code application/problem+json}).
 *
 * <p>Spring MVC's own exceptions are handled by {@link ResponseEntityExceptionHandler}; this class adds MITPass problem
 * types, a per-field {@code errors} array for validation failures, the request's {@code correlationId}, and mappings for
 * the shared domain exceptions. The status mapping is documented in {@code docs/plans/phase-1.md} §6.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final URI ABOUT_BLANK = URI.create("about:blank");

    // ---- validation (400) -------------------------------------------------------------------------------------

    @Override
    protected ResponseEntity<Object> handleMethodArgumentNotValid(
            MethodArgumentNotValidException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        List<FieldViolation> errors = new ArrayList<>();
        for (FieldError error : ex.getBindingResult().getFieldErrors()) {
            // A type-conversion failure (e.g. ?from=notadate) carries a long technical message; keep it short.
            String message = error.isBindingFailure()
                    ? "has an invalid value '%s'".formatted(error.getRejectedValue())
                    : error.getDefaultMessage();
            errors.add(new FieldViolation(error.getField(), message));
        }
        ex.getBindingResult()
                .getGlobalErrors()
                .forEach(error -> errors.add(new FieldViolation(error.getObjectName(), error.getDefaultMessage())));
        return validationProblem(ex, errors, headers, request);
    }

    @Override
    protected ResponseEntity<Object> handleHandlerMethodValidationException(
            HandlerMethodValidationException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        if (status.value() != HttpStatus.BAD_REQUEST.value()) {
            // Return-value validation failed: a server bug, not a client error.
            return super.handleHandlerMethodValidationException(ex, headers, status, request);
        }
        List<FieldViolation> errors = new ArrayList<>();
        for (ParameterValidationResult result : ex.getParameterValidationResults()) {
            if (result instanceof ParameterErrors parameterErrors) {
                for (FieldError error : parameterErrors.getFieldErrors()) {
                    errors.add(new FieldViolation(error.getField(), error.getDefaultMessage()));
                }
                continue;
            }
            String parameter = result.getMethodParameter().getParameterName();
            for (MessageSourceResolvable error : result.getResolvableErrors()) {
                errors.add(new FieldViolation(parameter, error.getDefaultMessage()));
            }
        }
        return validationProblem(ex, errors, headers, request);
    }

    @ExceptionHandler(ConstraintViolationException.class)
    public ResponseEntity<Object> handleConstraintViolation(ConstraintViolationException ex, WebRequest request) {
        List<FieldViolation> errors = new ArrayList<>();
        for (ConstraintViolation<?> violation : ex.getConstraintViolations()) {
            errors.add(new FieldViolation(leafName(violation.getPropertyPath()), violation.getMessage()));
        }
        return validationProblem(ex, errors, new HttpHeaders(), request);
    }

    // ---- malformed requests (400) -----------------------------------------------------------------------------

    @Override
    protected ResponseEntity<Object> handleHttpMessageNotReadable(
            HttpMessageNotReadableException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail body = problem(
                HttpStatus.BAD_REQUEST,
                ProblemTypes.MALFORMED_REQUEST,
                "Malformed request",
                "The request body is missing or is not valid JSON for this endpoint");
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    @Override
    protected ResponseEntity<Object> handleTypeMismatch(
            TypeMismatchException ex, HttpHeaders headers, HttpStatusCode status, WebRequest request) {
        ProblemDetail body = problem(
                HttpStatus.BAD_REQUEST,
                ProblemTypes.MALFORMED_REQUEST,
                "Malformed request",
                "Parameter '%s' has an invalid value '%s'".formatted(ex.getPropertyName(), ex.getValue()));
        return handleExceptionInternal(ex, body, headers, status, request);
    }

    /** {@code ?sort=unknownField}: Spring Data rejects the property; that's the client's mistake, not a 500. */
    @ExceptionHandler(PropertyReferenceException.class)
    public ResponseEntity<Object> handleUnknownSortProperty(PropertyReferenceException ex, WebRequest request) {
        return respond(
                ex,
                HttpStatus.BAD_REQUEST,
                ProblemTypes.MALFORMED_REQUEST,
                "Malformed request",
                "Unknown property '%s'".formatted(ex.getPropertyName()),
                request);
    }

    // ---- domain exceptions ------------------------------------------------------------------------------------

    @ExceptionHandler(NotFoundException.class)
    public ResponseEntity<Object> handleNotFound(NotFoundException ex, WebRequest request) {
        return respond(ex, HttpStatus.NOT_FOUND, ProblemTypes.NOT_FOUND, "Not found", ex.getMessage(), request);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<Object> handleConflict(ConflictException ex, WebRequest request) {
        return respond(ex, HttpStatus.CONFLICT, ProblemTypes.CONFLICT, "Conflict", ex.getMessage(), request);
    }

    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<Object> handleDataIntegrity(DataIntegrityViolationException ex, WebRequest request) {
        log.warn("Data integrity violation: {}", ex.getMostSpecificCause().getMessage());
        return respond(
                ex,
                HttpStatus.CONFLICT,
                ProblemTypes.CONFLICT,
                "Conflict",
                "The request conflicts with existing data",
                request);
    }

    @ExceptionHandler(OptimisticLockingFailureException.class)
    public ResponseEntity<Object> handleOptimisticLock(OptimisticLockingFailureException ex, WebRequest request) {
        return respond(
                ex,
                HttpStatus.CONFLICT,
                ProblemTypes.CONFLICT,
                "Conflict",
                "The resource was changed by someone else. Reload it and try again.",
                request);
    }

    @ExceptionHandler(BusinessRuleException.class)
    public ResponseEntity<Object> handleBusinessRule(BusinessRuleException ex, WebRequest request) {
        return respond(
                ex,
                HttpStatus.UNPROCESSABLE_CONTENT,
                ProblemTypes.BUSINESS_RULE,
                "Business rule violated",
                ex.getMessage(),
                request);
    }

    // ---- everything else (500) --------------------------------------------------------------------------------

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Object> handleUnexpected(Exception ex, WebRequest request) {
        log.error("Unhandled exception", ex);
        return respond(
                ex,
                HttpStatus.INTERNAL_SERVER_ERROR,
                ProblemTypes.INTERNAL,
                "Internal error",
                "An unexpected error occurred. Quote the correlationId when reporting it.",
                request);
    }

    // ---- shared enrichment ------------------------------------------------------------------------------------

    /** Every ProblemDetail, including the ones built by the base class, passes through here. */
    @Override
    protected ResponseEntity<Object> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        if (body instanceof ProblemDetail problem) {
            if (problem.getType() == null || ABOUT_BLANK.equals(problem.getType())) {
                URI type = defaultType(statusCode);
                if (type != null) {
                    problem.setType(type);
                }
            }
            String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
            if (correlationId != null) {
                problem.setProperty("correlationId", correlationId);
            }
            if (statusCode.is4xxClientError()) {
                // One line per rejected request; the log pattern adds the correlation id.
                log.info("{} {}: {}", statusCode.value(), request.getDescription(false), problem.getDetail());
            }
        }
        return super.createResponseEntity(body, headers, statusCode, request);
    }

    private ResponseEntity<Object> validationProblem(
            Exception ex, List<FieldViolation> errors, HttpHeaders headers, WebRequest request) {
        errors.sort(Comparator.comparing(FieldViolation::field).thenComparing(FieldViolation::message));
        String detail = errors.size() == 1 ? "1 field is invalid" : errors.size() + " fields are invalid";
        ProblemDetail body = problem(HttpStatus.BAD_REQUEST, ProblemTypes.VALIDATION, "Validation failed", detail);
        body.setProperty("errors", errors);
        return handleExceptionInternal(ex, body, headers, HttpStatus.BAD_REQUEST, request);
    }

    private ResponseEntity<Object> respond(
            Exception ex, HttpStatus status, URI type, String title, String detail, WebRequest request) {
        return handleExceptionInternal(ex, problem(status, type, title, detail), new HttpHeaders(), status, request);
    }

    private static ProblemDetail problem(HttpStatus status, URI type, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(type);
        problem.setTitle(title);
        return problem;
    }

    private static URI defaultType(HttpStatusCode status) {
        return switch (status.value()) {
            case 400 -> ProblemTypes.MALFORMED_REQUEST;
            case 404 -> ProblemTypes.NOT_FOUND;
            case 409 -> ProblemTypes.CONFLICT;
            case 422 -> ProblemTypes.BUSINESS_RULE;
            default -> status.is5xxServerError() ? ProblemTypes.INTERNAL : null;
        };
    }

    private static String leafName(Path path) {
        String name = null;
        for (Path.Node node : path) {
            name = node.getName();
        }
        return name != null ? name : path.toString();
    }
}
