package com.kaces.pandora.semantic.integrity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Registers immutable, bounded repair work without starting a repair. */
@Service
public class LawMissingEmbeddingRepairOperationService {
	private final LawMissingEmbeddingRepairOperationMapper operationMapper;
	private final LawMissingEmbeddingRepairService legacyRepairService;
	private final Clock clock;

	public LawMissingEmbeddingRepairOperationService(
		LawMissingEmbeddingRepairOperationMapper operationMapper,
		LawMissingEmbeddingRepairService legacyRepairService
	) {
		this(operationMapper, legacyRepairService, Clock.systemUTC());
	}

	LawMissingEmbeddingRepairOperationService(
		LawMissingEmbeddingRepairOperationMapper operationMapper,
		LawMissingEmbeddingRepairService legacyRepairService,
		Clock clock
	) {
		this.operationMapper = Objects.requireNonNull(operationMapper, "operationMapper");
		this.legacyRepairService = Objects.requireNonNull(legacyRepairService, "legacyRepairService");
		this.clock = Objects.requireNonNull(clock, "clock");
	}

	@Transactional
	public OperationView register(RepairRequest request) {
		validateRegistrationShape(request);
		String normalized = canonicalNormalizedRequest(request);
		String requestHash = sha256(normalized);
		LawMissingEmbeddingRepairOperation.OperationRow existing = operationMapper.findOperationByIdempotencyKey(requestHash);
		if (existing != null) {
			return requireIdenticalExisting(existing, request, normalized, requestHash);
		}

		Preflight preflight = preflight(request);
		LawMissingEmbeddingRepairOperation.OperationRow operation = newOperation(request, normalized, requestHash, preflight);
		List<LawMissingEmbeddingRepairOperation.Item> items = newItems(operation.operationId(), request, preflight);
		try {
			if (operationMapper.insertOperation(operation) != 1 || operationMapper.insertItems(operation.operationId(), items) != items.size()) {
				throw new IllegalStateException("Unable to persist complete repair operation.");
			}
		} catch (DuplicateKeyException exception) {
			LawMissingEmbeddingRepairOperation.OperationRow concurrent = operationMapper.findOperationByIdempotencyKey(requestHash);
			if (concurrent == null) {
				throw exception;
			}
			return requireIdenticalExisting(concurrent, request, normalized, requestHash);
		}
		return view(operation, items);
	}

	public Optional<OperationView> find(UUID operationId) {
		if (operationId == null) {
			return Optional.empty();
		}
		LawMissingEmbeddingRepairOperation.OperationRow operation = operationMapper.findOperationById(operationId.toString());
		return operation == null ? Optional.empty() : Optional.of(view(operation, operationMapper.findItemsByOperationId(operation.operationId())));
	}

	String canonicalNormalizedRequest(RepairRequest request) {
		validateRegistrationShape(request);
		StringBuilder normalized = new StringBuilder();
		normalized.append("target=law\n");
		normalized.append("runtimeInstanceId=").append(UUID.fromString(request.expectedRuntimeInstanceId())).append('\n');
		normalized.append("indexRevision=").append(request.expectedIndexRevision().toLowerCase(Locale.ROOT)).append('\n');
		normalized.append("apply=true\n");
		for (int index = 0; index < request.expectedDocumentIds().size(); index++) {
			normalized.append("expectedDocumentId[").append(index).append("]=").append(request.expectedDocumentIds().get(index)).append('\n');
		}
		for (int index = 0; index < request.candidates().size(); index++) {
			RepairCandidate candidate = request.candidates().get(index);
			normalized.append("candidate[").append(index).append("]=").append(candidate.chunkId()).append(':')
				.append(candidate.expectedChunkContentHash().toLowerCase(Locale.ROOT)).append('\n');
		}
		return normalized.toString();
	}

