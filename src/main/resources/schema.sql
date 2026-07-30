CREATE TABLE IF NOT EXISTS user_account (
    user_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '사용자 내부 식별자',
    login_id VARCHAR(50) NOT NULL COMMENT '로그인 아이디',
    password VARCHAR(255) NOT NULL COMMENT '암호화된 비밀번호',
    user_name VARCHAR(100) NOT NULL COMMENT '사용자 이름',
    email VARCHAR(255) NULL COMMENT '사용자 이메일',
    enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT '계정 활성 여부',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '생성일시',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '수정일시',
    PRIMARY KEY (user_id),
    UNIQUE KEY uk_user_account_login_id (login_id),
    UNIQUE KEY uk_user_account_email (email)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS law_api_documents (
    document_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '문서 내부 식별자',
    target VARCHAR(30) NOT NULL COMMENT '국가법령 API target 값(law, admrul, prec 등)',
    external_id VARCHAR(100) NOT NULL COMMENT '국가법령 API 원본 식별자',
    title VARCHAR(500) NOT NULL COMMENT '문서 제목',
    agency_name VARCHAR(255) NULL COMMENT '소관기관 또는 담당기관명',
    category_name VARCHAR(100) NULL COMMENT '법령/행정규칙/판례 등 원본 분류명',
    source_date VARCHAR(20) NULL COMMENT '원본 기준일자(시행일자, 발령일자, 공포일자 등)',
    canonical_key VARCHAR(600) NULL COMMENT '동일 법령/행정규칙 버전 묶음 식별자',
    effective_date VARCHAR(8) NULL COMMENT '검색 기준 시행/발령일자(yyyyMMdd)',
    effective_status VARCHAR(20) NOT NULL DEFAULT 'UNKNOWN' COMMENT '버전 상태(CURRENT, FUTURE, PAST, UNKNOWN)',
    detail_link VARCHAR(1000) NULL COMMENT '국가법령 API 상세 조회 링크',
    raw_json LONGTEXT NOT NULL COMMENT '검색 목록 API 원본 JSON' CHECK (JSON_VALID(raw_json)),
    content_hash CHAR(64) NULL COMMENT '원본 JSON 변경 감지용 SHA-256 해시',
    sync_status VARCHAR(30) NOT NULL DEFAULT 'SYNCED' COMMENT '동기화 상태',
    last_error_message TEXT NULL COMMENT '마지막 동기화 오류 메시지',
    fetched_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'API에서 마지막으로 가져온 일시',
    use_yn CHAR(1) NOT NULL DEFAULT 'Y' COMMENT '사용 여부(Y/N)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '생성일시',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '수정일시',
    PRIMARY KEY (document_id),
    UNIQUE KEY uk_law_api_documents_target_external (target, external_id),
    KEY idx_law_api_documents_title (title),
    KEY idx_law_api_documents_target_source_date (target, source_date),
    KEY idx_law_api_documents_effective_status (target, canonical_key, effective_status, effective_date),
    KEY idx_law_api_documents_fetched_at (fetched_at),
    CONSTRAINT chk_law_api_documents_use_yn CHECK (use_yn IN ('Y', 'N')),
    CONSTRAINT chk_law_api_documents_effective_status CHECK (effective_status IN ('CURRENT', 'FUTURE', 'PAST', 'UNKNOWN'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS law_api_document_details (
    detail_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '상세 정보 내부 식별자',
    document_id BIGINT NOT NULL COMMENT '연결된 문서 식별자',
    detail_title VARCHAR(500) NULL COMMENT '상세 화면 제목',
    meta_json LONGTEXT NULL COMMENT '상세 메타데이터 JSON' CHECK (meta_json IS NULL OR JSON_VALID(meta_json)),
    sections_json LONGTEXT NULL COMMENT '화면 표시용 섹션 JSON' CHECK (sections_json IS NULL OR JSON_VALID(sections_json)),
    raw_json LONGTEXT NOT NULL COMMENT '상세 API 원본 JSON 또는 변환 JSON' CHECK (JSON_VALID(raw_json)),
    content_hash CHAR(64) NULL COMMENT '상세 원본 변경 감지용 SHA-256 해시',
    sync_status VARCHAR(30) NOT NULL DEFAULT 'SYNCED' COMMENT '상세 동기화 상태',
    last_error_message TEXT NULL COMMENT '마지막 상세 동기화 오류 메시지',
    fetched_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '상세 API에서 마지막으로 가져온 일시',
    use_yn CHAR(1) NOT NULL DEFAULT 'Y' COMMENT '사용 여부(Y/N)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '생성일시',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '수정일시',
    PRIMARY KEY (detail_id),
    UNIQUE KEY uk_law_api_document_details_document (document_id),
    KEY idx_law_api_document_details_fetched_at (fetched_at),
    CONSTRAINT fk_law_api_document_details_document
        FOREIGN KEY (document_id) REFERENCES law_api_documents (document_id)
        ON DELETE CASCADE,
    CONSTRAINT chk_law_api_document_details_use_yn CHECK (use_yn IN ('Y', 'N'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS law_api_document_chunks (
    chunk_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '청크 내부 식별자',
    document_id BIGINT NOT NULL COMMENT '연결된 문서 식별자',
    detail_id BIGINT NULL COMMENT '연결된 상세 정보 식별자',
    chunk_type VARCHAR(50) NOT NULL COMMENT '청크 유형(article, paragraph, supplement, appendix, form 등)',
    chunk_no VARCHAR(100) NULL COMMENT '조문번호, 부칙번호, 별표번호 등 원본 번호',
    chunk_title VARCHAR(500) NULL COMMENT '청크 제목',
    chunk_text LONGTEXT NOT NULL COMMENT 'AI 검색 기준 원문 텍스트',
    source_path VARCHAR(500) NULL COMMENT '원본 JSON 경로 또는 정규화된 출처 위치',
    source_url VARCHAR(4000) NULL COMMENT '청크 출처 URL',
    sort_order INT NOT NULL DEFAULT 0 COMMENT '문서 내 표시 및 색인 순서',
    content_hash CHAR(64) NULL COMMENT '청크 텍스트 변경 감지용 SHA-256 해시',
    indexed_at DATETIME NULL COMMENT '검색/벡터 인덱스에 마지막으로 반영한 일시',
    index_status VARCHAR(30) NOT NULL DEFAULT 'PENDING' COMMENT '검색/벡터 인덱싱 상태',
    last_error_message TEXT NULL COMMENT '마지막 청크 처리 오류 메시지',
    use_yn CHAR(1) NOT NULL DEFAULT 'Y' COMMENT '사용 여부(Y/N)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '생성일시',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '수정일시',
    PRIMARY KEY (chunk_id),
    KEY idx_law_api_document_chunks_document (document_id, sort_order),
    KEY idx_law_api_document_chunks_detail (detail_id),
    KEY idx_law_api_document_chunks_type (chunk_type),
    KEY idx_law_api_document_chunks_index_status (index_status),
    CONSTRAINT fk_law_api_document_chunks_document
        FOREIGN KEY (document_id) REFERENCES law_api_documents (document_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_law_api_document_chunks_detail
        FOREIGN KEY (detail_id) REFERENCES law_api_document_details (detail_id)
        ON DELETE SET NULL,
    CONSTRAINT chk_law_api_document_chunks_use_yn CHECK (use_yn IN ('Y', 'N'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS law_api_assets (
    asset_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '파일/이미지 자산 내부 식별자',
    document_id BIGINT NOT NULL COMMENT '연결된 문서 식별자',
    detail_id BIGINT NULL COMMENT '연결된 상세 정보 식별자',
    asset_type VARCHAR(50) NOT NULL COMMENT '자산 유형(image, pdf, hwp, doc, file, link 등)',
    source_url VARCHAR(4000) NOT NULL COMMENT '국가법령 원본 파일 또는 이미지 URL',
    proxy_url TEXT NULL COMMENT '우리 서버 프록시 URL',
    file_name VARCHAR(500) NULL COMMENT '파일명',
    file_extension VARCHAR(20) NULL COMMENT '파일 확장자',
    mime_type VARCHAR(100) NULL COMMENT 'MIME 타입',
    alt_text VARCHAR(500) NULL COMMENT '이미지 대체 텍스트 또는 파일 설명',
    raw_json LONGTEXT NULL COMMENT '자산 관련 원본 JSON' CHECK (raw_json IS NULL OR JSON_VALID(raw_json)),
    sort_order INT NOT NULL DEFAULT 0 COMMENT '문서 내 자산 표시 순서',
    use_yn CHAR(1) NOT NULL DEFAULT 'Y' COMMENT '사용 여부(Y/N)',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '생성일시',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '수정일시',
    PRIMARY KEY (asset_id),
    KEY idx_law_api_assets_document (document_id, sort_order),
    KEY idx_law_api_assets_detail (detail_id),
    KEY idx_law_api_assets_type (asset_type),
    CONSTRAINT fk_law_api_assets_document
        FOREIGN KEY (document_id) REFERENCES law_api_documents (document_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_law_api_assets_detail
        FOREIGN KEY (detail_id) REFERENCES law_api_document_details (detail_id)
        ON DELETE SET NULL,
    CONSTRAINT chk_law_api_assets_use_yn CHECK (use_yn IN ('Y', 'N'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS law_api_chunk_embeddings (
    chunk_id BIGINT NOT NULL COMMENT '벡터화된 청크 식별자',
    embedding_model VARCHAR(100) NOT NULL COMMENT '임베딩 모델명',
    vector_store VARCHAR(100) NOT NULL COMMENT '벡터 저장소 또는 컬렉션명',
    vector_point_id VARCHAR(100) NOT NULL COMMENT '벡터 저장소 point id',
    content_hash CHAR(64) NULL COMMENT '벡터화 당시 청크 본문 해시',
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING' COMMENT '벡터 색인 상태',
    embedded_at DATETIME NULL COMMENT '벡터 저장 완료 일시',
    last_error_message TEXT NULL COMMENT '마지막 임베딩/색인 오류 메시지',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '생성일시',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '수정일시',
    PRIMARY KEY (chunk_id, embedding_model, vector_store),
    KEY idx_law_api_chunk_embeddings_status (status, embedded_at),
    CONSTRAINT fk_law_api_chunk_embeddings_chunk
        FOREIGN KEY (chunk_id) REFERENCES law_api_document_chunks (chunk_id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS semantic_batch_jobs (
    batch_job_id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'OpenAI Batch job local id',
    openai_batch_id VARCHAR(100) NOT NULL COMMENT 'OpenAI Batch id',
    input_file_id VARCHAR(100) NOT NULL COMMENT 'OpenAI input file id',
    output_file_id VARCHAR(100) NULL COMMENT 'OpenAI output file id',
    error_file_id VARCHAR(100) NULL COMMENT 'OpenAI error file id',
    status VARCHAR(30) NOT NULL COMMENT 'Batch status',
    target VARCHAR(30) NOT NULL COMMENT 'law data target',
    query_text VARCHAR(500) NULL COMMENT 'candidate filter query',
    embedding_model VARCHAR(100) NOT NULL COMMENT 'embedding model',
    vector_store VARCHAR(100) NOT NULL COMMENT 'Qdrant collection',
    input_file_path VARCHAR(1000) NOT NULL COMMENT 'local JSONL input file path',
    output_file_path VARCHAR(1000) NULL COMMENT 'local JSONL output file path',
    requested_count INT NOT NULL DEFAULT 0 COMMENT 'requested candidate count',
    submitted_count INT NOT NULL DEFAULT 0 COMMENT 'submitted request count',
    completed_count INT NOT NULL DEFAULT 0 COMMENT 'OpenAI completed request count',
    failed_count INT NOT NULL DEFAULT 0 COMMENT 'OpenAI failed request count',
    ingested_count INT NOT NULL DEFAULT 0 COMMENT 'Qdrant ingested vector count',
    last_error_message TEXT NULL COMMENT 'last error message',
    submitted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'submitted time',
    completed_at DATETIME NULL COMMENT 'OpenAI completed time',
    ingested_at DATETIME NULL COMMENT 'local ingest completed time',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'created time',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'updated time',
    PRIMARY KEY (batch_job_id),
    UNIQUE KEY uk_semantic_batch_jobs_openai (openai_batch_id),
    KEY idx_semantic_batch_jobs_status (status, submitted_at),
    KEY idx_semantic_batch_jobs_target (target, submitted_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS semantic_batch_job_chunks (
    batch_job_chunk_id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Batch job chunk local id',
    batch_job_id BIGINT NOT NULL COMMENT 'Local batch job id',
    openai_batch_id VARCHAR(100) NOT NULL COMMENT 'OpenAI Batch id',
    target VARCHAR(30) NOT NULL COMMENT 'law data target',
    chunk_id BIGINT NOT NULL COMMENT 'Submitted chunk id',
    custom_id VARCHAR(100) NOT NULL COMMENT 'OpenAI Batch request custom id',
    status VARCHAR(30) NOT NULL DEFAULT 'SUBMITTED' COMMENT 'Chunk batch status',
    error_code VARCHAR(100) NULL COMMENT 'OpenAI or local error code',
    error_message TEXT NULL COMMENT 'OpenAI or local error message',
    submitted_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'submitted time',
    output_ready_at DATETIME NULL COMMENT 'output line parsed time',
    ingested_at DATETIME NULL COMMENT 'Qdrant ingest completed time',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'created time',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'updated time',
    PRIMARY KEY (batch_job_chunk_id),
    UNIQUE KEY uk_semantic_batch_job_chunks_job_chunk (batch_job_id, chunk_id),
    UNIQUE KEY uk_semantic_batch_job_chunks_openai_custom (openai_batch_id, custom_id),
    KEY idx_semantic_batch_job_chunks_chunk (chunk_id),
    KEY idx_semantic_batch_job_chunks_status (status),
    KEY idx_semantic_batch_job_chunks_openai (openai_batch_id),
    CONSTRAINT fk_semantic_batch_job_chunks_job
        FOREIGN KEY (batch_job_id) REFERENCES semantic_batch_jobs (batch_job_id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS rag_documents (
    document_id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'RAG uploaded document id',
    document_type VARCHAR(30) NOT NULL COMMENT 'official_doc, internal_doc, reference_doc',
    title VARCHAR(500) NOT NULL COMMENT 'document title',
    source_org VARCHAR(200) NULL COMMENT 'source organization',
    document_category VARCHAR(100) NULL COMMENT 'business category',
    document_topic VARCHAR(500) NULL COMMENT 'document topic keywords',
    published_date VARCHAR(20) NULL COMMENT 'published date',
    version VARCHAR(100) NULL COMMENT 'document version',
    trust_level INT NOT NULL DEFAULT 1 COMMENT '1 official/work basis, 5 reference',
    file_name VARCHAR(500) NOT NULL COMMENT 'original file name',
    file_path VARCHAR(1000) NOT NULL COMMENT 'local file path',
    object_key VARCHAR(1000) NULL COMMENT 'private object storage key for original file',
    file_hash CHAR(64) NOT NULL COMMENT 'SHA-256 file hash',
    mime_type VARCHAR(100) NULL COMMENT 'detected mime type',
    source_url VARCHAR(4000) NULL COMMENT 'source url or local reference',
    import_status VARCHAR(30) NOT NULL DEFAULT 'PENDING' COMMENT 'import status',
    last_error_message TEXT NULL COMMENT 'last import error',
    use_yn CHAR(1) NOT NULL DEFAULT 'Y' COMMENT 'use flag',
    imported_at DATETIME NULL COMMENT 'last imported time',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'created time',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'updated time',
    PRIMARY KEY (document_id),
    UNIQUE KEY uk_rag_documents_file_hash (file_hash),
    KEY idx_rag_documents_type (document_type, use_yn),
    KEY idx_rag_documents_status (import_status, imported_at),
    CONSTRAINT chk_rag_documents_type CHECK (document_type IN ('official_doc', 'internal_doc', 'reference_doc')),
    CONSTRAINT chk_rag_documents_trust CHECK (trust_level IN (1, 5)),
    CONSTRAINT chk_rag_documents_use_yn CHECK (use_yn IN ('Y', 'N'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS rag_document_chunks (
    chunk_id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'RAG document chunk id',
    document_id BIGINT NOT NULL COMMENT 'RAG document id',
    chunk_version INT NOT NULL DEFAULT 1 COMMENT 'chunking strategy version',
    chunk_no VARCHAR(100) NULL COMMENT 'page or chunk number',
    parent_section_title VARCHAR(500) NULL COMMENT 'parent section title',
    chunk_title VARCHAR(500) NULL COMMENT 'section title',
    section_type VARCHAR(50) NOT NULL DEFAULT 'body' COMMENT 'body, target_scope, procedure, requirement, exception, example, table',
    chunk_text LONGTEXT NOT NULL COMMENT 'chunk text',
    embedding_text LONGTEXT NULL COMMENT 'versioned text used for embedding',
    page_no INT NULL COMMENT 'source page number',
    source_path VARCHAR(1000) NULL COMMENT 'source path',
    source_url VARCHAR(4000) NULL COMMENT 'source url',
    sort_order INT NOT NULL DEFAULT 0 COMMENT 'display order',
    content_hash CHAR(64) NOT NULL COMMENT 'chunk content hash',
	quality_status VARCHAR(20) NOT NULL DEFAULT 'PASS' COMMENT 'PASS, REVIEW, CONTEXT_ONLY, REJECT',
	quality_reason VARCHAR(100) NULL COMMENT 'chunk quality decision reason',
    index_status VARCHAR(30) NOT NULL DEFAULT 'PENDING' COMMENT 'index status',
    use_yn CHAR(1) NOT NULL DEFAULT 'Y' COMMENT 'use flag',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'created time',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'updated time',
    PRIMARY KEY (chunk_id),
    KEY idx_rag_document_chunks_document (document_id, sort_order),
    KEY idx_rag_document_chunks_version (document_id, chunk_version, use_yn, sort_order),
    KEY idx_rag_document_chunks_section (section_type, chunk_version),
    KEY idx_rag_document_chunks_status (index_status),
	KEY idx_rag_document_chunks_quality (quality_status, chunk_version, use_yn),
    CONSTRAINT fk_rag_document_chunks_document
        FOREIGN KEY (document_id) REFERENCES rag_documents (document_id)
        ON DELETE CASCADE,
    CONSTRAINT chk_rag_document_chunks_use_yn CHECK (use_yn IN ('Y', 'N'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE rag_document_chunks
    ADD COLUMN IF NOT EXISTS chunk_version INT NOT NULL DEFAULT 1 COMMENT 'chunking strategy version';

ALTER TABLE rag_document_chunks
    ADD COLUMN IF NOT EXISTS parent_section_title VARCHAR(500) NULL COMMENT 'parent section title';

ALTER TABLE rag_document_chunks
    ADD COLUMN IF NOT EXISTS section_type VARCHAR(50) NOT NULL DEFAULT 'body' COMMENT 'body, target_scope, procedure, requirement, exception, example, table';

ALTER TABLE rag_document_chunks
    ADD COLUMN IF NOT EXISTS embedding_text LONGTEXT NULL COMMENT 'versioned text used for embedding';

ALTER TABLE rag_document_chunks
	ADD COLUMN IF NOT EXISTS quality_status VARCHAR(20) NOT NULL DEFAULT 'PASS' COMMENT 'PASS, REVIEW, CONTEXT_ONLY, REJECT';

ALTER TABLE rag_document_chunks
	ADD COLUMN IF NOT EXISTS quality_reason VARCHAR(100) NULL COMMENT 'chunk quality decision reason';

CREATE INDEX IF NOT EXISTS idx_rag_document_chunks_version
    ON rag_document_chunks (document_id, chunk_version, use_yn, sort_order);

CREATE INDEX IF NOT EXISTS idx_rag_document_chunks_section
    ON rag_document_chunks (section_type, chunk_version);

CREATE INDEX IF NOT EXISTS idx_rag_document_chunks_quality
	ON rag_document_chunks (quality_status, chunk_version, use_yn);

CREATE TABLE IF NOT EXISTS rag_chunk_search_terms (
    term VARCHAR(80) NOT NULL COMMENT 'normalized exact search term',
    chunk_id BIGINT NOT NULL COMMENT 'RAG chunk id',
    document_id BIGINT NOT NULL COMMENT 'RAG document id',
    field_kind VARCHAR(30) NOT NULL COMMENT 'document_title, parent_title, chunk_title, body',
    weight SMALLINT NOT NULL DEFAULT 1 COMMENT 'field relevance weight',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'created time',
    PRIMARY KEY (term, chunk_id),
    KEY idx_rag_chunk_search_terms_chunk (chunk_id),
    KEY idx_rag_chunk_search_terms_document (document_id, chunk_id),
    CONSTRAINT fk_rag_chunk_search_terms_chunk
        FOREIGN KEY (chunk_id) REFERENCES rag_document_chunks (chunk_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_rag_chunk_search_terms_document
        FOREIGN KEY (document_id) REFERENCES rag_documents (document_id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS rag_chunk_search_index_state (
    chunk_id BIGINT NOT NULL COMMENT 'RAG chunk id',
    document_id BIGINT NOT NULL COMMENT 'RAG document id',
    index_version INT NOT NULL DEFAULT 2 COMMENT 'lexical index format version',
    content_hash CHAR(64) NULL COMMENT 'indexed chunk content hash',
    term_count INT NOT NULL DEFAULT 0 COMMENT 'number of indexed terms',
    completed_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'index completion time',
    PRIMARY KEY (chunk_id),
    KEY idx_rag_chunk_search_index_state_document (document_id, chunk_id),
    CONSTRAINT fk_rag_chunk_search_index_state_chunk
        FOREIGN KEY (chunk_id) REFERENCES rag_document_chunks (chunk_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_rag_chunk_search_index_state_document
        FOREIGN KEY (document_id) REFERENCES rag_documents (document_id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS rag_search_index_control (
    index_name VARCHAR(60) NOT NULL,
    index_version INT NOT NULL DEFAULT 2,
    status VARCHAR(20) NOT NULL DEFAULT 'BUILDING' COMMENT 'BUILDING, READY',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (index_name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO rag_search_index_control (index_name, index_version, status)
VALUES ('rag_chunk_terms', 2, 'BUILDING')
ON DUPLICATE KEY UPDATE
    status = IF(index_version = VALUES(index_version), status, 'BUILDING'),
    index_version = VALUES(index_version);

CREATE TABLE IF NOT EXISTS rag_chunk_embeddings (
    chunk_id BIGINT NOT NULL COMMENT 'RAG chunk id',
    embedding_model VARCHAR(100) NOT NULL COMMENT 'embedding model',
    vector_store VARCHAR(100) NOT NULL COMMENT 'Qdrant collection',
    vector_point_id VARCHAR(100) NOT NULL COMMENT 'Qdrant point id',
    content_hash CHAR(64) NULL COMMENT 'chunk content hash',
    status VARCHAR(30) NOT NULL DEFAULT 'PENDING' COMMENT 'embedding status',
    embedded_at DATETIME NULL COMMENT 'embedded time',
    last_error_message TEXT NULL COMMENT 'last embedding error',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'created time',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'updated time',
    PRIMARY KEY (chunk_id, embedding_model, vector_store),
    KEY idx_rag_chunk_embeddings_status (status, embedded_at),
    CONSTRAINT fk_rag_chunk_embeddings_chunk
        FOREIGN KEY (chunk_id) REFERENCES rag_document_chunks (chunk_id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS rag_import_jobs (
    import_job_id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'RAG import job id',
    import_path VARCHAR(1000) NOT NULL COMMENT 'folder or file path',
    document_type VARCHAR(30) NULL COMMENT 'requested document type',
    status VARCHAR(30) NOT NULL DEFAULT 'RUNNING' COMMENT 'job status',
    discovered_count INT NOT NULL DEFAULT 0 COMMENT 'discovered files',
    imported_count INT NOT NULL DEFAULT 0 COMMENT 'imported documents',
    skipped_count INT NOT NULL DEFAULT 0 COMMENT 'skipped documents',
    failed_count INT NOT NULL DEFAULT 0 COMMENT 'failed documents',
    indexed_count INT NOT NULL DEFAULT 0 COMMENT 'indexed chunks',
    last_error_message TEXT NULL COMMENT 'last error',
    started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'started time',
    finished_at DATETIME NULL COMMENT 'finished time',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'created time',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'updated time',
    PRIMARY KEY (import_job_id),
    KEY idx_rag_import_jobs_status (status, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS rag_collection_sources (
    source_id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'RAG external collection source id',
    source_key VARCHAR(100) NOT NULL COMMENT 'stable source key',
    source_type VARCHAR(30) NOT NULL DEFAULT 'RSS' COMMENT 'RSS, API, BOARD',
    agency_code VARCHAR(30) NOT NULL COMMENT 'agency code',
    agency_name VARCHAR(200) NOT NULL COMMENT 'agency display name',
    source_url VARCHAR(4000) NOT NULL COMMENT 'feed or API URL',
    enabled CHAR(1) NOT NULL DEFAULT 'Y' COMMENT 'enabled flag',
    last_checked_at DATETIME NULL COMMENT 'last checked time',
    last_success_at DATETIME NULL COMMENT 'last successful check time',
    last_error_message TEXT NULL COMMENT 'last collection error',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'created time',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'updated time',
    PRIMARY KEY (source_id),
    UNIQUE KEY uk_rag_collection_sources_key (source_key),
    KEY idx_rag_collection_sources_agency (agency_code, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS rag_source_articles (
    article_id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'RAG source article id',
    source_id BIGINT NOT NULL COMMENT 'collection source id',
    external_id VARCHAR(500) NOT NULL COMMENT 'RSS guid or normalized link hash',
    title VARCHAR(1000) NOT NULL COMMENT 'article title',
    link VARCHAR(4000) NOT NULL COMMENT 'article URL',
    published_at DATETIME NULL COMMENT 'published time',
    status VARCHAR(30) NOT NULL DEFAULT 'DISCOVERED' COMMENT 'DISCOVERED, DOWNLOADED, IMPORTED, SKIPPED, FAILED',
    detail_hash CHAR(64) NULL COMMENT 'detail page hash',
    last_error_message TEXT NULL COMMENT 'last article error',
    fetched_at DATETIME NULL COMMENT 'last fetched time',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'created time',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'updated time',
    PRIMARY KEY (article_id),
    UNIQUE KEY uk_rag_source_articles_external (source_id, external_id),
    KEY idx_rag_source_articles_status (status, fetched_at),
    CONSTRAINT fk_rag_source_articles_source
        FOREIGN KEY (source_id) REFERENCES rag_collection_sources (source_id)
        ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS rag_source_attachments (
    attachment_id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'RAG source attachment id',
    article_id BIGINT NOT NULL COMMENT 'source article id',
    url VARCHAR(4000) NOT NULL COMMENT 'attachment URL',
    file_name VARCHAR(500) NOT NULL COMMENT 'downloaded file name',
    extension VARCHAR(20) NOT NULL COMMENT 'file extension',
    mime_type VARCHAR(100) NULL COMMENT 'detected mime type',
    file_hash CHAR(64) NULL COMMENT 'SHA-256 file hash',
    local_path VARCHAR(1000) NULL COMMENT 'local downloaded path',
    document_id BIGINT NULL COMMENT 'linked rag document id',
    status VARCHAR(30) NOT NULL DEFAULT 'DISCOVERED' COMMENT 'DISCOVERED, DOWNLOADED, IMPORTED, SKIPPED, FAILED',
    last_error_message TEXT NULL COMMENT 'last attachment error',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'created time',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'updated time',
    PRIMARY KEY (attachment_id),
    UNIQUE KEY uk_rag_source_attachments_url (article_id, url(500)),
    KEY idx_rag_source_attachments_status (status),
    KEY idx_rag_source_attachments_document (document_id),
    CONSTRAINT fk_rag_source_attachments_article
        FOREIGN KEY (article_id) REFERENCES rag_source_articles (article_id)
        ON DELETE CASCADE,
    CONSTRAINT fk_rag_source_attachments_document
        FOREIGN KEY (document_id) REFERENCES rag_documents (document_id)
        ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS rag_collection_runs (
    run_id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'RAG collection run id',
    agency_code VARCHAR(30) NULL COMMENT 'requested agency code',
    status VARCHAR(30) NOT NULL DEFAULT 'RUNNING' COMMENT 'RUNNING, SUCCESS, PARTIAL_SUCCESS, FAILED',
    discovered_articles INT NOT NULL DEFAULT 0 COMMENT 'discovered article count',
    new_articles INT NOT NULL DEFAULT 0 COMMENT 'new article count',
    attachments_discovered INT NOT NULL DEFAULT 0 COMMENT 'discovered attachment count',
    downloaded_count INT NOT NULL DEFAULT 0 COMMENT 'downloaded attachment count',
    imported_count INT NOT NULL DEFAULT 0 COMMENT 'imported document count',
    skipped_count INT NOT NULL DEFAULT 0 COMMENT 'skipped count',
    failed_count INT NOT NULL DEFAULT 0 COMMENT 'failed count',
    submitted_batches INT NOT NULL DEFAULT 0 COMMENT 'newly submitted batch count',
    last_error_message TEXT NULL COMMENT 'last run error',
    started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'started time',
    finished_at DATETIME NULL COMMENT 'finished time',
    PRIMARY KEY (run_id),
    KEY idx_rag_collection_runs_status (status, started_at),
    KEY idx_rag_collection_runs_agency (agency_code, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS law_api_sync_history (
    sync_history_id BIGINT NOT NULL AUTO_INCREMENT COMMENT '동기화 이력 내부 식별자',
    sync_type VARCHAR(50) NOT NULL COMMENT '동기화 유형(search, detail, chunk, asset, full-sync 등)',
    target VARCHAR(30) NULL COMMENT '국가법령 API target 값',
    external_id VARCHAR(100) NULL COMMENT '국가법령 API 원본 식별자',
    document_id BIGINT NULL COMMENT '연결된 문서 식별자',
    status VARCHAR(30) NOT NULL COMMENT '동기화 처리 상태',
    request_json LONGTEXT NULL COMMENT 'API 요청 또는 내부 처리 요청 JSON' CHECK (request_json IS NULL OR JSON_VALID(request_json)),
    response_json LONGTEXT NULL COMMENT 'API 응답 또는 내부 처리 결과 JSON' CHECK (response_json IS NULL OR JSON_VALID(response_json)),
    error_message TEXT NULL COMMENT '동기화 실패 오류 메시지',
    started_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '동기화 시작일시',
    finished_at DATETIME NULL COMMENT '동기화 종료일시',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '생성일시',
    PRIMARY KEY (sync_history_id),
    KEY idx_law_api_sync_history_target_external (target, external_id),
    KEY idx_law_api_sync_history_document (document_id),
    KEY idx_law_api_sync_history_status_started (status, started_at),
    CONSTRAINT fk_law_api_sync_history_document
        FOREIGN KEY (document_id) REFERENCES law_api_documents (document_id)
        ON DELETE SET NULL
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS law_ai_search_failure_logs (
    failure_id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'AI search failure log id',
    question TEXT NOT NULL COMMENT 'user question',
    targets VARCHAR(500) NULL COMMENT 'selected search targets',
    intent_types VARCHAR(1000) NULL COMMENT 'detected intent types',
    entity_ids VARCHAR(1000) NULL COMMENT 'detected entity ids',
    lexical_keywords TEXT NULL COMMENT 'lexical keywords used for search',
    expanded_queries TEXT NULL COMMENT 'multi-query search texts',
    failure_type VARCHAR(50) NOT NULL DEFAULT 'PIPELINE_RESULT_INCONSISTENT' COMMENT 'classified failure type',
    failure_stage VARCHAR(50) NOT NULL DEFAULT 'PIPELINE' COMMENT 'pipeline stage where failure happened',
    retryable TINYINT(1) NOT NULL DEFAULT 1 COMMENT 'whether search logic/data can improve this failure',
    eval_candidate TINYINT(1) NOT NULL DEFAULT 1 COMMENT 'whether this failure should become an evaluation candidate',
    qdrant_hit_count INT NOT NULL DEFAULT 0 COMMENT 'raw qdrant hit count',
    vector_chunk_count INT NOT NULL DEFAULT 0 COMMENT 'vector chunks loaded from DB',
    lexical_chunk_count INT NOT NULL DEFAULT 0 COMMENT 'keyword chunks loaded from DB',
    merged_count INT NOT NULL DEFAULT 0 COMMENT 'merged candidate count',
    ranked_count INT NOT NULL DEFAULT 0 COMMENT 'reranked candidate count',
    intent_filtered_count INT NOT NULL DEFAULT 0 COMMENT 'intent filtered candidate count',
    judge_candidate_count INT NOT NULL DEFAULT 0 COMMENT 'candidate count sent to evidence judge',
    judged_count INT NOT NULL DEFAULT 0 COMMENT 'evidence judge accepted count',
    final_ground_count INT NOT NULL DEFAULT 0 COMMENT 'final returned ground count',
    topic_aligned_count INT NOT NULL DEFAULT 0 COMMENT 'topic-aligned evidence count',
    relevant_count INT NOT NULL DEFAULT 0 COMMENT 'relevant evidence count',
    direct_evidence_count INT NOT NULL DEFAULT 0 COMMENT 'direct evidence count',
    evidence_selection_policy VARCHAR(50) NOT NULL DEFAULT 'empty' COMMENT 'evidence selection policy',
    document_scope_mismatch TINYINT(1) NOT NULL DEFAULT 0 COMMENT 'selected targets excluded preferred targets',
    result_msg VARCHAR(50) NOT NULL COMMENT 'retrieval result code',
    public_message TEXT NULL COMMENT 'public no-ground message',
    diagnostic_message TEXT NULL COMMENT 'internal diagnostic message',
    review_status VARCHAR(30) NOT NULL DEFAULT 'OPEN' COMMENT 'review workflow status',
    promoted_eval_case_id VARCHAR(120) NULL COMMENT 'evaluation case id when promoted',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'created time',
    PRIMARY KEY (failure_id),
    KEY idx_law_ai_search_failure_created (created_at),
    KEY idx_law_ai_search_failure_result (result_msg, created_at),
    KEY idx_law_ai_search_failure_type (failure_type, created_at),
    KEY idx_law_ai_search_failure_review (review_status, eval_candidate, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS admin_user (
    admin_user_id BIGINT NOT NULL AUTO_INCREMENT COMMENT 'Admin user id',
    username VARCHAR(50) NOT NULL COMMENT 'Login username',
    password_hash VARCHAR(255) NOT NULL COMMENT 'BCrypt password hash',
    display_name VARCHAR(100) NOT NULL COMMENT 'Display name',
    role VARCHAR(30) NOT NULL DEFAULT 'ADMIN' COMMENT 'Admin role',
    enabled BOOLEAN NOT NULL DEFAULT TRUE COMMENT 'Enabled flag',
    failed_login_count INT NOT NULL DEFAULT 0 COMMENT 'Consecutive failed login count',
    locked_until DATETIME NULL COMMENT 'Temporary login lock expiration time',
    last_login_at DATETIME NULL COMMENT 'Last successful login time',
    last_failed_at DATETIME NULL COMMENT 'Last failed login time',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'Created time',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'Updated time',
    PRIMARY KEY (admin_user_id),
    UNIQUE KEY uk_admin_user_username (username),
    KEY idx_admin_user_enabled (enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
