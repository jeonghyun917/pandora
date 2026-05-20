package com.kaces.pandora.law.search;

import com.kaces.pandora.law.mapper.LawDocumentRow;
import java.util.List;

public record LawSearchQueryResult(
	LawSearchQuery query,
	int total,
	List<LawDocumentRow> rows
) {
}
