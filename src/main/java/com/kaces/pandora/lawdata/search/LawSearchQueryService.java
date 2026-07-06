package com.kaces.pandora.lawdata.search;

import com.kaces.pandora.lawdata.persistence.LawDocumentMapper;
import com.kaces.pandora.lawdata.persistence.LawDocumentRow;
import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import com.kaces.pandora.lawdata.chunk.LawChunkSearchRow;
import com.kaces.pandora.rag.persistence.RagDocumentMapper;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class LawSearchQueryService {

	private final LawDocumentMapper lawDocumentMapper;
	private final LawChunkMapper lawChunkMapper;
	private final RagDocumentMapper ragDocumentMapper;

	
	public LawSearchQueryService(
		LawDocumentMapper lawDocumentMapper,
		LawChunkMapper lawChunkMapper,
		RagDocumentMapper ragDocumentMapper
	) {
		this.lawDocumentMapper = lawDocumentMapper;
		this.lawChunkMapper = lawChunkMapper;
		this.ragDocumentMapper = ragDocumentMapper;
	}

	
	// 메소드 설명: search 처리 흐름을 수행합니다.
	public LawSearchQueryResult search(LawSearchQuery query) {
		if (isRagTarget(query.target())) {
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			int total = ragDocumentMapper.countDocuments(query.target(), query.query(), query.searchAll(), query.titleOnly());
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			List<LawDocumentRow> rows = ragDocumentMapper.searchDocuments(
				query.target(),
				query.query(),
				query.searchAll(),
				query.titleOnly(),
				query.display(),
				query.offset()
			);
			return new LawSearchQueryResult(query, total, rows);
		}

		
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		int total = lawDocumentMapper.countDocuments(query.target(), query.query(), query.searchAll(), query.titleOnly(), query.includeFuture());
		
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		List<LawDocumentRow> rows = lawDocumentMapper.searchDocuments(
			query.target(),
			query.query(),
			query.searchAll(),
			query.titleOnly(),
			query.includeFuture(),
			query.display(),
			query.offset()
		);
		
		return new LawSearchQueryResult(query, total, rows);
	}

	
	// 메소드 설명: searchChunks 처리 흐름을 수행합니다.
	public LawChunkSearchQueryResult searchChunks(LawSearchQuery query) {
		if (query.searchAll()) {
			return new LawChunkSearchQueryResult(query, 0, List.of());
		}
		if (isRagTarget(query.target())) {
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			int total = ragDocumentMapper.countChunkSearch(query.target(), query.query());
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			List<LawChunkSearchRow> rows = ragDocumentMapper.searchChunks(
				query.target(),
				query.query(),
				query.display(),
				query.offset()
			);
			return new LawChunkSearchQueryResult(query, total, rows);
		}
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		int total = lawChunkMapper.countChunkSearch(query.target(), query.query(), query.includeFuture());
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		List<LawChunkSearchRow> rows = lawChunkMapper.searchChunks(
			query.target(),
			query.query(),
			query.includeFuture(),
			query.display(),
			query.offset()
		);
		return new LawChunkSearchQueryResult(query, total, rows);
	}

	// 메소드 설명: isRagTarget 처리 흐름을 수행합니다.
	private boolean isRagTarget(String target) {
		return "official_doc".equals(target)
			|| "internal_doc".equals(target)
			|| "reference_doc".equals(target);
	}
}