	String sha256(String value) {
		try {
			byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
			StringBuilder encoded = new StringBuilder(digest.length * 2);
			for (byte b : digest) {
				encoded.append(String.format(Locale.ROOT, "%02x", b));
			}
			return encoded.toString();
		} catch (NoSuchAlgorithmException exception) {
			throw new IllegalStateException("SHA-256 is unavailable.", exception);
		}
	}

	private Preflight preflight(RepairRequest request) {
		LawMissingEmbeddingRepairService.RepairRequest legacyRequest = new LawMissingEmbeddingRepairService.RepairRequest(
			request.target(), request.expectedRuntimeInstanceId(), request.expectedIndexRevision(), request.expectedDocumentIds(),
			request.candidates().stream().map(candidate -> new LawMissingEmbeddingRepairService.RepairCandidate(
				candidate.chunkId(), candidate.expectedChunkContentHash().toLowerCase(Locale.ROOT)
			)).toList(), false
		);
		LawMissingEmbeddingRepairService.RepairResult result = legacyRepairService.preflight(legacyRequest);
		if (result.runtime() == null || !request.expectedRuntimeInstanceId().equals(result.runtime().runtimeInstanceId())
			|| !request.expectedIndexRevision().equalsIgnoreCase(result.runtime().indexRevision())
			|| result.outcomes().size() != request.candidates().size()
			|| result.outcomes().stream().anyMatch(outcome -> outcome.state() != LawMissingEmbeddingRepairService.RepairState.READY)) {
			throw new RegistrationRejectedException(Rejection.CONFLICT);
		}
		LinkedHashSet<Long> actualDocuments = result.outcomes().stream().map(LawMissingEmbeddingRepairService.RepairOutcome::documentId)
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
		if (!actualDocuments.equals(new LinkedHashSet<>(request.expectedDocumentIds()))) {
			throw new RegistrationRejectedException(Rejection.CONFLICT);
		}
		return new Preflight(result.runtime().indexRevision(), result.outcomes());
	}

	private LawMissingEmbeddingRepairOperation.OperationRow newOperation(
		RepairRequest request, String normalized, String requestHash, Preflight preflight
	) {
		Instant now = clock.instant();
		return new LawMissingEmbeddingRepairOperation.OperationRow(
			UUID.randomUUID().toString(), requestHash, normalized, requestHash, "law", request.expectedRuntimeInstanceId(),
			preflight.trustedIndexRevision(), LawMissingEmbeddingRepairOperation.Status.READY, request.candidates().size(),
			request.expectedDocumentIds().size(), 0, 0, null, null, null, now, now
		);
	}

	private List<LawMissingEmbeddingRepairOperation.Item> newItems(
		String operationId, RepairRequest request, Preflight preflight
	) {
		Instant now = clock.instant();
		List<LawMissingEmbeddingRepairOperation.Item> items = new ArrayList<>();
		for (int index = 0; index < request.candidates().size(); index++) {
			RepairCandidate candidate = request.candidates().get(index);
			LawMissingEmbeddingRepairService.RepairOutcome outcome = preflight.outcomes().get(index);
			items.add(new LawMissingEmbeddingRepairOperation.Item(
				operationId, index, candidate.chunkId(), outcome.documentId(), candidate.expectedChunkContentHash().toLowerCase(Locale.ROOT),
				LawMissingEmbeddingRepairOperation.ItemState.READY, null, null, null, null, null, now, now
			));
		}
		return List.copyOf(items);
	}

	private OperationView requireIdenticalExisting(
		LawMissingEmbeddingRepairOperation.OperationRow existing, RepairRequest request, String normalized, String requestHash
	) {
		List<LawMissingEmbeddingRepairOperation.Item> items = operationMapper.findItemsByOperationId(existing.operationId());
		boolean matches = requestHash.equals(existing.idempotencyKey()) && requestHash.equals(existing.requestHash())
			&& normalized.equals(existing.normalizedRequest()) && "law".equals(existing.target())
			&& request.expectedRuntimeInstanceId().equals(existing.runtimeInstanceId())
			&& request.candidates().size() == existing.candidateCount() && request.expectedDocumentIds().size() == existing.documentCount()
			&& itemsMatchRequest(items, request);
		if (!matches) {
			throw new RegistrationRejectedException(Rejection.CONFLICT);
		}
		return view(existing, items);
	}

