CREATE TABLE transcriptions (
 id CHAR(36) PRIMARY KEY,
 owner_id VARCHAR(64) NOT NULL,
 title VARCHAR(120) NOT NULL,
 original_text MEDIUMTEXT NOT NULL,
 edited_text MEDIUMTEXT NULL,
 status VARCHAR(20) NOT NULL,
 duration_ms BIGINT NOT NULL DEFAULT 0,
 model VARCHAR(100) NOT NULL DEFAULT 'whisper',
 created_at TIMESTAMP(6) NOT NULL,
 updated_at TIMESTAMP(6) NOT NULL,
 version BIGINT NOT NULL DEFAULT 0,
 INDEX idx_owner_created (owner_id, created_at)
);
