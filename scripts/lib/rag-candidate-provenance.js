const fs=require('node:fs'),path=require('node:path');
const legacy=require('./rag-eval-provenance');
const canonical=value=>typeof value==='string' && /^[a-f0-9]{64}$/.test(value);
function validateCandidate(identity) {
  if(Object.hasOwn(identity,'evaluationCandidateSha256') && (!canonical(identity.evaluationCandidateSha256)
      || identity.evaluationCandidateSha256!==identity.runtimeArtifactSha256 || identity.schemaVersion!==5))
    throw Error('Candidate SHA-256 must match the bound runtime JAR');
}
function buildCheckpointIdentity(options) {
  const identity=legacy.buildCheckpointIdentity(options);
  if(options.evaluationCandidateSha256!==undefined)identity.evaluationCandidateSha256=options.evaluationCandidateSha256;
  validateCandidate(identity);return identity;
}
function mapPaths(paths,identity) {
  if(!identity?.evaluationCandidateSha256)return paths;
  validateCandidate(identity);
  const prefix=`logs/contracts/${identity.evaluationContractSha256}/`;
  return Object.fromEntries(Object.entries(paths).map(([key,value])=>[key,value.replace(prefix,prefix+`candidates/${identity.evaluationCandidateSha256}/`)]));
}
function resolveReportPaths(scope,env,identity) {validateCandidate(identity??{});return mapPaths(legacy.resolveReportPaths(scope,env,identity),identity);}
function archivePaths(scope,id,identity) {validateCandidate(identity??{});return mapPaths(legacy.archivePaths(scope,id,identity),identity);}
function isCheckpointCompatible(checkpoint,expected) {
  try {validateCandidate(checkpoint?.checkpointIdentity??{});validateCandidate(expected??{});}catch{return false;}
  return checkpoint?.checkpointIdentity?.evaluationCandidateSha256===expected?.evaluationCandidateSha256
    && legacy.isCheckpointCompatible(checkpoint,expected);
}
function assertLegacyOwnerClosed(scope,identity,root) {
  const directory=path.resolve(root,'logs/contracts',identity.evaluationContractSha256,identity.selectionHash);
  if(!fs.existsSync(directory) || !fs.readdirSync(directory).some(name=>name.startsWith(scope+'-')))return;
  const terminal=path.join(directory,scope+'-latest.json');
  if(!fs.existsSync(terminal))throw Error('Legacy slot owner/terminal status is unavailable');
  const metadata=new RegExp(`^${scope}-(?:latest|checkpoint|\\d{8}-\\d{6}-\\d{3}Z)\\.json$`,'i');
  const owners=new Set();let latest;
  for(const name of fs.readdirSync(directory).filter(name=>metadata.test(name))) {
    const filename=path.join(directory,name),stat=fs.lstatSync(filename);
    if(!stat.isFile() || stat.size<1 || stat.size>64*1024*1024)throw Error('Invalid legacy owner artifact');
    const body=JSON.parse(fs.readFileSync(filename,'utf8')),owner=body.checkpointIdentity;
    if(owner?.schemaVersion!==5 || owner.evaluationContractSha256!==identity.evaluationContractSha256
        || owner.selectionHash!==identity.selectionHash || owner.runScope!==scope || !canonical(owner.runtimeArtifactSha256))
      throw Error('Legacy slot owner/scope metadata is ambiguous');
    owners.add(owner.runtimeArtifactSha256);if(name===scope+'-latest.json')latest=body;
  }
  if(owners.size!==1)throw Error('Legacy slot owner metadata conflicts');
  if(!(latest?.completionStatus==='ERROR' || (typeof latest?.gatePassed==='boolean' && latest.checkpoint!==true
        && Number.isSafeInteger(latest.total) && latest.total===latest.checkpointIdentity.selectedCaseCount && latest.results?.length===latest.total)))
    throw Error('Legacy slot owner/terminal status is ambiguous');
  if(owners.has(identity.evaluationCandidateSha256))throw Error('This candidate already consumed the legacy slot');
}
function reserveCommonCandidateClaim(scope,identity,root) {
  if(!canonical(identity.runtimeArtifactSha256))throw Error('Candidate reservation requires a bound runtime JAR');
  const directory=path.resolve(root,'logs/contracts',identity.evaluationContractSha256,'candidate-claims',identity.runtimeArtifactSha256,identity.selectionHash);
  fs.mkdirSync(directory,{recursive:true});
  try {fs.writeFileSync(path.join(directory,scope+'-started.json'),JSON.stringify({schemaVersion:5,runScope:scope,
    evaluationContractSha256:identity.evaluationContractSha256,evaluationCandidateSha256:identity.runtimeArtifactSha256,
    selectionHash:identity.selectionHash,startedAt:new Date().toISOString()}),{encoding:'utf8',flag:'wx',flush:true});}
  catch(error){if(error.code==='EEXIST')throw Error('Candidate slot already started',{cause:error});throw error;}
}
function reserveVersionedEvaluationSlot(scope,identity,{root=process.cwd(),env=process.env}={}) {
  const paths=resolveReportPaths(scope,env,identity);
  if(!identity.evaluationCandidateSha256) {
    if(canonical(identity.runtimeArtifactSha256)) {
      const candidate=path.resolve(root,'logs/contracts',identity.evaluationContractSha256,'candidates',identity.runtimeArtifactSha256,identity.selectionHash);
      if(fs.existsSync(candidate) && fs.readdirSync(candidate).some(name=>name.startsWith(scope+'-')))
        throw Error('This candidate already has a consumed checkpoint slot');
    }
    reserveCommonCandidateClaim(scope,identity,root);
    return legacy.reserveVersionedEvaluationSlot(scope,identity,{root,env});
  }
  assertLegacyOwnerClosed(scope,identity,root);
  reserveCommonCandidateClaim(scope,identity,root);
  const directory=path.resolve(root,path.dirname(paths.outputPath));fs.mkdirSync(directory,{recursive:true});
  if(fs.readdirSync(directory).some(name=>name.startsWith(scope+'-')))throw Error('Candidate slot already started or has existing evidence');
  try {fs.writeFileSync(path.join(directory,scope+'-started.json'),JSON.stringify({schemaVersion:5,runScope:scope,
    evaluationContractSha256:identity.evaluationContractSha256,evaluationCandidateSha256:identity.evaluationCandidateSha256,
    selectionHash:identity.selectionHash,startedAt:new Date().toISOString()}),{encoding:'utf8',flag:'wx',flush:true});}
  catch(error){if(error.code==='EEXIST')throw Error('Candidate slot already started',{cause:error});throw error;}
  return paths;
}
module.exports={buildCheckpointIdentity,resolveReportPaths,archivePaths,isCheckpointCompatible,reserveVersionedEvaluationSlot};