	private boolean itemsMatchRequest(List<LawMissingEmbeddingRepairOperation.Item> items, RepairRequest request) {
		if (items == null || items.size() != request.candidates().size()) {
			return false;
		}
		for (int index = 0; index < items.size(); index++) {
			LawMissingEmbeddingRepairOperation.Item item = items.get(index);
			RepairCandidate candidate = request.candidates().get(index);
			if (item.ordinal() != index || item.chunkId() != candidate.chunkId()
				|| !candidate.expectedChunkContentHash().equalsIgnoreCase(item.expectedContentHash())) {
				return false;
			}
		}
		return items.stream().map(LawMissingEmbeddingRepairOperation.Item::documentId)
			.collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new))
			.equals(new LinkedHashSet<>(request.expectedDocumentIds()));
	}

	private OperationView view(
		LawMissingEmbeddingRepairOperation.OperationRow operation, List<LawMissingEmbeddingRepairOperation.Item> items
	) {
		return new OperationView(operation.toOperation(), LawMissingEmbeddingRepairOperation.immutableItems(items == null ? List.of() : items));
	}

	private void validateRegistrationShape(RepairRequest request) {
		if (request == null || !request.apply() || !"law".equals(request.target()) || !isUuid(request.expectedRuntimeInstanceId())
			|| !LawMissingEmbeddingRepairService.isHash(request.expectedIndexRevision())) {
			throw new RegistrationRejectedException(Rejection.BAD_REQUEST);
		}
		List<RepairCandidate> candidates = request.candidates() == null ? List.of() : request.candidates();
		List<Long> documentIds = request.expectedDocumentIds() == null ? List.of() : request.expectedDocumentIds();
		List<LawMissingEmbeddingRepairService.RepairCandidate> legacyCandidates = candidates.stream()
			.map(candidate -> candidate == null ? null : new LawMissingEmbeddingRepairService.RepairCandidate(candidate.chunkId(), candidate.expectedChunkContentHash()))
			.toList();
		if (!LawMissingEmbeddingRepairService.isValidRequest(new LawMissingEmbeddingRepairService.RepairRequest(
			request.target(), request.expectedRuntimeInstanceId(), request.expectedIndexRevision(), documentIds, legacyCandidates, false
		), legacyCandidates)) {
			throw new RegistrationRejectedException(Rejection.BAD_REQUEST);
		}
	}

	private boolean isUuid(String value) {
		try {
			return value != null && UUID.fromString(value).toString().equals(value.toLowerCase(Locale.ROOT));
		} catch (IllegalArgumentException exception) {
			return false;
		}
	}

	private record Preflight(String trustedIndexRevision, List<LawMissingEmbeddingRepairService.RepairOutcome> outcomes) {
	}

	public record RepairRequest(
		String target,
		String expectedRuntimeInstanceId,
		String expectedIndexRevision,
		boolean apply,
		List<Long> expectedDocumentIds,
		List<RepairCandidate> candidates
	) {
	}

	public record RepairCandidate(long chunkId, String expectedChunkContentHash) {
	}

	public record OperationView(LawMissingEmbeddingRepairOperation operation, List<LawMissingEmbeddingRepairOperation.Item> items) {
		public OperationView {
			Objects.requireNonNull(operation, "operation");
			items = LawMissingEmbeddingRepairOperation.immutableItems(items);
		}
	}

	public enum Rejection {
		BAD_REQUEST, CONFLICT
	}

	public static final class RegistrationRejectedException extends RuntimeException {
		private final Rejection rejection;

		public RegistrationRejectedException(Rejection rejection) {
			super(rejection.name());
			this.rejection = rejection;
		}

		public Rejection rejection() {
			return rejection;
		}
	}
}
