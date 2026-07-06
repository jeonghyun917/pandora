package com.kaces.pandora.lawdata.search;

import java.util.Map;
import org.springframework.stereotype.Service;

@Service
public class LawSearchService {

	private final LawSearchQueryService queryService;
	private final LawSearchResponseAssembler responseAssembler;

	
	// 메소드 설명: LawSearchService 처리 흐름을 수행합니다.
	public LawSearchService(LawSearchQueryService queryService, LawSearchResponseAssembler responseAssembler) {
		this.queryService = queryService;
		this.responseAssembler = responseAssembler;
	}

	
	// 메소드 설명: search 처리 흐름을 수행합니다.
	public Map<String, LawSearchPayloadResponse> search(String target, String query, int page, int display) {
		return search(target, query, page, display, false, true);
	}

	public Map<String, LawSearchPayloadResponse> search(String target, String query, int page, int display, boolean titleOnly) {
		return search(target, query, page, display, titleOnly, true);
	}

	public Map<String, LawSearchPayloadResponse> search(String target, String query, int page, int display, boolean titleOnly, boolean includeFuture) {
		
		LawSearchQuery normalizedQuery = LawSearchQuery.normalize(target, query, page, display, titleOnly, includeFuture);
		
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return responseAssembler.assemble(queryService.search(normalizedQuery));
	}

	
	// 메소드 설명: chunkSearch 처리 흐름을 수행합니다.
	public Map<String, LawSearchPayloadResponse> chunkSearch(String target, String query, int page, int display) {
		return chunkSearch(target, query, page, display, true);
	}

	public Map<String, LawSearchPayloadResponse> chunkSearch(String target, String query, int page, int display, boolean includeFuture) {
		LawSearchQuery normalizedQuery = LawSearchQuery.normalize(target, query, page, display, false, includeFuture);
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return responseAssembler.assembleChunkSearch(queryService.searchChunks(normalizedQuery));
	}
}
