const STAGE_NAMES = [
  'vectorHits',
  'lexicalHits',
  'merged',
  'reranked',
  'intentFiltered',
  'judgeCandidates',
  'judged',
  'selected',
];

const SHADOW_STAGE_NAMES = [
  'bm25Hits',
  'fused',
];

const DOWNSTREAM_STAGES = STAGE_NAMES.slice(2);

function measureRetrievalCase(evalCase, response, k = 10) {
  const safeK = normalizeK(k);
  const noGroundExpected = (evalCase?.expectedResultMsgs ?? [])
    .some((value) => normalize(value) === 'nogrounds');
  const stages = Object.fromEntries([...STAGE_NAMES, ...SHADOW_STAGE_NAMES].map((stage) => [
    stage,
    measureStage(evalCase, response?.[stage], safeK),
  ]));
  const candidateItems = uniqueItems([
    ...topK(response?.vectorHits, safeK),
    ...topK(response?.lexicalHits, safeK),
  ]);
  const candidateEntry = measureStage(evalCase, candidateItems, candidateItems.length || safeK);
  const hasRetrievalGold = candidateEntry.hasRetrievalGold;
  const recallEligible = !noGroundExpected && hasRetrievalGold;
  const candidateEntryHit = recallEligible && candidateEntry.directHit;
  let firstDropStage = null;
  if (recallEligible && !candidateEntryHit) {
    firstDropStage = 'candidateSources';
  } else if (candidateEntryHit) {
    firstDropStage = DOWNSTREAM_STAGES.find((stage) => !stages[stage].directHit) ?? null;
  }
  const selected = Array.isArray(response?.selected) ? response.selected : [];
  return {
    id: String(evalCase?.id ?? ''),
    k: safeK,
    resultMsg: response?.resultMsg ?? null,
    noGroundExpected,
    falseGround: noGroundExpected && selected.length > 0,
    recallEligible,
    exclusionReason: noGroundExpected
      ? 'no_ground_expected'
      : (hasRetrievalGold ? null : 'missing_retrieval_gold'),
    candidateEntryHit,
    firstDropStage,
    stageSurvival: {
      candidateSources: candidateEntryHit,
      ...Object.fromEntries(DOWNSTREAM_STAGES.map((stage) => [stage, candidateEntryHit && stages[stage].directHit])),
    },
    stages,
    shadowRanks: Object.fromEntries(SHADOW_STAGE_NAMES.map((stage) => [
      stage,
      topK(response?.[stage], safeK).map(candidateKey).filter(Boolean),
    ])),
    oraclePresence: measureOraclePresence(evalCase, response, safeK),
  };
}

function measureOraclePresence(evalCase, response, k) {
  const propositionGroups = Array.isArray(evalCase?.requiredPropositionGroups)
    ? evalCase.requiredPropositionGroups
    : [];
  const conditionGroups = Array.isArray(evalCase?.requiredConditionGroups)
    ? evalCase.requiredConditionGroups
    : [];
  const totalGroupCount = propositionGroups.length + conditionGroups.length;
  if (totalGroupCount === 0) {
    return {
      auditable: false,
      classification: 'NO_EXPLICIT_ORACLE',
      propositionGroupCount: 0,
      conditionGroupCount: 0,
      totalGroupCount: 0,
      firstLossStage: null,
      stages: {},
    };
  }

  const candidateItems = uniqueItems([
    ...topK(response?.vectorHits, k),
    ...topK(response?.lexicalHits, k),
  ]);
  const stages = {
    candidateSources: measureAuditStage(candidateItems, candidateItems.length, totalGroupCount),
    ...Object.fromEntries([...STAGE_NAMES, ...SHADOW_STAGE_NAMES].map((stage) => [
      stage,
      measureAuditStage(response?.[stage], k, totalGroupCount),
    ])),
  };
  const candidate = stages.candidateSources;
  const selected = stages.selected;
  let classification;
  let firstLossStage = null;
  if (!candidate.anyRequiredPresent) {
    classification = 'ABSENT_FROM_TOP_K_CANDIDATES';
    firstLossStage = 'candidateSources';
  } else if (!candidate.allRequiredPresent) {
    classification = 'PARTIAL_IN_CANDIDATES';
    firstLossStage = 'candidateSources';
  } else if (selected.allRequiredPresent) {
    classification = 'PRESENT_IN_SELECTED';
  } else {
    classification = 'DROPPED_BEFORE_SELECTED';
    firstLossStage = DOWNSTREAM_STAGES.find((stage) => !stages[stage].allRequiredPresent) ?? 'selected';
  }
  return {
    auditable: true,
    classification,
    propositionGroupCount: propositionGroups.length,
    conditionGroupCount: conditionGroups.length,
    totalGroupCount,
    firstLossStage,
    stages,
  };
}

