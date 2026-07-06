// 메소드 설명: searchLawData 처리 흐름을 수행합니다.
export async function fetchAuthStatus() {
  const response = await fetch('/api/auth/me', {
    credentials: 'same-origin',
  });
  if (!response.ok) {
    return { authenticated: false };
  }
  return readJsonResponse(response);
}

export async function loginAdmin(username, password) {
  const response = await fetch('/api/auth/login', {
    method: 'POST',
    credentials: 'same-origin',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ username, password }),
  });
  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    throw new Error(publicApiErrorMessage(problem, '아이디 또는 비밀번호를 확인하세요.'));
  }
  return readJsonResponse(response);
}

export async function logoutAdmin() {
  const response = await fetch('/api/auth/logout', {
    method: 'POST',
    credentials: 'same-origin',
  });
  if (!response.ok) {
    return { authenticated: false };
  }
  return readJsonResponse(response);
}

export async function searchLawData(menu, query, options = {}) {
  const params = new URLSearchParams({
    target: menu.target,
    query: query || '*',
    display: '10',
  });
  if (options.titleOnly) {
    params.set('titleOnly', 'true');
  }
  if (typeof options.includeFuture === 'boolean') {
    params.set('includeFuture', String(options.includeFuture));
  }
  const endpoint = options.titleOnly ? 'search' : query?.trim() ? 'chunk-search' : 'search';
  // 주요 호출: API 또는 프레임워크 기능을 호출합니다.
  const response = await fetch(`/api/law-data/${endpoint}?${params.toString()}`);
  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    throw new Error(publicApiErrorMessage(problem, `${menu.title} 조회에 실패했습니다.`));
  }
  return readJsonResponse(response);
}

// 메소드 설명: askLawAi 처리 흐름을 수행합니다.
export async function askLawAi(target, question, limit = 8, targets = [], options = {}) {
  // 주요 호출: API 또는 프레임워크 기능을 호출합니다.
  const response = await fetch('/api/law-data/ai/answer', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ target, targets, question, limit, includeFuture: options.includeFuture ?? true }),
  });
  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    throw new Error(publicApiErrorMessage(problem, 'AI 답변 생성에 실패했습니다.'));
  }
  return readJsonResponse(response);
}

// 메소드 설명: askLawAiStream 처리 흐름을 수행합니다.
export async function askLawAiStream(target, question, limit = 8, targets = [], handlers = {}, options = {}) {
  // 주요 호출: API 또는 프레임워크 기능을 호출합니다.
  const response = await fetch('/api/law-data/ai/answer-stream', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'text/event-stream',
    },
    body: JSON.stringify({ target, targets, question, limit, includeFuture: options.includeFuture ?? true }),
  });
  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    throw new Error(publicApiErrorMessage(problem, 'AI 답변 생성에 실패했습니다.'));
  }
  if (!response.body) {
    const answer = await readJsonResponse(response);
    handlers.onAnswer?.(answer);
    return answer;
  }

  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let finalAnswer = null;
  while (true) {
    const { done, value } = await reader.read();
    if (done) {
      break;
    }
    buffer += decoder.decode(value, { stream: true });
    const events = buffer.split(/\r?\n\r?\n/);
    buffer = events.pop() ?? '';
    for (const rawEvent of events) {
      const event = parseSseEvent(rawEvent);
      if (!event) {
        continue;
      }
      if (event.name === 'grounds') {
        handlers.onGrounds?.(event.data);
      } else if (event.name === 'delta') {
        handlers.onDelta?.(event.data?.text ?? '');
      } else if (event.name === 'answer') {
        finalAnswer = event.data;
        handlers.onAnswer?.(event.data);
      } else if (event.name === 'error') {
        throw new Error(event.data?.message ?? 'AI 답변 생성 중 오류가 발생했습니다.');
      }
    }
  }
  if (buffer.trim()) {
    const event = parseSseEvent(buffer);
    if (event?.name === 'answer') {
      finalAnswer = event.data;
      handlers.onAnswer?.(event.data);
    }
  }
  return finalAnswer ?? {};
}

// 메소드 설명: parseSseEvent 처리 흐름을 수행합니다.
function parseSseEvent(rawEvent) {
  const lines = rawEvent.split(/\r?\n/);
  let name = 'message';
  const dataLines = [];
  for (const line of lines) {
    if (line.startsWith('event:')) {
      name = line.slice('event:'.length).trim();
    } else if (line.startsWith('data:')) {
      dataLines.push(line.slice('data:'.length).trimStart());
    }
  }
  if (dataLines.length === 0) {
    return null;
  }
  const dataText = dataLines.join('\n');
  return {
    name,
    data: dataText ? JSON.parse(dataText) : null,
  };
}

