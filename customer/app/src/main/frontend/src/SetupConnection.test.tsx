// @vitest-environment happy-dom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, expect, it, vi } from 'vitest';
import { SetupConnection } from './setup';

Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
const { request } = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock('./api', () => ({ api: request }));

let root: ReturnType<typeof createRoot>;
let container: HTMLDivElement;

afterEach(async () => {
  if (root) await act(async () => root.unmount());
  container?.remove();
  vi.clearAllMocks();
});

it('edits a Chinese display name without changing the stable datasource identity', async () => {
  request.mockResolvedValue({ tested: true });
  const onSaved = vi.fn().mockResolvedValue(undefined);
  const source = { name: 'sales', displayName: '销售数据库', datasource: 'mysql', host: 'db', port: '3306', database: 'sales', user: 'app', active: true, modelingInUse: true, currentInUse: true };
  container = document.createElement('div'); document.body.append(container); root = createRoot(container);
  await act(async () => root.render(<SetupConnection initial={source} onSaved={onSaved}/>));

  const identity = container.querySelector<HTMLInputElement>('#name')!;
  const displayName = container.querySelector<HTMLInputElement>('#displayName')!;
  expect(identity.disabled).toBe(true);
  expect(identity.value).toBe('sales');
  expect(displayName.value).toBe('销售数据库');

  await act(async () => {
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!;
    setter.call(displayName, '华东销售库');
    displayName.dispatchEvent(new Event('input', { bubbles: true }));
    displayName.dispatchEvent(new Event('change', { bubbles: true }));
  });
  const save = [...container.querySelectorAll('button')].find(button => button.textContent?.includes('保存并测试'))!;
  await act(async () => save.click());

  expect(request).toHaveBeenCalledWith('/datasources/sales', expect.objectContaining({ method: 'PUT' }));
  expect(JSON.parse(request.mock.calls[0][1].body)).toEqual(expect.objectContaining({
    name: 'sales', displayName: '华东销售库'
  }));
  expect(onSaved).toHaveBeenCalledWith('sales');
});
