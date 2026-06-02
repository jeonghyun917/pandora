package com.kaces.pandora.lawdata.sync;

import com.kaces.pandora.lawdata.client.LawOpenApiService;
import com.kaces.pandora.common.json.LawJsonWriter;
import static com.kaces.pandora.common.text.LawHashUtils.sha256;
import com.kaces.pandora.lawdata.chunk.LawChunkRebuildRow;
import com.kaces.pandora.lawdata.persistence.LawDetailMapper;
import com.kaces.pandora.lawdata.persistence.LawDocumentSyncState;
import com.kaces.pandora.lawdata.persistence.LawSyncHistoryMapper;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class LawOpenApiSyncService {
	private static final Duration DETAIL_REVALIDATE_AFTER = Duration.ofHours(12);

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
		return syncLaws(target, query, page, display, fetchDetails, "");
	}

	@Transactional
	public SyncResult syncLaws(String target, String query, int page, int display, boolean fetchDetails, String sort) {
		return syncLaws(target, query, page, display, fetchDetails, sort, "", "", "");
	}

	@Transactional
	public SyncResult syncLaws(String target, String query, int page, int display, boolean fetchDetails, String sort, String date, String efYd, String ancYd) {
		String safeTarget = StringUtils.hasText(target) ? target.trim() : "law";
		String safeQuery = StringUtils.hasText(query) ? query.trim() : "*";
		String safeSort = StringUtils.hasText(sort) ? sort.trim() : "";
		String safeDate = StringUtils.hasText(date) ? date.trim() : "";
		String safeEfYd = StringUtils.hasText(efYd) ? efYd.trim() : "";
		String safeAncYd = StringUtils.hasText(ancYd) ? ancYd.trim() : "";
		int safePage = Math.max(page, 1);
		int safeDisplay = Math.min(Math.max(display, 1), 100);
		
		long historyId = insertSyncHistory(safeTarget, safeQuery, safePage, safeDisplay, fetchDetails, safeSort, safeDate, safeEfYd, safeAncYd);

		try {
			
			String searchJson = lawOpenApiService.search(safeTarget, safeQuery, safePage, safeDisplay, safeSort, safeDate, safeEfYd, safeAncYd);
			
			List<SearchDocument> documents = payloadParser.parseSearchDocuments(safeTarget, searchJson);
			
			SyncCounters counters = syncDocuments(documents, fetchDetails);
			SyncResult result = new SyncResult(
				historyId,
				safeTarget,
				safeQuery,
				safeSort,
				safeDate,
				safeEfYd,
				safeAncYd,
				documents.size(),
				counters.details(),
				counters.skippedDetails(),
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

	@Transactional
	public SyncResult syncDetail(String target, String externalId, String title, String sourceDate, String agencyName, String categoryName, String detailLink) {
		String safeTarget = StringUtils.hasText(target) ? target.trim() : "law";
		String safeExternalId = StringUtils.hasText(externalId) ? externalId.trim() : "";
		if (!StringUtils.hasText(safeExternalId)) {
			throw new IllegalArgumentException("externalId is required.");
		}
		String safeTitle = StringUtils.hasText(title) ? title.trim() : safeTarget + "-" + safeExternalId;
		String safeSourceDate = StringUtils.hasText(sourceDate) ? sourceDate.trim() : "";
		String safeAgencyName = StringUtils.hasText(agencyName) ? agencyName.trim() : "";
		String safeCategoryName = StringUtils.hasText(categoryName) ? categoryName.trim() : "";
		String safeDetailLink = StringUtils.hasText(detailLink) ? detailLink.trim() : "/DRF/lawService.do?OC=***&target=" + safeTarget + "&MST=" + safeExternalId + "&type=HTML&mobileYn=";
		long historyId = insertSyncDetailHistory(safeTarget, safeExternalId, safeTitle, safeSourceDate, safeAgencyName, safeCategoryName, safeDetailLink);
		try {
			String rawJson = toJson(Map.of(
				"target", safeTarget,
				"externalId", safeExternalId,
				"title", safeTitle,
				"agencyName", safeAgencyName,
				"categoryName", safeCategoryName,
				"sourceDate", safeSourceDate,
				"detailLink", safeDetailLink
			));
			SearchDocument document = new SearchDocument(safeTarget, safeExternalId, safeTitle, safeAgencyName, safeCategoryName, safeSourceDate, safeDetailLink, rawJson);
			SyncCounters counters = syncDocuments(List.of(document), true);
			SyncResult result = new SyncResult(historyId, safeTarget, safeTitle, "", "", "", "", 1, counters.details(), counters.skippedDetails(), counters.chunks(), counters.assets());
			markSyncSuccess(historyId, toJson(result));
			return result;
		} catch (Exception exception) {
			markSyncFailure(historyId, exception);
			throw new IllegalStateException("Law API detail sync failed: " + exception.getMessage(), exception);
		}
	}

	@Transactional
	public ChunkRebuildResult rebuildChunksByDocumentIds(String target, List<Long> documentIds) {
		String safeTarget = StringUtils.hasText(target) ? target.trim() : "law";
		if (!List.of("law", "admrul").contains(safeTarget)) {
			throw new IllegalArgumentException("Unsupported law data target: " + safeTarget);
		}
		List<Long> safeDocumentIds = documentIds == null ? List.of() : documentIds.stream()
			.filter(id -> id != null && id > 0)
			.distinct()
			.toList();
		if (safeDocumentIds.isEmpty()) {
			return new ChunkRebuildResult(safeTarget, 0, 0, 0);
		}
		List<LawChunkRebuildRow> rows = lawDetailMapper.findChunkRebuildRowsByDocumentIds(safeTarget, safeDocumentIds);
		int rebuiltDocuments = 0;
		int rebuiltChunks = 0;
		for (LawChunkRebuildRow row : rows) {
			SyncDetailDocument detail = payloadParser.parseDetailDocument(row.rawJson(), row.detailTitle() == null ? row.title() : row.detailTitle());
			rebuiltChunks += documentWriter.replaceChunks(row.documentId(), row.detailId(), detail.sections(), "db:" + row.documentId());
			rebuiltDocuments++;
		}
		return new ChunkRebuildResult(safeTarget, 0, rebuiltDocuments, rebuiltChunks);
	}

	
	// 메소드 설명: syncDocuments 처리 흐름을 수행합니다.
	private SyncCounters syncDocuments(List<SearchDocument> documents, boolean fetchDetails) {
		int detailCount = 0;
		int skippedDetailCount = 0;
		int chunkCount = 0;
		int assetCount = 0;
		for (SearchDocument document : documents) {
			String documentHash = sha256(document.rawJson());
			LawDocumentSyncState state = documentWriter.findSyncState(document.target(), document.externalId());
			
			long documentId = documentWriter.upsertDocument(document);
			if (!fetchDetails || !StringUtils.hasText(document.detailLink())) {
				continue;
			}

			if (hasCurrentChunksForDocument(state, documentHash)) {
				skippedDetailCount++;
				continue;
			}
			
			String detailJson = lawOpenApiService.detail(document.detailLink());
			if (hasCurrentChunksForDetail(state, sha256(detailJson))) {
				skippedDetailCount++;
				continue;
			}
			SyncDetailDocument detail = payloadParser.parseDetailDocument(detailJson, document.title());
			
			long detailId = documentWriter.upsertDetail(documentId, detail, detailJson);
			
			chunkCount += documentWriter.replaceChunks(documentId, detailId, detail.sections(), document.detailLink());
			
			assetCount += documentWriter.replaceAssets(documentId, detailId, detail.assets());
			detailCount++;
		}
		return new SyncCounters(detailCount, skippedDetailCount, chunkCount, assetCount);
	}

	private boolean hasCurrentChunksForDocument(LawDocumentSyncState state, String documentHash) {
		return state != null
			&& state.activeChunkCount() > 0
			&& StringUtils.hasText(state.documentContentHash())
			&& StringUtils.hasText(state.detailContentHash())
			&& state.documentContentHash().equals(documentHash)
			&& detailRecentlyValidated(state);
	}

	private boolean hasCurrentChunksForDetail(LawDocumentSyncState state, String detailHash) {
		return state != null
			&& state.activeChunkCount() > 0
			&& StringUtils.hasText(state.detailContentHash())
			&& state.detailContentHash().equals(detailHash);
	}

	private boolean detailRecentlyValidated(LawDocumentSyncState state) {
		if (state.detailFetchedAt() == null) {
			return false;
		}
		return state.detailFetchedAt().isAfter(LocalDateTime.now().minus(DETAIL_REVALIDATE_AFTER));
	}

	
	// 메소드 설명: insertSyncHistory 처리 흐름을 수행합니다.
	private long insertSyncHistory(String target, String query, int page, int display, boolean fetchDetails, String sort, String date, String efYd, String ancYd) {
		String requestJson = toJson(Map.of(
			"target", target,
			"query", query,
			"sort", sort,
			"date", date,
			"efYd", efYd,
			"ancYd", ancYd,
			"page", page,
			"display", display,
			"fetchDetails", fetchDetails
		));
		
		syncHistoryMapper.insertSyncHistory(target, requestJson);
		
		return syncHistoryMapper.lastInsertId();
	}

	private long insertSyncDetailHistory(String target, String externalId, String title, String sourceDate, String agencyName, String categoryName, String detailLink) {
		String requestJson = toJson(Map.of(
			"target", target,
			"externalId", externalId,
			"title", title,
			"sourceDate", sourceDate,
			"agencyName", agencyName,
			"categoryName", categoryName,
			"detailLink", detailLink
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
	private record SyncCounters(int details, int skippedDetails, int chunks, int assets) {
	}

	public record SyncResult(
		long syncHistoryId,
		String target,
		String query,
		String sort,
		String date,
		String efYd,
		String ancYd,
		int documents,
		int details,
		int skippedDetails,
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