function measureAuditStage(items, k, totalGroupCount) {
  const matchedGroupIndexes = Array.from(new Set(
    topK(items, Math.max(1, k))
      .flatMap((item) => Array.isArray(item?.matchedAuditGroupIndexes)
        ? item.matchedAuditGroupIndexes
        : [])
      .filter((index) => Number.isSafeInteger(index) && index >= 0 && index < totalGroupCount),
  )).sort((left, right) => left - right);
  const matched = new Set(matchedGroupIndexes);
  const missingGroupIndexes = Array.from(
    { length: totalGroupCount },
    (_, index) => index,
  ).filter((index) => !matched.has(index));
  return {
    matchedGroupIndexes,
    missingGroupIndexes,
    matchedGroupCount: matchedGroupIndexes.length,
    requiredGroupCount: totalGroupCount,
    anyRequiredPresent: matchedGroupIndexes.length > 0,
    allRequiredPresent: matchedGroupIndexes.length === totalGroupCount,
  };
}

function measureStage(evalCase, items, k) {
  const topItems = topK(items, k);
  const titleTerms = terms(evalCase?.expectedTitleTerms);
  const documentTerms = terms(evalCase?.expectedDocumentTerms);
  const sectionTypes = terms(evalCase?.expectedSectionTypes);
  const parentTerms = terms(evalCase?.expectedParentTerms);
  const documentGold = uniqueTerms([...titleTerms, ...documentTerms]);
  const sectionParentGold = uniqueTerms([...sectionTypes, ...parentTerms]);
  const matchedDocumentTerms = matchedTerms(topItems, documentGold, documentText);
  const matchedSectionTypes = sectionTypes.filter((term) => topItems.some((item) => sectionMatches(item, term)));
  const matchedParentTerms = matchedTerms(topItems, parentTerms, parentText);
  const matchedSectionParentTerms = uniqueTerms([...matchedSectionTypes, ...matchedParentTerms]);
  const documentHit = documentGold.length > 0 && topItems.some((item) => (
    groupMatchesText(item, titleTerms, documentText)
      && groupMatchesText(item, documentTerms, documentText)
  ));
  const sectionParentHit = sectionParentGold.length > 0 && topItems.some((item) => (
    groupMatchesSection(item, sectionTypes)
      && groupMatchesText(item, parentTerms, parentText)
  ));
  const hasRetrievalGold = documentGold.length > 0 || sectionParentGold.length > 0;
  const directHit = hasRetrievalGold && topItems.some((item) => (
    groupMatchesText(item, titleTerms, documentText)
      && groupMatchesText(item, documentTerms, documentText)
      && groupMatchesSection(item, sectionTypes)
      && groupMatchesText(item, parentTerms, parentText)
  ));
  return {
    candidates: topItems.length,
    hasRetrievalGold,
    documentGoldCount: documentGold.length,
    documentMatchedCount: matchedDocumentTerms.length,
    documentMatchedTerms: matchedDocumentTerms,
    documentTermCoverage: ratio(matchedDocumentTerms.length, documentGold.length),
    documentHit,
    sectionParentGoldCount: sectionParentGold.length,
    sectionParentMatchedCount: matchedSectionParentTerms.length,
    sectionParentMatchedTerms: matchedSectionParentTerms,
    sectionParentTermCoverage: ratio(matchedSectionParentTerms.length, sectionParentGold.length),
    sectionParentHit,
    directHit,
  };
}

function summarizeRetrievalCases(results, k = 10) {
  const rows = results ?? [];
  const eligible = rows.filter((row) => row.recallEligible);
  const noGround = rows.filter((row) => row.noGroundExpected);
  const firstDropCounts = {};
  for (const row of eligible) {
    const label = row.firstDropStage ?? 'survived';
    firstDropCounts[label] = (firstDropCounts[label] ?? 0) + 1;
  }
  return {
    schemaVersion: 2,
    k: normalizeK(k),
    totalCases: rows.length,
    recallEligibleCases: eligible.length,
    missingRetrievalGoldCases: rows.filter((row) => row.exclusionReason === 'missing_retrieval_gold').length,
    noGroundCases: noGround.length,
    falseGround: {
      cases: noGround.length,
      falseGrounds: noGround.filter((row) => row.falseGround).length,
      rate: ratio(noGround.filter((row) => row.falseGround).length, noGround.length),
      ids: noGround.filter((row) => row.falseGround).map((row) => row.id),
    },
    stages: Object.fromEntries(STAGE_NAMES.map((stage) => [stage, summarizeStage(eligible, stage)])),
    shadowStages: Object.fromEntries(SHADOW_STAGE_NAMES.map((stage) => [
      stage,
      summarizeStage(eligible, stage),
    ])),
    stageSurvival: {
      candidateSources: survivalSummary(eligible, 'candidateSources', eligible.length),
      ...Object.fromEntries(DOWNSTREAM_STAGES.map((stage) => [
        stage,
        survivalSummary(eligible, stage, eligible.filter((row) => row.candidateEntryHit).length),
      ])),
    },
    firstDropCounts,
  };
}

