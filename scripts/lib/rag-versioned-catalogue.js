const {createHash}=require('node:crypto');
const {loadVersionedDatasetProposal}=require('../rag-versioned-dataset-preflight');
const {datasetHash}=require('./rag-eval-provenance');
// Returns bytes only: no registration, approval receipt, filesystem write or HTTP.
function sealProposedCatalogue(options) {
  const {report,cases,descriptor}=loadVersionedDatasetProposal(options);
  const catalogue={schemaVersion:1,kind:'pandora-versioned-evaluation',version:descriptor.version,
    authorization:'requires-explicit-registration-and-payload-approval',
    baselineDatasetSha256:report.baselineDatasetSha256,preparationSha256:report.preparationSha256,
    catalogueBuilderSha256:datasetHash([__filename]),
    scoringOverrides:Object.fromEntries(descriptor.overrides.map(item=>[item.id,item.scorer])),
    sourceContracts:structuredClone(descriptor.sourceContracts),
    cases:cases.map(({failureTaxonomy,...requestCase})=>requestCase)};
  const bytes=Buffer.from(JSON.stringify(catalogue,null,2)+'\n','utf8');
  return {bytes,sha256:createHash('sha256').update(bytes).digest('hex'),liveEligible:false};
}
module.exports={sealProposedCatalogue};
