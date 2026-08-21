function summarize(name, rows) {
  const failedRows = rows.filter((row) => row.passed !== true);
  return {
    total: rows.length,
    passed: rows.length - failedRows.length,
    failed: failedRows.length,
    passRate: rows.length === 0 ? 0 : (rows.length - failedRows.length) / rows.length,
    gatePassed: rows.length > 0 && failedRows.length === 0,
    blockingFailureIds: failedRows.map((row) => row.id),
  };
}

function buildBlockingGates(results = []) {
  const curated = results.filter((row) => !String(row.id ?? '').startsWith('gen-'));
  const answerOracle = results.filter((row) => row.answerVerificationRequired === true);
  const noGrounds = results.filter((row) =>
    (row.expectedResultMsgs ?? []).includes('NO_GROUNDS')
      || String(row.id ?? '').startsWith('no-'));
  return {
    curated: summarize('curated', curated),
    answerOracle: summarize('answerOracle', answerOracle),
    noGrounds: summarize('noGrounds', noGrounds),
  };
}

module.exports = { buildBlockingGates };
