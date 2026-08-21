const fs = require('fs');

const CANONICAL_ANSWER_ORACLE_IDS = new Set([
  'project-review-target',
  'project-review-simple-software',
  'project-review-hardware-exclusion',
  'project-review-sns-operation',
  'project-review-pre-consultation-relation',
  'pre-consultation-target',
  'pre-consultation-when',
  'pre-consultation-exception',
  'security-review-target',
  'security-review-exception',
  'security-review-procedure',
  'it-compliance-penalty',
  'egov-preliminary-review-target',
  'rfp-required-items',
  'rfp-tech-score-table',
  'public-data-db-standard',
  'procurement-catalog-contract',
  'commercial-sw-direct-purchase',
  'performance-measure-when',
  'irm-faithfulness',
  'whistleblower-protection-scope',
  'traffic-crosswalk-stop',
  'video-cctv-guide',
  'personal-info-purpose',
  'official-doc-title',
  'noise-unification-white-paper-header',
  'privacy-integrated-guide-purpose',
  'privacy-consent-notice-items',
  'public-data-custom-support',
  'public-data-preprocessing',
  'mois-autonomy-preconsultation-target',
  'mois-autonomy-preconsultation-procedure',
  'mcst-tourism-dure-support',
  'pipc-cctv-public-place-exception',
  'pipc-cctv-retention-period',
  'pipc-pseudonym-additional-info',
  'public-data-portal-standard-scope',
  'mcst-tourism-dure-period',
  'msit-tving-investigation',
  'project-review-all-sw-projects',
  'project-review-exclusion-hardware',
  'pre-consultation-public-agency',
  'pre-consultation-plan-stage',
  'security-review-sensitive-info',
  'security-review-notice-result',
  'rfp-requirement-evaluation',
  'commercial-sw-direct-buy-exception',
  'procurement-digital-service-mall',
  'privacy-consent-refusal',
  'cctv-public-place-rule',
  'cctv-retention-not-fixed-30',
  'ai-law-enforcement-date',
  'traffic-right-turn-pedestrian',
  'whistleblower-disadvantage',
  'irm-faithfulness-meaning',
  'mois-autonomy-document-confusion',
  'project-review-maintenance-check',
  'project-review-scope-change',
  'pre-consultation-central-agency',
  'pre-consultation-excluded-project',
  'security-review-major-infra',
  'security-review-skip-condition',
  'rfp-requirement-method',
  'commercial-sw-direct-buy-target',
  'procurement-catalog-vs-contract',
  'public-data-portal-manual-application',
  'privacy-consent-items-law',
  'privacy-processing-principle',
  'pseudonym-extra-info-separate',
  'traffic-right-turn-stop-rule',
  'whistleblower-protection-action',
  'irm-measure-period',
  'mois-autonomy-request-docs',
  'privacy-retention-notice',
  'privacy-minimum-collection',
  'privacy-destruction-principle',
  'cctv-install-purpose-limit',
  'cctv-retention-period',
  'public-data-open-format',
  'public-data-meta-management',
  'mois-national-safety-plan',
  'law-effective-date-check',
  'admrul-notice-exception',
  'no-unrelated-privacy-for-sw',
  'public-data-obligation-system',
  'contract-completion-before-period',
  'contract-completion-before-period-paraphrase',
  'contract-completion-actual-finished',
  'contract-completion-work-remaining-control',
]);

function parseEvalCasesTsv(text, source = 'evaluation TSV') {
  const cases = [];
  for (const row of parseTsvRows(String(text ?? '').replace(/^\uFEFF/, ''), source)) {
    const columns = row.columns;
    const first = String(columns[0] ?? '').trim();
    const blank = columns.every((column) => !String(column ?? '').trim());
    if (blank || first.startsWith('#') || first.toLowerCase() === 'id') {
      continue;
    }
    if (!first) {
      throw new Error(`${source} line ${row.line}: empty ID`);
    }
    if (columns.length < 8) {
      throw new Error(`${source} line ${row.line}: expected at least 8 columns, got ${columns.length}`);
    }
    cases.push(toEvalCase(columns));
  }
  return cases;
}

function parseAnswerOraclesTsv(text, source = 'answer oracle TSV') {
  const oracles = [];
  const ids = new Set();
  const normalizedText = String(text ?? '').replace(/^\uFEFF/, '');
  if (normalizedText.includes('"')) {
    throw new Error(`${source}: quoted fields are not supported`);
  }
  for (const row of parseTsvRows(normalizedText, source)) {
    const columns = row.columns;
    const first = String(columns[0] ?? '').trim();
    const blank = columns.every((column) => !String(column ?? '').trim());
    if (blank || first.startsWith('#') || first.toLowerCase() === 'id') {
      continue;
    }
    if (!first) {
      throw new Error(`${source} line ${row.line}: empty oracle ID`);
    }
    if (columns.length !== 4) {
      throw new Error(`${source} line ${row.line}: expected exactly 4 columns, got ${columns.length}`);
    }
    if (ids.has(first)) {
      throw new Error(`duplicate oracle ID "${first}" in ${source}`);
    }
    ids.add(first);
    oracles.push({
      id: first,
      requiredPropositionGroups: parseOracleGroups(columns[1], first, 'proposition', false),
      requiredConditionGroups: parseOracleGroups(columns[2], first, 'condition', true),
      forbiddenAnswerExpressions: parseForbiddenAnswerExpressions(columns[3], first),
    });
  }
  return oracles;
}

