package com.kaces.pandora.lawdata.sync;


import static com.kaces.pandora.common.text.LawHashUtils.sha256;
import static com.kaces.pandora.common.text.LawTextUtils.emptyToNull;

import com.kaces.pandora.common.json.LawJsonWriter;
import com.kaces.pandora.infra.qdrant.QdrantClient;
import com.kaces.pandora.lawdata.persistence.LawAssetMapper;
import com.kaces.pandora.lawdata.persistence.LawChunkMapper;
import com.kaces.pandora.lawdata.persistence.LawDetailMapper;
import com.kaces.pandora.lawdata.persistence.LawDocumentMapper;
import com.kaces.pandora.lawdata.persistence.LawDocumentSyncState;
import com.kaces.pandora.lawdata.chunk.LawChunkVersionRow;
import com.kaces.pandora.lawdata.chunk.LawChunkVersionVerification;
import com.kaces.pandora.lawdata.version.LawVersionStatusService;
import com.kaces.pandora.semantic.config.LawAiProperties;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

@Component
public class LawDocumentWriter {

	private static final int MAX_STORED_DETAIL_JSON_CHARS = 4_000_000;

	private final LawDocumentMapper lawDocumentMapper;
	private final LawDetailMapper lawDetailMapper;
	private final LawChunkMapper lawChunkMapper;
	private final LawAssetMapper lawAssetMapper;
	private final LawJsonWriter jsonWriter;
	private final QdrantClient qdrantClient;
	private final LawVersionStatusService lawVersionStatusService;
	private final LawAiProperties properties;
	private final LawSemanticChunkPlanner chunkPlanner = new LawSemanticChunkPlanner();

	
	public LawDocumentWriter(
		LawDocumentMapper lawDocumentMapper,
		LawDetailMapper lawDetailMapper,
		LawChunkMapper lawChunkMapper,
		LawAssetMapper lawAssetMapper,
		LawJsonWriter jsonWriter,
		QdrantClient qdrantClient,
		LawVersionStatusService lawVersionStatusService,
		LawAiProperties properties
	) {
		this.lawDocumentMapper = lawDocumentMapper;
		this.lawDetailMapper = lawDetailMapper;
		this.lawChunkMapper = lawChunkMapper;
		this.lawAssetMapper = lawAssetMapper;
		this.jsonWriter = jsonWriter;
		this.qdrantClient = qdrantClient;
		this.lawVersionStatusService = lawVersionStatusService;
		this.properties = properties;
	}

	
	// 메소드 설명: upsertDocument 처리 흐름을 수행합니다.
	public long upsertDocument(SearchDocument document) {
		
		SearchDocument normalized = new SearchDocument(
			document.target(),
			document.externalId(),
			document.title(),
			emptyToNull(document.agencyName()),
			emptyToNull(document.categoryName()),
			emptyToNull(document.sourceDate()),
			emptyToNull(document.canonicalKey()),
			emptyToNull(document.effectiveDate()),
			emptyToNull(document.effectiveStatus()),
			emptyToNull(document.detailLink()),
			document.rawJson()
		);
		
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		lawDocumentMapper.upsertDocument(normalized, sha256(document.rawJson()));
		
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		long documentId = lawDocumentMapper.findDocumentId(document.target(), document.externalId());
		lawVersionStatusService.refreshGroup(normalized.target(), normalized.canonicalKey());
		return documentId;
	}

