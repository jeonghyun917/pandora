package com.kaces.pandora.lawdata.sync;

import com.kaces.pandora.lawdata.client.LawOpenApiService;
import com.kaces.pandora.common.json.LawJsonWriter;
import com.kaces.pandora.lawdata.chunk.LawChunkRebuildRow;
import com.kaces.pandora.lawdata.persistence.LawDetailMapper;
import com.kaces.pandora.lawdata.persistence.LawSyncHistoryMapper;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class LawOpenApiSyncService {

	private final LawOpenApiService lawOpenApiService;
	private final LawOpenApiPayloadParser payloadParser;
	private final LawDocumentWriter documentWriter;
	private final LawDetailMapper lawDetailMapper;
	private final LawSyncHistoryMapper syncHistoryMapper;
	private final LawJsonWriter jsonWriter;

	
	public LawOpenApiSyncService(
		LawOpenApiService lawOpenApiService,
		LawOpenApiPayloadParser payloadParser,
		LawDocumentWriter documentWriter,
		LawDetailMapper lawDetailMapper,
		LawSyncHistoryMapper syncHistoryMapper,
		LawJsonWriter jsonWriter
	) {
		this.lawOpenApiService = lawOpenApiService;
		this.payloadParser = payloadParser;
		this.documentWriter = documentWriter;
		this.lawDetailMapper = lawDetailMapper;
		this.syncHistoryMapper = syncHistoryMapper;
		this.jsonWriter = jsonWriter;
	}

	
	@Transactional
	// 메소드 설명: syncLaws 처리 흐름을 수행합니다.
	public SyncResult syncLaws(String target, String query, int page, int display, boolean fetchDetails) {
		String safeTarget = StringUtils.hasText(target) ? target.trim() : "law";
		String safeQuery = StringUtils.hasText(query) ? query.trim() : "*";
		int safePage = Math.max(page, 1);
		int safeDisplay = Math.min(Math.max(display, 1), 100);
		
		long historyId = insertSyncHistory(safeTarget, safeQuery, safePage, safeDisplay, fetchDetails);

		try {
			
			String searchJson = lawOpenApiService.search(safeTarget, safeQuery, safePage, safeDisplay);
			
			List<SearchDocument> documents = payloadParser.parseSearchDocuments(safeTarget, searchJson);
			
			SyncCounters counters = syncDocuments(documents, fetchDetails);
			SyncResult result = new SyncResult(
				historyId,
				safeTarget,
				safeQuery,
				documents.size(),
				counters.details(),
				counters.chunks(),
				counters.assets()
			);
			
			markSyncSuccess(historyId, toJson(result));
			return result;
		} catch (Exception exception) {
			
			markSyncFailure(historyId, exception);
			throw new IllegalStateException("Law API sync failed: " + exception.getMessage(), exception);
		}
	}

	
	@Transactional
	// 메소드 설명: rebuildChunks 처리 흐름을 수행합니다.
	public ChunkRebuildResult rebuildChunks(String target, int limit, int offset) {
		String safeTarget = StringUtils.hasText(target) ? target.trim() : "law";
		if (!List.of("law", "admrul").contains(safeTarget)) {
			throw new IllegalArgumentException("Unsupported law data target: " + safeTarget);
		}
		int safeLimit = Math.min(Math.max(limit, 1), 1_000);
		int safeOffset = Math.max(offset, 0);
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		List<LawChunkRebuildRow> rows = lawDetailMapper.findChunkRebuildRows(safeTarget, safeLimit, safeOffset);
		int rebuiltDocuments = 0;
		int rebuiltChunks = 0;
		for (LawChunkRebuildRow row : rows) {
			SyncDetailDocument detail = payloadParser.parseDetailDocument(row.rawJson(), row.detailTitle() == null ? row.title() : row.detailTitle());
			rebuiltChunks += documentWriter.replaceChunks(row.documentId(), row.detailId(), detail.sections(), "db:" + row.documentId());
			rebuiltDocuments++;
		}
		return new ChunkRebuildResult(safeTarget, safeOffset, rebuiltDocuments, rebuiltChunks);
	}

	
	// 메소드 설명: syncDocuments 처리 흐름을 수행합니다.
	private SyncCounters syncDocuments(List<SearchDocument> documents, boolean fetchDetails) {
		int detailCount = 0;
		int chunkCount = 0;
		int assetCount = 0;
		for (SearchDocument document : documents) {
			
			long documentId = documentWriter.upsertDocument(document);
			if (!fetchDetails || !StringUtils.hasText(document.detailLink())) {
				continue;
			}

			
			String detailJson = lawOpenApiService.detail(document.detailLink());
			SyncDetailDocument detail = payloadParser.parseDetailDocument(detailJson, document.title());
			
			long detailId = documentWriter.upsertDetail(documentId, detail, detailJson);
			
			chunkCount += documentWriter.replaceChunks(documentId, detailId, detail.sections(), document.detailLink());
			
			assetCount += documentWriter.replaceAssets(documentId, detailId, detail.assets());
			detailCount++;
		}
		return new SyncCounters(detailCount, chunkCount, assetCount);
	}

	
	// 메소드 설명: insertSyncHistory 처리 흐름을 수행합니다.
	private long insertSyncHistory(String target, String query, int page, int display, boolean fetchDetails) {
		String requestJson = toJson(Map.of(
			"target", target,
			"query", query,
			"page", page,
			"display", display,
			"fetchDetails", fetchDetails
		));
		
		syncHistoryMapper.insertSyncHistory(target, requestJson);
		
		return syncHistoryMapper.lastInsertId();
	}

	
	// 메소드 설명: markSyncSuccess 처리 흐름을 수행합니다.
	private void markSyncSuccess(long historyId, String responseJson) {
		syncHistoryMapper.markSyncSuccess(historyId, responseJson);
	}

	
	// 메소드 설명: markSyncFailure 처리 흐름을 수행합니다.
	private void markSyncFailure(long historyId, Exception exception) {
		syncHistoryMapper.markSyncFailure(historyId, exception.getMessage());
	}

	
	// 메소드 설명: toJson 처리 흐름을 수행합니다.
	private String toJson(Object value) {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return jsonWriter.write(value);
	}

	// 메소드 설명: SyncCounters 처리 흐름을 수행합니다.
	private record SyncCounters(int details, int chunks, int assets) {
	}

	public record SyncResult(
		long syncHistoryId,
		String target,
		String query,
		int documents,
		int details,
		int chunks,
		int assets
	) {
	}

	public record ChunkRebuildResult(
		String target,
		int offset,
		int documents,
		int chunks
	) {
	}
}
