package com.voiceline;

import jakarta.persistence.*;
import java.time.Instant;

@Entity
@Table(name = "transcriptions")
public class Transcript {
  @Id
  @Column(columnDefinition = "char(36)")
  public String id;

  @Column(nullable = false, length = 64)
  public String ownerId;

  @Column(nullable = false, length = 120)
  public String title;

  @Column(nullable = false, columnDefinition = "mediumtext")
  public String originalText = "";

  @Column(columnDefinition = "mediumtext")
  public String editedText;

  @Column(nullable = false, length = 20)
  public String status = "READY";

  public long durationMs;

  @Column(nullable = false, length = 100)
  public String model = "whisper";

  public Instant createdAt;
  public Instant updatedAt;
  @Version public long version;

  public Transcript() {}
}
