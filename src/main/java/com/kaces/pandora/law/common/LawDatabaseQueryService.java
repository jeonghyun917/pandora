package com.kaces.pandora.law.common;

import static com.kaces.pandora.law.common.LawJsonNodes.child;
import static com.kaces.pandora.law.common.LawJsonNodes.nodes;
import static com.kaces.pandora.law.common.LawJsonNodes.text;

import com.kaces.pandora.law.detail.LawDetailResponse;
import com.kaces.pandora.law.mapper.LawDetailMapper;
import com.kaces.pandora.law.mapper.LawDetailRow;
import com.kaces.pandora.law.mapper.LawDocumentMapper;
import com.kaces.pandora.law.mapper.LawDocumentRow;
import com.kaces.pandora.law.parser.StoredDetailSectionReader;
import com.kaces.pandora.law.search.LawSearchItemResponse;
import com.kaces.pandora.law.search.LawSearchPayloadResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class LawDatabaseQueryService {

	private final LawDocumentMapper lawDocumentMapper;
	private final LawDetailMapper lawDetailMapper;
	private final StoredDetailSectionReader sectionReader;
	private final ObjectMapper objectMapper;

	/**
	 * DB 조회 Mapper, 상세 섹션 reader, JSON 직렬화기를 주입받습니다.
	 */
	public LawDatabaseQueryService(
		LawDocumentMapper lawDocumentMapper,
		LawDetailMapper lawDetailMapper,
		StoredDetailSectionReader sectionReader,
		ObjectMapper objectMapper
	) {
		this.lawDocumentMapper = lawDocumentMapper;
		this.lawDetailMapper = lawDetailMapper;
		this.sectionReader = sectionReader;
		this.objectMapper = objectMapper;
	}

	/**
	 * DB에 저장된 문서를 국가법령 검색 응답 형태로 조회합니다.
	 */
	public String search(String target, String query, int page, int display) {
		String safeTarget = StringUtils.hasText(target) ? target.trim() : "law";
		String safeQuery = StringUtils.hasText(query) ? query.trim() : "*";
		int safePage = Math.max(page, 1);
		int safeDisplay = Math.min(Math.max(display, 1), 100);
		int offset = (safePage - 1) * safeDisplay;
		boolean searchAll = "*".equals(safeQuery);

		// MyBatis에서 검색 조건에 맞는 전체 건수를 먼저 구해 프론트 페이지 요약에 사용합니다.
		int total = lawDocumentMapper.countDocuments(safeTarget, safeQuery, searchAll);
		// 실제 목록 행은 페이지 크기와 offset을 적용해 필요한 만큼만 가져옵니다.
		List<LawSearchItemResponse> rows = lawDocumentMapper.searchDocuments(safeTarget, safeQuery, searchAll, safeDisplay, offset)
			.stream()
			// DB row를 국가법령 API 호환 응답 필드명으로 변환합니다.
			.map(this::toSearchItem)
			.toList();

		// 기존 프론트 normalizeList 로직이 그대로 읽을 수 있도록 LawSearch 루트 DTO를 만듭니다.
		LawSearchPayloadResponse lawSearch = new LawSearchPayloadResponse(
			"00",
			"DB",
			safeTarget,
			safeQuery,
			safePage,
			safeDisplay,
			total,
			rows
		);
		// 컨트롤러가 문자열 JSON을 그대로 내려주므로 DTO를 JSON 문자열로 직렬화합니다.
		return toJson(Map.of("LawSearch", lawSearch));
	}

	/**
	 * DB 상세 원문을 화면 상세 응답 DTO로 정규화합니다.
	 */
	public String detail(String link) {
		// 프론트에서 전달한 db:{id} 링크를 내부 document_id로 바꿉니다.
		long documentId = parseDocumentId(link);
		// 상세 테이블과 문서 테이블을 조인해 화면 구성에 필요한 원문을 조회합니다.
		LawDetailRow detail = lawDetailMapper.findDetail(documentId);
		// raw_json은 parser 전략으로 조문을 우선 추출하고, 실패하면 sections_json으로 fallback합니다.
		LawDetailResponse response = new LawDetailResponse(
			true,
			"DB",
			documentId,
			firstNonBlank(detail.detailTitle(), detail.title()),
			readDetailMeta(detail),
			sectionReader.readSections(detail.rawJson(), detail.sectionsJson())
		);
		// 상세 응답 DTO를 JSON 문자열로 변환해 기존 컨트롤러 계약을 유지합니다.
		return toJson(response);
	}

	/**
	 * 목록 DB row를 기존 프론트엔드가 이해하는 검색 항목 DTO로 변환합니다.
	 */
	private LawSearchItemResponse toSearchItem(LawDocumentRow row) {
		return new LawSearchItemResponse(
			row.documentId(),
			row.target(),
			row.externalId(),
			row.title(),
			row.agencyName(),
			row.categoryName(),
			row.sourceDate(),
			"db:" + row.documentId(),
			"DB"
		);
	}

	/**
	 * 법령 상세 원문에는 담당 부서가 있고, 그 외에는 문서 컬럼으로 메타를 구성합니다.
	 */
	private List<String> readDetailMeta(LawDetailRow detail) {
		List<String> meta = new ArrayList<>();
		if (StringUtils.hasText(detail.rawJson())) {
			try {
				// 법령 원문에는 시행/개정/담당부서가 기본정보 하위에 있으므로 먼저 이 경로를 시도합니다.
				JsonNode basic = child(child(objectMapper.readTree(detail.rawJson()), "법령"), "기본정보");
				if (basic != null) {
					// 제목 아래 중앙 메타 영역에 표시할 시행일과 개정 정보를 순서대로 추가합니다.
					addMeta(meta, "시행 " + formatDate(text(basic, "시행일자", "")));
					addMeta(meta, lawRevisionText(basic));
					// 담당부서 정보는 프론트에서 오른쪽 메타 영역으로 분리해 렌더링합니다.
					for (JsonNode department : nodes(child(child(basic, "연락부서"), "부서단위"))) {
						String agency = text(department, "소관부처명", "");
						String name = text(department, "부서명", "");
						String phone = text(department, "부서연락처", "");
						addMeta(meta, agency + (StringUtils.hasText(name) ? " (" + name + ")" : "") + (StringUtils.hasText(phone) ? " " + phone : ""));
					}
				}
			} catch (Exception ignored) {
				// 원문 메타 파싱 실패 시 문서 컬럼으로 fallback합니다.
			}
		}
		if (meta.isEmpty()) {
			// 행정규칙처럼 법령 기본정보가 없는 원문은 문서 컬럼 값으로 최소 메타를 구성합니다.
			addMeta(meta, detail.agencyName());
			addMeta(meta, formatDate(detail.sourceDate()));
		}
		return meta;
	}

	/**
	 * 법령 기본정보에서 공포번호, 공포일자, 제개정구분을 조합합니다.
	 */
	private String lawRevisionText(JsonNode basic) {
		String lawType = text(child(basic, "법종구분"), "content", "");
		String promulgationNo = text(basic, "공포번호", "");
		String promulgationDate = formatDate(text(basic, "공포일자", ""));
		String revisionType = text(basic, "제개정구분", "");
		List<String> parts = new ArrayList<>();
		// 비어 있는 조각은 addMeta에서 걸러서 불완전한 쉼표 조합을 막습니다.
		addMeta(parts, lawType + (StringUtils.hasText(promulgationNo) ? " 제" + promulgationNo + "호" : ""));
		addMeta(parts, promulgationDate);
		addMeta(parts, revisionType);
		return String.join(", ", parts);
	}

	/**
	 * 메타 값이 비어 있지 않을 때만 목록에 추가합니다.
	 */
	private void addMeta(List<String> meta, String value) {
		if (StringUtils.hasText(value)) {
			meta.add(value.trim());
		}
	}

	/**
	 * db:{documentId} 형태의 상세 링크에서 내부 ID를 추출합니다.
	 */
	private long parseDocumentId(String link) {
		if (!StringUtils.hasText(link) || !link.startsWith("db:")) {
			throw new IllegalArgumentException("DB detail link is required.");
		}
		try {
			return Long.parseLong(link.substring(3));
		} catch (NumberFormatException exception) {
			throw new IllegalArgumentException("Invalid DB detail link.", exception);
		}
	}

	/**
	 * yyyyMMdd 또는 숫자형 날짜를 화면용 날짜로 변환합니다.
	 */
	private String formatDate(String value) {
		if (value == null) {
			return "";
		}
		String digits = value.replaceAll("\\D", "");
		if (digits.length() != 8) {
			return StringUtils.hasText(value) ? value : "";
		}
		return Integer.parseInt(digits.substring(0, 4)) + ". "
			+ Integer.parseInt(digits.substring(4, 6)) + ". "
			+ Integer.parseInt(digits.substring(6, 8)) + ".";
	}

	/**
	 * 왼쪽 값이 비어 있으면 오른쪽 값을 사용합니다.
	 */
	private String firstNonBlank(String left, String right) {
		return StringUtils.hasText(left) ? left : right;
	}

	/**
	 * 응답 DTO를 JSON 문자열로 직렬화합니다.
	 */
	private String toJson(Object value) {
		try {
			// Jackson ObjectMapper로 record DTO와 Map 루트를 모두 JSON 문자열로 변환합니다.
			return objectMapper.writeValueAsString(value);
		} catch (Exception exception) {
			throw new IllegalStateException("JSON serialization failed.", exception);
		}
	}
}
