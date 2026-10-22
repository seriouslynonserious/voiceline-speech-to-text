package com.voiceline;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;

@SpringBootTest(
    properties = {
      "spring.datasource.url=${MYSQL_TEST_URL}",
      "spring.datasource.username=root",
      "spring.datasource.password=${MYSQL_TEST_PASSWORD}",
      "app.speech-token=ci-token"
    })
@EnabledIfEnvironmentVariable(named = "MYSQL_TEST_URL", matches = ".+")
class MySqlMigrationTest {
  @Autowired TranscriptService service;
  @Autowired JdbcTemplate jdbc;

  @Test
  void migrationsAndUnicodeCrud() {
    var t = service.create("mysql-test", "हिन्दी & English");
    service.start(t.ownerId, t.id);
    service.progress(t.ownerId, t.id, "आज meeting है 👋", 3000, "small", "COMPLETED");
    assertEquals("आज meeting है 👋", service.get(t.ownerId, t.id).originalText);
    assertEquals(1, service.list(t.ownerId, "meeting", 0).getTotalElements());
    service.delete(t.ownerId, t.id);
    assertEquals(0, service.list(t.ownerId, "", 0).getTotalElements());
    assertNotNull(jdbc.queryForObject("SELECT COUNT(*) FROM SPRING_SESSION", Long.class));
  }
}