function parseTsvRows(text, source) {
  const rows = [];
  let row = [];
  let field = '';
  let quoted = false;
  let line = 1;
  let rowLine = 1;
  let quoteLine = null;

  const pushField = () => {
    row.push(field);
    field = '';
  };
  const pushRow = () => {
    pushField();
    rows.push({ columns: row, line: rowLine });
    row = [];
  };

  for (let index = 0; index < text.length; index += 1) {
    const character = text[index];
    if (quoted) {
      if (character === '"' && text[index + 1] === '"') {
        field += '"';
        index += 1;
      } else if (character === '"') {
        quoted = false;
      } else {
        field += character;
        if (character === '\n') {
          line += 1;
        }
      }
      continue;
    }
    if (character === '"' && field.length === 0) {
      quoted = true;
      quoteLine = line;
    } else if (character === '\t') {
      pushField();
    } else if (character === '\n') {
      pushRow();
      line += 1;
      rowLine = line;
    } else if (character === '\r') {
      if (text[index + 1] === '\n') {
        index += 1;
      }
      pushRow();
      line += 1;
      rowLine = line;
    } else {
      field += character;
    }
  }
  if (quoted) {
    throw new Error(`${source} unclosed quoted field starting at line ${quoteLine}`);
  }
  if (field.length > 0 || row.length > 0) {
    pushRow();
  }
  return rows;
}

function toEvalCase(columns) {
  const id = String(columns[0] ?? '').trim();
  return {
    id,
    question: String(columns[1] ?? '').trim(),
    targets: splitList(columns[2]),
    expectedTerms: splitList(columns[3]),
    requiredMatches: parseRequiredMatches(columns[4]),
    expectedTitleTerms: splitList(columns[5]),
    expectedSectionTypes: splitList(columns[6]),
    forbiddenTerms: splitList(columns[7]),
    expectedDocumentTerms: splitList(columns[8]),
    expectedPageNumbers: splitList(columns[9]),
    expectedParentTerms: splitList(columns[10]),
    answerDirection: String(columns[11] ?? '').trim(),
    expectedResultMsgs: expectedResultMsgs(id, columns[12]),
    answerVerificationRequired: parseOptionalBoolean(columns[13]),
    expectedAnswerTerms: splitList(columns[14]),
    forbiddenAnswerTerms: splitList(columns[15]),
    failureTaxonomy: String(columns[16] ?? '').trim(),
    requiredPropositionGroups: [],
    requiredConditionGroups: [],
  };
}

function loadEvalCases(filePaths, options = {}) {
  const byId = new Map();
  for (const filePath of filePaths ?? []) {
    if (!fs.existsSync(filePath)) {
      continue;
    }
    for (const row of parseEvalCasesTsv(fs.readFileSync(filePath, 'utf8'), filePath)) {
      const previous = byId.get(row.id);
      if (previous) {
        throw new Error(`duplicate evaluation case ID "${row.id}" in ${filePath} (already defined in ${previous.filePath})`);
      }
      byId.set(row.id, { evalCase: row, filePath });
    }
  }
  const cases = Array.from(byId.values(), ({ evalCase }) => evalCase);
  if (!options.answerOraclePath) {
    return cases;
  }
  if (!fs.existsSync(options.answerOraclePath)) {
    throw new Error(`missing answer oracle TSV: ${options.answerOraclePath}`);
  }
  const oracles = parseAnswerOraclesTsv(
    fs.readFileSync(options.answerOraclePath, 'utf8'),
    options.answerOraclePath,
  );
  return mergeAnswerOracles(
    cases,
    oracles,
    options.requiredOracleIds ?? CANONICAL_ANSWER_ORACLE_IDS,
  );
}

