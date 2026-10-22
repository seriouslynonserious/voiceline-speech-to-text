package com.voiceline;

import java.time.Instant;
import java.util.UUID;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.data.domain.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class TranscriptService {
  private final TranscriptRepository repo;

  public TranscriptService(TranscriptRepository repo) {
    this.repo = repo;
  }

  @EventListener(ApplicationReadyEvent.class)
  @Transactional
  public void recover() {
    repo.interruptStale(Instant.now());
  }

  @Transactional
  public Transcript create(String owner, String title) {
    var t = new Transcript();
    t.id = UUID.randomUUID().toString();
    t.ownerId = owner;
    t.title = title;
    t.createdAt = t.updatedAt = Instant.now();
    return repo.save(t);
  }

  @Transactional(readOnly = true)
  public Transcript get(String owner, String id) {
    return repo.findByIdAndOwnerId(id, owner)
        .orElseThrow(
            () -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Transcription not found"));
  }

  @Transactional(readOnly = true)
  public Page<Transcript> list(String owner, String q, int page) {
    return repo.search(
        owner, q, PageRequest.of(page, 10, Sort.by(Sort.Direction.DESC, "createdAt")));
  }

  @Transactional
  public Transcript edit(String owner, String id, String title, String text, long version) {
    var t = get(owner, id);
    inactive(t);
    if (t.version != version)
      throw new ResponseStatusException(
          HttpStatus.CONFLICT, "This transcript changed. Reopen it before editing.");
    t.title = title;
    t.editedText = text;
    t.updatedAt = Instant.now();
    return repo.saveAndFlush(t);
  }

  @Transactional
  public void delete(String owner, String id) {
    var t = get(owner, id);
    inactive(t);
    repo.delete(t);
  }

  private void inactive(Transcript t) {
    if (t.status.equals("RECORDING"))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Stop recording first");
  }

  @Transactional
  public void start(String owner, String id) {
    var t = get(owner, id);
    if (!t.status.equals("READY"))
      throw new ResponseStatusException(HttpStatus.CONFLICT, "Create a new recording session");
    t.status = "RECORDING";
    t.updatedAt = Instant.now();
    repo.saveAndFlush(t);
  }

  @Transactional
  public void progress(
      String owner, String id, String text, long duration, String model, String status) {
    var t = get(owner, id);
    if (!t.status.equals("RECORDING")) return;
    t.originalText = text;
    t.durationMs = duration;
    t.model = model;
    t.status = status;
    t.updatedAt = Instant.now();
  }

  @Transactional
  public void interrupt(String owner, String id) {
    var t = get(owner, id);
    if (t.status.equals("RECORDING")) {
      t.status = "INTERRUPTED";
      t.updatedAt = Instant.now();
    }
  }
}
