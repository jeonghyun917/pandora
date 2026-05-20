package com.kaces.pandora.law.sync;

import com.kaces.pandora.law.client.LawOpenApiService;
import com.kaces.pandora.law.common.LawJsonWriter;
import com.kaces.pandora.law.mapper.LawSyncHistoryMapper;
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
	private final LawSyncHistoryMapper syncHistoryMapper;
	private final LawJsonWriter jsonWriter;

	public LawOpenApiSyncService(
		LawOpenApiService lawOpenApiService,
		LawOpenApiPayloadParser payloadParser,
		LawDocumentWriter documentWriter,
		LawSyncHistoryMapper syncHistoryMapper,
		LawJsonWriter jsonWriter
	) {
		this.lawOpenApiService = lawOpenApiService;
		this.payloadParser = payloadParser;
		this.documentWriter = documentWriter;
		this.syncHistoryMapper = syncHistoryMapper;
		this.jsonWriter = jsonWriter;
	}

	@Transactional
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

	private void markSyncSuccess(long historyId, String responseJson) {
		syncHistoryMapper.markSyncSuccess(historyId, responseJson);
	}

	private void markSyncFailure(long historyId, Exception exception) {
		syncHistoryMapper.markSyncFailure(historyId, exception.getMessage());
	}

	private String toJson(Object value) {
		return jsonWriter.write(value);
	}

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
}