function mergeAnswerOracles(cases, oracles, requiredOracleIds = CANONICAL_ANSWER_ORACLE_IDS) {
  const byId = new Map((cases ?? []).map((evalCase) => [evalCase.id, evalCase]));
  const oracleById = new Map();
  for (const oracle of oracles ?? []) {
    if (oracleById.has(oracle.id)) {
      throw new Error(`duplicate oracle ID "${oracle.id}"`);
    }
    if (!byId.has(oracle.id)) {
      throw new Error(`orphan oracle ID "${oracle.id}"`);
    }
    oracleById.set(oracle.id, oracle);
  }
  const required = new Set(requiredOracleIds ?? []);
  const missing = Array.from(required).filter((id) => !oracleById.has(id));
  if (missing.length > 0) {
    throw new Error(`missing oracle IDs: ${missing.join(', ')}`);
  }
  const unexpected = Array.from(oracleById.keys()).filter((id) => !required.has(id));
  if (unexpected.length > 0) {
    throw new Error(`unexpected oracle IDs: ${unexpected.join(', ')}`);
  }
  if (oracleById.size !== required.size) {
    throw new Error(`bundled answer oracle count must be ${required.size}, got ${oracleById.size}`);
  }
  return (cases ?? []).map((evalCase) => {
    const oracle = oracleById.get(evalCase.id);
    return oracle ? {
      ...evalCase,
      answerVerificationRequired: true,
      forbiddenAnswerTerms: [...oracle.forbiddenAnswerExpressions],
      requiredPropositionGroups: oracle.requiredPropositionGroups.map((group) => [...group]),
      requiredConditionGroups: oracle.requiredConditionGroups.map((group) => [...group]),
    } : evalCase;
  });
}

function parseOracleGroups(value, id, kind, allowNone) {
  const text = String(value ?? '').trim();
  if (allowNone && text === '-') {
    return [];
  }
  if (!text || text === '-') {
    throw new Error(`malformed ${kind} groups for oracle ID "${id}"`);
  }
  return text.split(';').map((rawGroup) => {
    if (!rawGroup.trim() || rawGroup.trim() === '-') {
      throw new Error(`malformed ${kind} groups for oracle ID "${id}"`);
    }
    const aliases = rawGroup.split('|').map((alias) => alias.trim());
    if (aliases.some((alias) => !alias)) {
      throw new Error(`malformed ${kind} group for oracle ID "${id}"`);
    }
    return aliases;
  });
}

function parseForbiddenAnswerExpressions(value, id) {
  const text = String(value ?? '').trim();
  if (!text || text === '-') {
    throw new Error(`missing forbidden answer expression for oracle ID "${id}"`);
  }
  const expressions = text.split('|').map((expression) => expression.trim());
  if (expressions.some((expression) => !expression)) {
    throw new Error(`malformed forbidden answer expression for oracle ID "${id}"`);
  }
  return expressions;
}

function selectEvalCases(cases, { caseIds = [], caseLimit = 0 } = {}) {
  let selected = cases ?? [];
  if (caseIds.length > 0) {
    const available = new Set(selected.map((item) => item.id));
    const missing = uniqueStrings(caseIds.filter((id) => !available.has(id)));
    if (missing.length > 0) {
      throw new Error(`unknown evaluation case IDs: ${missing.join(', ')}`);
    }
    const allowed = new Set(caseIds);
    selected = selected.filter((item) => allowed.has(item.id));
  }
  const limit = Number(caseLimit);
  if (Number.isSafeInteger(limit) && limit > 0) {
    selected = selected.slice(0, limit);
  }
  return selected;
}

function uniqueStrings(values) {
  return Array.from(new Set(values.map((value) => String(value))));
}

function splitList(value) {
  if (!value || String(value).trim() === '-') {
    return [];
  }
  return String(value).split('|').map((term) => term.trim()).filter(Boolean);
}

function splitCaseIds(value) {
  if (!value || String(value).trim() === '-') {
    return [];
  }
  return String(value).split(/[|,]/).map((term) => term.trim()).filter(Boolean);
}

function parseRequiredMatches(value) {
  if (!value || !String(value).trim()) {
    return null;
  }
  const parsed = Number.parseInt(String(value).trim(), 10);
  return Number.isFinite(parsed) ? Math.max(0, parsed) : null;
}

function parseOptionalBoolean(value) {
  if (!value || !String(value).trim() || String(value).trim() === '-') {
    return null;
  }
  const normalized = String(value).trim().toLowerCase();
  if (['true', '1', 'yes', 'y'].includes(normalized)) {
    return true;
  }
  if (['false', '0', 'no', 'n'].includes(normalized)) {
    return false;
  }
  return null;
}

function expectedResultMsgs(id, value) {
  const explicit = splitList(value);
  if (explicit.length > 0) {
    return explicit;
  }
  return String(id ?? '').trim().startsWith('no-') ? ['NO_GROUNDS'] : [];
}

