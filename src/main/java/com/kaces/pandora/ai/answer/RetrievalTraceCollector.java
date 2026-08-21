package com.kaces.pandora.ai.answer;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class RetrievalTraceCollector {

	private final int limit;
	private final Map<String, MutableTrace> traces = new LinkedHashMap<>();
	private Set<String> activeKeys = new LinkedHashSet<>();

	public RetrievalTraceCollector(int limit) {
		this.limit = Math.max(1, Math.min(limit, 100));
	}

	public void source(
		String candidateKey,
		String target,
		long chunkId,
		String source,
		int rank
	) {
		if (source == null || source.isBlank() || rank <= 0) {
			return;
		}
		MutableTrace trace = trace(candidateKey, target, chunkId, rank);
		if (trace == null) {
			return;
		}
		trace.sourceRanks.merge(source, rank, Math::min);
		activeKeys.add(candidateKey);
	}

	public void enter(String candidateKey, String stage) {
		MutableTrace trace = traces.get(candidateKey);
		if (trace == null || stage == null || stage.isBlank()) {
			return;
		}
		trace.enteredStages.add(stage);
	}

	public void transition(String stage, Collection<String> candidateKeys, String missingReasonCode) {
		Set<String> current = candidateKeys == null
			? Set.of()
			: new LinkedHashSet<>(candidateKeys);
		for (String previous : activeKeys) {
			if (!current.contains(previous)) {
				lose(previous, stage, missingReasonCode);
			}
		}
		for (String candidateKey : current) {
			enter(candidateKey, stage);
		}
		activeKeys = new LinkedHashSet<>(current);
	}

	public void lose(String candidateKey, String stage, String reasonCode) {
		MutableTrace trace = traces.get(candidateKey);
		if (trace == null || trace.selected || trace.firstLossStage != null) {
			return;
		}
		trace.firstLossStage = stage;
		if (reasonCode != null && !reasonCode.isBlank()) {
			trace.reasonCodes.add(reasonCode);
		}
	}

	public void note(String candidateKey, String stage, String reasonCode) {
		MutableTrace trace = traces.get(candidateKey);
		if (trace == null) {
			return;
		}
		if (stage != null && !stage.isBlank()) {
			trace.enteredStages.add(stage);
		}
		if (reasonCode != null && !reasonCode.isBlank() && !trace.reasonCodes.contains(reasonCode)) {
			trace.reasonCodes.add(reasonCode);
		}
	}

	public void select(String candidateKey) {
		MutableTrace trace = traces.get(candidateKey);
		if (trace == null) {
			return;
		}
		trace.selected = true;
		trace.enteredStages.add("selected");
	}

	public RetrievalCandidateTrace finish(String candidateKey) {
		MutableTrace trace = traces.get(candidateKey);
		if (trace == null) {
			throw new IllegalArgumentException("Unknown retrieval candidate: " + candidateKey);
		}
		return trace.immutable();
	}

	public List<RetrievalCandidateTrace> finishAll() {
		return traces.values().stream().map(MutableTrace::immutable).toList();
	}

	private MutableTrace trace(String candidateKey, String target, long chunkId, int rank) {
		if (candidateKey == null || candidateKey.isBlank()) {
			return null;
		}
		MutableTrace existing = traces.get(candidateKey);
		if (existing != null) {
			return existing;
		}
		if (traces.size() >= limit) {
			String evictionKey = null;
			int worstRank = rank;
			for (Map.Entry<String, MutableTrace> entry : traces.entrySet()) {
				int candidateRank = entry.getValue().bestSourceRank();
				if (entry.getValue().canEvict() && candidateRank > worstRank) {
					evictionKey = entry.getKey();
					worstRank = candidateRank;
				}
			}
			if (evictionKey == null) {
				return null;
			}
			traces.remove(evictionKey);
			activeKeys.remove(evictionKey);
		}
		MutableTrace created = new MutableTrace(candidateKey, target, chunkId);
		traces.put(candidateKey, created);
		return created;
	}

	private static final class MutableTrace {
		private final String candidateKey;
		private final String target;
		private final long chunkId;
		private final Map<String, Integer> sourceRanks = new LinkedHashMap<>();
		private final Set<String> enteredStages = new LinkedHashSet<>();
		private final List<String> reasonCodes = new ArrayList<>();
		private String firstLossStage;
		private boolean selected;

		private MutableTrace(String candidateKey, String target, long chunkId) {
			this.candidateKey = candidateKey;
			this.target = target;
			this.chunkId = chunkId;
		}

		private int bestSourceRank() {
			return sourceRanks.values().stream().mapToInt(Integer::intValue).min().orElse(Integer.MAX_VALUE);
		}

		private boolean canEvict() {
			return enteredStages.isEmpty() && firstLossStage == null && !selected;
		}

		private RetrievalCandidateTrace immutable() {
			return new RetrievalCandidateTrace(
				candidateKey,
				target,
				chunkId,
				sourceRanks,
				List.copyOf(enteredStages),
				selected ? null : firstLossStage,
				List.copyOf(reasonCodes),
				selected
			);
		}
	}
}
