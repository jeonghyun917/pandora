package com.kaces.pandora.law.search;

import com.kaces.pandora.law.mapper.LawDocumentRow;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public class LawSearchResponseAssembler {

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
}
