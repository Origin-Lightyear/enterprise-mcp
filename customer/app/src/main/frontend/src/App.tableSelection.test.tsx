// @vitest-environment happy-dom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, describe, expect, it, vi } from 'vitest';
import App from './App';

Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
vi.mock('reactflow', () => ({ default: () => null, Background: () => null, Controls: () => null, MiniMap: () => null }));
const { request } = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock('./api', async (original) => ({ ...await original<typeof import('./api')>(), api: request }));

const names = ['Zebra', 'Table_b', 'table_a', ...Array.from({ length: 52 }, (_, index) => `Item_${String(index).padStart(2, '0')}`)];
let root: ReturnType<typeof createRoot> | undefined;
let container: HTMLDivElement;

async function click(label: string) {
  const button = [...container.querySelectorAll('button')].find(item => item.textContent?.replace(/\s/g, '') === label);
  expect(button, label).toBeDefined();
  await act(async () => button!.click());
}

async function openSelection() {
  sessionStorage.setItem('wren.csrf', 'test');
  request.mockImplementation(async (path: string) => {
    if (path === '/datasources') return { items: [{ name: 'sales', displayName: '销售数据库', datasource: 'mysql', database: 'sales', modelingInUse: false }] };
    if (path === '/modeling') return { revision: 0, models: [], relationships: [] };
    if (path === '/introspection-jobs') return { jobId: 'job' };
    if (path.endsWith('/snapshot')) return { tables: names.map(name => ({ id: name, name, schema: 'public', comment: '', columns: [], primaryKey: [] })), foreignKeys: [] };
    if (path === '/introspection-jobs/job') return { status: 'SUCCEEDED' };
    return { readiness: 'NOT_READY' };
  });
  container = document.createElement('div');
  document.body.append(container);
  root = createRoot(container);
  await act(async () => root!.render(<App/>));
  await click('探测数据库结构');
  await act(async () => { await new Promise(resolve => setTimeout(resolve, 550)); });
  expect(container.textContent).toContain('选择表以创建 Models');
}

function rows() { return [...container.querySelectorAll('tbody tr.ant-table-row')]; }

async function search(value: string) {
  const input = container.querySelector<HTMLInputElement>('input[aria-label="搜索表名"]');
  expect(input).not.toBeNull();
  await act(async () => {
    Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!.call(input, value);
    input!.dispatchEvent(new Event('input', { bubbles: true }));
  });
}

afterEach(async () => {
  if (root) await act(async () => root!.unmount());
  root = undefined;
  container?.remove();
  sessionStorage.clear();
  vi.clearAllMocks();
});

describe('table selection', () => {
  it('changes the actual page size through the dropdown', async () => {
    await openSelection();
    expect(rows()).toHaveLength(12);
    const selector = container.querySelector('.ant-pagination-options .ant-select-selector');
    expect(selector).not.toBeNull();
    await act(async () => selector!.dispatchEvent(new MouseEvent('mousedown', { bubbles: true })));
    const option = [...document.querySelectorAll('.ant-select-item-option')].find(item => item.textContent?.startsWith('20'));
    expect(option).toBeDefined();
    await act(async () => option!.dispatchEvent(new MouseEvent('click', { bubbles: true })));
    expect(rows()).toHaveLength(20);
    expect(container.querySelector('.ant-pagination-options')?.textContent).toContain('20');
    await act(async () => (container.querySelector('.ant-pagination-next button') as HTMLButtonElement).click());
    expect(rows()).toHaveLength(20);
    expect(rows().every(row => row.querySelector<HTMLInputElement>('input[type="checkbox"]')!.checked)).toBe(true);
  });

  it('sorts names ignoring case, filters by substring, and resets the page on search', async () => {
    await openSelection();
    await act(async () => (container.querySelector('[title="5"]') as HTMLElement).click());
    expect(rows().map(row => row.querySelectorAll('td')[2].textContent)).toEqual(['Item_48', 'Item_49', 'Item_50', 'Item_51', 'table_a', 'Table_b', 'Zebra']);
    await search('TaBlE_');
    expect(rows().map(row => row.querySelectorAll('td')[2].textContent)).toEqual(['table_a', 'Table_b']);
    expect(container.querySelector('.ant-pagination-item-active')?.textContent).toBe('1');
    await search('missing');
    expect(rows()).toHaveLength(0);
    await search('');
    expect(rows()).toHaveLength(12);
  });

  it('selects and inverts all filtered pages while preserving hidden selections', async () => {
    await openSelection();
    await search('item_');
    await click('反选');
    expect(container.textContent).toContain('已选择 3 / 55 张表');
    expect(rows().every(row => !row.querySelector<HTMLInputElement>('input[type="checkbox"]')!.checked)).toBe(true);
    await act(async () => (container.querySelector('.ant-pagination-next button') as HTMLButtonElement).click());
    expect(rows().every(row => !row.querySelector<HTMLInputElement>('input[type="checkbox"]')!.checked)).toBe(true);
    await click('全选');
    expect(container.textContent).toContain('已选择 55 / 55 张表');
    await search('');
    await click('反选');
    expect(container.textContent).toContain('已选择 0 / 55 张表');
    expect([...container.querySelectorAll('button')].find(button => button.textContent?.includes('下一步'))?.disabled).toBe(true);
  });
});
