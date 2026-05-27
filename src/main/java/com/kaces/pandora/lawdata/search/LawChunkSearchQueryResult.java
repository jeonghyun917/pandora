package com.kaces.pandora.lawdata.search;

import com.kaces.pandora.lawdata.chunk.LawChunkSearchRow;
import java.util.List;

public record LawChunkSearchQueryResult(
	LawSearchQuery query,
	int total,
	List<LawChunkSearchRow> rows
) {
}
