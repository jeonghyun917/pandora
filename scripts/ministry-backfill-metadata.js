const { spawnSync } = require('node:child_process');
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');

const DB_EXE = process.env.MARIADB_EXE || 'C:\\Program Files\\MariaDB 12.2\\bin\\mariadb.exe';
const DB_ARGS = ['--ssl=0', '-upandora', '-ppandora', '--batch', '--raw', 'pandora'];
const REPORT_PATH = path.resolve('logs', 'ministry-backfill-metadata-latest.md');
const LOG_PATH = path.resolve('logs', 'ministry-backfill-metadata.log');
const USER_AGENT = 'PandoraMinistryBackfillMetadata/1.0';

const PAGE_LIMIT = Number(process.env.BACKFILL_MAX_PAGES || process.argv.find((arg) => arg.startsWith('--pages='))?.split('=')[1] || 2);
const DETAIL_LIMIT = Number(process.env.BACKFILL_MAX_DETAILS || process.argv.find((arg) => arg.startsWith('--details='))?.split('=')[1] || 80);
const AGENCY = String(process.env.BACKFILL_AGENCY || process.argv.find((arg) => arg.startsWith('--agency='))?.split('=')[1] || 'ALL').toUpperCase();

const KEYWORDS = [
  '가이드', '가이드라인', '매뉴얼', '메뉴얼', '지침', '안내서', '해설서', '편람',
  '운영기준', '업무처리', '업무 처리', '절차', '작성요령', '표준', '기준', '요령',
  '계획', '시행계획', '종합계획', '실태조사', '백서', '보고서', '사례집', '자료집',
  '개인정보', '보호법', '영향평가', '안전성', '가명정보', '마이데이터', 'ISMS-P'
];

const KNOWN_EXTENSIONS = ['.pdf', '.hwpx', '.docx', '.hwp', '.doc', '.txt', '.md'];

