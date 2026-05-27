export const lawApiMenus = [
  { id: 'law', target: 'law', title: '법령', description: '현행 법령과 연혁 법령 목록을 조회합니다.', defaultQuery: '개인정보' },
  { id: 'admin-rule', target: 'admrul', title: '행정규칙', description: '훈령, 예규, 고시 등 행정규칙 목록을 조회합니다.', defaultQuery: '개인정보' },
  { id: 'official-doc', target: 'official_doc', title: '공식 가이드 문서', description: '정부/기관 공식 가이드 문서를 검색합니다.', defaultQuery: '' },
  { id: 'internal-doc', target: 'internal_doc', title: '내부 지침/매뉴얼', description: '내부 업무 지침과 매뉴얼을 검색합니다.', defaultQuery: '' },
  { id: 'reference-doc', target: 'reference_doc', title: '참고자료', description: '참고자료를 검색합니다.', defaultQuery: '' },
];

export const defaultSelectedMenuIds = lawApiMenus
  .filter((menu) => menu.target !== 'reference_doc')
  .map((menu) => menu.id);
