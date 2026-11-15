package com.enterprise.outlook.gateway.web;

import com.enterprise.outlook.common.resilience.JitterBackoff;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Circuit-breaker fallback: graceful degradation with a jittered Retry-After, never a hung connection. */
@RestController
public class FallbackController {
    @RequestMapping("/fallback/{service}")
    public ResponseEntity<Map<String, Object>> fallback(@PathVariable String service) {
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header("Retry-After", String.valueOf(JitterBackoff.retryAfterSeconds(3, 30)))
                .header("X-Fallback", service)
                .body(Map.of("error", "service_degraded", "service", service,
                        "message", "Request accepted for graceful degradation; retry with exponential backoff and full jitter"));
    }
}
