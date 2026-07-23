const { matchOracleGroup } = require('./rag-explicit-oracle-matcher');

const EVIDENCE_STAGES = [
  'candidateSources',
  'merged',
  'reranked',
  'intentFiltered',
  'judgeCandidates',
  'judged',
  'selected',
];

function measureEvidenceCoverage(evalCase, response, k = 10) {
  const safeK = normalizeK(k);
  const stageItems = {
    candidateSources: uniqueItems([
      ...topK(response?.vectorHits, safeK),
      ...topK(response?.lexicalHits, safeK),
    ]),
    ...Object.fromEntries(EVIDENCE_STAGES.slice(1).map((stage) => [
      stage,
      topK(response?.[stage], safeK),
    ])),
  };
  return {
    stages: Object.fromEntries(EVIDENCE_STAGES.map((stage) => [stage, { items: stageItems[stage].length }])),
    propositionGroups: measureGroups('proposition', evalCase?.requiredPropositionGroups, stageItems),
    conditionGroups: measureGroups('condition', evalCase?.requiredConditionGroups, stageItems),
  };
}

function measureGroups(type, groups, stageItems) {
  return (groups ?? []).map((rawAliases, index) => {
    const aliases = (rawAliases ?? []).map((alias) => String(alias).trim()).filter(Boolean);
    const coverage = Object.fromEntries(EVIDENCE_STAGES.map((stage) => [
      stage,
      stageItems[stage].some((item) => matchOracleGroup(itemText(item), aliases) !== null),
    ]));
    return {
      id: `${type}:${index + 1}`,
      aliases,
      coverage,
      firstLossStage: firstLossStage(coverage),
    };
  });
}

function firstLossStage(coverage) {
  if (!coverage.candidateSources) {
    return 'candidateSources';
  }
  return EVIDENCE_STAGES.slice(1).find((stage) => !coverage[stage]) ?? 'survived';
}

function summarizeEvidenceCoverage(results) {
  const coverageRows = (results ?? []).map((result) => result?.evidenceCoverage ?? result).filter(Boolean);
  return {
    proposition: summarizeGroups(coverageRows.flatMap((coverage) => coverage.propositionGroups ?? [])),
    condition: summarizeGroups(coverageRows.flatMap((coverage) => coverage.conditionGroups ?? [])),
  };
}

function summarizeGroups(groups) {
  const firstLossCounts = {};
  for (const group of groups) {
    const label = group.firstLossStage;
    firstLossCounts[label] = (firstLossCounts[label] ?? 0) + 1;
  }
  return {
    totalGroups: groups.length,
    stages: Object.fromEntries(EVIDENCE_STAGES.map((stage) => {
      const coveredGroups = groups.filter((group) => group.coverage?.[stage]).length;
      return [stage, { coveredGroups, rate: ratio(coveredGroups, groups.length) }];
    })),
    firstLossCounts,
  };
}

function itemText(item) {
  return ['title', 'chunkTitle', 'parentSectionTitle', 'matchedChildText', 'snippet']
    .map((field) => item?.[field] ?? '')
    .join(' ');
}

function uniqueItems(items) {
  const byKey = new Map();
  for (const [index, item] of (items ?? []).entries()) {
    const key = item?.chunkId == null
      ? `position:${index}`
      : `${item?.target ?? ''}:${item.chunkId}`;
    if (!byKey.has(key)) {
      byKey.set(key, item);
    }
  }
  return Array.from(byKey.values());
}

function topK(items, k) {
  return Array.isArray(items) ? items.slice(0, normalizeK(k)) : [];
}

function normalizeK(k) {
  const parsed = Number(k);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : 10;
}

function ratio(numerator, denominator) {
  return denominator > 0 ? numerator / denominator : null;
}

module.exports = {
  EVIDENCE_STAGES,
  measureEvidenceCoverage,
  summarizeEvidenceCoverage,
  uniqueItems,
};
