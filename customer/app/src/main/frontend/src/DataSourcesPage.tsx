/*
 * Data source management follows Canner/WrenAI GenBI Classic settings patterns.
 * Copyright (c) Canner. Licensed under AGPL-3.0; modified for enterprise-mcp.
 */
import { useRef, useState } from 'react';
import { Button, Input, Layout, Modal, Space, Table, Tag, Tooltip, Typography, message } from 'antd';
import { Check, Pencil, Play, Plus, RefreshCw, Settings, Trash2, Unplug, X } from 'lucide-react';
import { api, downloadBackup, restoreBackup } from './api';
import { ConsoleHeader } from './ConsoleHeader';
import { createIdempotencyKey } from './idempotencyKey';
import type { Datasource } from './types';

const { Content } = Layout;

export function DataSourcesPage({ datasources, loading, onAdd, onEdit, onModel, onSync, onReload, onModeling, mdlDigest }: {
  datasources: Datasource[];
  loading: boolean;
  onAdd: () => void;
  onEdit: (datasource: Datasource) => void;
  onModel: (datasource: Datasource) => void;
  onSync: (datasource: Datasource) => void;
  onReload: () => Promise<unknown>;
  onModeling: () => void;
  mdlDigest: string;
}) {
  const [testing, setTesting] = useState('');
  const [deleting, setDeleting] = useState('');
  const [editingName, setEditingName] = useState('');
  const [displayNameDraft, setDisplayNameDraft] = useState('');
  const [renaming, setRenaming] = useState('');
  const [backupBusy, setBackupBusy] = useState(false);
  const [restoreBusy, setRestoreBusy] = useState(false);
  const restoreInput = useRef<HTMLInputElement>(null);

  const test = async (datasource: Datasource) => {
    setTesting(datasource.name);
    try {
      const result = await api<{ success: boolean; elapsedMs: number }>(`/datasources/${encodeURIComponent(datasource.name)}/test`, { method: 'POST' });
      message.success(`连接成功，耗时 ${result.elapsedMs} ms`);
    } catch (error) {
      message.error(`连接失败：${String(error)}`);
    } finally {
      setTesting('');
    }
  };

  const beginRename = (datasource: Datasource) => {
    setEditingName(datasource.name);
    setDisplayNameDraft(datasource.displayName);
  };

  const cancelRename = () => {
    if (renaming) return;
    setEditingName('');
    setDisplayNameDraft('');
  };

  const saveDisplayName = async (datasource: Datasource) => {
    const displayName = displayNameDraft.normalize('NFC').trim();
    if (!displayName) {
      message.error('请输入显示名称');
      return;
    }
    if (Array.from(displayName).length > 64) {
      message.error('显示名称最多 64 个字符');
      return;
    }
    if (displayName === datasource.displayName) {
      cancelRename();
      return;
    }
    setRenaming(datasource.name);
    try {
      await api(`/datasources/${encodeURIComponent(datasource.name)}/display-name`, {
        method: 'PUT', body: JSON.stringify({ displayName }),
      });
      setEditingName('');
      setDisplayNameDraft('');
      message.success('显示名称已更新');
      await onReload();
    } catch (error) {
      message.error(`显示名称更新失败：${String(error)}`);
    } finally {
      setRenaming('');
    }
  };

  const remove = (datasource: Datasource) => {
    const idempotencyKey = createIdempotencyKey();
    Modal.confirm({
      title: `删除数据源 ${datasource.displayName}？`,
      content: `将删除该数据源的连接配置和建模草稿。${datasource.currentInUse ? '同时从 Current MDL 中移除，依赖旧模型的未完成计划将失效；云端确认失败时不会删除。' : ''}不会删除真实数据库中的表和数据。此操作无法撤销，建议先下载备份。`,
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        setDeleting(datasource.name);
        try {
          await api(`/datasources/${encodeURIComponent(datasource.name)}`, { method: 'DELETE', body: JSON.stringify({ expectedMdlDigest: mdlDigest, idempotencyKey }) });
          message.success('数据源已删除');
          await onReload();
        } catch (error) {
          message.error(`删除失败：${String(error)}`);
          await onReload();
          throw error;
        } finally {
          setDeleting('');
        }
      },
    });
  };

  const backup = async () => {
    setBackupBusy(true);
    try { const blob = await downloadBackup(); const url = URL.createObjectURL(blob); const link = document.createElement('a'); link.href = url; link.download = 'wren-backup.zip'; link.click(); URL.revokeObjectURL(url); message.success('备份已下载'); }
    catch (error) { message.error(`备份失败：${String(error)}`); } finally { setBackupBusy(false); }
  };

  const restore = (file: File) => {
    Modal.confirm({ title: '恢复 Wren 备份？', content: '备份包含数据源凭据，并会覆盖当前数据源、建模草稿和 Current MDL。请确认已停止相关编辑。', okText: '恢复并覆盖', okButtonProps: { danger: true }, cancelText: '取消', onOk: async () => { setRestoreBusy(true); try { await restoreBackup(file); message.success('恢复成功，Runtime 正在重新同步'); await onReload(); } catch (error) { message.error(`恢复失败：${String(error)}`); } finally { setRestoreBusy(false); } } });
  };

  return <Layout className="app datasource-app">
    <ConsoleHeader active="datasources" onModeling={onModeling} onDatasources={() => undefined}/>
    <Content className="datasource-content">
      <div className="page-heading">
        <div><Typography.Title level={2}>数据源</Typography.Title><Typography.Paragraph type="secondary">管理可用于语义建模的数据库连接。应用更新后，建模内容会成为 Current MDL。</Typography.Paragraph></div>
        <Space wrap><Button icon={<RefreshCw size={16}/>} loading={loading} onClick={() => void onReload()}>刷新</Button><Button loading={backupBusy} onClick={() => void backup()}>备份下载</Button><Button loading={restoreBusy} danger onClick={() => restoreInput.current?.click()}>恢复备份</Button><input ref={restoreInput} type="file" accept=".zip,application/zip" hidden onChange={event => { const file = event.target.files?.[0]; event.currentTarget.value = ''; if (file) restore(file); }}/><Button type="primary" icon={<Plus size={16}/>} onClick={onAdd}>添加数据库</Button></Space>
      </div>
      <Table<Datasource>
        rowKey="name"
        dataSource={datasources}
        pagination={false}
        scroll={{ x: 980 }}
        locale={{ emptyText: <div className="empty-source"><Unplug size={28}/><span>还没有数据库连接</span></div> }}
        columns={[
          { title: '数据源名称', width: 260, render: (_, item) => <Space direction="vertical" size={0} style={{ width: '100%' }}>
            {editingName === item.name ? <Space.Compact style={{ width: '100%' }}>
              <Input autoFocus value={displayNameDraft} disabled={renaming === item.name} aria-label={`${item.displayName}的新显示名称`}
                onChange={event => setDisplayNameDraft(event.target.value)}
                onKeyDown={event => { if (event.key === 'Enter') void saveDisplayName(item); if (event.key === 'Escape') cancelRename(); }}/>
              <Tooltip title="保存显示名称"><Button aria-label="保存显示名称" icon={<Check size={15}/>} loading={renaming === item.name} onClick={() => void saveDisplayName(item)}/></Tooltip>
              <Tooltip title="取消"><Button aria-label="取消修改显示名称" icon={<X size={15}/>} disabled={renaming === item.name} onClick={cancelRename}/></Tooltip>
            </Space.Compact> : <Space size={4}>
              <strong>{item.displayName}</strong>
              <Tooltip title="修改显示名称"><Button type="text" size="small" aria-label={`修改 ${item.displayName} 的显示名称`} icon={<Pencil size={14}/>} onClick={() => beginRename(item)}/></Tooltip>
            </Space>}
            <Typography.Text type="secondary">{item.name}</Typography.Text>
          </Space> },
          { title: '类型', dataIndex: 'datasource', width: 120, render: (value: string) => value === 'mssql' ? 'SQL Server' : value === 'postgres' ? 'PostgreSQL' : 'MySQL' },
          { title: '地址', width: 210, ellipsis: true, render: (_, item) => <span className="connection-address">{item.host}:{item.port}</span> },
          { title: '数据库', dataIndex: 'database', width: 160, ellipsis: true },
          { title: '状态', width: 260, render: (_, item) => <Space size={[4, 4]} wrap>{item.active && <Tag color="blue">当前连接</Tag>}{item.modelingInUse && <Tag color="gold">已有建模</Tag>}{item.currentInUse && <Tag color="green">当前生效</Tag>}</Space> },
          { title: '操作', width: 380, render: (_, item) => <Space size={4} wrap>
            <Button type="link" icon={<Play size={14}/>} onClick={() => onModel(item)}>{item.modelingInUse ? '进入 Modeling' : '用于建模'}</Button>
            {item.modelingInUse && <Button type="text" icon={<RefreshCw size={14}/>} onClick={() => onSync(item)}>重新探测</Button>}
            <Button type="text" icon={<RefreshCw size={14}/>} loading={testing === item.name} onClick={() => void test(item)}>测试</Button>
            <Button type="text" icon={<Settings size={14}/>} onClick={() => onEdit(item)}>连接设置</Button>
            <Button type="text" danger icon={<Trash2 size={14}/>} disabled={Boolean(deleting)} loading={deleting === item.name} title="删除数据源及其建模配置" onClick={() => remove(item)}>删除</Button>
          </Space> },
        ]}
      />
    </Content>
  </Layout>;
}
