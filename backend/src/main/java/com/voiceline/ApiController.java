package com.voiceline;

import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api")
public class ApiController {
  private final TranscriptService service;

  public ApiController(TranscriptService service) {
    this.service = service;
  }

  public static String owner(HttpSession session) {
    synchronized (session) {
      if (session.getAttribute("owner") == null)
        session.setAttribute("owner", UUID.randomUUID().toString());
      return (String) session.getAttribute("owner");
    }
  }

  @GetMapping("/session")
  public Map<String, String> session(HttpSession s) {
    owner(s);
    synchronized (s) {
      if (s.getAttribute("csrf") == null) s.setAttribute("csrf", UUID.randomUUID().toString());
      return Map.of("csrfToken", (String) s.getAttribute("csrf"));
    }
  }

  record Create(@NotBlank @Size(max = 120) String title) {}

  record Edit(
      @NotBlank @Size(max = 120) String title,
      @NotNull @Size(max = 100000) String text,
      @Min(0) long version) {}

  public record View(
      String id,
      String title,
      String text,
      String originalText,
      String status,
      long durationMs,
      String model,
      Instant createdAt,
      Instant updatedAt,
      long version) {
    static View of(Transcript t) {
      return new View(
          t.id,
          t.title,
          t.editedText == null ? t.originalText : t.editedText,
          t.originalText,
          t.status,
          t.durationMs,
          t.model,
          t.createdAt,
          t.updatedAt,
          t.version);
    }
  }

  @PostMapping("/transcriptions")
  @ResponseStatus(HttpStatus.CREATED)
  public View create(HttpSession s, @Valid @RequestBody Create req) {
    return View.of(service.create(owner(s), req.title()));
  }

  @GetMapping("/transcriptions")
  public Map<String, Object> list(
      HttpSession s,
      @RequestParam(defaultValue = "") String q,
      @RequestParam(defaultValue = "0") int page) {
    if (page < 0 || page > 10000 || q.length() > 200)
      throw new org.springframework.web.server.ResponseStatusException(
          HttpStatus.BAD_REQUEST, "Invalid search or page");
    var p = service.list(owner(s), q, page);
    return Map.of(
        "items",
        p.getContent().stream().map(View::of).toList(),
        "total",
        p.getTotalElements(),
        "pages",
        p.getTotalPages(),
        "page",
        page);
  }

  @GetMapping("/transcriptions/{id}")
  public View get(HttpSession s, @PathVariable String id) {
    return View.of(service.get(owner(s), id));
  }

  @PatchMapping("/transcriptions/{id}")
  public View edit(HttpSession s, @PathVariable String id, @Valid @RequestBody Edit req) {
    return View.of(service.edit(owner(s), id, req.title(), req.text(), req.version()));
  }

  @DeleteMapping("/transcriptions/{id}")
  @ResponseStatus(HttpStatus.NO_CONTENT)
  public void delete(HttpSession s, @PathVariable String id) {
    service.delete(owner(s), id);
  }
}
