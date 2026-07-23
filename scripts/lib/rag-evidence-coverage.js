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
const END_TO_END_STAGES = [
  ...EVIDENCE_STAGES,
  'supportedEvidence',
  'verifiedAnswer',
];
const NOT_MEASURED = 'not_measured';

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

function extendEvidenceCoverage(evalCase, retrievalCoverage, answerResult = null) {
  const measured = answerResult != null;
  const supportedEvidence = measured
    ? (answerResult?.claimEvidenceLinks ?? [])
      .filter((link) => link?.relation === 'SUPPORTED' && typeof link.evidenceSentence === 'string')
      .map((link) => link.evidenceSentence)
    : [];
  const verifiedAnswer = measured && typeof answerResult?.verifiedAnswer === 'string'
    ? [answerResult.verifiedAnswer]
    : [];
  const propositionGroups = extendGroups(
    retrievalCoverage?.propositionGroups,
    supportedEvidence,
    verifiedAnswer,
    measured,
  );
  const conditionGroups = extendGroups(
    retrievalCoverage?.conditionGroups,
    supportedEvidence,
    verifiedAnswer,
    measured,
  );
  const missingGroups = {};
  for (const stage of END_TO_END_STAGES) {
    const proposition = propositionGroups
      .filter((group) => group.coverage[stage] === false)
      .map((group) => group.id);
    const condition = conditionGroups
      .filter((group) => group.coverage[stage] === false)
      .map((group) => group.id);
    if (proposition.length > 0 || condition.length > 0) {
      missingGroups[stage] = { proposition, condition };
    }
  }
  return {
    stages: {
      ...retrievalCoverage?.stages,
      supportedEvidence: measured
        ? { status: 'measured', items: supportedEvidence.length }
        : { status: NOT_MEASURED, items: null },
      verifiedAnswer: measured
        ? { status: 'measured', items: verifiedAnswer.length }
        : { status: NOT_MEASURED, items: null },
    },
    propositionGroups,
    conditionGroups,
    missingGroups,
  };
}

function extendGroups(groups, supportedEvidence, verifiedAnswer, measured) {
  return (groups ?? []).map((group) => {
    const answerCoverage = measured
      ? {
        supportedEvidence: supportedEvidence
          .some((sentence) => matchOracleGroup(sentence, group.aliases) !== null),
        verifiedAnswer: verifiedAnswer
          .some((answer) => matchOracleGroup(answer, group.aliases) !== null),
      }
      : {
        supportedEvidence: NOT_MEASURED,
        verifiedAnswer: NOT_MEASURED,
      };
    const coverage = { ...group.coverage, ...answerCoverage };
    return {
      ...group,
      coverage,
      firstLossStage: endToEndFirstLossStage(coverage),
    };
  });
}

function endToEndFirstLossStage(coverage) {
  for (const stage of END_TO_END_STAGES) {
    if (coverage[stage] === false) {
      return stage;
    }
    if (coverage[stage] === NOT_MEASURED) {
      return NOT_MEASURED;
    }
  }
  return 'survived';
}

function summarizeEndToEndEvidenceCoverage(results) {
  const coverageRows = (results ?? [])
    .map((result) => result?.endToEndEvidenceCoverage ?? result)
    .filter(Boolean);
  return {
    proposition: summarizeEndToEndGroups(
      coverageRows.flatMap((coverage) => coverage.propositionGroups ?? []),
      coverageRows,
    ),
    condition: summarizeEndToEndGroups(
      coverageRows.flatMap((coverage) => coverage.conditionGroups ?? []),
      coverageRows,
    ),
  };
}

function summarizeEndToEndGroups(groups, coverageRows) {
  const firstLossCounts = {};
  for (const group of groups) {
    const label = group.firstLossStage;
    firstLossCounts[label] = (firstLossCounts[label] ?? 0) + 1;
  }
  return {
    totalGroups: groups.length,
    stages: Object.fromEntries(END_TO_END_STAGES.map((stage) => {
      const status = stageStatus(coverageRows, stage);
      if (status === NOT_MEASURED) {
        return [stage, {
          status,
          coveredGroups: null,
          rate: null,
        }];
      }
      const coveredGroups = groups.filter((group) => group.coverage?.[stage] === true).length;
      return [stage, {
        status,
        coveredGroups,
        rate: ratio(coveredGroups, groups.length),
      }];
    })),
    firstLossCounts,
  };
}

function stageStatus(coverageRows, stage) {
  if (EVIDENCE_STAGES.includes(stage)) {
    return 'measured';
  }
  return coverageRows.length > 0
    && coverageRows.every((coverage) => coverage.stages?.[stage]?.status === 'measured')
    ? 'measured'
    : NOT_MEASURED;
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
  END_TO_END_STAGES,
  EVIDENCE_STAGES,
  extendEvidenceCoverage,
  measureEvidenceCoverage,
  summarizeEndToEndEvidenceCoverage,
  summarizeEvidenceCoverage,
  uniqueItems,
};
