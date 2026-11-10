// @vitest-environment happy-dom
import { act } from 'react';
import { createRoot } from 'react-dom/client';
import { afterEach, expect, it, vi } from 'vitest';
import { Modal } from 'antd';
import { DataSourcesPage } from './DataSourcesPage';

Object.assign(globalThis, { IS_REACT_ACT_ENVIRONMENT: true });
const { request } = vi.hoisted(() => ({ request: vi.fn() }));
vi.mock('./api', () => ({ api: request, downloadBackup: vi.fn(), restoreBackup: vi.fn() }));
const source = { name: 'sales', displayName: '销售数据库', datasource: 'mysql', host: 'db', port: '3306', database: 'sales', user: 'app', active: true, modelingInUse: true, currentInUse: true };
let root: ReturnType<typeof createRoot>;
let container: HTMLDivElement;
afterEach(async () => { await act(async () => { Modal.destroyAll(); root?.unmount(); }); container?.remove(); vi.restoreAllMocks(); });

it('enables deletion for an in-use source and submits a confirmed operation with digest and retry identity', async () => {
  const reload = vi.fn().mockResolvedValue([]);
  request.mockResolvedValue({ binding: {} });
  const confirm = vi.spyOn(Modal, 'confirm').mockReturnValue({ destroy: vi.fn(), update: vi.fn() });
  container = document.createElement('div'); document.body.append(container); root = createRoot(container);
  await act(async () => root.render(<DataSourcesPage datasources={[source]} loading={false} onAdd={vi.fn()} onEdit={vi.fn()} onModel={vi.fn()} onSync={vi.fn()} onReload={reload} onModeling={vi.fn()} mdlDigest="old-mdl"/>));
  const button = [...container.querySelectorAll('button')].find(item => item.textContent?.replace(/\s/g, '') === '删除')!;
  expect(button.disabled).toBe(false);
  await act(async () => button.click());
  expect(request).not.toHaveBeenCalled();
  const options = confirm.mock.calls[0][0];
  expect(String(options.content)).toContain('Current MDL');
  await act(async () => { await options.onOk?.(); });
  expect(request).toHaveBeenCalledWith('/datasources/sales', expect.objectContaining({ method: 'DELETE' }));
  expect(JSON.parse(request.mock.calls[0][1].body)).toEqual({ expectedMdlDigest: 'old-mdl', idempotencyKey: expect.any(String) });
  expect(reload).toHaveBeenCalledOnce();
});

it('edits only the display name inline without entering connection settings', async () => {
  const reload = vi.fn().mockResolvedValue([]);
  const editConnection = vi.fn();
  request.mockResolvedValue({ name: 'sales', displayName: '华东销售库' });
  container = document.createElement('div'); document.body.append(container); root = createRoot(container);
  await act(async () => root.render(<DataSourcesPage datasources={[source]} loading={false} onAdd={vi.fn()} onEdit={editConnection} onModel={vi.fn()} onSync={vi.fn()} onReload={reload} onModeling={vi.fn()} mdlDigest="old-mdl"/>));

  const rename = container.querySelector<HTMLButtonElement>('[aria-label="修改 销售数据库 的显示名称"]')!;
  await act(async () => rename.click());
  const input = container.querySelector<HTMLInputElement>('[aria-label="销售数据库的新显示名称"]')!;
  await act(async () => {
    const setter = Object.getOwnPropertyDescriptor(HTMLInputElement.prototype, 'value')!.set!;
    setter.call(input, '华东销售库');
    input.dispatchEvent(new Event('input', { bubbles: true }));
    input.dispatchEvent(new Event('change', { bubbles: true }));
  });
  await act(async () => container.querySelector<HTMLButtonElement>('[aria-label="保存显示名称"]')!.click());

  expect(request).toHaveBeenCalledWith('/datasources/sales/display-name', {
    method: 'PUT', body: JSON.stringify({ displayName: '华东销售库' }),
  });
  expect(editConnection).not.toHaveBeenCalled();
  expect(reload).toHaveBeenCalledOnce();
});
