package com.kaces.pandora.lawdata.search;

import static com.kaces.pandora.common.text.LawTextUtils.stripHtmlTags;

import com.kaces.pandora.lawdata.persistence.LawDocumentRow;
import com.kaces.pandora.lawdata.chunk.LawChunkSearchRow;
import com.kaces.pandora.rag.common.HwpxTextCleaner;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class LawSearchResponseAssembler {

	
	// 메소드 설명: assemble 처리 흐름을 수행합니다.
	public Map<String, LawSearchPayloadResponse> assemble(LawSearchQueryResult result) {
		LawSearchQuery query = result.query();
		
		LawSearchPayloadResponse lawSearch = new LawSearchPayloadResponse(
			"00",
			"DB",
			query.target(),
			query.query(),
			query.page(),
			query.display(),
			result.total(),
			result.rows().stream().map(this::toSearchItem).toList()
		);
		return Map.of("LawSearch", lawSearch);
	}

	
	// 메소드 설명: assembleChunkSearch 처리 흐름을 수행합니다.
	public Map<String, LawSearchPayloadResponse> assembleChunkSearch(LawChunkSearchQueryResult result) {
		LawSearchQuery query = result.query();
		LawSearchPayloadResponse lawSearch = new LawSearchPayloadResponse(
			"00",
			"DB_CHUNK",
			query.target(),
			query.query(),
			query.page(),
			query.display(),
			result.total(),
			result.rows().stream().map(row -> toChunkSearchItem(row, query.query())).toList()
		);
		return Map.of("LawSearch", lawSearch);
	}

	
	// 메소드 설명: toSearchItem 처리 흐름을 수행합니다.
	private LawSearchItemResponse toSearchItem(LawDocumentRow row) {
		return new LawSearchItemResponse(
			row.documentId(),
			row.target(),
			row.externalId(),
			stripHtmlTags(row.title()),
			row.agencyName(),
			row.categoryName(),
			row.sourceDate(),
			row.effectiveStatus(),
			detailLink(row),
			"DB"
		);
	}

	
	// 메소드 설명: toChunkSearchItem 처리 흐름을 수행합니다.
	private LawSearchItemResponse toChunkSearchItem(LawChunkSearchRow row, String query) {
		return new LawSearchItemResponse(
			row.documentId(),
			row.target(),
			row.externalId(),
			row.title(),
			row.agencyName(),
			row.categoryName(),
			row.sourceDate(),
			row.effectiveStatus(),
			chunkDetailLink(row),
			"DB_CHUNK",
			row.chunkId(),
			row.chunkNo(),
			stripHtmlTags(row.chunkTitle()),
			snippet(row.chunkText(), query),
			row.sourcePath()
		);
	}

	
	// 메소드 설명: snippet 처리 흐름을 수행합니다.
	private String snippet(String text, String query) {
		text = stripHtmlTags(HwpxTextCleaner.clean(text));
		if (query == null || query.isBlank()) {
			return text.length() <= 220 ? text : text.substring(0, 220) + "...";
		}
		int index = text.indexOf(query);
		if (index < 0) {
			return text.length() <= 220 ? text : text.substring(0, 220) + "...";
		}
		int start = Math.max(0, index - 80);
		int end = Math.min(text.length(), index + query.length() + 140);
		return (start > 0 ? "..." : "") + text.substring(start, end) + (end < text.length() ? "..." : "");
	}

	// 메소드 설명: hasText 처리 흐름을 수행합니다.
	private boolean hasText(String value) {
		return value != null && !value.isBlank();
	}

	// 메소드 설명: detailLink 처리 흐름을 수행합니다.
	private String detailLink(LawDocumentRow row) {
		if (isRagTarget(row.target()) && hasText(row.detailLink())) {
			return row.detailLink();
		}
		return "db:" + row.documentId();
	}

	// 메소드 설명: isRagTarget 처리 흐름을 수행합니다.
	private boolean isRagTarget(String target) {
		return "official_doc".equals(target)
			|| "internal_doc".equals(target)
			|| "reference_doc".equals(target);
	}

	// 메소드 설명: chunkDetailLink 처리 흐름을 수행합니다.
	private String chunkDetailLink(LawChunkSearchRow row) {
		return (isRagTarget(row.target()) ? "rag:" : "db:") + row.documentId();
	}
}
