// Offline evidence extraction only. This is not a legal-status or live release gate.
const { createHash } = require('node:crypto');

function validDate(value) {
  if (typeof value !== 'string' || !/^[12][0-9]{7}$/.test(value)) return false;
  const iso = value.slice(0,4)+'-'+value.slice(4,6)+'-'+value.slice(6,8);
  const date = new Date(iso+'T00:00:00Z');
  return Number.isFinite(date.valueOf()) && date.toISOString().slice(0,10) === iso;
}

function proveGeneralEffectivePhase(html, request) {
  if (typeof html !== 'string' || html.length > 200000 || !html.trimEnd().endsWith('</html>')
      || !request || request.scope !== 'general-phase'
      || !['effectiveFrom','asOf','observedAt'].every(key => validDate(request[key]))
      || request.asOf > request.observedAt
      || !['lawId','edition','number'].every(key => typeof request[key] === 'string' && /^[0-9]+$/.test(request[key]))
      || !validDate(request.promulgation)) return null;
  let url;
  try { url = new URL(request.sourceUrl); } catch { return null; }
  if (url.protocol !== 'https:' || url.hostname !== 'www.law.go.kr' || url.port || url.username || url.password
      || url.pathname !== '/LSW/lsHstListR.do' || url.searchParams.get('lsId') !== request.lawId
      || url.searchParams.get('lsiSeq') !== request.edition || url.searchParams.get('ancYnChk') !== '0'
      || ['lsId','lsiSeq','ancYnChk'].some(key => url.searchParams.getAll(key).length !== 1)) return null;
  // Only the public Korean effective-history rows; ignore translation history and the CURRENT marker.
  const row = /lsViewLsHst2\('([0-9]+)',\s*'([0-9]{8})',\s*'([0-9]+)',\s*'([0-9]{8})',\s*'[YN]',\s*'0'\)/g;
  const rows = [...html.matchAll(row)].map(match => ({
    edition: match[1], promulgation: match[2], number: match[3], effectiveFrom: match[4],
  }));
  if (rows.length < 2 || rows.length > 500 || [...html.matchAll(/lsViewLsHst2\(/g)].length !== rows.length
      || rows.some(item => !validDate(item.promulgation) || !validDate(item.effectiveFrom))) return null;
  rows.sort((a,b) => a.effectiveFrom.localeCompare(b.effectiveFrom));
  if (new Set(rows.map(item => item.effectiveFrom)).size !== rows.length) return null;
  const index = rows.findIndex(item => item.edition === request.edition && item.promulgation === request.promulgation
    && item.number === request.number && item.effectiveFrom === request.effectiveFrom);
  const current = rows[index], next = rows[index+1];
  // An open last phase is not a proven interval. Do not infer absence of a later amendment.
  if (!current || !next || request.asOf < current.effectiveFrom || request.asOf >= next.effectiveFrom) return null;
  return { ...current, lawId: request.lawId, effectiveUntilExclusive: next.effectiveFrom, asOf: request.asOf,
    observedAt: request.observedAt, scope: request.scope, sourceUrl: url.href,
    sourceSha256: createHash('sha256').update(html).digest('hex'),
    limitation: 'General effective-history phase only; delayed provisions require their own commencement proof.' };
}

// Join a captured, existing Java commencement receipt with its original detail.
// This is evidence preparation, not a replacement for live server verification.
function bindGeneralPhaseToCommencement(html, request, source, receipt) {
  const phase = proveGeneralEffectivePhase(html, request);
  if (!phase || !source || !receipt || source.target !== 'law'
      || !['documentId','chunkId','chunkVersion'].every(key => Number.isSafeInteger(source[key]) && source[key] > 0)
      || !['target','documentId','chunkId','chunkVersion','edition'].every(key => source[key] === receipt[key])
      || source.edition !== phase.edition || receipt.effectiveFrom !== phase.effectiveFrom
      || typeof source.rawJson !== 'string' || source.rawJson.length > 2097152
      || createHash('sha256').update(source.rawJson).digest('hex') !== receipt.sourceSha256
      || typeof receipt.clause !== 'string' || !receipt.clause.trim()) return null;
  let law;
  try { law = JSON.parse(source.rawJson)?.법령; } catch { return null; }
  const basic = law?.기본정보;
  if (!basic || basic.법령ID !== phase.lawId || basic.공포번호 !== phase.number
      || basic.공포일자 !== phase.promulgation || basic.시행일자 !== phase.effectiveFrom) return null;
  const units = law?.부칙?.부칙단위;
  if (!Array.isArray(units) || units.length > 1000) return null;
  const owners = units.map((unit,index) => ({unit,index})).filter(({unit}) =>
    unit?.부칙공포번호 === phase.number && unit?.부칙공포일자 === phase.promulgation);
  if (owners.length !== 1) return null;
  const {unit,index} = owners[0];
  if (receipt.sourcePath !== `$.법령.부칙.부칙단위[${index}]` || !Array.isArray(unit.부칙내용)) return null;
  const lines = unit.부칙내용.flat(1);
  if (lines.some(line => typeof line !== 'string')) return null;
  const clauses = lines.map(line => line.trim()).filter(line => /^제[0-9]+조\s*\(시행일\)\s*\S/u.test(line));
  // Preserve every exception in the original clause; do not use a shortened quote.
  if (clauses.length !== 1 || receipt.clause !== clauses[0]) return null;
  const evidence = { scope:'general-phase', phase,
    owner:{target:source.target,documentId:source.documentId,chunkId:source.chunkId,
      chunkVersion:source.chunkVersion,edition:source.edition},
    commencement:{effectiveFrom:receipt.effectiveFrom,clause:receipt.clause,
      sourcePath:receipt.sourcePath,sourceSha256:receipt.sourceSha256} };
  return {...evidence,evidenceSha256:createHash('sha256').update(JSON.stringify(evidence)).digest('hex')};
}

module.exports = { proveGeneralEffectivePhase, bindGeneralPhaseToCommencement };
