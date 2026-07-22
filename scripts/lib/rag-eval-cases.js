const fs = require('fs');

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
  };
}

function loadEvalCases(filePaths) {
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
  return Array.from(byId.values(), ({ evalCase }) => evalCase);
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

module.exports = {
  loadEvalCases,
  parseEvalCasesTsv,
  selectEvalCases,
  splitCaseIds,
};
