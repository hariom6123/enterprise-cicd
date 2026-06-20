package com.example.springboot.user;

/**
 * Thrown when a user lookup misses. Translated to HTTP 404 (with an
 * {@code application/problem+json} body) by
 * {@link com.example.springboot.config.GlobalExceptionHandler}.
 */
public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException(Long id) {
        super("User not found: id=" + id);
    }
}
