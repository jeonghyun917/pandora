const reservedSearchKeys = new Set(['resultCode', 'resultMsg', 'target', 'query', 'section', 'totalCnt', 'page', 'numOfRows']);

// 메소드 설명: normalizeList 처리 흐름을 수행합니다.
export function normalizeList(payload, menu) {
  const target = menu.target;
  const searchRootKey = Object.keys(payload ?? {}).find((key) => {
    const value = payload[key];
    return value && typeof value === 'object' && ('resultCode' in value || 'resultMsg' in value || 'totalCnt' in value);
  });
  const root = searchRootKey ? payload[searchRootKey] : payload;
  if (!root) {
    return { rows: [], total: 0, message: '응답 데이터가 비어 있습니다.' };
  }

  const listKey = Object.keys(root).find((key) => !reservedSearchKeys.has(key) && (Array.isArray(root[key]) || typeof root[key] === 'object'));
  const rawRows = listKey ? root[listKey] : [];
  const rows = (Array.isArray(rawRows) ? rawRows : [rawRows]).filter(Boolean);

  return {
    rows: rows.map((row, index) => normalizeRow(row, target, index, menu)),
    total: Number(root.totalCnt ?? rows.length),
    message: root.resultMsg ?? 'success',
  };
}

// 메소드 설명: normalizeDetail 처리 흐름을 수행합니다.
export function normalizeDetail(payload, fallbackTitle) {
  if (payload?.source === 'rag' || (Array.isArray(payload?.sections) && payload.sections.length > 0)) {
    return {
      title: payload.title || fallbackTitle,
      meta: Array.isArray(payload.meta) ? payload.meta.filter(Boolean) : [],
      contacts: [],
      sections: payload.sections?.length ? payload.sections : [{ title: '문서 내용', body: '표시할 본문 항목을 찾지 못했습니다.' }],
      ragDetail: true,
      originalFileUrl: payload.originalFileUrl || '',
      originalFileName: payload.originalFileName || '',
      originalMimeType: payload.originalMimeType || '',
      previewFileUrl: payload.previewFileUrl || '',
      previewHtmlUrl: payload.previewHtmlUrl || '',
    };
  }

  if (payload?.htmlDetail) {
    const meta = splitDetailMeta(payload.meta);
    return {
      title: payload.title || fallbackTitle,
      meta: meta.lawMeta,
      contacts: meta.contacts,
      sections: payload.sections?.length ? payload.sections : [{ title: '원문 내용', body: '표시할 원문 텍스트를 찾지 못했습니다.' }],
      htmlDetail: true,
    };
  }

  if (payload?.unsupported) {
    return {
      title: fallbackTitle,
      meta: ['원문 보기 필요'],
      contacts: [],
      sections: [{ title: '원문', body: '이 항목은 현재 텍스트로 변환할 수 없는 원문 형식입니다.' }],
      unsupported: true,
    };
  }

  const rootKey = Object.keys(payload ?? {})[0];
  const root = rootKey ? payload[rootKey] : payload;
  const base = root?.기본정보 ?? root?.판례기본정보 ?? root?.행정규칙기본정보 ?? root?.자치법규기본정보 ?? root ?? {};
  const title =
    base.법령명한글 ??
    base.법령명 ??
    base.판례명 ??
    base.사건명 ??
    base.행정규칙명 ??
    base.자치법규명 ??
    base.조약명 ??
    fallbackTitle;
  const meta = [
    getText(base.소관부처),
    base.법종구분?.content,
    base.공포번호,
    base.사건번호,
    base.법원명,
    formatDate(String(base.시행일자 ?? base.공포일자 ?? base.선고일자 ?? '')),
  ].filter(Boolean);

  return {
    title,
    meta,
    contacts: [],
    sections: collectDetailSections(root),
  };
}

