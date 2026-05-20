export const lawApiMenus = [
  { id: 'law', target: 'law', title: '법령', description: '현행 법령과 연혁 법령 목록을 조회합니다.', defaultQuery: '개인정보' },
  { id: 'case', target: 'prec', title: '판례', description: '대법원과 하급심 판례 목록을 조회합니다.', defaultQuery: '손해배상' },
  { id: 'admin-rule', target: 'admrul', title: '행정규칙', description: '훈령, 예규, 고시 등 행정규칙 목록을 조회합니다.', defaultQuery: '개인정보' },
  { id: 'local-law', target: 'ordin', title: '자치법규', description: '지방자치단체 조례와 규칙 목록을 조회합니다.', defaultQuery: '주차장' },
  { id: 'constitutional', target: 'detc', title: '헌재결정례', description: '헌법재판소 결정례 목록을 조회합니다.', defaultQuery: '위헌' },
  { id: 'interpretation', target: 'expc', title: '법령해석례', description: '법제처 법령해석례 목록을 조회합니다.', defaultQuery: '건축법' },
  { id: 'appeal', target: 'decc', title: '행정심판례', description: '중앙행정심판위원회 재결례 목록을 조회합니다.', defaultQuery: '영업정지' },
  { id: 'treaty', target: 'trty', title: '조약', description: '대한민국 조약 목록을 조회합니다.', defaultQuery: '무역' },
  { id: 'law-forms', target: 'licbyl', title: '법령 별표서식', description: '법령에 포함된 별표와 서식 목록을 조회합니다.', defaultQuery: '신청서' },
  { id: 'admin-rule-forms', target: 'admbyl', title: '행정규칙 별표서식', description: '행정규칙의 별표와 서식 목록을 조회합니다.', defaultQuery: '서식' },
  { id: 'local-law-forms', target: 'ordinbyl', title: '자치법규 별표서식', description: '자치법규의 별표와 서식 목록을 조회합니다.', defaultQuery: '서식' },
  { id: 'linked-local-law', target: 'lnkLs', title: '법령-자치법규 연계', description: '법령과 연결된 자치법규 정보를 조회합니다.', defaultQuery: '도로교통법' },
];

export const defaultSelectedMenuIds = lawApiMenus.map((menu) => menu.id);
