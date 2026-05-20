package com.kaces.pandora.law.search;

import com.kaces.pandora.law.mapper.LawDocumentMapper;
import com.kaces.pandora.law.mapper.LawDocumentRow;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class LawSearchQueryService {

	private final LawDocumentMapper lawDocumentMapper;

	public LawSearchQueryService(LawDocumentMapper lawDocumentMapper) {
		this.lawDocumentMapper = lawDocumentMapper;
	}

	public LawSearchQueryResult search(LawSearchQuery query) {
		int total = lawDocumentMapper.countDocuments(query.target(), query.query(), query.searchAll());
		List<LawDocumentRow> rows = lawDocumentMapper.searchDocuments(
			query.target(),
			query.query(),
			query.searchAll(),
			query.display(),
			query.offset()
		);
		return new LawSearchQueryResult(query, total, rows);
	}
}
