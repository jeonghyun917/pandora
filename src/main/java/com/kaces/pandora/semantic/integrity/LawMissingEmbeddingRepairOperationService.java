package com.kaces.pandora.semantic.integrity;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/** Registers immutable, bounded repair work without starting a repair. */
@Service
public class LawMissingEmbeddingRepairOperationService {
	/**
	 * OpenAI worst case is 2 x (10s connect + 180s read) + 200ms retry delay = 380.2s.
	 * Qdrant is bounded by 5s connect + 120s read; Hikari connection acquisition defaults
	 * to 30s and MariaDB lock wait to 50s. A 600s DB-clock lease leaves 219.8s over
	 * the longest single blocking call and is renewed every 30s while sequential audit phases run.
	 */
	private static final LeasePolicy PRODUCTION_LEASE = new LeasePolicy(600, Duration.ofSeconds(30));
	private final LawMissingEmbeddingRepairOperationMapper operationMapper;
	private final LawMissingEmbeddingRepairService legacyRepairService;
	private final LawMissingEmbeddingRepairOperationPersistenceService persistenceService;
	private final Clock clock;
	private final LeasePolicy leasePolicy;

	@Autowired
	public LawMissingEmbeddingRepairOperationService(
		LawMissingEmbeddingRepairOperationMapper operationMapper,
		LawMissingEmbeddingRepairService legacyRepairService,
		LawMissingEmbeddingRepairOperationPersistenceService persistenceService
	) {
		this(operationMapper, legacyRepairService, persistenceService, Clock.systemUTC(), PRODUCTION_LEASE);
	}

	LawMissingEmbeddingRepairOperationService(
		LawMissingEmbeddingRepairOperationMapper operationMapper,
		LawMissingEmbeddingRepairService legacyRepairService,
		LawMissingEmbeddingRepairOperationPersistenceService persistenceService,
		Clock clock
	) {
		this(operationMapper, legacyRepairService, persistenceService, clock, PRODUCTION_LEASE);
	}

