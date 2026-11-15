package com.enterprise.outlook.common.web.servlet;

import com.enterprise.outlook.common.resilience.DependencyUnavailableException;
import com.enterprise.outlook.common.resilience.JitterBackoff;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {
    /** Degraded shard / dependency => 503 with jittered Retry-After (clients must back off with full jitter). */
    @ExceptionHandler(DependencyUnavailableException.class)
    ResponseEntity<Map<String, String>> unavailable(DependencyUnavailableException e) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", String.valueOf(JitterBackoff.retryAfterSeconds(3, 30)))
                .body(Map.of("error", "dependency_unavailable", "dependency", e.dependency()));
    }
}
