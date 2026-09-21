import { afterEach, describe, expect, it, vi } from 'vitest';

describe('Wren admin API network failures', () => {
  afterEach(() => {
    vi.unstubAllGlobals();
    vi.resetModules();
  });

  it('reports an actionable service error when fetch cannot reach the customer application', async () => {
    vi.stubGlobal('sessionStorage', {
      getItem: vi.fn(() => ''),
      setItem: vi.fn(),
      removeItem: vi.fn(),
    });
    vi.stubGlobal('fetch', vi.fn().mockRejectedValue(new TypeError('Failed to fetch')));
    const { api } = await import('./api');

    await expect(api('/datasources')).rejects.toMatchObject({
      status: 0,
      code: 'NETWORK_ERROR',
      message: 'Wren 管理服务不可用，请确认客户应用和 Wren Adapter 已启动',
    });
  });
});
