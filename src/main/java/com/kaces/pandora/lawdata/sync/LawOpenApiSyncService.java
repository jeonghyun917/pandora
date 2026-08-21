package com.kaces.pandora.lawdata.sync;

import com.kaces.pandora.lawdata.client.LawOpenApiService;
import com.kaces.pandora.common.json.LawJsonWriter;
import static com.kaces.pandora.common.text.LawHashUtils.sha256;
import com.kaces.pandora.lawdata.chunk.LawChunkRebuildRow;
import com.kaces.pandora.lawdata.persistence.LawDetailMapper;
import com.kaces.pandora.lawdata.persistence.LawDocumentSyncState;
import com.kaces.pandora.lawdata.persistence.LawSyncHistoryMapper;
import com.kaces.pandora.lawdata.version.LawVersionUtils;
import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
public class LawOpenApiSyncService {
	private static final Duration DETAIL_REVALIDATE_AFTER = Duration.ofHours(12);
	private static final Clock VERSION_CLOCK = Clock.system(ZoneId.of("Asia/Seoul"));

	private final LawOpenApiService lawOpenApiService;
	private final LawOpenApiPayloadParser payloadParser;
	private final LawDocumentWriter documentWriter;
	private final LawDetailMapper lawDetailMapper;
	private final LawSyncHistoryMapper syncHistoryMapper;
	private final LawJsonWriter jsonWriter;
	private final LawChunkActivationSaga activationSaga;
	private final LawSemanticChunkPlanner chunkPlanner = new LawSemanticChunkPlanner();

	
	public LawOpenApiSyncService(
		LawOpenApiService lawOpenApiService,
		LawOpenApiPayloadParser payloadParser,
		LawDocumentWriter documentWriter,
		LawDetailMapper lawDetailMapper,
		LawSyncHistoryMapper syncHistoryMapper,
		LawJsonWriter jsonWriter,
		LawChunkActivationSaga activationSaga
	) {
		this.lawOpenApiService = lawOpenApiService;
		this.payloadParser = payloadParser;
		this.documentWriter = documentWriter;
		this.lawDetailMapper = lawDetailMapper;
		this.syncHistoryMapper = syncHistoryMapper;
		this.jsonWriter = jsonWriter;
		this.activationSaga = activationSaga;
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
			rebuiltChunks += documentWriter.replaceChunks(row.documentId(), row.detailId(), row.target(), row.title(), detail.sections(), sourceUrl(row));
			rebuiltDocuments++;
		}
		return new ChunkRebuildResult(safeTarget, safeOffset, rebuiltDocuments, rebuiltChunks);
	}

	@Transactional(readOnly = true)
	public ChunkRebuildPreviewResult previewRebuildChunks(String target, int limit, int offset) {
		String safeTarget = StringUtils.hasText(target) ? target.trim() : "law";
		if (!List.of("law", "admrul").contains(safeTarget)) {
			throw new IllegalArgumentException("Unsupported law data target: " + safeTarget);
		}
		int safeLimit = Math.min(Math.max(limit, 1), 200);
		int safeOffset = Math.max(offset, 0);
		List<LawChunkRebuildRow> rows = lawDetailMapper.findChunkRebuildRows(safeTarget, safeLimit, safeOffset);
		return previewRebuildRows(safeTarget, safeOffset, rows);
	}

	@Transactional(readOnly = true)
	public ChunkRebuildPreviewResult previewRebuildChunksByDocumentIds(String target, List<Long> documentIds) {
		String safeTarget = StringUtils.hasText(target) ? target.trim() : "law";
		if (!List.of("law", "admrul").contains(safeTarget)) {
			throw new IllegalArgumentException("Unsupported law data target: " + safeTarget);
		}
		List<Long> safeDocumentIds = documentIds == null ? List.of() : documentIds.stream()
			.filter(id -> id != null && id > 0)
			.distinct()
			.toList();
		if (safeDocumentIds.isEmpty()) {
			return new ChunkRebuildPreviewResult(safeTarget, 0, 0, 0, 0, "0.0%", 0, 0, 0, List.of());
		}
		List<LawChunkRebuildRow> rows = lawDetailMapper.findChunkRebuildRowsByDocumentIds(safeTarget, safeDocumentIds);
		return previewRebuildRows(safeTarget, 0, rows);
	}

	@Transactional
	public CandidateChunkVersionResult createCandidateChunks(String target, long documentId) {
		return createCandidateChunks(target, documentId, "");
	}

	@Transactional
	public CandidateChunkVersionResult createCandidateChunks(String target, long documentId, String previewApprovalToken) {
		String safeTarget = requireSupportedTarget(target);
		if (documentId <= 0) {
			throw new IllegalArgumentException("documentId is required.");
		}
		List<LawChunkRebuildRow> rows = lawDetailMapper.findChunkRebuildRowsByDocumentIds(safeTarget, List.of(documentId));
		if (rows.size() != 1) {
			throw new IllegalArgumentException("Preview-approved document was not found.");
		}
		LawChunkRebuildRow row = rows.get(0);
		ChunkRebuildPreviewItem preview = previewItem(row);
		if (preview.projectedChunks() == 0) {
			throw new IllegalStateException("Candidate creation blocked because preview has no searchable chunks.");
		}
		SyncDetailDocument detail = payloadParser.parseDetailDocument(
			row.rawJson(), row.detailTitle() == null ? row.title() : row.detailTitle());
		if (!preview.approvalToken().equals(previewApprovalToken)) {
			throw new IllegalArgumentException("Preview approval token is missing, stale, or does not match the current source.");
		}
		return documentWriter.createCandidateChunks(
			row.documentId(), row.detailId(), row.target(), row.title(), detail.sections(), sourceUrl(row),
			preview.approvalToken(), preview.unexplainedLossSpanCount());
	}

	public ChunkActivationResult activateCandidate(long documentId, int candidateVersion) {
		return activationSaga.activate(documentId, candidateVersion);
	}

	@Transactional
	public ChunkActivationResult rollbackToVersion(long documentId, int retiredVersion) {
		return documentWriter.rollbackToVersion(documentId, retiredVersion);
	}

	private String requireSupportedTarget(String target) {
		String safeTarget = StringUtils.hasText(target) ? target.trim() : "law";
		if (!List.of("law", "admrul").contains(safeTarget)) {
			throw new IllegalArgumentException("Unsupported law data target: " + safeTarget);
		}
		return safeTarget;
	}

	private ChunkRebuildPreviewResult previewRebuildRows(String safeTarget, int safeOffset, List<LawChunkRebuildRow> rows) {
		List<ChunkRebuildPreviewItem> items = rows.stream()
			.map(this::previewItem)
			.toList();
		int currentChunks = items.stream().mapToInt(ChunkRebuildPreviewItem::currentChunks).sum();
		int projectedChunks = items.stream().mapToInt(ChunkRebuildPreviewItem::projectedChunks).sum();
		int currentTinyChunks = items.stream().mapToInt(ChunkRebuildPreviewItem::currentTinyChunks).sum();
		int projectedTinyChunks = items.stream().mapToInt(ChunkRebuildPreviewItem::projectedTinyChunks).sum();
		int projectedShortChunks = items.stream().mapToInt(ChunkRebuildPreviewItem::projectedShortChunks).sum();
		String projectedReduction = currentChunks == 0 ? "0.0%" : String.format(java.util.Locale.ROOT, "%.1f%%", 100.0 - (projectedChunks * 100.0 / currentChunks));
		return new ChunkRebuildPreviewResult(
			safeTarget,
			safeOffset,
			items.size(),
			currentChunks,
			projectedChunks,
			projectedReduction,
			currentTinyChunks,
			projectedTinyChunks,
			projectedShortChunks,
			items
		);
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
			String canonicalKey = LawVersionUtils.canonicalKey(safeTarget, safeTitle);
			String effectiveDate = LawVersionUtils.normalizeEffectiveDate(safeSourceDate);
			String effectiveStatus = LawVersionUtils.initialStatus(safeTarget, effectiveDate, VERSION_CLOCK);
			SearchDocument document = new SearchDocument(
				safeTarget,
				safeExternalId,
				safeTitle,
				safeAgencyName,
				safeCategoryName,
				safeSourceDate,
				canonicalKey,
				effectiveDate,
				effectiveStatus,
				safeDetailLink,
				rawJson
			);
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
			rebuiltChunks += documentWriter.replaceChunks(row.documentId(), row.detailId(), row.target(), row.title(), detail.sections(), sourceUrl(row));
			rebuiltDocuments++;
		}
		return new ChunkRebuildResult(safeTarget, 0, rebuiltDocuments, rebuiltChunks);
	}

	private ChunkRebuildPreviewItem previewItem(LawChunkRebuildRow row) {
		SyncDetailDocument detail = payloadParser.parseDetailDocument(row.rawJson(), row.detailTitle() == null ? row.title() : row.detailTitle());
		List<PlannedLawChunk> planned = chunkPlanner.plan(
			new ChunkPlanningContext(row.target(), row.documentId(), row.title()),
			detail.sections()
		);
		int projectedTinyChunks = (int) planned.stream()
			.filter(chunk -> chunk.text() != null && chunk.text().length() < 80)
			.count();
		int projectedShortChunks = (int) planned.stream()
			.filter(chunk -> chunk.text() != null && chunk.text().length() < 800)
			.count();
		List<String> projectedTinySamples = planned.stream()
			.filter(chunk -> chunk.text() != null && chunk.text().length() < 80)
			.map(chunk -> String.format(
				java.util.Locale.ROOT,
				"[%s/%s/%d] %s",
				nullToDash(chunk.no()),
				nullToDash(chunk.title()),
				chunk.text().length(),
				previewText(chunk.text())
			))
			.limit(5)
			.toList();
		int maxProjectedLength = planned.stream()
			.map(PlannedLawChunk::text)
			.filter(StringUtils::hasText)
			.mapToInt(String::length)
			.max()
			.orElse(0);
		ChunkPreviewApproval approval = ChunkPreviewApproval.assess(
			row.target(), row.documentId(), row.detailId(), row.rawJson(), detail.sections(), planned);
		return new ChunkRebuildPreviewItem(
			row.documentId(),
			row.target(),
			row.title(),
			row.currentChunkCount(),
			planned.size(),
			row.currentTinyChunkCount(),
			projectedTinyChunks,
			projectedShortChunks,
			maxProjectedLength,
			sourceUrl(row),
			projectedTinySamples,
			approval.unexplainedLossSpanCount(),
			approval.token()
		);
	}

	private String nullToDash(String value) {
		return StringUtils.hasText(value) ? value.trim() : "-";
	}

	private String previewText(String value) {
		if (!StringUtils.hasText(value)) {
			return "";
		}
		String normalized = value.replaceAll("\\s+", " ").trim();
		return normalized.length() <= 120 ? normalized : normalized.substring(0, 117) + "...";
	}

	private String sourceUrl(LawChunkRebuildRow row) {
		return StringUtils.hasText(row.detailLink()) ? row.detailLink() : "db:" + row.documentId();
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
			
			chunkCount += documentWriter.replaceChunks(documentId, detailId, document.target(), document.title(), detail.sections(), document.detailLink());
			
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

	public record ChunkRebuildPreviewResult(
		String target,
		int offset,
		int documents,
		int currentChunks,
		int projectedChunks,
		String projectedReduction,
		int currentTinyChunks,
		int projectedTinyChunks,
		int projectedShortChunks,
		List<ChunkRebuildPreviewItem> items
	) {
	}

	public record ChunkRebuildPreviewItem(
		long documentId,
		String target,
		String title,
		int currentChunks,
		int projectedChunks,
		int currentTinyChunks,
		int projectedTinyChunks,
		int projectedShortChunks,
		int maxProjectedLength,
		String sourceUrl,
		List<String> projectedTinySamples,
		int unexplainedLossSpanCount,
		String approvalToken
	) {
	}
}