// 메소드 설명: normalizeRow 처리 흐름을 수행합니다.
function normalizeRow(row, target, index, menu) {
  const title =
    row.title ??
    row.법령명한글 ??
    row.판례명 ??
    row.사건명 ??
    row.행정규칙명 ??
    row.자치법규명 ??
    row.조약명 ??
    row.별표명 ??
    row.별표서식명 ??
    row.관계법령명 ??
    row.관계법령명한글 ??
    row.관계행정규칙명 ??
    row.관계자치법규명 ??
    row.안건명 ??
    row.제목 ??
    `${target} 항목 ${index + 1}`;
  const meta =
    row.agencyName ??
    row.categoryName ??
    row.소관부처명 ??
    row.법원명 ??
    row.자치단체명 ??
    row.지자체기관명 ??
    row.전체기관명 ??
    row.소관기관명 ??
    row.질의기관명 ??
    row.사건번호 ??
    row.공포번호 ??
    row.구분 ??
    '국가법령정보센터';
  const date =
    row.sourceDate ??
    row.시행일자 ??
    row.공포일자 ??
    row.선고일자 ??
    row.발령일자 ??
    row.회신일자 ??
    row.의결일자 ??
    row.처분일자 ??
    row.발효일자 ??
    row.체결일자 ??
    '';
  const id =
    row.externalId ??
    row.documentId ??
    row.chunkId ??
    row.법령일련번호 ??
    row.판례일련번호 ??
    row.행정규칙일련번호 ??
    row.자치법규일련번호 ??
    row.ID ??
    row.id ??
    `${target}-${index}`;

  const detailLink = findDetailLink(row, target);
  const documentId = isRagTarget(target) ? findDocumentId(row, detailLink, id) : undefined;

  return {
    id,
    documentId,
    category: menu.title,
    target,
    title: sanitizeText(title),
    meta: sanitizeText(meta),
    date: formatDate(String(date)),
    detailLink,
    position: sanitizeText(row.sourcePath ?? row.chunkNo ?? row.chunkTitle ?? row.조문위치 ?? row.조문제목 ?? ''),
    snippet: sanitizeText(row.snippet ?? row.본문 ?? ''),
    sourcePath: row.sourcePath ?? row.원문경로 ?? '',
    raw: row,
  };
}

// 메소드 설명: isRagTarget 처리 흐름을 수행합니다.
function isRagTarget(target) {
  return ['official_doc', 'internal_doc', 'reference_doc'].includes(target);
}

// 메소드 설명: findDocumentId 처리 흐름을 수행합니다.
function findDocumentId(row, detailLink, fallbackId) {
  const fromLink = parseDocumentId(detailLink);
  if (fromLink) {
    return fromLink;
  }

  const idKey = Object.keys(row).find((key) => (
    key === 'documentId'
    || key === 'document_id'
    || key.toLowerCase() === 'documentid'
    || key.toLowerCase() === 'externalid'
    || key.includes('일련번호')
    || key.includes('생성번호')
  ));
  const fromRow = parseDocumentId(idKey ? row[idKey] : '');
  return fromRow || parseDocumentId(fallbackId);
}

// 메소드 설명: parseDocumentId 처리 흐름을 수행합니다.
function parseDocumentId(value) {
  const match = String(value ?? '').match(/(?:rag:|db:)?(\d+)$/);
  return match?.[1] ?? '';
}

// 메소드 설명: findDetailLink 처리 흐름을 수행합니다.
function findDetailLink(row, target) {
  if (row.detailLink) {
    return row.detailLink;
  }
  const linkKey = Object.keys(row).find((key) => key.includes('상세링크') || key.includes('파일링크') || key.toLowerCase().includes('link'));
  if (linkKey) {
    return row[linkKey];
  }
  if (target === 'law' && row.법령일련번호) {
    return `/DRF/lawService.do?OC=***&target=law&MST=${row.법령일련번호}&type=HTML&mobileYn=`;
  }
  return '';
}

// 메소드 설명: sanitizeText 처리 흐름을 수행합니다.
function sanitizeText(value) {
  return String(value ?? '').replace(/<[^>]*>/g, '').replace(/\s+/g, ' ').trim();
}

// 메소드 설명: splitDetailMeta 처리 흐름을 수행합니다.
function splitDetailMeta(meta) {
  const values = Array.isArray(meta) ? meta.filter(Boolean) : [];
  return {
    lawMeta: values.slice(0, 2),
    contacts: values.slice(2),
  };
}

// 메소드 설명: collectDetailSections 처리 흐름을 수행합니다.
function collectDetailSections(root) {
  const adminRuleArticles = asArray(root?.조문내용)
    .map(getText)
    .map((text) => text.trim())
    .filter(Boolean)
    .slice(0, 20)
    .map((body, index) => ({
      title: extractArticleTitle(body) || `조문 ${index + 1}`,
      body,
    }));
  if (adminRuleArticles.length > 0) {
    return [
      ...adminRuleArticles,
      ...collectSupplementSections(root),
      ...collectAttachedFormSections(root),
    ];
  }

  const articleUnits = asArray(root?.조문?.조문단위)
    .filter((unit) => unit.조문여부 === '조문' && unit.조문내용)
    .map((unit) => ({
      title: unit.조문제목 ? `제${unit.조문번호}조 ${unit.조문제목}` : `제${unit.조문번호}조`,
      body: [unit.조문내용, ...asArray(unit.항).map((항) => 항.항내용)].filter(Boolean).join('\n'),
    }));
  if (articleUnits.length > 0) {
    return [
      ...articleUnits,
      ...collectLawSupplementSections(root),
    ];
  }

  const caseBody = root?.판례내용 ?? root?.예시사항 ?? root?.의결요지 ?? root?.이유;
  if (caseBody) {
    return [{ title: '본문', body: getText(caseBody) }];
  }

  const textValue = findFirstLongText(root);
  return textValue ? [{ title: '상세 내용', body: textValue }] : [{ title: '상세 데이터', body: '표시할 본문 항목을 찾지 못했습니다.' }];
}

