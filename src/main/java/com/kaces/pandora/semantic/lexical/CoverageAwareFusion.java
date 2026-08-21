package com.kaces.pandora.semantic.lexical;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class CoverageAwareFusion {

	private static final int MAX_CANDIDATE_LIMIT = 100;
	private static final String RESCUE_REASON = "DOCUMENT_SIBLING_RESCUE";

	public Result rerank(
		List<ReciprocalRankFusion.RrfHit> baseline,
		Map<String, Long> documentIds,
		Policy policy,
		int topK
	) {
		List<ReciprocalRankFusion.RrfHit> safeBaseline = baseline == null
			? List.of()
			: List.copyOf(baseline);
		if (policy == null || !policy.enabled() || policy.maxRescues() == 0) {
			return result(safeBaseline, safeBaseline, List.of(), Status.DISABLED);
		}
		if (!validInputs(safeBaseline, documentIds, policy, topK)) {
			return result(safeBaseline, safeBaseline, List.of(), Status.FALLBACK_BASELINE);
		}

		Map<String, Anchor> anchors = eligibleAnchors(safeBaseline, documentIds, topK);
		List<Proposal> proposals = eligibleProposals(safeBaseline, documentIds, anchors, policy, topK);
		List<Proposal> selected = selectWithinBudgets(proposals, policy);
		if (selected.isEmpty()) {
			return result(safeBaseline, safeBaseline, List.of(), Status.NO_ELIGIBLE_SIBLING);
		}
		return replaceTail(safeBaseline, anchors, selected, topK);
	}

	private boolean validInputs(
		List<ReciprocalRankFusion.RrfHit> baseline,
		Map<String, Long> documentIds,
		Policy policy,
		int topK
	) {
		if (topK <= 0 || baseline.size() < topK || documentIds == null
			|| policy.maxRescues() < 0 || policy.maxRescuesPerDocument() <= 0
			|| policy.maxRescuesPerDocument() > policy.maxRescues()
			|| policy.sourceRankLimit() <= 0) {
			return false;
		}
		Set<String> seen = new HashSet<>();
		for (ReciprocalRankFusion.RrfHit hit : baseline.stream().limit(MAX_CANDIDATE_LIMIT).toList()) {
			if (hit == null || hit.candidateKey() == null || hit.target() == null
				|| !hit.candidateKey().equals(hit.target() + ':' + hit.chunkId())
				|| !seen.add(hit.candidateKey())
				|| documentIds.getOrDefault(hit.candidateKey(), 0L) <= 0) {
				return false;
			}
		}
		return true;
	}

	private Map<String, Anchor> eligibleAnchors(
		List<ReciprocalRankFusion.RrfHit> baseline,
		Map<String, Long> documentIds,
		int topK
	) {
		Map<String, Anchor> anchors = new LinkedHashMap<>();
		for (int index = 0; index < topK; index++) {
			ReciprocalRankFusion.RrfHit hit = baseline.get(index);
			if (!crossSource(hit)) {
				continue;
			}
			String documentKey = documentKey(hit, documentIds.get(hit.candidateKey()));
			anchors.putIfAbsent(documentKey, new Anchor(hit, index + 1, documentKey));
		}
		return anchors;
	}

	private List<Proposal> eligibleProposals(
		List<ReciprocalRankFusion.RrfHit> baseline,
		Map<String, Long> documentIds,
		Map<String, Anchor> anchors,
		Policy policy,
		int topK
	) {
		List<Proposal> proposals = new ArrayList<>();
		int limit = Math.min(baseline.size(), MAX_CANDIDATE_LIMIT);
		for (int index = topK; index < limit; index++) {
			ReciprocalRankFusion.RrfHit sibling = baseline.get(index);
			if (crossSource(sibling) || sibling.bestSourceRank() > policy.sourceRankLimit()) {
				continue;
			}
			long documentId = documentIds.get(sibling.candidateKey());
			String documentKey = documentKey(sibling, documentId);
			Anchor anchor = anchors.get(documentKey);
			if (anchor != null) {
				proposals.add(new Proposal(
					sibling,
					anchor.hit(),
					documentKey,
					documentId,
					anchor.rank(),
					index + 1,
					sibling.bestSourceRank()
				));
			}
		}
		proposals.sort(Comparator
			.comparingInt(Proposal::anchorRank)
			.thenComparingInt(Proposal::bestSourceRank)
			.thenComparingInt(Proposal::baselineRank)
			.thenComparing(proposal -> proposal.sibling().target())
			.thenComparingLong(Proposal::documentId)
			.thenComparingLong(proposal -> proposal.sibling().chunkId()));
		return List.copyOf(proposals);
	}

	private List<Proposal> selectWithinBudgets(List<Proposal> proposals, Policy policy) {
		List<Proposal> selected = new ArrayList<>();
		Map<String, Integer> selectedPerDocument = new HashMap<>();
		for (Proposal proposal : proposals) {
			if (selected.size() >= policy.maxRescues()) {
				break;
			}
			int count = selectedPerDocument.getOrDefault(proposal.documentKey(), 0);
			if (count >= policy.maxRescuesPerDocument()) {
				continue;
			}
			selected.add(proposal);
			selectedPerDocument.put(proposal.documentKey(), count + 1);
		}
		return List.copyOf(selected);
	}

	private Result replaceTail(
		List<ReciprocalRankFusion.RrfHit> baseline,
		Map<String, Anchor> anchors,
		List<Proposal> selected,
		int topK
	) {
		List<ReciprocalRankFusion.RrfHit> ranking = new ArrayList<>(baseline.subList(0, topK));
		Set<String> protectedKeys = anchors.values().stream()
			.map(anchor -> anchor.hit().candidateKey())
			.collect(java.util.stream.Collectors.toSet());

		for (int rescueIndex = 0; rescueIndex < selected.size(); rescueIndex++) {
			int replacementIndex = lastReplaceableIndex(ranking, protectedKeys);
			if (replacementIndex < 0) {
				return result(baseline, baseline, List.of(), Status.FALLBACK_BASELINE);
			}
			ranking.remove(replacementIndex);
		}
		List<Rescue> rescues = new ArrayList<>();
		for (Proposal proposal : selected) {
			ranking.add(proposal.sibling());
			rescues.add(new Rescue(
				proposal.sibling().candidateKey(),
				proposal.documentKey(),
				proposal.anchor().candidateKey(),
				proposal.baselineRank(),
				ranking.size(),
				RESCUE_REASON
			));
		}
		if (ranking.size() != topK
			|| ranking.stream().map(ReciprocalRankFusion.RrfHit::candidateKey).distinct().count() != topK) {
			return result(baseline, baseline, List.of(), Status.FALLBACK_BASELINE);
		}
		return result(baseline, ranking, rescues, Status.APPLIED);
	}

	private int lastReplaceableIndex(
		List<ReciprocalRankFusion.RrfHit> ranking,
		Set<String> protectedKeys
	) {
		for (int index = ranking.size() - 1; index >= 0; index--) {
			if (!protectedKeys.contains(ranking.get(index).candidateKey())) {
				return index;
			}
		}
		return -1;
	}

	private boolean crossSource(ReciprocalRankFusion.RrfHit hit) {
		return hit.vectorRank() != null && hit.lexicalRank() != null;
	}

	private String documentKey(ReciprocalRankFusion.RrfHit hit, long documentId) {
		return hit.target() + ':' + documentId;
	}

	private Result result(
		List<ReciprocalRankFusion.RrfHit> baseline,
		List<ReciprocalRankFusion.RrfHit> ranking,
		List<Rescue> rescues,
		Status status
	) {
		return new Result(baseline, ranking, rescues, status);
	}

	public record Policy(
		boolean enabled,
		int maxRescues,
		int maxRescuesPerDocument,
		int sourceRankLimit
	) {
	}

	public record Rescue(
		String candidateKey,
		String documentKey,
		String anchorCandidateKey,
		int baselineRank,
		int rescuedRank,
		String reason
	) {
	}

	public record Result(
		List<ReciprocalRankFusion.RrfHit> baseline,
		List<ReciprocalRankFusion.RrfHit> ranking,
		List<Rescue> rescues,
		Status status
	) {
		public Result {
			baseline = baseline == null ? List.of() : List.copyOf(baseline);
			ranking = ranking == null ? List.of() : List.copyOf(ranking);
			rescues = rescues == null ? List.of() : List.copyOf(rescues);
		}
	}

	public enum Status {
		DISABLED,
		NO_ELIGIBLE_SIBLING,
		APPLIED,
		FALLBACK_BASELINE
	}

	private record Anchor(
		ReciprocalRankFusion.RrfHit hit,
		int rank,
		String documentKey
	) {
	}

	private record Proposal(
		ReciprocalRankFusion.RrfHit sibling,
		ReciprocalRankFusion.RrfHit anchor,
		String documentKey,
		long documentId,
		int anchorRank,
		int baselineRank,
		int bestSourceRank
	) {
	}
}
