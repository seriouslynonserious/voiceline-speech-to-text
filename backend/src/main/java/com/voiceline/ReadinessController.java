package com.voiceline;

import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
public class ReadinessController {
  private final String url;
  private final HttpClient client =
      HttpClient.newBuilder()
          .version(HttpClient.Version.HTTP_1_1)
          .connectTimeout(Duration.ofSeconds(2))
          .build();

  public ReadinessController(@Value("${app.speech-health-url}") String url) {
    this.url = url;
  }

  @GetMapping("/api/readiness")
  public ResponseEntity<?> health() {
    try {
      var r =
          client.send(
              HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(3)).GET().build(),
              HttpResponse.BodyHandlers.ofString());
      return ResponseEntity.status(r.statusCode() == 200 ? 200 : 503)
          .body(Map.of("ready", r.statusCode() == 200));
    } catch (Exception e) {
      return ResponseEntity.status(503).body(Map.of("ready", false));
    }
  }
}