// 메소드 설명: findFirstLongText 처리 흐름을 수행합니다.
function findFirstLongText(value) {
  if (typeof value === 'string') {
    const normalized = value.replace(/\s+/g, ' ').trim();
    return normalized.length > 40 ? value.trim() : '';
  }
  if (Array.isArray(value)) {
    return value.map(findFirstLongText).find(Boolean);
  }
  if (value && typeof value === 'object') {
    return Object.values(value).map(findFirstLongText).find(Boolean);
  }
  return '';
}

// 메소드 설명: extractArticleTitle 처리 흐름을 수행합니다.
function extractArticleTitle(value) {
  return value.match(/^(제\d+조(?:의\d+)?(?:\([^)]+\))?)/)?.[1] ?? '';
}

// 메소드 설명: collectSupplementSections 처리 흐름을 수행합니다.
function collectSupplementSections(root) {
  const numbers = asArray(root?.부칙?.부칙공포번호);
  const dates = asArray(root?.부칙?.부칙공포일자);
  return asArray(root?.부칙?.부칙내용)
    .map(getText)
    .map((body, index) => ({
      title: ['부칙', numbers[index], formatDate(String(dates[index] ?? ''))].filter(Boolean).join(' · '),
      body,
    }))
    .filter((section) => section.body.trim())
    .slice(0, 5);
}

// 메소드 설명: collectLawSupplementSections 처리 흐름을 수행합니다.
function collectLawSupplementSections(root) {
  const supplementUnits = asArray(root?.부칙?.부칙단위);
  if (supplementUnits.length > 0) {
    return supplementUnits
      .map((unit, index) => ({
        title: unit.부칙공포번호
          ? `부칙 · ${unit.부칙공포번호}${unit.부칙공포일자 ? ` · ${formatDate(String(unit.부칙공포일자))}` : ''}`
          : `부칙 ${index + 1}`,
        body: getText(unit.부칙내용 ?? unit),
      }))
      .filter((section) => section.body.trim());
  }

  const numbers = asArray(root?.부칙?.부칙공포번호);
  const dates = asArray(root?.부칙?.부칙공포일자);
  return asArray(root?.부칙?.부칙내용)
    .map(getText)
    .map((body, index) => ({
      title: ['부칙', numbers[index], formatDate(String(dates[index] ?? ''))].filter(Boolean).join(' · '),
      body,
    }))
    .filter((section) => section.body.trim());
}

// 메소드 설명: collectAttachedFormSections 처리 흐름을 수행합니다.
function collectAttachedFormSections(root) {
  return asArray(root?.별표?.별표단위)
    .map((unit) => ({
      title: unit.별표제목 || `${unit.별표구분 ?? '별표'} ${unit.별표번호 ?? ''}`.trim(),
      body: getText(unit.별표내용),
    }))
    .filter((section) => section.body.replace(/\s+/g, '').length > 0)
    .slice(0, 5);
}

// 메소드 설명: asArray 처리 흐름을 수행합니다.
function asArray(value) {
  if (!value) {
    return [];
  }
  return Array.isArray(value) ? value : [value];
}

// 메소드 설명: getText 처리 흐름을 수행합니다.
function getText(value) {
  if (!value) {
    return '';
  }
  if (typeof value === 'string') {
    return value;
  }
  if (Array.isArray(value)) {
    return value.map(getText).filter(Boolean).join('\n');
  }
  if (typeof value === 'object') {
    return value.content ?? Object.values(value).map(getText).filter(Boolean).join('\n');
  }
  return String(value);
}

// 메소드 설명: formatDate 처리 흐름을 수행합니다.
function formatDate(value) {
  if (!/^\d{8}$/.test(value)) {
    return value;
  }
  return `${value.slice(0, 4)}.${value.slice(4, 6)}.${value.slice(6, 8)}`;
}