	public LawDocumentSyncState findSyncState(String target, String externalId) {
		return lawDocumentMapper.findSyncState(target, externalId);
	}

	
	// 메소드 설명: upsertDetail 처리 흐름을 수행합니다.
	public long upsertDetail(long documentId, SyncDetailDocument detail, String rawJson) {
		
		String sectionsJson = toJson(detail.sections());
		String metaJson = toJson(Map.of(
			"sectionCount", detail.sections().size(),
			"assetCount", detail.assets().size(),
			"rawJsonCharLength", rawJson.length(),
			"rawJsonContentHash", sha256(rawJson),
			"sectionsJsonCharLength", sectionsJson.length(),
			"sectionsJsonContentHash", sha256(sectionsJson)
		));

		
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		lawDetailMapper.upsertDetail(new StoredDetail(
			documentId,
			emptyToNull(detail.title()),
			metaJson,
			compactLargeJson(sectionsJson, "sections_json"),
			compactLargeJson(rawJson, "raw_json"),
			sha256(rawJson)
		));
		
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return lawDetailMapper.findDetailId(documentId);
	}

	
	// 메소드 설명: replaceChunks 처리 흐름을 수행합니다.
	public int replaceChunks(
		long documentId,
		long detailId,
		String documentTarget,
		String documentTitle,
		List<SyncDetailSection> sections,
		String sourceUrl
	) {
		
		if (lawChunkMapper.findActiveChunkVersion(documentId) > 0) {
			return createCandidateChunks(documentId, detailId, documentTarget, documentTitle, sections, sourceUrl).expectedChunkCount();
		}
		
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		List<PlannedLawChunk> plannedChunks = chunkPlanner.plan(
			new ChunkPlanningContext(documentTarget, documentId, documentTitle),
			sections
		);
		lawChunkMapper.upsertChunkVersion(new LawChunkVersionRow(documentId, 1, "ACTIVE", plannedChunks.size(), true, 0));
		int count = 0;
		for (PlannedLawChunk chunk : plannedChunks) {
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			lawChunkMapper.insertChunk(new StoredChunk(
				documentId,
				detailId,
				chunk.type(),
				emptyToNull(chunk.no()),
				emptyToNull(chunk.title()),
				chunk.text(),
				emptyToNull(chunk.sourcePath()),
				emptyToNull(sourceUrl),
				count,
				sha256(chunk.embeddingText()),
				chunk.chunkSchemaVersion(),
				1,
				"ACTIVE",
				chunk.parentKey(),
				chunk.parentTitle(),
				chunk.parentSourcePath(),
				chunk.childOrder(),
				chunk.embeddingText(),
				chunk.qualityStatus(),
				chunk.qualityReason()
			));
			count++;
		}
		return count;
	}

	@Transactional
	public CandidateChunkVersionResult createCandidateChunks(
		long documentId,
		long detailId,
		String documentTarget,
		String documentTitle,
		List<SyncDetailSection> sections,
		String sourceUrl
	) {
		return createCandidateChunks(documentId, detailId, documentTarget, documentTitle, sections, sourceUrl, false, Integer.MAX_VALUE);
	}

	@Transactional
	public CandidateChunkVersionResult createCandidateChunks(
		long documentId,
		long detailId,
		List<SyncDetailSection> sections,
		String sourceUrl
	) {
		return createCandidateChunks(documentId, detailId, "law", "", sections, sourceUrl, false, Integer.MAX_VALUE);
	}

	@Transactional
	public CandidateChunkVersionResult createCandidateChunks(
		long documentId,
		long detailId,
		String documentTarget,
		String documentTitle,
		List<SyncDetailSection> sections,
		String sourceUrl,
		boolean previewApproved,
		int unexplainedLossSpanCount
	) {
		int candidateVersion = Math.max(2, lawChunkMapper.findNextChunkVersion(documentId));
		List<PlannedLawChunk> plannedChunks = chunkPlanner.plan(
			new ChunkPlanningContext(documentTarget, documentId, documentTitle), sections);
		if (plannedChunks.isEmpty()) {
			throw new IllegalArgumentException("Candidate chunk version must contain at least one chunk.");
		}
		lawChunkMapper.upsertChunkVersion(new LawChunkVersionRow(
			documentId, candidateVersion, "CANDIDATE", plannedChunks.size(), previewApproved, Math.max(0, unexplainedLossSpanCount)));
		for (int sortOrder = 0; sortOrder < plannedChunks.size(); sortOrder++) {
			PlannedLawChunk chunk = plannedChunks.get(sortOrder);
			lawChunkMapper.insertChunk(storedChunk(
				documentId, detailId, chunk, sourceUrl, sortOrder, candidateVersion, "CANDIDATE"));
		}
		return new CandidateChunkVersionResult(
			documentId, candidateVersion, "CANDIDATE", plannedChunks.size(), previewApproved, Math.max(0, unexplainedLossSpanCount),
			lawChunkMapper.findChunkIdsByDocumentIdAndVersion(documentId, candidateVersion));
	}

