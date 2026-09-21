let csrfToken = sessionStorage.getItem('wren.csrf') || '';

export type ApiResult<T> = { code: string; message: string; data: T; requestId: string };

export class ApiError extends Error {
  constructor(public readonly status: number, public readonly code: string, message: string) {
    super(message);
    this.name = 'ApiError';
  }
}

export async function api<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers);
  headers.set('Content-Type', 'application/json');
  if (csrfToken && init.method && init.method !== 'GET') headers.set('X-CSRF-Token', csrfToken);
  let response: Response;
  try {
    response = await fetch(`/internal/wren/admin${path}`, { ...init, headers, credentials: 'same-origin' });
  } catch {
    throw new ApiError(0, 'NETWORK_ERROR', 'Wren 管理服务不可用，请确认客户应用和 Wren Adapter 已启动');
  }
  const body = await response.json().catch(() => ({ message: response.statusText }));
  if (!response.ok) {
    if (response.status === 401 && path !== '/session') {
      csrfToken = '';
      sessionStorage.removeItem('wren.csrf');
    }
    throw new ApiError(response.status, body.code || 'REQUEST_FAILED', body.data?.error || body.message || '请求失败');
  }
  return (body as ApiResult<T>).data;
}

export async function login(token: string) {
  const data = await api<{ csrfToken: string }>('/session', { method: 'POST', body: JSON.stringify({ token }) });
  csrfToken = data.csrfToken;
  sessionStorage.setItem('wren.csrf', csrfToken);
}

export async function logout() {
  try {
    await api('/session', { method: 'DELETE' });
  } finally {
    csrfToken = '';
    sessionStorage.removeItem('wren.csrf');
  }
}