const SOURCES = [
  {
    sourceKey: 'data_go_kr_resources_backfill',
    agencyCode: 'PUBLIC_DATA',
    agencyName: '공공데이터포털',
    sourceUrl: 'https://www.data.go.kr/bbs/rcr/selectRecsroomList.do',
    pageUrl: (page) => `https://www.data.go.kr/bbs/rcr/selectRecsroomList.do?pageIndex=${page}`,
  },
  {
    sourceKey: 'mois_publications_backfill',
    agencyCode: 'MOIS',
    agencyName: 'Ministry of the Interior and Safety',
    sourceUrl: 'https://www.mois.go.kr/frt/bbs/type001/commonSelectBoardList.do?bbsId=BBSMSTR_000000000012',
    pageUrl: (page) => `https://www.mois.go.kr/frt/bbs/type001/commonSelectBoardList.do?bbsId=BBSMSTR_000000000012&pageIndex=${page}`,
  },
  {
    sourceKey: 'mois_references_backfill',
    agencyCode: 'MOIS',
    agencyName: 'Ministry of the Interior and Safety',
    sourceUrl: 'https://www.mois.go.kr/frt/bbs/type001/commonSelectBoardList.do?bbsId=BBSMSTR_000000000015',
    pageUrl: (page) => `https://www.mois.go.kr/frt/bbs/type001/commonSelectBoardList.do?bbsId=BBSMSTR_000000000015&pageIndex=${page}`,
  },
  {
    sourceKey: 'mois_it_guidelines_backfill',
    agencyCode: 'MOIS',
    agencyName: 'Ministry of the Interior and Safety',
    sourceUrl: 'https://www.mois.go.kr/frt/bbs/type001/commonSelectBoardList.do?bbsId=BBSMSTR_000000000045',
    pageUrl: (page) => `https://www.mois.go.kr/frt/bbs/type001/commonSelectBoardList.do?bbsId=BBSMSTR_000000000045&pageIndex=${page}`,
  },
  {
    sourceKey: 'mcst_policy_backfill',
    agencyCode: 'MCST',
    agencyName: 'Ministry of Culture, Sports and Tourism',
    sourceUrl: 'https://www.mcst.go.kr/site/s_policy/dept/deptList.jsp',
    pageUrl: (page) => `https://www.mcst.go.kr/site/s_policy/dept/deptList.jsp?pCurrentPage=${page}`,
  },
  {
    sourceKey: 'msit_press_backfill',
    agencyCode: 'MSIT',
    agencyName: 'Ministry of Science and ICT',
    sourceUrl: 'https://www.msit.go.kr/bbs/list.do?bbsSeqNo=94&sCode=user',
    pageUrl: (page) => `https://www.msit.go.kr/bbs/list.do?bbsSeqNo=94&pageIndex=${page}&sCode=user`,
  },
  {
    sourceKey: 'msit_notice_backfill',
    agencyCode: 'MSIT',
    agencyName: 'Ministry of Science and ICT',
    sourceUrl: 'https://www.msit.go.kr/bbs/list.do?bbsSeqNo=100&sCode=user',
    pageUrl: (page) => `https://www.msit.go.kr/bbs/list.do?bbsSeqNo=100&pageIndex=${page}&sCode=user`,
  },
  {
    sourceKey: 'pipc_guides_backfill',
    agencyCode: 'PIPC',
    agencyName: '개인정보보호위원회',
    sourceUrl: 'https://www.pipc.go.kr/np/cop/bbs/selectBoardList.do?bbsId=BS217&mCode=D010030000',
    pageUrl: (page) => `https://www.pipc.go.kr/np/cop/bbs/selectBoardList.do?bbsId=BS217&mCode=D010030000&pageIndex=${page}`,
  },
  {
    sourceKey: 'pipc_public_policy_backfill',
    agencyCode: 'PIPC',
    agencyName: '개인정보보호위원회',
    sourceUrl: 'https://www.pipc.go.kr/np/cop/bbs/selectBoardList.do?bbsId=BS288&mCode=D030060000',
    pageUrl: (page) => `https://www.pipc.go.kr/np/cop/bbs/selectBoardList.do?bbsId=BS288&mCode=D030060000&pageIndex=${page}`,
  },
  {
    sourceKey: 'pipc_business_policy_backfill',
    agencyCode: 'PIPC',
    agencyName: '개인정보보호위원회',
    sourceUrl: 'https://www.pipc.go.kr/np/cop/bbs/selectBoardList.do?bbsId=BS289&mCode=D040070000',
    pageUrl: (page) => `https://www.pipc.go.kr/np/cop/bbs/selectBoardList.do?bbsId=BS289&mCode=D040070000&pageIndex=${page}`,
  },
  {
    sourceKey: 'pipc_public_sector_policy_backfill',
    agencyCode: 'PIPC',
    agencyName: '개인정보보호위원회',
    sourceUrl: 'https://www.pipc.go.kr/np/cop/bbs/selectBoardList.do?bbsId=BS290&mCode=D050050000',
    pageUrl: (page) => `https://www.pipc.go.kr/np/cop/bbs/selectBoardList.do?bbsId=BS290&mCode=D050050000&pageIndex=${page}`,
  },
  {
    sourceKey: 'pipc_global_privacy_backfill',
    agencyCode: 'PIPC',
    agencyName: '개인정보보호위원회',
    sourceUrl: 'https://www.pipc.go.kr/np/cop/bbs/selectBoardList.do?bbsId=BS271&mCode=D060030010',
    pageUrl: (page) => `https://www.pipc.go.kr/np/cop/bbs/selectBoardList.do?bbsId=BS271&mCode=D060030010&pageIndex=${page}`,
  },
  {
    sourceKey: 'pipc_cases_backfill',
    agencyCode: 'PIPC',
    agencyName: '개인정보보호위원회',
    sourceUrl: 'https://www.pipc.go.kr/np/cop/bbs/selectBoardList.do?bbsId=BS231&mCode=D070010010',
    pageUrl: (page) => `https://www.pipc.go.kr/np/cop/bbs/selectBoardList.do?bbsId=BS231&mCode=D070010010&pageIndex=${page}`,
  },
  {
    sourceKey: 'pipc_annual_report_backfill',
    agencyCode: 'PIPC',
    agencyName: '개인정보보호위원회',
    sourceUrl: 'https://www.pipc.go.kr/np/cop/bbs/selectBoardList.do?bbsId=BS079&mCode=D070020000',
    pageUrl: (page) => `https://www.pipc.go.kr/np/cop/bbs/selectBoardList.do?bbsId=BS079&mCode=D070020000&pageIndex=${page}`,
  },
  {
    sourceKey: 'pipc_seminar_backfill',
    agencyCode: 'PIPC',
    agencyName: '개인정보보호위원회',
    sourceUrl: 'https://www.pipc.go.kr/np/cop/bbs/selectBoardList.do?bbsId=BS120&mCode=D070030000',
    pageUrl: (page) => `https://www.pipc.go.kr/np/cop/bbs/selectBoardList.do?bbsId=BS120&mCode=D070030000&pageIndex=${page}`,
  },
];

