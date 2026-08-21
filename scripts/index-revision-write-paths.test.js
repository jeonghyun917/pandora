const assert = require('node:assert/strict');
const fs = require('node:fs');
const path = require('node:path');
const test = require('node:test');

const root = path.resolve(__dirname, '..');

function read(relativePath) {
  return fs.readFileSync(path.join(root, relativePath), 'utf8');
}

test('direct RAG embedding writer maintains revision_hash', () => {
  const script = read('scripts/direct-index-rag-embeddings.ps1');

  assert.match(script, /content_hash, revision_hash,/);
  assert.match(script, /SHA2\(CONCAT\(CAST\(\$chunkId AS CHAR\), ':', '\$contentHash'\), 256\)/);
  assert.match(script, /revision_hash = VALUES\(revision_hash\)/);
});

test('official document reconciliation maintains revision_hash', () => {
  const script = read('scripts/official-doc-batch-guard.js');

  assert.match(script, /content_hash, revision_hash,/);
  assert.match(script, /SHA2\(CONCAT\([\s\S]*CAST\(c\.chunk_id AS CHAR\)[\s\S]*COALESCE\(c\.content_hash/);
  assert.match(script, /revision_hash = VALUES\(revision_hash\)/);
});