function releaseCoverage(cases, options = {}) {
  const minimumNoGround = Number(options.minimumNoGround ?? 30);
  const rows = cases ?? [];
  const risks = [];
  const noGround = rows.filter((item) => item.expectedResultMsgs?.includes('NO_GROUNDS'));
  for (const item of noGround) {
    if ((item.forbiddenTerms?.length ?? 0) === 0) {
      risks.push({ code: 'NO_GROUNDS_DISTRACTOR_MISSING', id: item.id });
    }
  }
  for (const item of rows.filter((row) => row.answerVerificationRequired === true)) {
    if ((item.requiredPropositionGroups?.length ?? 0) === 0) {
      risks.push({ code: 'ANSWER_PROPOSITION_MISSING', id: item.id });
    }
  }
  const combinations = new Map();
  for (const item of rows) {
    const question = normalizeCoverageText(item.question);
    const oracle = JSON.stringify(coverageOracle(item));
    const key = `${question}\u0000${oracle}`;
    const previous = combinations.get(key);
    if (question && previous) {
      risks.push({ code: 'DUPLICATE_QUESTION_ORACLE', id: item.id, duplicateOf: previous });
    } else if (question) {
      combinations.set(key, item.id);
    }
  }
  for (const item of rows.filter((row) => String(row.id ?? '').startsWith('reviewed-failure-'))) {
    const taxonomy = String(item.failureTaxonomy ?? '').trim();
    if (!taxonomy || taxonomy === '기타' || taxonomy.toLowerCase() === 'other') {
      risks.push({ code: 'GENERIC_FAILURE_TAXONOMY', id: item.id });
    }
  }
  if (noGround.length < minimumNoGround) {
    risks.push({
      code: 'NO_GROUNDS_MINIMUM',
      actual: noGround.length,
      required: minimumNoGround,
    });
  }
  return {
    releaseTotal: rows.length,
    curatedTotal: rows.filter((item) => !String(item.id ?? '').startsWith('gen-')).length,
    generatedTotal: rows.filter((item) => String(item.id ?? '').startsWith('gen-')).length,
    answerOracleTotal: rows.filter((item) => item.answerVerificationRequired === true).length,
    noGroundTotal: noGround.length,
    explicitConditionTotal: rows.filter((item) => (item.requiredConditionGroups?.length ?? 0) > 0).length,
    failureTaxonomyCounts: countStrings(rows
      .map((item) => String(item.failureTaxonomy ?? '').trim())
      .filter(Boolean)),
    risks,
    passed: risks.length === 0,
  };
}

function assertReleaseCoverage(cases, options = {}) {
  const coverage = releaseCoverage(cases, options);
  if (!coverage.passed) {
    const details = coverage.risks.map((risk) => {
      if (risk.code === 'NO_GROUNDS_MINIMUM') {
        return `${risk.code}:${risk.actual}/${risk.required}`;
      }
      return `${risk.code}:${risk.id ?? 'release'}`;
    });
    throw new Error(`RAG release coverage rejected: ${details.join(', ')}`);
  }
  return coverage;
}

function normalizeCoverageText(value) {
  return String(value ?? '')
    .normalize('NFKC')
    .toLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, '');
}

function coverageOracle(item) {
  return {
    targets: normalizeCoverageList(item.targets),
    expectedTerms: normalizeCoverageList(item.expectedTerms),
    requiredMatches: item.requiredMatches ?? null,
    expectedTitleTerms: normalizeCoverageList(item.expectedTitleTerms),
    expectedSectionTypes: normalizeCoverageList(item.expectedSectionTypes),
    forbiddenTerms: normalizeCoverageList(item.forbiddenTerms),
    expectedDocumentTerms: normalizeCoverageList(item.expectedDocumentTerms),
    expectedPageNumbers: normalizeCoverageList(item.expectedPageNumbers),
    expectedParentTerms: normalizeCoverageList(item.expectedParentTerms),
    expectedResultMsgs: normalizeCoverageList(item.expectedResultMsgs),
    propositions: normalizeCoverageGroups(item.requiredPropositionGroups),
    conditions: normalizeCoverageGroups(item.requiredConditionGroups),
    expectedAnswerTerms: normalizeCoverageList(item.expectedAnswerTerms),
    forbiddenAnswerTerms: normalizeCoverageList(item.forbiddenAnswerTerms),
  };
}

function normalizeCoverageList(values) {
  return (values ?? [])
    .map(normalizeCoverageText)
    .filter(Boolean)
    .sort();
}

function normalizeCoverageGroups(groups) {
  return (groups ?? [])
    .map((group) => normalizeCoverageList(group).join('|'))
    .filter(Boolean)
    .sort();
}

function countStrings(values) {
  const counts = {};
  for (const value of values) {
    counts[value] = (counts[value] ?? 0) + 1;
  }
  return counts;
}

module.exports = {
  assertReleaseCoverage,
  loadEvalCases,
  mergeAnswerOracles,
  parseAnswerOraclesTsv,
  parseEvalCasesTsv,
  selectEvalCases,
  releaseCoverage,
  splitCaseIds,
};
