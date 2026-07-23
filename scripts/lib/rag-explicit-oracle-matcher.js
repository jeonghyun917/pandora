const GRAMMATICAL_NEGATIONS = ['안됩니다', '안된다', '안됨', '아닙', '아니', '않', '없', '불가'];
const LOCAL_POLARITY_BRIDGES = [
  '이라고는', '라고는', '이라고', '라는', '라고', '다고',
  '한다면', '하면', '할경우', '하는경우', '한경우', '했을경우',
  '단정해서는', '단정할수', '말할수', '볼수', '할수', '해서는',
  '이라는것은', '라는것은', '인것은', '것은',
  '반드시', '절대', '전혀',
  '으로', '은', '는', '이', '가', '도', '만', '지',
];
const SUPERSEDED_BY_FINAL_ASSERTION = /(^|[.!?\r\n])\s*[^.!?\r\n]*?(?:하지만|그러나|반면|지만|으나)[,:;\s]*(?=(?:실제로는|실제로|오히려|사실은|사실상|결론적으로|결국|정리하면|요컨대))/gu;
const PARTICLES = ['으로', '에서', '에게', '까지', '부터', '하고', '하며', '은', '는', '이', '가', '을', '를', '에', '의', '와', '과'];

function matchOracleGroup(text, aliases) {
  for (const alias of aliases ?? []) {
    if (matchesExplicitOracleTerm(text, alias)) {
      return alias;
    }
  }
  return null;
}

function matchesExplicitOracleTerm(text, expectedExpression) {
  const expected = normalize(expectedExpression);
  if (!expected) {
    return false;
  }
  const tokens = materialTokens(expectedExpression);
  return clauses(text).some((clause) => {
    const normalizedClause = normalize(clause);
    return normalizedClause
      && (normalizedClause.includes(expected) || tokens.every((token) => normalizedClause.includes(token)))
      && hasCompatiblePolarity(normalizedClause, expected, tokens);
  });
}

function clauses(text) {
  return String(text ?? '')
    .replace(SUPERSEDED_BY_FINAL_ASSERTION, '$1')
    .split(/[!?;\r\n]+|(?<![0-9])\.\s*(?=[^0-9\s]|$)/u)
    .map((value) => value.trim())
    .filter(Boolean);
}

function materialTokens(expression) {
  return String(expression ?? '')
    .split(/[^\p{L}\p{N}]+/u)
    .map((token) => stripTrailingParticle(normalize(token)))
    .filter((token) => token.length >= 2)
    .filter((token) => !GRAMMATICAL_NEGATIONS.some((negation) => token.startsWith(negation)));
}

function hasCompatiblePolarity(clause, expected, tokens) {
  const expectedNegation = firstNegationIndex(expected);
  const core = expectedNegation < 0 ? expected : expected.slice(0, expectedNegation);
  const coreIndex = clause.indexOf(core);
  if (coreIndex < 0) {
    const anchor = tokens.at(-1);
    const anchorIndex = anchor ? clause.lastIndexOf(anchor) : -1;
    return expectedNegation < 0
      && anchorIndex >= 0
      && negationParity(clause.slice(anchorIndex + anchor.length)) === 0;
  }
  return negationParity(expected.slice(core.length)) === negationParity(clause.slice(coreIndex + core.length));
}

function firstNegationIndex(value) {
  for (let index = 0; index < value.length; index += 1) {
    if (leadingNegation(value.slice(index))) {
      return index;
    }
  }
  return -1;
}

function negationParity(value) {
  let remaining = value;
  let count = 0;
  while (remaining) {
    const negation = leadingNegation(remaining);
    if (negation) {
      count += 1;
      remaining = remaining.slice(negation.length);
      continue;
    }
    const bridge = leadingBridge(remaining);
    if (!bridge) {
      break;
    }
    remaining = remaining.slice(bridge.length);
  }
  return count % 2;
}