	@Transactional
	public ChunkActivationResult activateCandidate(long documentId, int candidateVersion) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			return ChunkActivationResult.blocked(documentId, candidateVersion, "Candidate activation requires an active transaction.");
		}
		LawChunkVersionVerification verification = lawChunkMapper.findChunkVersionVerification(
			documentId, candidateVersion, properties.openai().embeddingModel(), properties.qdrant().collection());
		if (verification == null || !verification.databaseGatesPass()) {
			return ChunkActivationResult.blocked(documentId, candidateVersion, "Candidate database verification failed.");
		}
		if (!candidatePointsArePresent(documentId, candidateVersion)
			|| qdrantClient.indexSnapshot(qdrantClient.lawCandidateCollection())
				.filter(snapshot -> snapshot.isStable() && snapshot.vectorSize() == properties.qdrant().vectorSize())
				.isEmpty()) {
			return ChunkActivationResult.blocked(documentId, candidateVersion, "Candidate Qdrant verification failed.");
		}
		List<Long> candidateChunkIds = lawChunkMapper.findChunkIdsByDocumentIdAndVersion(documentId, candidateVersion);
		List<Long> retiredChunkIds = lawChunkMapper.findChunkIdsByDocumentId(documentId).stream()
			.filter(chunkId -> !candidateChunkIds.contains(chunkId))
			.toList();
		qdrantClient.promoteLawCandidatePoints(candidateChunkIds);
		lawChunkMapper.retireOtherChunkVersions(documentId, candidateVersion);
		lawChunkMapper.activateChunkVersion(documentId, candidateVersion);
		lawChunkMapper.retireOtherActiveChunkVersionStates(documentId, candidateVersion);
		lawChunkMapper.updateChunkVersionStatus(documentId, candidateVersion, "ACTIVE");
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				qdrantClient.markLawPointsActive(candidateChunkIds);
				qdrantClient.markLawPointsRetired(retiredChunkIds);
				qdrantClient.deleteLawPointsBestEffort(retiredChunkIds);
			}
		});
		return new ChunkActivationResult(documentId, candidateVersion, true, "ACTIVATED", retiredChunkIds);
	}

	@Transactional
	public ChunkActivationResult rollbackToVersion(long documentId, int retiredVersion) {
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			return ChunkActivationResult.blocked(documentId, retiredVersion, "Rollback requires an active transaction.");
		}
		if (!"RETIRED".equals(lawChunkMapper.findChunkVersionStatus(documentId, retiredVersion))) {
			return ChunkActivationResult.blocked(documentId, retiredVersion, "Requested version is not retired.");
		}
		List<Long> rollbackChunkIds = lawChunkMapper.findChunkIdsByDocumentIdAndVersion(documentId, retiredVersion);
		if (!pointsArePresent(rollbackChunkIds)) {
			return ChunkActivationResult.blocked(documentId, retiredVersion, "Retired version Qdrant verification failed.");
		}
		List<Long> activeChunkIds = lawChunkMapper.findChunkIdsByDocumentId(documentId).stream()
			.filter(chunkId -> !rollbackChunkIds.contains(chunkId))
			.toList();
		lawChunkMapper.retireActiveChunkVersionsExcept(documentId, retiredVersion);
		lawChunkMapper.reactivateChunkVersion(documentId, retiredVersion);
		lawChunkMapper.retireOtherActiveChunkVersionStates(documentId, retiredVersion);
		lawChunkMapper.updateChunkVersionStatus(documentId, retiredVersion, "ACTIVE");
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				qdrantClient.markLawPointsActive(rollbackChunkIds);
				qdrantClient.markLawPointsRetired(activeChunkIds);
				qdrantClient.deleteLawPointsBestEffort(activeChunkIds);
			}
		});
		return new ChunkActivationResult(documentId, retiredVersion, true, "ROLLED_BACK", activeChunkIds);
	}

	private boolean candidatePointsArePresent(long documentId, int candidateVersion) {
		return candidatePointsArePresent(lawChunkMapper.findChunkIdsByDocumentIdAndVersion(documentId, candidateVersion));
	}

	private boolean candidatePointsArePresent(List<Long> pointIds) {
		if (pointIds == null || pointIds.isEmpty()) {
			return false;
		}
		for (int start = 0; start < pointIds.size(); start += 256) {
			List<Long> batch = pointIds.subList(start, Math.min(pointIds.size(), start + 256));
			if (qdrantClient.findExistingLawCandidatePointIds(batch).size() != batch.size()) {
				return false;
			}
		}
		return true;
	}

	private boolean pointsArePresent(List<Long> pointIds) {
		if (pointIds == null || pointIds.isEmpty()) {
			return false;
		}
		for (int start = 0; start < pointIds.size(); start += 256) {
			List<Long> batch = pointIds.subList(start, Math.min(pointIds.size(), start + 256));
			if (qdrantClient.findExistingLawPointIds(batch).size() != batch.size()) {
				return false;
			}
		}
		return true;
	}

	private StoredChunk storedChunk(
		long documentId, long detailId, PlannedLawChunk chunk, String sourceUrl,
		int sortOrder, int chunkVersion, String activationStatus
	) {
		return new StoredChunk(
			documentId, detailId, chunk.type(), emptyToNull(chunk.no()), emptyToNull(chunk.title()), chunk.text(),
			emptyToNull(chunk.sourcePath()), emptyToNull(sourceUrl), sortOrder, sha256(chunk.embeddingText()),
			chunk.chunkSchemaVersion(), chunkVersion, activationStatus, chunk.parentKey(), chunk.parentTitle(),
			chunk.parentSourcePath(), chunk.childOrder(), chunk.embeddingText(), chunk.qualityStatus(), chunk.qualityReason());
	}

	private void deleteOldQdrantPointsAfterCommit(List<Long> oldChunkIds) {
		if (oldChunkIds == null || oldChunkIds.isEmpty()) {
			return;
		}
		if (!TransactionSynchronizationManager.isSynchronizationActive()) {
			return;
		}
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				qdrantClient.deleteLawPointsBestEffort(oldChunkIds);
			}
		});
	}

	// 메소드 설명: replaceAssets 처리 흐름을 수행합니다.
	public int replaceAssets(long documentId, long detailId, List<SyncAsset> assets) {
		
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		lawAssetMapper.deleteAssets(documentId);
		int count = 0;
		for (SyncAsset asset : assets) {
			
			// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
			lawAssetMapper.insertAsset(new StoredAsset(
				documentId,
				detailId,
				asset.type(),
				asset.sourceUrl(),
				asset.proxyUrl(),
				emptyToNull(asset.fileName()),
				emptyToNull(asset.fileExtension()),
				null,
				emptyToNull(asset.altText()),
				toJson(asset),
				count
			));
			count++;
		}
		return count;
	}

	
	// 메소드 설명: compactLargeJson 처리 흐름을 수행합니다.
	private String compactLargeJson(String json, String fieldName) {
		if (json.length() <= MAX_STORED_DETAIL_JSON_CHARS) {
			return json;
		}
		return toJson(Map.of(
			"storage", "compacted",
			"field", fieldName,
			"originalCharLength", json.length(),
			"originalContentHash", sha256(json),
			"reason", "exceeds-db-packet-safe-size"
		));
	}

	
	// 메소드 설명: toJson 처리 흐름을 수행합니다.
	private String toJson(Object value) {
		// 주요 호출: 외부 컴포넌트나 인프라 기능을 호출합니다.
		return jsonWriter.write(value);
	}
}