// 메소드 설명: fetchLawDetail 처리 흐름을 수행합니다.
export async function fetchLawDetail(link) {
  const params = new URLSearchParams({ link });
  // 주요 호출: API 또는 프레임워크 기능을 호출합니다.
  const response = await fetch(`/api/law-data/detail?${params.toString()}`);
  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    throw new Error(publicApiErrorMessage(problem, '상세 정보를 불러오지 못했습니다.'));
  }
  return readJsonResponse(response);
}

// 메소드 설명: fetchRagDocumentDetail 처리 흐름을 수행합니다.
export async function fetchRagDocumentDetail(documentId) {
  // 주요 호출: API 또는 프레임워크 기능을 호출합니다.
  const response = await fetch(`/api/rag-documents/${encodeURIComponent(documentId)}/detail`);
  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    throw new Error(publicApiErrorMessage(problem, '문서 상세 정보를 불러오지 못했습니다.'));
  }
  return readJsonResponse(response);
}

// 메소드 설명: debugLawAiSearch 처리 흐름을 수행합니다.
export async function debugLawAiSearch(question, targets = [], limit = 10, options = {}) {
  // 주요 호출: API 또는 프레임워크 기능을 호출합니다.
  const response = await fetch('/api/law-data/ai/debug/search', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ question, targets, limit, includeFuture: options.includeFuture ?? true }),
  });
  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    throw new Error(problem?.message ?? '검색 진단에 실패했습니다.');
  }
  return readJsonResponse(response);
}

// 메소드 설명: fetchLawAiEvaluationCases 처리 흐름을 수행합니다.
export async function fetchLawAiEvaluationCases() {
  // 주요 호출: API 또는 프레임워크 기능을 호출합니다.
  const response = await fetch('/api/law-data/ai/debug/evaluation-cases');
  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    throw new Error(problem?.message ?? '평가셋을 불러오지 못했습니다.');
  }
  return readJsonResponse(response);
}

// 메소드 설명: runLawAiEvaluation 처리 흐름을 수행합니다.
export async function runLawAiEvaluation(cases = []) {
  // 주요 호출: API 또는 프레임워크 기능을 호출합니다.
  const response = await fetch('/api/law-data/ai/debug/evaluate', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
    },
    body: JSON.stringify({ cases }),
  });
  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    throw new Error(problem?.message ?? '평가 실행에 실패했습니다.');
  }
  return readJsonResponse(response);
}

// 메소드 설명: readJsonResponse 처리 흐름을 수행합니다.
export async function fetchMinistryCollectionStatus() {
  const response = await fetch('/api/rag-collection/ministry/status');
  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    throw new Error(publicApiErrorMessage(problem, '수집 상태를 불러오지 못했습니다.'));
  }
  return readJsonResponse(response);
}

export async function fetchAdminOverview() {
  const response = await fetch('/api/admin/overview');
  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    throw new Error(publicApiErrorMessage(problem, '어드민 현황을 불러오지 못했습니다.'));
  }
  return readJsonResponse(response);
}

export async function fetchAdminPipelines(options = {}) {
  const params = new URLSearchParams();
  if (options.refresh) {
    params.set('refresh', 'true');
  }
  const suffix = params.toString() ? `?${params.toString()}` : '';
  const response = await fetch(`/api/admin/pipelines${suffix}`);
  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    throw new Error(publicApiErrorMessage(problem, '파이프라인 현황을 불러오지 못했습니다.'));
  }
  return readJsonResponse(response);
}

export async function fetchAdminOperations(options = {}) {
  const params = new URLSearchParams();
  if (options.refresh) {
    params.set('refresh', 'true');
  }
  const suffix = params.toString() ? `?${params.toString()}` : '';
  const response = await fetch(`/api/admin/operations${suffix}`);
  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    throw new Error(publicApiErrorMessage(problem, '수집·Batch 현황을 불러오지 못했습니다.'));
  }
  return readJsonResponse(response);
}

export async function runMinistryCollection(options = {}) {
  const params = new URLSearchParams({
    agency: options.agency ?? 'ALL',
    fillQueue: String(options.fillQueue ?? true),
    maxArticles: String(options.maxArticles ?? 20),
    maxAttachmentsPerArticle: String(options.maxAttachmentsPerArticle ?? 3),
  });
  const response = await fetch(`/api/rag-collection/ministry/run?${params.toString()}`, {
    method: 'POST',
  });
  if (!response.ok) {
    const problem = await response.json().catch(() => null);
    throw new Error(publicApiErrorMessage(problem, '수집 작업을 시작하지 못했습니다.'));
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

function publicApiErrorMessage(problem, fallback) {
  const message = typeof problem?.message === 'string' ? problem.message.trim() : '';
  if (!message) {
    return fallback;
  }
  return isPublicSafeMessage(message) ? message : fallback;
}

function isPublicSafeMessage(message) {
  if (message.length > 120) {
    return false;
  }
  return !/(exception|sql|qdrant|openai|connection refused|stack|trace|http:\/\/|https:\/\/|jdbc|mybatis|mapper|java\.|org\.|com\.|i\/o error|unsupported media type)/i.test(message);
}