function log(line) {
  fs.mkdirSync(path.dirname(LOG_PATH), { recursive: true });
  fs.appendFileSync(LOG_PATH, `${new Date().toISOString()} ${line}\n`, 'utf8');
}

function db(sql) {
  const result = spawnSync(DB_EXE, DB_ARGS, {
    input: sql,
    encoding: 'utf8',
    windowsHide: true,
    maxBuffer: 20 * 1024 * 1024,
  });
  if (result.status !== 0) {
    throw new Error((result.stderr || result.stdout || '').trim());
  }
  return (result.stdout || '').trim();
}

function sql(value) {
  if (value === null || value === undefined) {
    return 'NULL';
  }
  return `'${String(value).replace(/\\/g, '\\\\').replace(/'/g, "''")}'`;
}

function limit(value, length) {
  const text = String(value || '').trim();
  return text.length > length ? text.slice(0, length) : text;
}

function sha256(value) {
  return crypto.createHash('sha256').update(String(value || ''), 'utf8').digest('hex');
}

async function fetchText(url) {
  try {
    const response = await fetch(url, {
      headers: {
        'User-Agent': USER_AGENT,
        'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8',
      },
    });
    if (!response.ok) {
      throw new Error(`HTTP ${response.status} ${url}`);
    }
    const bytes = Buffer.from(await response.arrayBuffer());
    return assertAvailablePage(bytes.toString('utf8'), url);
  } catch (error) {
    return fetchTextWithPowerShell(url, error);
  }
}

function assertAvailablePage(html, url) {
  if (/시스템\s*점검\s*안내/.test(html)) {
    throw new Error(`maintenance page returned ${url}`);
  }
  return html;
}