function leadingNegation(value) {
  const negation = GRAMMATICAL_NEGATIONS.find((candidate) => value.startsWith(candidate));
  if (negation) {
    return negation;
  }
  return /^안(?:되|돼|됩|됨|하|할|합|된|될|함|있)/u.test(value) ? '안' : null;
}

function leadingBridge(value) {
  const bridge = LOCAL_POLARITY_BRIDGES.find((candidate) => value.startsWith(candidate));
  if (bridge) {
    return bridge;
  }
  return /^(?:했을|할|하는|인|있을)?경우/u.exec(value)?.[0] ?? null;
}

function stripTrailingParticle(value) {
  const particle = PARTICLES.find((candidate) => (
    value.endsWith(candidate) && value.length > candidate.length + 1
  ));
  return particle ? value.slice(0, -particle.length) : value;
}

function normalize(value) {
  return String(value ?? '')
    .normalize('NFKC')
    .toLowerCase()
    .replace(/[^\p{L}\p{N}]+/gu, '');
}

function parseJavaListConstant(source, name) {
  const constant = String(name ?? '');
  const match = String(source ?? '').match(new RegExp(
    `\\b${escapeRegExp(constant)}\\s*=\\s*List\\.of\\(([\\s\\S]*?)\\);`,
  ));
  if (!match) {
    throw new Error(`missing Java List.of constant: ${constant}`);
  }
  const block = match[1];
  const values = [];
  let index = 0;
  while (index < block.length) {
    index = skipSeparators(block, index);
    if (index >= block.length) {
      break;
    }
    if (block[index] !== '"') {
      throw new Error(`invalid Java string list entry in ${constant}`);
    }
    const parsed = parseJavaString(block, index + 1, constant);
    values.push(parsed.value);
    index = skipWhitespace(block, parsed.index);
    if (index < block.length && block[index] !== ',') {
      throw new Error(`missing comma in Java string list: ${constant}`);
    }
    if (block[index] === ',') {
      index += 1;
    }
  }
  return values;
}

function parseJavaString(value, start, constant) {
  let decoded = '';
  let index = start;
  while (index < value.length) {
    const character = value[index];
    if (character === '"') {
      return { value: decoded, index: index + 1 };
    }
    if (character !== '\\') {
      decoded += character;
      index += 1;
      continue;
    }
    index += 1;
    const escape = value[index];
    const simpleEscapes = {
      b: '\b', f: '\f', n: '\n', r: '\r', t: '\t', '"': '"', "'": "'", '\\': '\\',
    };
    if (Object.hasOwn(simpleEscapes, escape)) {
      decoded += simpleEscapes[escape];
      index += 1;
      continue;
    }
    if (escape === 'u') {
      while (value[index] === 'u') {
        index += 1;
      }
      const hex = value.slice(index, index + 4);
      if (!/^[0-9a-f]{4}$/iu.test(hex)) {
        throw new Error(`invalid Unicode escape in Java string list: ${constant}`);
      }
      decoded += String.fromCharCode(Number.parseInt(hex, 16));
      index += 4;
      continue;
    }
    throw new Error(`unsupported Java string escape in ${constant}`);
  }
  throw new Error(`unterminated Java string list entry in ${constant}`);
}

function skipSeparators(value, index) {
  let next = skipWhitespace(value, index);
  while (value[next] === ',') {
    next = skipWhitespace(value, next + 1);
  }
  return next;
}

function skipWhitespace(value, index) {
  let next = index;
  while (/\s/u.test(value[next] ?? '')) {
    next += 1;
  }
  return next;
}

function escapeRegExp(value) {
  return String(value).replace(/[.*+?^${}()|[\]\\]/gu, '\\$&');
}

module.exports = {
  GRAMMATICAL_NEGATIONS,
  LOCAL_POLARITY_BRIDGES,
  matchOracleGroup,
  matchesExplicitOracleTerm,
  parseJavaListConstant,
};
