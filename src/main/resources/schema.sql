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