	LawMissingEmbeddingRepairOperationService(
		LawMissingEmbeddingRepairOperationMapper operationMapper,
		LawMissingEmbeddingRepairService legacyRepairService,
		LawMissingEmbeddingRepairOperationPersistenceService persistenceService,
		Clock clock,
		LeasePolicy leasePolicy
	) {
		this.operationMapper = Objects.requireNonNull(operationMapper, "operationMapper");
		this.legacyRepairService = Objects.requireNonNull(legacyRepairService, "legacyRepairService");
		this.persistenceService = Objects.requireNonNull(persistenceService, "persistenceService");
		this.clock = Objects.requireNonNull(clock, "clock");
		this.leasePolicy = Objects.requireNonNull(leasePolicy, "leasePolicy");
	}

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
			persistenceService.persist(operation, items);
		} catch (DuplicateKeyException exception) {
			LawMissingEmbeddingRepairOperation.OperationRow concurrent = persistenceService.findCommittedWinner(requestHash);
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

	/** Claims and resolves at most one durable item. Remote indexing never runs inside a DB transaction. */
	public Optional<OperationView> step(UUID operationId) {
		Optional<OperationView> found = find(operationId);
		if (found.isEmpty()) {
			return Optional.empty();
		}
		OperationView currentView = found.orElseThrow();
		LawMissingEmbeddingRepairOperation operation = currentView.operation();
		if (operation.progress().status() == LawMissingEmbeddingRepairOperation.Status.INDEXING_COMPLETE
			|| operation.progress().status() == LawMissingEmbeddingRepairOperation.Status.FAILED) {
			return found;
		}

		LawMissingEmbeddingRepairOperation.Item processing = currentView.items().stream()
			.filter(item -> item.state() == LawMissingEmbeddingRepairOperation.ItemState.PROCESSING)
			.findFirst().orElse(null);
		boolean recovery = processing != null;
		LawMissingEmbeddingRepairOperation.Item item = processing != null ? processing : currentView.items().stream()
			.filter(candidate -> candidate.state() == LawMissingEmbeddingRepairOperation.ItemState.READY)
			.findFirst().orElse(null);
		if (item == null) {
			return found;
		}
		String owner = UUID.randomUUID().toString();
		boolean claimed = recovery
			? persistenceService.claimExpiredItem(operation.request().operationId(), item.ordinal(), owner,
				operation.request().runtimeInstanceId(), operation.progress().trustedIndexRevision(), leasePolicy.leaseSeconds())
			: persistenceService.claimReadyItem(operation.request().operationId(), item.ordinal(), owner,
				operation.request().runtimeInstanceId(), operation.progress().trustedIndexRevision(), leasePolicy.leaseSeconds());
		if (!claimed) {
			return find(operationId);
		}

		try (StepLease lease = new StepLease(operation.request().operationId(), item.ordinal(), owner)) {
			if (recovery) {
				return reconcileExpired(operationId, operation, item, owner, lease);
			}
			LawIndexIntegrityRuntimeInfo runtimeBefore;
			try {
				lease.assertOwned();
				runtimeBefore = legacyRepairService.currentRuntimeSnapshot();
				lease.assertOwned();
			} catch (RuntimeException exception) {
				return fail(operationId, item, owner, "RUNTIME_IDENTITY_UNAVAILABLE", lease);
			}
			return repairClaimed(operationId, operation, item, owner, runtimeBefore, lease);
		}
	}

	private Optional<OperationView> reconcileExpired(
		UUID operationId,
		LawMissingEmbeddingRepairOperation operation,
		LawMissingEmbeddingRepairOperation.Item item,
		String owner,
		StepLease lease
	) {
		LawMissingEmbeddingRepairService.ExactInspection inspection;
		try {
			lease.assertOwned();
			inspection = legacyRepairService.inspectExactCandidate(
				new LawMissingEmbeddingRepairService.RepairCandidate(item.chunkId(), item.expectedContentHash()), item.documentId()
			);
			lease.assertOwned();
		} catch (RuntimeException exception) {
			return fail(operationId, item, owner, "RECOVERY_AUDIT_FAILED", lease);
		}
		if (inspection == null) {
			return fail(operationId, item, owner, "RECOVERY_AMBIGUOUS", lease);
		}
		if (!sameInstance(operation.request().runtimeInstanceId(), inspection.runtime())) {
			return fail(operationId, item, owner, "RUNTIME_FENCE_DRIFT", lease);
		}
		if (inspection.state() == LawMissingEmbeddingRepairService.RepairState.INDEXED) {
			return complete(operationId, operation, item, owner, inspection.runtime().indexRevision(), lease);
		}
		if (inspection.state() != LawMissingEmbeddingRepairService.RepairState.READY) {
			return fail(operationId, item, owner, "RECOVERY_AMBIGUOUS", lease);
		}
		if (!sameRuntime(operation, inspection.runtime())) {
			return fail(operationId, item, owner, "RUNTIME_FENCE_DRIFT", lease);
		}
		return repairClaimed(operationId, operation, item, owner, inspection.runtime(), lease);
	}

	private Optional<OperationView> repairClaimed(
		UUID operationId,
		LawMissingEmbeddingRepairOperation operation,
		LawMissingEmbeddingRepairOperation.Item item,
		String owner,
		LawIndexIntegrityRuntimeInfo runtimeBefore,
		StepLease lease
	) {
		if (!sameRuntime(operation, runtimeBefore)) {
			return fail(operationId, item, owner, "RUNTIME_FENCE_DRIFT", lease);
		}
		LawMissingEmbeddingRepairService.RepairResult result;
		try {
			lease.assertOwned();
			result = legacyRepairService.repairExact(new LawMissingEmbeddingRepairService.RepairRequest(
				"law", operation.request().runtimeInstanceId(), operation.progress().trustedIndexRevision(),
				List.of(item.documentId()), List.of(new LawMissingEmbeddingRepairService.RepairCandidate(
					item.chunkId(), item.expectedContentHash())), true
			), lease::assertOwned);
			lease.assertOwned();
		} catch (RuntimeException exception) {
			return fail(operationId, item, owner, "EXACT_INDEX_FAILED", lease);
		}
		if (result == null || !sameInstance(operation.request().runtimeInstanceId(), result.runtime())) {
			return fail(operationId, item, owner, "RUNTIME_FENCE_DRIFT", lease);
		}
		if (!isVerifiedExactSuccess(operation, item, result)) {
			return fail(operationId, item, owner, failureReason(result), lease);
		}
		return complete(operationId, operation, item, owner, result.runtime().indexRevision(), lease);
	}

	private boolean isVerifiedExactSuccess(
		LawMissingEmbeddingRepairOperation operation,
		LawMissingEmbeddingRepairOperation.Item item,
		LawMissingEmbeddingRepairService.RepairResult result
	) {
		return result != null && result.applied() && result.complete() && sameInstance(operation.request().runtimeInstanceId(), result.runtime())
			&& result.outcomes() != null && result.outcomes().size() == 1
			&& result.outcomes().get(0).chunkId() == item.chunkId()
			&& result.outcomes().get(0).documentId() == item.documentId()
			&& result.outcomes().get(0).state() == LawMissingEmbeddingRepairService.RepairState.INDEXED;
	}

	private String failureReason(LawMissingEmbeddingRepairService.RepairResult result) {
		if (result == null || result.outcomes() == null || result.outcomes().size() != 1) {
			return "EXACT_REPAIR_INVALID_RESULT";
		}
		return switch (result.outcomes().get(0).state()) {
			case REJECTED_RUNTIME_FENCE -> "RUNTIME_FENCE_DRIFT";
			case REJECTED_CHUNK_DRIFT, REJECTED_CLASSIFICATION_DRIFT, REJECTED_DOCUMENT_WAVE -> "CANDIDATE_DRIFT";
			case INDEX_FAILED -> "EXACT_INDEX_FAILED";
			case VERIFICATION_FAILED -> "POST_INDEX_VERIFICATION_FAILED";
			default -> "EXACT_REPAIR_INVALID_RESULT";
		};
	}

	private Optional<OperationView> complete(
		UUID operationId,
		LawMissingEmbeddingRepairOperation operation,
		LawMissingEmbeddingRepairOperation.Item item,
		String owner,
		String afterIndexRevision,
		StepLease lease
	) {
		if (!LawMissingEmbeddingRepairService.isHash(afterIndexRevision)) {
			return fail(operationId, item, owner, "POST_INDEX_RUNTIME_INVALID", lease);
		}
		if (!lease.executeIfOwned(() -> persistenceService.completeClaimedItem(
			operation.request().operationId(), item.ordinal(), owner, operation.request().runtimeInstanceId(),
			operation.progress().trustedIndexRevision(), afterIndexRevision
		))) {
			return find(operationId);
		}
		return find(operationId);
	}

	private Optional<OperationView> fail(
		UUID operationId, LawMissingEmbeddingRepairOperation.Item item, String owner, String reason, StepLease lease
	) {
		if (!lease.executeIfOwned(() -> persistenceService.failClaimedItem(
			operationId.toString(), item.ordinal(), owner, reason))) {
			return find(operationId);
		}
		return find(operationId);
	}

	private boolean sameRuntime(LawMissingEmbeddingRepairOperation operation, LawIndexIntegrityRuntimeInfo runtime) {
		return sameInstance(operation.request().runtimeInstanceId(), runtime)
			&& operation.progress().trustedIndexRevision().equals(runtime.indexRevision());
	}

	private boolean sameInstance(String expected, LawIndexIntegrityRuntimeInfo runtime) {
		return runtime != null && runtime.isComplete() && expected.equals(runtime.runtimeInstanceId());
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

	private final class StepLease implements AutoCloseable {
		private final String operationId;
		private final int ordinal;
		private final String owner;
		private final AtomicBoolean ownershipLost = new AtomicBoolean();
		private final ScheduledExecutorService scheduler;
		private final ScheduledFuture<?> heartbeat;

		private StepLease(String operationId, int ordinal, String owner) {
			this.operationId = operationId;
			this.ordinal = ordinal;
			this.owner = owner;
			this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
				Thread thread = new Thread(runnable, "law-repair-lease-" + operationId + "-" + ordinal);
				thread.setDaemon(true);
				return thread;
			});
			long heartbeatMillis = leasePolicy.heartbeatInterval().toMillis();
			this.heartbeat = scheduler.scheduleWithFixedDelay(
				this::renew, heartbeatMillis, heartbeatMillis, TimeUnit.MILLISECONDS
			);
		}

		private void renew() {
			synchronized (this) {
				try {
					assertOwned();
				} catch (OwnershipLostException exception) {
					// The next foreground checkpoint observes the durable ownership loss.
				}
			}
		}

		private synchronized void assertOwned() {
			if (ownershipLost.get()) {
				throw new OwnershipLostException();
			}
			try {
				if (!persistenceService.renewItemLease(operationId, ordinal, owner, leasePolicy.leaseSeconds())) {
					ownershipLost.set(true);
					throw new OwnershipLostException();
				}
			} catch (OwnershipLostException exception) {
				throw exception;
			} catch (RuntimeException exception) {
				ownershipLost.set(true);
				throw new OwnershipLostException();
			}
		}

		private synchronized boolean executeIfOwned(BooleanSupplier ownerCasMutation) {
			try {
				assertOwned();
			} catch (OwnershipLostException exception) {
				return false;
			}
			boolean applied = ownerCasMutation.getAsBoolean();
			if (!applied) {
				ownershipLost.set(true);
			}
			return applied;
		}

		@Override
		public void close() {
			heartbeat.cancel(true);
			scheduler.shutdownNow();
			try {
				scheduler.awaitTermination(
					Math.max(1L, Math.min(1_000L, leasePolicy.heartbeatInterval().toMillis())), TimeUnit.MILLISECONDS);
			} catch (InterruptedException exception) {
				Thread.currentThread().interrupt();
			}
		}
	}

	static record LeasePolicy(int leaseSeconds, Duration heartbeatInterval) {
		LeasePolicy {
			if (leaseSeconds <= 0 || heartbeatInterval == null || heartbeatInterval.isZero()
				|| heartbeatInterval.isNegative() || heartbeatInterval.compareTo(Duration.ofSeconds(leaseSeconds)) >= 0) {
				throw new IllegalArgumentException("Lease policy must renew before a positive lease expires.");
			}
		}
	}

	private static final class OwnershipLostException extends IllegalStateException {
		private OwnershipLostException() {
			super("Durable repair step ownership was lost.");
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
