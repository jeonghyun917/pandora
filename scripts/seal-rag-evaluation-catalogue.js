const fs=require('node:fs'),path=require('node:path'),{createHash}=require('node:crypto');
const {sealProposedCatalogue}=require('./lib/rag-versioned-catalogue');
function writeSealedCatalogue(sealed,directory) {
  if(!Buffer.isBuffer(sealed?.bytes) || sealed.bytes.length<1 || sealed.bytes.length>4_000_000
      || typeof sealed.sha256!=='string' || !/^[a-f0-9]{64}$/.test(sealed.sha256)
      || createHash('sha256').update(sealed.bytes).digest('hex')!==sealed.sha256)
    throw Error('Sealed catalogue hash identity mismatch');
  fs.mkdirSync(directory,{recursive:true});
  const target=path.resolve(directory,sealed.sha256+'.json');
  try {fs.writeFileSync(target,sealed.bytes,{flag:'wx',flush:true});}
  catch(error) {
    if(error.code!=='EEXIST') throw error;
    const existing=fs.lstatSync(target);
    if(!existing.isFile() || existing.size!==sealed.bytes.length || !fs.readFileSync(target).equals(sealed.bytes))
      throw Error('Refusing conflicting existing catalogue content');
  }
  return target;
}
if(require.main===module) {
  const args=process.argv.slice(2);
  if(args.length>1 || args.length===1 && args[0]!=='--write') throw Error('Expected only optional --write');
  const sealed=sealProposedCatalogue();
  const target=args.length?writeSealedCatalogue(sealed,path.resolve(__dirname,'../src/main/resources/rag-evaluation-contracts')):null;
  console.log(JSON.stringify({sha256:sealed.sha256,bytes:sealed.bytes.length,path:target,registered:false,liveEligible:false},null,2));
}
module.exports={writeSealedCatalogue};