function fetchTextWithPowerShell(url, originalError) {
  const escapedUrl = String(url).replace(/'/g, "''");
  const script = [
    '[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)',
    '$headers = @{ "User-Agent" = "Mozilla/5.0"; "Accept" = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8" }',
    `$response = Invoke-WebRequest -UseBasicParsing -Uri '${escapedUrl}' -Headers $headers -TimeoutSec 45`,
    '[Console]::Out.Write($response.Content)',
  ].join('; ');
  const encodedCommand = Buffer.from(script, 'utf16le').toString('base64');
  const result = spawnSync('powershell.exe', ['-NoProfile', '-ExecutionPolicy', 'Bypass', '-EncodedCommand', encodedCommand], {
    encoding: 'utf8',
    windowsHide: true,
    timeout: 60000,
    maxBuffer: 10 * 1024 * 1024,
  });
  if (result.status !== 0) {
    const fallbackError = (result.stderr || result.stdout || '').trim();
    throw new Error(`${originalError.message}; PowerShell fallback failed: ${fallbackError}`);
  }
  return assertAvailablePage(result.stdout || '', url);
}

function decodeHtml(value) {
	return String(value || '')
		.replace(/&amp;/g, '&')
		.replace(/&quot;/g, '"')
		.replace(/&#39;/g, "'")
		.replace(/&#039;/g, "'")
		.replace(/&lt;/g, '<')
		.replace(/&gt;/g, '>')
		.replace(/&nbsp;/g, ' ')
    .replace(/\s+/g, ' ')
    .trim();
}

function stripTags(value) {
  return decodeHtml(String(value || '').replace(/<script[\s\S]*?<\/script>/gi, ' ').replace(/<style[\s\S]*?<\/style>/gi, ' ').replace(/<[^>]+>/g, ' '));
}

function absoluteUrl(baseUrl, href) {
  try {
    const raw = decodeHtml(href);
    if (!raw || raw.startsWith('#') || raw.includes('${') || /^javascript:|^mailto:|^tel:/i.test(raw)) {
      return '';
    }
    return new URL(raw, baseUrl).toString();
  } catch {
    return '';
  }
}

function extractLinks(baseUrl, html) {
	const links = [];
	const pattern = /<a\b[^>]*href\s*=\s*(["'])(.*?)\1[^>]*>([\s\S]*?)<\/a>/gi;
  let match;
  while ((match = pattern.exec(html)) !== null) {
    const url = absoluteUrl(baseUrl, match[2]);
		if (!url) continue;
		links.push({ url, text: stripTags(match[3]) });
	}
	const mcstPattern = /<a\b[^>]*onclick\s*=\s*(["'])\s*fnViews\(\s*['"]?(\d+)['"]?\s*,\s*['"]?([^'")]+)['"]?\s*\)[^"']*\1[^>]*>([\s\S]*?)<\/a>/gi;
	while ((match = mcstPattern.exec(html)) !== null) {
		const url = absoluteUrl(baseUrl, `deptView.jsp?pSeq=${match[2]}&pDataCD=${encodeURIComponent(match[3])}`);
		if (!url) continue;
		links.push({ url, text: stripTags(match[4]) });
	}
  const dataGoKrResourcePattern = /<a\b[^>]*href\s*=\s*(["'])\s*javascript:fn_view\(\s*["']([^"']+)["']\s*,\s*["']([^"']*)["']\s*\)\s*\1[^>]*>([\s\S]*?)<\/a>/gi;
  while ((match = dataGoKrResourcePattern.exec(html)) !== null) {
    const originId = decodeHtml(match[2]).trim();
    const atchFileId = decodeHtml(match[3]).trim();
    if (!originId || !originId.startsWith('PDS_')) continue;
    const url = absoluteUrl(
      baseUrl,
      `/bbs/rcr/selectRecsroom.do?originId=${encodeURIComponent(originId)}&atchFileId=${encodeURIComponent(atchFileId)}`
    );
    if (!url) continue;
    links.push({ url, text: stripTags(match[4]) });
  }
	return links;
}

function isArticleUrl(url) {
	return /commonSelectBoardArticle\.do/i.test(url)
		|| /selectBoardArticle\.do/i.test(url)
		|| /\/dept\/deptView\.jsp\?pSeq=/i.test(url)
		|| /\/bbs\/view\.do/i.test(url)
    || /\/bbs\/rcr\/selectRecsroom\.do/i.test(url);
}

function externalId(url) {
  const parsed = new URL(url);
  const originId = parsed.searchParams.get('originId');
  const nttId = parsed.searchParams.get('nttId');
  const pSeq = parsed.searchParams.get('pSeq');
  const nttSeqNo = parsed.searchParams.get('nttSeqNo');
  const bbsSeqNo = parsed.searchParams.get('bbsSeqNo') || parsed.searchParams.get('bbsId') || '';
  if (originId) return `data.go.kr:rcr:${originId.trim()}`;
  if (nttId) return `${bbsSeqNo}:${nttId}`;
  if (pSeq) return `${parsed.pathname}:${pSeq}`;
  if (nttSeqNo) return `${bbsSeqNo}:${nttSeqNo}`;
  return sha256(url);
}

function titleFromDetail(html, fallback) {
	const fallbackTitle = limit(fallback || '', 1000);
	if (fallbackTitle && !/^(상세|보기|자세히|문화체육관광부|국민이 주인인 나라)/.test(fallbackTitle)) {
		return fallbackTitle;
	}
  const dataGoKrTitle = html.match(/<div\b[^>]*class\s*=\s*(["'])[^"']*\btitle\b[^"']*\1[^>]*>([\s\S]*?)<\/div>/i);
  if (dataGoKrTitle) return limit(stripTags(dataGoKrTitle[2]), 1000);
	const h = html.match(/<h[1-4][^>]*>([\s\S]*?)<\/h[1-4]>/i);
	if (h) return limit(stripTags(h[1]), 1000);
  const og = html.match(/<meta[^>]+property=["']og:title["'][^>]+content=["']([^"']+)["']/i);
  if (og) return limit(decodeHtml(og[1]), 1000);
  const title = html.match(/<title[^>]*>([\s\S]*?)<\/title>/i);
  if (title) return limit(stripTags(title[1]).replace(/\s*\|.*$/, ''), 1000);
	return fallbackTitle || 'Untitled';
}

function publishedAtFromDetail(html) {
  const candidates = [
    /등록일\s*[:：]?\s*<\/strong>\s*<span[^>]*>\s*([0-9]{4}-[0-9]{2}-[0-9]{2})/i,
    /등록일\s*[:：]?\s*([0-9]{4}-[0-9]{2}-[0-9]{2})/i,
    /등록일\s*[:：]?\s*([0-9]{4}\.[0-9]{2}\.[0-9]{2})/i,
    /작성일\s*[:：]?\s*([A-Za-z]{3}\s+\d{1,2},\s+\d{4})/i,
    /게시일\s*[:：]?\s*([0-9]{4}\.[0-9]{2}\.[0-9]{2})/i,
    /([0-9]{4}\.[0-9]{2}\.[0-9]{2})/,
  ];
  for (const pattern of candidates) {
    const match = html.match(pattern);
    if (match) {
      const value = match[1].replace(/\./g, '-');
      if (/^\d{4}-\d{2}-\d{2}$/.test(value)) return `${value} 00:00:00`;
      const date = new Date(match[1]);
      if (!Number.isNaN(date.getTime())) {
        return date.toISOString().slice(0, 19).replace('T', ' ');
      }
    }
  }
  return null;
}

function fileNameFromText(value) {
  const text = decodeHtml(value);
  const lower = text.toLowerCase();
  for (const extension of KNOWN_EXTENSIONS) {
    const index = lower.indexOf(extension);
    if (index >= 0) {
      return sanitizeFileName(text.slice(0, index + extension.length));
    }
  }
  return sanitizeFileName(text);
}

function decodeFormArgument(value) {
  try {
    return decodeURIComponent(String(value || '').replace(/\+/g, ' '));
  } catch {
    return decodeHtml(value);
  }
}

function sanitizeFileName(value) {
  const sanitized = String(value || '').replace(/[\\/:*?"<>|]/g, '_').replace(/\s+/g, ' ').trim();
  return limit(sanitized || 'attachment', 180);
}

function extensionOf(fileNameOrUrl) {
  const lower = String(fileNameOrUrl || '').toLowerCase();
  for (const extension of KNOWN_EXTENSIONS) {
    if (lower.includes(extension)) return extension;
  }
  return '';
}

function extractAttachments(baseUrl, html) {
  const candidates = new Map();
  for (const link of extractLinks(baseUrl, html)) {
    let extension = extensionOf(link.url);
    let fileName = '';
    if (!extension) {
      fileName = fileNameFromText(link.text);
      extension = extensionOf(fileName);
    }
    if (!extension) continue;
    if (!fileName) {
      try {
        fileName = sanitizeFileName(decodeURIComponent(new URL(link.url).pathname.split('/').pop() || 'attachment'));
      } catch {
        fileName = 'attachment';
      }
    }
    if (!extensionOf(fileName)) {
      fileName = `${fileName}${extension}`;
    }
    candidates.set(link.url, { url: link.url, fileName: sanitizeFileName(fileName), extension });
  }
  for (const attachment of extractPipcAttachments(baseUrl, html)) {
    candidates.set(attachment.url, attachment);
  }
  for (const attachment of extractMcstAttachments(baseUrl, html)) {
    candidates.set(attachment.url, attachment);
  }
  for (const attachment of extractDataGoKrAttachments(baseUrl, html)) {
    candidates.set(attachment.url, attachment);
  }
  return [...candidates.values()].sort((a, b) => (a.extension === '.pdf' ? -1 : b.extension === '.pdf' ? 1 : a.fileName.localeCompare(b.fileName)));
}

function extractDataGoKrAttachments(baseUrl, html) {
  const attachments = [];
  const pattern = /<a\b[^>]*href\s*=\s*(["'])\s*javascript:fn_fileDownload\(\s*["']([^"']+)["']\s*,\s*["']([^"']+)["']\s*\)\s*;?\s*\1[^>]*>([\s\S]*?)<\/a>/gi;
  let match;
  while ((match = pattern.exec(html)) !== null) {
    const atchFileId = decodeHtml(match[2]).trim();
    const fileDetailSn = decodeHtml(match[3]).trim();
    const fileName = fileNameFromText(stripTags(match[4]).replace(/^첨부파일\s*/i, ''));
    const extension = extensionOf(fileName);
    if (!atchFileId || !fileDetailSn || !KNOWN_EXTENSIONS.includes(extension)) continue;
    const url = absoluteUrl(
      baseUrl,
      `/cmm/cmm/fileDownload.do?atchFileId=${encodeURIComponent(atchFileId)}&fileDetailSn=${encodeURIComponent(fileDetailSn)}`
    );
    if (url) {
      attachments.push({ url, fileName, extension });
    }
  }
  return attachments;
}

function extractMcstAttachments(baseUrl, html) {
  const attachments = [];
  const pattern = /file_download\s*\(\s*['"]([^'"]+)['"]\s*,\s*['"]([^'"]+)['"]\s*,\s*['"]([^'"]+)['"]\s*\)/gi;
  let match;
  while ((match = pattern.exec(html)) !== null) {
    const rawFileName = match[1];
    const saveName = decodeFormArgument(match[2]);
    const filePath = decodeFormArgument(match[3]);
    const fileName = sanitizeFileName(decodeFormArgument(rawFileName) || saveName);
    const extension = extensionOf(fileName) || extensionOf(saveName);
    if (!KNOWN_EXTENSIONS.includes(extension)) continue;
    const url = absoluteUrl(
      baseUrl,
      `/servlets/eduport/front/upload/UplDownloadFile?pFileName=${rawFileName}&pRealName=${encodeURIComponent(saveName)}&pPath=${encodeURIComponent(filePath)}&pFlag=`
    );
    if (url) {
      attachments.push({ url, fileName, extension });
    }
  }
  return attachments;
}

function extractPipcAttachments(baseUrl, html) {
  const attachments = [];
  const blockPattern = /<div\b[^>]*class\s*=\s*(["'])[^"']*\bdownload\b[^"']*\1[^>]*>([\s\S]*?)<\/div>/gi;
  let blockMatch;
  while ((blockMatch = blockPattern.exec(html)) !== null) {
    const block = blockMatch[2];
    const call = block.match(/fn_egov_downFile\s*\(\s*['"]([^'"]+)['"]\s*,\s*['"]?([^'",)]+)['"]?\s*,\s*['"]?([^'")]+)['"]?\s*\)/i);
    if (!call) continue;
    const atchFileId = call[1];
    const fileSn = call[2];
    const extension = extensionOf(`.${call[3]}`) || `.${String(call[3] || '').toLowerCase()}`;
    if (!KNOWN_EXTENSIONS.includes(extension)) continue;
    const url = absoluteUrl(
      baseUrl,
      `/np/cmm/fms/FileDown.do?atchFileId=${encodeURIComponent(atchFileId)}&fileSn=${encodeURIComponent(fileSn)}&fileExtsn=${encodeURIComponent(extension.replace(/^\./, ''))}`
    );
    const fileName = pipcFileNameFromDownloadBlock(block, extension);
    if (url) {
      attachments.push({ url, fileName, extension });
    }
  }
  return attachments;
}

function pipcFileNameFromDownloadBlock(block, extension) {
  const text = stripTags(block)
    .replace(/\b다운로드\b/g, ' ')
    .replace(/\[[0-9,\s]+byte\]/gi, ' ')
    .replace(/\s+/g, ' ')
    .trim();
  const fromText = fileNameFromText(text);
  if (fromText && extensionOf(fromText)) {
    return fromText;
  }
  return sanitizeFileName(`attachment${extension}`);
}

function isRelevant(title, html, attachments) {
  const haystack = `${title} ${stripTags(html).slice(0, 4000)} ${attachments.map((item) => item.fileName).join(' ')}`;
  return KEYWORDS.some((keyword) => haystack.includes(keyword));
}

function upsertSource(source) {
  db(`
INSERT INTO rag_collection_sources (source_key, source_type, agency_code, agency_name, source_url, enabled)
VALUES (${sql(source.sourceKey)}, 'BACKFILL', ${sql(source.agencyCode)}, ${sql(source.agencyName)}, ${sql(source.sourceUrl)}, 'Y')
ON DUPLICATE KEY UPDATE
  source_type = VALUES(source_type),
  agency_code = VALUES(agency_code),
  agency_name = VALUES(agency_name),
  source_url = VALUES(source_url);
SELECT source_id FROM rag_collection_sources WHERE source_key = ${sql(source.sourceKey)} LIMIT 1;
`);
  const out = db(`SELECT source_id FROM rag_collection_sources WHERE source_key = ${sql(source.sourceKey)} LIMIT 1;`);
  return Number(out.split(/\s+/).pop());
}

function upsertArticle(sourceId, article) {
  db(`
INSERT INTO rag_source_articles (source_id, external_id, title, link, published_at, status, detail_hash, fetched_at)
VALUES (${sourceId}, ${sql(article.externalId)}, ${sql(article.title)}, ${sql(article.url)}, ${article.publishedAt ? sql(article.publishedAt) : 'NULL'}, ${sql(article.status)}, ${sql(article.detailHash)}, NOW())
ON DUPLICATE KEY UPDATE
  title = VALUES(title),
  link = VALUES(link),
  published_at = VALUES(published_at),
  status = VALUES(status),
  detail_hash = VALUES(detail_hash),
  fetched_at = NOW(),
  last_error_message = NULL;
`);
  const out = db(`SELECT article_id FROM rag_source_articles WHERE source_id=${sourceId} AND external_id=${sql(article.externalId)} LIMIT 1;`);
  return Number(out.split(/\s+/).pop());
}

function upsertAttachment(articleId, attachment) {
  db(`
INSERT INTO rag_source_attachments (article_id, url, file_name, extension, status, last_error_message)
VALUES (${articleId}, ${sql(attachment.url)}, ${sql(attachment.fileName)}, ${sql(attachment.extension)}, 'DISCOVERED', NULL)
ON DUPLICATE KEY UPDATE
  file_name = VALUES(file_name),
  extension = VALUES(extension),
  status = CASE WHEN status IN ('IMPORTED', 'SKIPPED') THEN status ELSE VALUES(status) END,
  last_error_message = NULL;
`);
}

async function scanSource(source) {
  const sourceId = upsertSource(source);
  const articleMap = new Map();
  const stats = { pages: 0, articles: 0, relevant: 0, attachments: 0, errors: 0 };

  for (let page = 1; page <= PAGE_LIMIT; page++) {
    const pageUrl = source.pageUrl(page);
    try {
      const html = await fetchText(pageUrl);
      stats.pages++;
      for (const link of extractLinks(pageUrl, html)) {
        if (!isArticleUrl(link.url)) continue;
        articleMap.set(link.url, { url: link.url, fallbackTitle: link.text });
      }
    } catch (error) {
      stats.errors++;
      log(`${source.sourceKey} page ${page} failed: ${error.stack || error.message}`);
    }
  }

  const articleCandidates = [...articleMap.values()].slice(0, DETAIL_LIMIT);
  stats.articles = articleCandidates.length;

  for (const candidate of articleCandidates) {
    try {
      const html = await fetchText(candidate.url);
      const attachments = extractAttachments(candidate.url, html);
      const title = titleFromDetail(html, candidate.fallbackTitle);
      const relevant = isRelevant(title, html, attachments);
      const articleId = upsertArticle(sourceId, {
        externalId: externalId(candidate.url),
        title,
        url: candidate.url,
        publishedAt: publishedAtFromDetail(html),
        detailHash: sha256(html),
        status: relevant ? 'DISCOVERED' : 'SKIPPED',
      });
      if (relevant) {
        stats.relevant++;
        for (const attachment of attachments) {
          upsertAttachment(articleId, attachment);
          stats.attachments++;
        }
      }
    } catch (error) {
      stats.errors++;
      log(`${source.sourceKey} detail failed ${candidate.url}: ${error.stack || error.message}`);
    }
  }

  db(`UPDATE rag_collection_sources SET last_checked_at=NOW(), last_success_at=NOW(), last_error_message=NULL WHERE source_id=${sourceId};`);
  return { source, stats };
}

function summaryFromDb() {
  const rows = db(`
SELECT s.agency_code, s.source_key, COUNT(DISTINCT a.article_id) articles,
       COUNT(DISTINCT CASE WHEN a.status='DISCOVERED' THEN a.article_id END) relevant_articles,
       COUNT(att.attachment_id) attachments
FROM rag_collection_sources s
LEFT JOIN rag_source_articles a ON a.source_id=s.source_id
LEFT JOIN rag_source_attachments att ON att.article_id=a.article_id
WHERE s.source_type='BACKFILL'
GROUP BY s.agency_code, s.source_key
ORDER BY s.agency_code, s.source_key;
`);
  return rows;
}

async function main() {
  log(`start agency=${AGENCY} pages=${PAGE_LIMIT} details=${DETAIL_LIMIT}`);
  const sources = AGENCY === 'ALL' ? SOURCES : SOURCES.filter((source) => source.agencyCode === AGENCY);
  const results = [];
  for (const source of sources) {
    results.push(await scanSource(source));
  }

  const latest = new Date().toLocaleString('ko-KR', { timeZone: 'Asia/Seoul', hour12: false });
  const lines = [
    'ministry backfill metadata report.',
    '',
    `Latest status time: ${latest}`,
    `Agency: ${AGENCY}`,
    `Page limit per source: ${PAGE_LIMIT}`,
    `Detail limit per source: ${DETAIL_LIMIT}`,
    '',
    '| Source | Agency | Pages | Article candidates | Relevant articles | Attachment candidates | Errors |',
    '| --- | --- | ---: | ---: | ---: | ---: | ---: |',
    ...results.map(({ source, stats }) =>
      `| ${source.sourceKey} | ${source.agencyCode} | ${stats.pages} | ${stats.articles} | ${stats.relevant} | ${stats.attachments} | ${stats.errors} |`
    ),
    '',
    'DB summary:',
    '',
    '```',
    summaryFromDb() || '(empty)',
    '```',
    '',
    'Next step: review candidate volume, then enable bounded download/import for selected BACKFILL sources.',
  ];
  fs.mkdirSync(path.dirname(REPORT_PATH), { recursive: true });
  fs.writeFileSync(REPORT_PATH, `${lines.join('\n')}\n`, 'utf8');
  log('done');
}

main().catch((error) => {
  log(`fatal ${error.stack || error.message}`);
  process.exitCode = 1;
});
