// @vitest-environment happy-dom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import App from './App';

Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });

vi.mock('reactflow', () => ({ default: () => <div>Modeling 画布</div>, Background: () => null, Controls: () => null, MiniMap: () => null }));
const { request } = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock('./api', async (original) => ({ ...await original<typeof import('./api')>(), api: request }));

const source = { name: 'sales', displayName: '销售数据库', datasource: 'mysql', host: 'db', port: '3306', database: 'sales', user: 'app', active: true, modelingInUse: true, currentInUse: true };
const modeling = { revision: 1, profileName: 'sales', setupCompleted: false, models: [{ name: 'Order', displayName: '订单', description: '', source: { catalog: '', schema: '', table: 'orders' }, columns: [], primaryKey: [] }], relationships: [] };
let root: ReturnType<typeof createRoot>;
let container: HTMLDivElement;

async function click(label: string) {
  const button = [...container.querySelectorAll('button')].find(item => (item.getAttribute('aria-label') || item.textContent || '').replace(/\s/g, '') === label);
  expect(button, label).toBeDefined();
  await act(async () => button!.click());
}

afterEach(async () => { if (root) await act(async () => root.unmount()); container?.remove(); sessionStorage.clear(); vi.clearAllMocks(); });

describe('Modeling navigation', () => {
  it('returns to the canvas after editing without saving even with unfinished relationship setup', async () => {
    sessionStorage.setItem('wren.csrf', 'test');
    request.mockImplementation(async (path: string) => path === '/datasources' ? { items: [source] } : path.startsWith('/modeling') ? modeling : { readiness: 'READY', mdlDigest: 'old' });
    container = document.createElement('div'); document.body.append(container); root = createRoot(container);
    await act(async () => root.render(<App/>));
    await click('数据源'); await click('连接设置');
    expect(container.textContent).toContain('编辑数据源');
    await click('返回'); await click('Modeling');
    expect(container.textContent).toContain('Modeling 画布');
    expect(container.textContent).not.toContain('编辑数据源');
    expect(container.textContent).not.toContain('定义关系');
    expect(request.mock.calls.every(([, init]) => !init?.method || init.method === 'GET')).toBe(true);
  });

  it('leaves structure introspection through the Modeling menu without re-entering the wizard', async () => {
    sessionStorage.setItem('wren.csrf', 'test');
    request.mockImplementation(async (path: string) => path === '/datasources' ? { items: [source] } : path.startsWith('/modeling') ? modeling : { readiness: 'READY', mdlDigest: 'old' });
    container = document.createElement('div'); document.body.append(container); root = createRoot(container);
    await act(async () => root.render(<App/>));
    await click('数据源'); await click('重新探测');
    expect(container.textContent).toContain('探测数据库结构');
    await click('Modeling');
    expect(container.textContent).toContain('Modeling 画布');
    await click('数据源'); await click('连接设置'); await click('Modeling');
    expect(container.textContent).toContain('Modeling 画布');
  });
});
