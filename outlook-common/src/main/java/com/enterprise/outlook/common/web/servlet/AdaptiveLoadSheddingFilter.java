package com.enterprise.outlook.common.web.servlet;

import com.enterprise.outlook.common.resilience.AimdConcurrencyLimiter;
import com.enterprise.outlook.common.resilience.JitterBackoff;
import com.enterprise.outlook.common.resilience.RequestPriority;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/** Sheds excess low-priority work with 503 + randomised Retry-After while interactive traffic keeps flowing. */
public class AdaptiveLoadSheddingFilter extends OncePerRequestFilter {
    private final AimdConcurrencyLimiter limiter;

    public AdaptiveLoadSheddingFilter(AimdConcurrencyLimiter limiter) { this.limiter = limiter; }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        RequestPriority priority = RequestPriority.classify(req.getMethod(), req.getRequestURI(), req.getHeader("X-Outlook-Priority"));
        AimdConcurrencyLimiter.Permit permit = limiter.tryAcquire(priority);
        if (permit == null) {
            res.setStatus(HttpServletResponse.SC_SERVICE_UNAVAILABLE);
            res.setHeader("Retry-After", String.valueOf(JitterBackoff.retryAfterSeconds(2, 20)));
            res.setHeader("X-Shed-Reason", "adaptive-concurrency");
            res.setContentType("application/json");
            res.getWriter().write("{\"error\":\"overloaded\",\"priority\":\"" + priority + "\"}");
            return;
        }
        boolean ok = false;
        try {
            chain.doFilter(req, res);
            ok = res.getStatus() < 500;
        } finally {
            limiter.onComplete(permit, ok);
        }
    }
}
