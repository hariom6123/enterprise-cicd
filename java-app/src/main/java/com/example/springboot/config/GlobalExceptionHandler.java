package com.example.springboot.config;

import java.net.URI;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import com.example.springboot.user.UserNotFoundException;

/**
 * Maps exceptions to RFC 7807 {@link ProblemDetail} bodies.
 *
 * <p>Extends {@link ResponseEntityExceptionHandler} so the framework's built-in
 * handlers for {@code MethodArgumentNotValidException},
 * {@code HttpMessageNotReadableException}, {@code HttpRequestMethodNotSupportedException},
 * {@code HttpMediaTypeNotSupportedException}, {@code NoHandlerFoundException},
 * etc. all return the same envelope as our domain handlers.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    /**
     * Database unique-constraint or FK violation — surfaced from
     * {@code UserService.create} when a duplicate email is inserted.
     * Mapped to 409 Conflict (RFC 9110 §15.5.10).
     */
    @ExceptionHandler(DataIntegrityViolationException.class)
    public ResponseEntity<ProblemDetail> duplicate(DataIntegrityViolationException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(problem(HttpStatus.CONFLICT, "Resource conflict", rootMessage(ex)));
    }

    /**
     * User lookup miss. Overrides the {@code @ResponseStatus} annotation on
     * {@link UserNotFoundException} so the 404 returns the same
     * {@code application/problem+json} shape as every other error.
     */
    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ProblemDetail> notFound(UserNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(problem(HttpStatus.NOT_FOUND, "Not Found", ex.getMessage()));
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(
            Exception ex, Object body, HttpHeaders headers,
            HttpStatusCode statusCode, WebRequest request) {

        if (body == null) {
            HttpStatus status = HttpStatus.valueOf(statusCode.value());
            body = problem(status, status.getReasonPhrase(), ex.getMessage());
        }
        return new ResponseEntity<>(body, headers, statusCode);
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setTitle(title);
        pd.setType(URI.create("https://api.example.com/errors/" + status.value()));
        return pd;
    }

    private static String rootMessage(Throwable t) {
        Throwable cause = t;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? t.toString() : cause.getMessage();
    }
}
