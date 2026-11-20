const test=require('node:test');
const assert=require('node:assert/strict');
const fs=require('node:fs'),os=require('node:os'),path=require('node:path');
const {writeSealedCatalogue}=require('./seal-rag-evaluation-catalogue');
const {sealProposedCatalogue}=require('./lib/rag-versioned-catalogue');
test('writes only hash-named exact bytes and refuses conflicting existing content',()=>{
  const directory=fs.mkdtempSync(path.join(os.tmpdir(),'pandora-catalogue-'));
  try {
    const sealed=sealProposedCatalogue();
    const target=writeSealedCatalogue(sealed,directory);
    assert.equal(path.basename(target),sealed.sha256+'.json');
    assert.deepEqual(fs.readFileSync(target),sealed.bytes);
    assert.equal(writeSealedCatalogue(sealed,directory),target);
    assert.throws(()=>writeSealedCatalogue({...sealed,bytes:Buffer.from('{}')},directory),/hash|identity/i);
    fs.writeFileSync(target,'conflicting fixture');
    assert.throws(()=>writeSealedCatalogue(sealed,directory),/existing|conflict/i);
    assert.equal(fs.readFileSync(target,'utf8'),'conflicting fixture');
  } finally {
    assert.ok(path.resolve(directory).startsWith(path.resolve(os.tmpdir())+path.sep+'pandora-catalogue-'));
    fs.rmSync(directory,{recursive:true,force:true});
  }
});
