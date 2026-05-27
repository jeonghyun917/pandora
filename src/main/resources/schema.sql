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
    KEY idx_law_api_documents_fetched_at (fetched_at),
    CONSTRAINT chk_law_api_documents_use_yn CHECK (use_yn IN ('Y', 'N'))
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
    source_url VARCHAR(1000) NULL COMMENT '청크 출처 URL',
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
    source_url VARCHAR(1000) NOT NULL COMMENT '국가법령 원본 파일 또는 이미지 URL',
    proxy_url VARCHAR(1000) NULL COMMENT '우리 서버 프록시 URL',
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
    file_hash CHAR(64) NOT NULL COMMENT 'SHA-256 file hash',
    mime_type VARCHAR(100) NULL COMMENT 'detected mime type',
    source_url VARCHAR(1000) NULL COMMENT 'source url or local reference',
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
    chunk_no VARCHAR(100) NULL COMMENT 'page or chunk number',
    chunk_title VARCHAR(500) NULL COMMENT 'section title',
    chunk_text LONGTEXT NOT NULL COMMENT 'chunk text',
    page_no INT NULL COMMENT 'source page number',
    source_path VARCHAR(1000) NULL COMMENT 'source path',
    source_url VARCHAR(1000) NULL COMMENT 'source url',
    sort_order INT NOT NULL DEFAULT 0 COMMENT 'display order',
    content_hash CHAR(64) NOT NULL COMMENT 'chunk content hash',
    index_status VARCHAR(30) NOT NULL DEFAULT 'PENDING' COMMENT 'index status',
    use_yn CHAR(1) NOT NULL DEFAULT 'Y' COMMENT 'use flag',
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT 'created time',
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT 'updated time',
    PRIMARY KEY (chunk_id),
    KEY idx_rag_document_chunks_document (document_id, sort_order),
    KEY idx_rag_document_chunks_status (index_status),
    CONSTRAINT fk_rag_document_chunks_document
        FOREIGN KEY (document_id) REFERENCES rag_documents (document_id)
        ON DELETE CASCADE,
    CONSTRAINT chk_rag_document_chunks_use_yn CHECK (use_yn IN ('Y', 'N'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
