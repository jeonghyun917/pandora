package com.kaces.pandora.law.search;

import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class LawSearchService {

	private final LawSearchQueryService queryService;
	private final LawSearchResponseAssembler responseAssembler;

	public LawSearchService(LawSearchQueryService queryService, LawSearchResponseAssembler responseAssembler) {
		this.queryService = queryService;
		this.responseAssembler = responseAssembler;
	}

	public Map<String, LawSearchPayloadResponse> search(String target, String query, int page, int display) {
		LawSearchQuery normalizedQuery = LawSearchQuery.normalize(target, query, page, display);
		return responseAssembler.assemble(queryService.search(normalizedQuery));
	}
}
