package com.voiceline;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(
    properties = {
      "spring.datasource.url=jdbc:h2:mem:test;MODE=MySQL;DB_CLOSE_DELAY=-1",
      "spring.datasource.username=sa",
      "spring.datasource.password=",
      "spring.flyway.enabled=false",
      "spring.jpa.hibernate.ddl-auto=create-drop",
      "app.speech-token=test-token",
      "spring.session.jdbc.initialize-schema=always"
    })
@AutoConfigureMockMvc
class ApiIntegrationTest {
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired TranscriptService service;
  jakarta.servlet.http.Cookie cookie;
  String csrf, owner;
  @Autowired org.springframework.session.jdbc.JdbcIndexedSessionRepository sessions;

  @BeforeEach
  void setup() throws Exception {
    var result = mvc.perform(get("/api/session")).andExpect(status().isOk()).andReturn();
    cookie = result.getResponse().getCookie("SESSION");
    org.springframework.session.Session stored =
        sessions.findById(new String(java.util.Base64.getDecoder().decode(cookie.getValue())));
    owner = stored.getAttribute("owner");
    csrf = json.readTree(result.getResponse().getContentAsString()).get("csrfToken").asText();
  }

  String create(String title) throws Exception {
    var result =
        mvc.perform(
                post("/api/transcriptions")
                    .cookie(cookie)
                    .header("X-CSRF-Token", csrf)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(json.writeValueAsString(Map.of("title", title))))
            .andExpect(status().isCreated())
            .andReturn();
    return json.readTree(result.getResponse().getContentAsString()).get("id").asText();
  }

  @Test
  void fullCrudPreservesOriginalAndSupportsHindi() throws Exception {
    String id = create("Mixed language meeting"), owner = this.owner;
    service.start(owner, id);
    service.progress(owner, id, "आज meeting है", 4000, "small", "COMPLETED");
    var current = service.get(owner, id);
    mvc.perform(
            patch("/api/transcriptions/" + id)
                .cookie(cookie)
                .header("X-CSRF-Token", csrf)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    json.writeValueAsString(
                        Map.of(
                            "title",
                            "Updated title",
                            "text",
                            "आज हमारी meeting है",
                            "version",
                            current.version))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.text").value("आज हमारी meeting है"))
        .andExpect(jsonPath("$.originalText").value("आज meeting है"));
    mvc.perform(get("/api/transcriptions").cookie(cookie).param("q", "Updated"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.total").value(1));
    mvc.perform(delete("/api/transcriptions/" + id).cookie(cookie).header("X-CSRF-Token", csrf))
        .andExpect(status().isNoContent());
    mvc.perform(get("/api/transcriptions/" + id).cookie(cookie)).andExpect(status().isNotFound());
  }

  @Test
  void anotherVisitorCannotReadEditDeleteOrList() throws Exception {
    String id = create("Private entry");
    var otherResult = mvc.perform(get("/api/session")).andReturn();
    var other = otherResult.getResponse().getCookie("SESSION");
    var otherToken =
        json.readTree(otherResult.getResponse().getContentAsString()).get("csrfToken").asText();
    mvc.perform(get("/api/transcriptions/" + id).cookie(other)).andExpect(status().isNotFound());
    mvc.perform(get("/api/transcriptions").cookie(other)).andExpect(jsonPath("$.total").value(0));
    mvc.perform(
            delete("/api/transcriptions/" + id).cookie(other).header("X-CSRF-Token", otherToken))
        .andExpect(status().isNotFound());
    mvc.perform(
            patch("/api/transcriptions/" + id)
                .cookie(other)
                .header("X-CSRF-Token", otherToken)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"x\",\"text\":\"x\",\"version\":0}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void mutationRequiresCsrfAndValidOrigin() throws Exception {
    mvc.perform(
            post("/api/transcriptions")
                .cookie(cookie)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"x\"}"))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/transcriptions")
                .cookie(cookie)
                .header("X-CSRF-Token", csrf)
                .header("Origin", "https://evil.example")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"x\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void validationAndPagination() throws Exception {
    mvc.perform(
            post("/api/transcriptions")
                .cookie(cookie)
                .header("X-CSRF-Token", csrf)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\" \"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(get("/api/transcriptions").cookie(cookie).param("page", "-1"))
        .andExpect(status().isBadRequest());
    for (int i = 0; i < 11; i++) create("Entry " + i);
    mvc.perform(get("/api/transcriptions").cookie(cookie))
        .andExpect(jsonPath("$.items.length()").value(10))
        .andExpect(jsonPath("$.pages").value(2));
  }

  @Test
  void cannotModifyRecordingOrReuseCompletedSession() throws Exception {
    String id = create("Active");
    String owner = this.owner;
    service.start(owner, id);
    mvc.perform(delete("/api/transcriptions/" + id).cookie(cookie).header("X-CSRF-Token", csrf))
        .andExpect(status().isConflict());
    service.interrupt(owner, id);
    assertEquals("INTERRUPTED", service.get(owner, id).status);
    assertThrows(
        org.springframework.web.server.ResponseStatusException.class,
        () -> service.start(owner, id));
  }

  @Test
  void staleEditIsRejected() throws Exception {
    String id = create("First");
    String owner = this.owner;
    service.edit(owner, id, "Second", "body", 0);
    mvc.perform(
            patch("/api/transcriptions/" + id)
                .cookie(cookie)
                .header("X-CSRF-Token", csrf)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"title\":\"Stale\",\"text\":\"x\",\"version\":0}"))
        .andExpect(status().isConflict());
  }

  @Test
  void interruptedStreamKeepsConfirmedText() {
    var t = service.create(this.owner, "Interrupted");
    service.start(t.ownerId, t.id);
    service.progress(t.ownerId, t.id, "Already saved", 2000, "small", "RECORDING");
    service.interrupt(t.ownerId, t.id);
    var actual = service.get(t.ownerId, t.id);
    assertEquals("Already saved", actual.originalText);
    assertEquals("INTERRUPTED", actual.status);
  }
}
