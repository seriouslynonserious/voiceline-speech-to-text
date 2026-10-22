package com.voiceline;

import java.util.Optional;
import org.springframework.data.domain.*;
import org.springframework.data.jpa.repository.*;
import org.springframework.data.repository.query.Param;

public interface TranscriptRepository extends JpaRepository<Transcript, String> {
  Optional<Transcript> findByIdAndOwnerId(String id, String ownerId);

  @Query(
      "select t from Transcript t where t.ownerId=:owner and (lower(t.title) like"
          + " lower(concat('%',:q,'%')) or lower(coalesce(t.editedText,t.originalText)) like"
          + " lower(concat('%',:q,'%'))) ")
  Page<Transcript> search(@Param("owner") String owner, @Param("q") String q, Pageable page);

  @Modifying
  @Query(
      "update Transcript t set t.status='INTERRUPTED', t.updatedAt=:now, t.version=t.version+1"
          + " where t.status='RECORDING'")
  void interruptStale(@Param("now") java.time.Instant now);
}
