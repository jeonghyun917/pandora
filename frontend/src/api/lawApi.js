export async function searchLawData(menu, query) {
  const params = new URLSearchParams({
    target: menu.target,
    query: query || '*',
    display: '10',
  });
  const response = await fetch(`/api/law-data/search?${params.toString()}`);
  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    throw new Error(problem?.message ?? `${menu.title} 조회에 실패했습니다.`);
  }
  return readJsonResponse(response);
}

export async function fetchLawDetail(link) {
  const params = new URLSearchParams({ link });
  const response = await fetch(`/api/law-data/detail?${params.toString()}`);
  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    throw new Error(problem?.message ?? '상세 정보를 불러오지 못했습니다.');
  }
  return readJsonResponse(response);
}

async function readJsonResponse(response) {
  const text = await response.text();
  if (!text.trim()) {
    return {};
  }
  return JSON.parse(text);
}
