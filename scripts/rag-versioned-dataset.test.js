const test=require('node:test');
const assert=require('node:assert/strict');
const {applyDeclaredCaseVersion}=require('./lib/rag-versioned-dataset');
const hash='a'.repeat(64);
const baseline=()=>Array.from({length:1003},(_,i)=>({id:`case-${i}`,question:`Question ${i}`,
  targets:['law'],requiredMatches:1,expectedTerms:['ground'],requiredPropositionGroups:[['fact']]}));
const descriptor=()=>({schemaVersion:1,version:'reviewed-v2',baselineDatasetSha256:hash,
  status:'inactive-proposed-payload',overrides:[{id:'case-1',patch:{question:'Explicitly qualified question'}},
    {id:'case-2',patch:{requiredPropositionGroups:[['dated fact']]}}]});
test('applies only declared patches while preserving full order and caller data',()=>{
  const base=baseline(),before=structuredClone(base),spec=descriptor();
  const result=applyDeclaredCaseVersion(base,hash,spec);
  assert.equal(result.length,1003);
  assert.deepEqual(result.map(x=>x.id),base.map(x=>x.id));
  assert.deepEqual(result.filter((x,i)=>JSON.stringify(x)!==JSON.stringify(base[i])).map(x=>x.id),['case-1','case-2']);
  result[0].expectedTerms.push('local mutation');
  result[1].targets.push('admrul');
  assert.deepEqual(base,before);
  assert.deepEqual(spec,descriptor());
});
test('rejects stale or malformed baseline and incomplete/duplicate universe',()=>{
  assert.throws(()=>applyDeclaredCaseVersion(baseline(),'b'.repeat(64),descriptor()),/baseline/i);
  assert.throws(()=>applyDeclaredCaseVersion(baseline(),'A'.repeat(64),descriptor()),/baseline/i);
  assert.throws(()=>applyDeclaredCaseVersion(baseline().slice(1),hash,descriptor()),/1003/);
  const duplicate=baseline(); duplicate[1002].id='case-0';
  assert.throws(()=>applyDeclaredCaseVersion(duplicate,hash,descriptor()),/unique/i);
});
test('rejects undeclared IDs, duplicate/no-op patches and unsupported fields',()=>{
  for(const override of [{id:'missing',patch:{question:'x'}},{id:'case-0',patch:{}},
    {id:'case-0',patch:{question:'Question 0'}},{id:'case-0',patch:{id:'moved'}},
    {id:'case-0',patch:{oracleBypass:true}},{id:'case-0',patch:{question:null}},
    {id:'case-0',patch:{requiredMatches:-1}},{id:'case-0',patch:{targets:['unknown']}}]) {
    const spec=descriptor(); spec.overrides=[override];
    assert.throws(()=>applyDeclaredCaseVersion(baseline(),hash,spec));
  }
  const spec=descriptor(); spec.overrides.push(spec.overrides[0]);
  assert.throws(()=>applyDeclaredCaseVersion(baseline(),hash,spec),/duplicate/i);
});
test('requires an explicitly inactive version descriptor',()=>{
  for(const patch of [{schemaVersion:2},{version:''},{status:'active'},{overrides:[]}])
    assert.throws(()=>applyDeclaredCaseVersion(baseline(),hash,{...descriptor(),...patch}));
});
