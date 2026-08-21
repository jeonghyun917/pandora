const COVERAGE_POLICY_GRID = Object.freeze([
  Object.freeze({ enabled: false, maxRescues: 0, maxRescuesPerDocument: 1, sourceRankLimit: 30 }),
  Object.freeze({ enabled: true, maxRescues: 1, maxRescuesPerDocument: 1, sourceRankLimit: 20 }),
  Object.freeze({ enabled: true, maxRescues: 1, maxRescuesPerDocument: 1, sourceRankLimit: 30 }),
  Object.freeze({ enabled: true, maxRescues: 2, maxRescuesPerDocument: 1, sourceRankLimit: 20 }),
  Object.freeze({ enabled: true, maxRescues: 2, maxRescuesPerDocument: 1, sourceRankLimit: 30 }),
]);

const MAX_CANDIDATE_LIMIT = 100;
const RESCUE_REASON = 'DOCUMENT_SIBLING_RESCUE';

function rerankCoverage({ ranking, documentIdByCandidate, policy, topK = 30 }) {
  const baseline = Array.isArray(ranking) ? [...ranking] : [];
  if (!policy?.enabled || policy.maxRescues === 0) {
    return result(baseline, baseline, [], 'DISABLED');
  }
  if (!validInputs(baseline, documentIdByCandidate, policy, topK)) {
    return result(baseline, baseline, [], 'FALLBACK_BASELINE');
  }

  const anchors = eligibleAnchors(baseline, documentIdByCandidate, topK);
  const proposals = eligibleProposals(baseline, documentIdByCandidate, anchors, policy, topK);
  const selected = selectWithinBudgets(proposals, policy);
  if (selected.length === 0) {
    return result(baseline, baseline, [], 'NO_ELIGIBLE_SIBLING');
  }
  return replaceTail(baseline, anchors, selected, topK);
}

function validInputs(baseline, documentIds, policy, topK) {
  if (!Number.isSafeInteger(topK) || topK <= 0 || baseline.length < topK
    || !documentIds || typeof documentIds !== 'object'
    || !Number.isSafeInteger(policy.maxRescues) || policy.maxRescues < 0
    || !Number.isSafeInteger(policy.maxRescuesPerDocument) || policy.maxRescuesPerDocument <= 0
    || policy.maxRescuesPerDocument > policy.maxRescues
    || !Number.isSafeInteger(policy.sourceRankLimit) || policy.sourceRankLimit <= 0) {
    return false;
  }
  const seen = new Set();
  return baseline.slice(0, MAX_CANDIDATE_LIMIT).every((item) => {
    const key = String(item?.candidateKey ?? '');
    if (key !== `${item?.target}:${item?.chunkId}` || seen.has(key)) {
      return false;
    }
    seen.add(key);
    return Number.isSafeInteger(documentIds[key]) && documentIds[key] > 0;
  });
}

function eligibleAnchors(baseline, documentIds, topK) {
  const anchors = new Map();
  baseline.slice(0, topK).forEach((item, index) => {
    if (!crossSource(item)) {
      return;
    }
    const key = documentKey(item, documentIds[item.candidateKey]);
    if (!anchors.has(key)) {
      anchors.set(key, { hit: item, rank: index + 1, documentKey: key });
    }
  });
  return anchors;
}

function eligibleProposals(baseline, documentIds, anchors, policy, topK) {
  const proposals = [];
  baseline.slice(topK, MAX_CANDIDATE_LIMIT).forEach((sibling, offset) => {
    if (crossSource(sibling) || sibling.bestSourceRank > policy.sourceRankLimit) {
      return;
    }
    const documentId = documentIds[sibling.candidateKey];
    const key = documentKey(sibling, documentId);
    const anchor = anchors.get(key);
    if (anchor) {
      proposals.push({
        sibling,
        anchor: anchor.hit,
        documentKey: key,
        documentId,
        anchorRank: anchor.rank,
        baselineRank: topK + offset + 1,
        bestSourceRank: sibling.bestSourceRank,
      });
    }
  });
  return proposals.sort((left, right) => left.anchorRank - right.anchorRank
    || left.bestSourceRank - right.bestSourceRank
    || left.baselineRank - right.baselineRank
    || left.sibling.target.localeCompare(right.sibling.target, 'en')
    || left.documentId - right.documentId
    || left.sibling.chunkId - right.sibling.chunkId);
}

function selectWithinBudgets(proposals, policy) {
  const selected = [];
  const counts = new Map();
  for (const proposal of proposals) {
    if (selected.length >= policy.maxRescues) {
      break;
    }
    const count = counts.get(proposal.documentKey) ?? 0;
    if (count >= policy.maxRescuesPerDocument) {
      continue;
    }
    selected.push(proposal);
    counts.set(proposal.documentKey, count + 1);
  }
  return selected;
}

function replaceTail(baseline, anchors, selected, topK) {
  const ranking = baseline.slice(0, topK);
  const protectedKeys = new Set(Array.from(anchors.values(), (anchor) => anchor.hit.candidateKey));
  for (let rescueIndex = 0; rescueIndex < selected.length; rescueIndex += 1) {
    let replacementIndex = -1;
    for (let index = ranking.length - 1; index >= 0; index -= 1) {
      if (!protectedKeys.has(ranking[index].candidateKey)) {
        replacementIndex = index;
        break;
      }
    }
    if (replacementIndex < 0) {
      return result(baseline, baseline, [], 'FALLBACK_BASELINE');
    }
    ranking.splice(replacementIndex, 1);
  }
  const rescues = selected.map((proposal) => {
    ranking.push(proposal.sibling);
    return {
      candidateKey: proposal.sibling.candidateKey,
      documentKey: proposal.documentKey,
      anchorCandidateKey: proposal.anchor.candidateKey,
      baselineRank: proposal.baselineRank,
      rescuedRank: ranking.length,
      reason: RESCUE_REASON,
    };
  });
  if (ranking.length !== topK || new Set(ranking.map((item) => item.candidateKey)).size !== topK) {
    return result(baseline, baseline, [], 'FALLBACK_BASELINE');
  }
  return result(baseline, ranking, rescues, 'APPLIED');
}

function validateDocumentIdentitySnapshot(snapshot) {
  const documentIds = {};
  for (const source of ['vector', 'bm25']) {
    for (const item of Array.isArray(snapshot?.[source]) ? snapshot[source] : []) {
      const key = String(item?.candidateKey ?? '').trim();
      const documentId = Number(item?.documentId);
      if (!key || !Number.isSafeInteger(documentId) || documentId <= 0) {
        throw new Error(`invalid document id for candidate: ${key || '<empty>'}`);
      }
      if (documentIds[key] != null && documentIds[key] !== documentId) {
        throw new Error(`conflicting document id for candidate: ${key}`);
      }
      documentIds[key] = documentId;
    }
  }
  return documentIds;
}

function crossSource(item) {
  return item?.vectorRank != null && item?.bm25Rank != null;
}

function documentKey(item, documentId) {
  return `${item.target}:${documentId}`;
}

function result(baseline, ranking, rescues, status) {
  return {
    baseline: [...baseline],
    ranking: [...ranking],
    rescues: [...rescues],
    status,
  };
}

module.exports = {
  COVERAGE_POLICY_GRID,
  rerankCoverage,
  validateDocumentIdentitySnapshot,
};
