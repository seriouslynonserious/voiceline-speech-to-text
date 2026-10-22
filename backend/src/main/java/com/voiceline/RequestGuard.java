package com.voiceline;

import jakarta.servlet.*;
import jakarta.servlet.http.*;
import java.io.IOException;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
public class RequestGuard extends OncePerRequestFilter {
  private final String origin;

  public RequestGuard(@Value("${app.origin}") String origin) {
    this.origin = origin;
  }

  @Override
  protected void doFilterInternal(
      HttpServletRequest req, HttpServletResponse res, FilterChain chain)
      throws ServletException, IOException {
    if (req.getRequestURI().startsWith("/api/")
        && !Set.of("GET", "HEAD", "OPTIONS").contains(req.getMethod())) {
      var s = req.getSession(false);
      var token = req.getHeader("X-CSRF-Token");
      var sentOrigin = req.getHeader("Origin");
      if (s == null
          || token == null
          || !token.equals(s.getAttribute("csrf"))
          || (sentOrigin != null && !origin.equals(sentOrigin))) {
        res.sendError(403, "Invalid session or request origin");
        return;
      }
    }
    res.setHeader("X-Content-Type-Options", "nosniff");
    if (req.getRequestURI().startsWith("/api/")) res.setHeader("Cache-Control", "no-store");
    chain.doFilter(req, res);
  }
}
