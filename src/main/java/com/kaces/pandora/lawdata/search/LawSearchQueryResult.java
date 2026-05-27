package com.kaces.pandora.lawdata.search;

import com.kaces.pandora.lawdata.persistence.LawDocumentRow;
import java.util.List;


public record LawSearchQueryResult(
	LawSearchQuery query,
	int total,
	List<LawDocumentRow> rows
) {
}
