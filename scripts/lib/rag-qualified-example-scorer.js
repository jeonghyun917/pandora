// Offline evidence binding only. This is not a release scorer.
const { isDeepStrictEqual } = require('node:util');

function uniquelyAttributed(result, claim, owner) {
  if (typeof claim !== 'string' || !claim.trim() || !owner
      || !Array.isArray(result?.claimEvidenceLinks) || !Array.isArray(result?.attributedClaims)) {
    return false;
  }
  const links = result.claimEvidenceLinks.filter(link => link?.claim === claim);
  const attributions = result.attributedClaims.filter(attribution => attribution?.link?.claim === claim);
  if (links.length !== 1 || attributions.length !== 1) return false;
  const [link] = links;
  const [attribution] = attributions;
  return link.relation === 'SUPPORTED'
    && Number.isSafeInteger(link.groundNumber) && link.groundNumber > 0
    && typeof link.evidenceSentence === 'string' && link.evidenceSentence.trim().length > 0
    && isDeepStrictEqual(link, attribution.link)
    && typeof owner.target === 'string' && owner.target.length > 0
    && Number.isSafeInteger(owner.documentId) && owner.documentId > 0
    && Number.isSafeInteger(owner.chunkId) && owner.chunkId > 0
    && attribution.target === owner.target
    && attribution.documentId === owner.documentId
    && attribution.chunkId === owner.chunkId;
}

const roles = ['projectScope', 'trigger', 'actor', 'action', 'modality'];
const fold = (value) => typeof value === 'string' ? value.replace(/\s+/g, ' ').trim() : '';

// Candidate for source-literal conditional examples. The envelope must come
// from full server verification, not from a model or a truncated gate report.
function scoreConditionalExamples(report, reviewedRules) {
  if (report?.aligned !== true || report.insufficientEvidence !== false
      || report.unsafeSemanticShadowDisagreementCount !== 0
      || !['unsupportedAnswerClaims', 'unsupportedNumericClaims', 'contradictedAnswerClaims']
        .every(key => Array.isArray(report[key]) && report[key].length === 0)
      || !Array.isArray(report.claimEvidenceLinks) || report.claimEvidenceLinks.length === 0
      || !Array.isArray(report.attributedClaims) || report.attributedClaims.length !== report.claimEvidenceLinks.length
      || !Array.isArray(reviewedRules) || reviewedRules.length === 0) return false;
  const claims = report.claimEvidenceLinks.map(link => fold(link?.claim));
  if (claims.some(claim => !claim) || fold(report.verifiedAnswer) !== claims.join(' ')) return false;
  return report.claimEvidenceLinks.every((link) => reviewedRules.some((rule) => {
    const source = fold(link.evidenceSentence);
    // A literal match prevents predicate or actor grafting across sentences.
    // The reviewed actor must also own the head of the candidate relation.
    return source === fold(link.claim)
      && uniquelyAttributed(report, link.claim, rule?.owner)
      && roles.every(role => fold(rule?.anchors?.[role]) && source.includes(fold(rule.anchors[role])))
      && source.startsWith(fold(rule.anchors.actor));
  }));
}

function scoreQualifiedConditionalExample(report, contract) {
  const scope = contract?.qualification;
  const remedy = contract?.remedy;
  const scopeOwner = scope?.owner;
  const remedyOwner = remedy?.owner;
  if (!scopeOwner || !remedyOwner || scopeOwner.target !== remedyOwner.target
      || scopeOwner.documentId !== remedyOwner.documentId || scopeOwner.chunkId === remedyOwner.chunkId
      || !fold(scope.literal) || !fold(scope.heading)
      || !Array.isArray(report?.claimEvidenceLinks) || report.claimEvidenceLinks.length !== 2
      || !Array.isArray(report.attributedClaims) || report.attributedClaims.length !== 2) return false;
  const [qualification, operative] = report.claimEvidenceLinks;
  if (!qualification || !operative || qualification.groundNumber === operative.groundNumber
      || fold(report.verifiedAnswer) !== [qualification.claim, operative.claim].map(fold).join(' ')
      || fold(qualification.claim) !== fold(scope.literal)
      || ![fold(scope.literal), fold(scope.heading + ' ' + scope.literal)].includes(fold(qualification.evidenceSentence))
      || !uniquelyAttributed(report, qualification.claim, scopeOwner)
      || !uniquelyAttributed(report, operative.claim, remedyOwner)) return false;
  return scoreConditionalExamples({
    ...report, verifiedAnswer: operative.claim, claimEvidenceLinks: [operative],
    attributedClaims: report.attributedClaims.filter(item => item.link.claim === operative.claim),
  }, [remedy]);
}

function scoreAssumedConsultation(report, contract) {
  const scope = contract?.scope;
  const remedy = contract?.remedy;
  if (!scope?.owner || !remedy?.owner || scope.owner.target !== remedy.owner.target
      || scope.owner.documentId !== remedy.owner.documentId || scope.owner.chunkId === remedy.owner.chunkId
      || !Array.isArray(scope.clauses) || scope.clauses.length !== 3
      || scope.clauses.some(value => !fold(value)) || new Set(scope.clauses.map(fold)).size !== 3
      || !fold(remedy.literal) || report?.aligned !== true || report.insufficientEvidence !== false
      || report.unsafeSemanticShadowDisagreementCount !== 0
      || !['unsupportedAnswerClaims','unsupportedNumericClaims','contradictedAnswerClaims']
        .every(key => Array.isArray(report[key]) && report[key].length === 0)
      || !Array.isArray(report.claimEvidenceLinks) || report.claimEvidenceLinks.length !== 4
      || !Array.isArray(report.attributedClaims) || report.attributedClaims.length !== 4) return false;
  const claims = [...scope.clauses, remedy.literal];
  const links = report.claimEvidenceLinks;
  if (links.some(link => !link || typeof link !== 'object')
      || fold(report.verifiedAnswer) !== claims.map(fold).join(' ')
      || links[0].groundNumber === links[3].groundNumber) return false;
  return links.every((link, i) => link && fold(link.claim) === fold(claims[i])
    && fold(link.evidenceSentence) === fold(claims[i])
    && link.groundNumber === (i < 3 ? links[0].groundNumber : links[3].groundNumber)
    && uniquelyAttributed(report, link.claim, i < 3 ? scope.owner : remedy.owner));
}

module.exports = { uniquelyAttributed, scoreConditionalExamples, scoreQualifiedConditionalExample, scoreAssumedConsultation };