function summarizeStage(rows, stage) {
  const documentRows = rows.filter((row) => row.stages[stage].documentGoldCount > 0);
  const sectionRows = rows.filter((row) => row.stages[stage].sectionParentGoldCount > 0);
  return {
    recallEligibleCases: rows.length,
    directHits: rows.filter((row) => row.stages[stage].directHit).length,
    directHitRate: ratio(rows.filter((row) => row.stages[stage].directHit).length, rows.length),
    documentGoldCases: documentRows.length,
    documentHits: documentRows.filter((row) => row.stages[stage].documentHit).length,
    documentHitRate: ratio(documentRows.filter((row) => row.stages[stage].documentHit).length, documentRows.length),
    documentTermCoverageAtK: average(documentRows.map((row) => row.stages[stage].documentTermCoverage)),
    sectionParentGoldCases: sectionRows.length,
    sectionParentHits: sectionRows.filter((row) => row.stages[stage].sectionParentHit).length,
    sectionParentHitRate: ratio(sectionRows.filter((row) => row.stages[stage].sectionParentHit).length, sectionRows.length),
    sectionParentTermCoverageAtK: average(sectionRows.map((row) => row.stages[stage].sectionParentTermCoverage)),
  };
}

function survivalSummary(rows, stage, denominator) {
  const survived = rows.filter((row) => row.stageSurvival[stage]).length;
  return { denominator, survived, rate: ratio(survived, denominator) };
}

function groupMatchesText(item, expectedTerms, textProvider) {
  return expectedTerms.length === 0 || expectedTerms.some((term) => includesTerm(textProvider(item), term));
}

function groupMatchesSection(item, expectedSectionTypes) {
  return expectedSectionTypes.length === 0 || expectedSectionTypes.some((term) => sectionMatches(item, term));
}

function sectionMatches(item, expectedSectionType) {
  const expected = normalize(expectedSectionType);
  if (!expected) {
    return false;
  }
  return normalize(item?.sectionType) === expected;
}

function matchedTerms(items, expectedTerms, textProvider) {
  const texts = items.map(textProvider);
  return expectedTerms.filter((term) => texts.some((text) => includesTerm(text, term)));
}

function includesTerm(text, term) {
  const expected = normalize(term);
  return Boolean(expected) && normalize(text).includes(expected);
}

function documentText(item) {
  return joinFields(item, ['title']);
}

function parentText(item) {
  return joinFields(item, ['parentSectionTitle', 'chunkTitle', 'sourcePath']);
}

function joinFields(item, fields) {
  return fields.map((field) => item?.[field] ?? '').join(' ');
}

function terms(values) {
  return uniqueTerms((values ?? []).map((value) => String(value).trim()).filter((value) => value && value !== '-'));
}

function uniqueTerms(values) {
  const byNormalized = new Map();
  for (const value of values ?? []) {
    const key = normalize(value);
    if (key && !byNormalized.has(key)) {
      byNormalized.set(key, value);
    }
  }
  return Array.from(byNormalized.values());
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

function candidateKey(item) {
  return item?.chunkId == null ? null : `${item?.target ?? ''}:${item.chunkId}`;
}

function topK(items, k) {
  return Array.isArray(items) ? items.slice(0, normalizeK(k)) : [];
}

function normalizeK(k) {
  const parsed = Number(k);
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : 10;
}

function normalize(value) {
  return String(value ?? '')
    .normalize('NFKC')
    .toLowerCase()
    .replace(/[\p{P}\p{S}\s]+/gu, '');
}

function ratio(numerator, denominator) {
  return denominator > 0 ? numerator / denominator : null;
}

function average(values) {
  return values.length > 0 ? values.reduce((sum, value) => sum + value, 0) / values.length : null;
}

module.exports = {
  SHADOW_STAGE_NAMES,
  STAGE_NAMES,
  measureOraclePresence,
  measureRetrievalCase,
  summarizeRetrievalCases,
};
