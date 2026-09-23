/*
 * Data source management follows Canner/WrenAI GenBI Classic settings patterns.
 * Copyright (c) Canner. Licensed under AGPL-3.0; modified for enterprise-mcp.
 */
import { useRef, useState } from 'react';
import { Button, Layout, Modal, Space, Table, Tag, Typography, message } from 'antd';
import { Pencil, Play, Plus, RefreshCw, Trash2, Unplug } from 'lucide-react';
import { api, downloadBackup, restoreBackup } from './api';
import { ConsoleHeader } from './ConsoleHeader';
import { datasourceCanBeDeleted } from './datasourceState';
import type { Datasource } from './types';

const { Content } = Layout;

export function DataSourcesPage({ datasources, loading, onAdd, onEdit, onModel, onSync, onReload, onModeling }: {
  datasources: Datasource[];
  loading: boolean;
  onAdd: () => void;
  onEdit: (datasource: Datasource) => void;
  onModel: (datasource: Datasource) => void;
  onSync: (datasource: Datasource) => void;
  onReload: () => Promise<unknown>;
  onModeling: () => void;
}) {
  const [testing, setTesting] = useState('');
  const [deleting, setDeleting] = useState('');
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

  const remove = (datasource: Datasource) => {
    Modal.confirm({
      title: `删除数据源 ${datasource.name}？`,
      content: '只删除本地连接配置，不会删除数据库中的任何数据。此操作无法撤销。',
      okText: '删除',
      okButtonProps: { danger: true },
      cancelText: '取消',
      onOk: async () => {
        setDeleting(datasource.name);
        try {
          await api(`/datasources/${encodeURIComponent(datasource.name)}`, { method: 'DELETE' });
          message.success('数据源已删除');
          await onReload();
        } catch (error) {
          message.error(`删除失败：${String(error)}`);
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
          { title: '连接名称', dataIndex: 'name', width: 170, render: (value: string) => <strong>{value}</strong> },
          { title: '类型', dataIndex: 'datasource', width: 120, render: (value: string) => value === 'mssql' ? 'SQL Server' : value === 'postgres' ? 'PostgreSQL' : 'MySQL' },
          { title: '地址', width: 210, ellipsis: true, render: (_, item) => <span className="connection-address">{item.host}:{item.port}</span> },
          { title: '数据库', dataIndex: 'database', width: 160, ellipsis: true },
          { title: '状态', width: 260, render: (_, item) => <Space size={[4, 4]} wrap>{item.active && <Tag color="blue">当前连接</Tag>}{item.modelingInUse && <Tag color="gold">已有建模</Tag>}{item.currentInUse && <Tag color="green">当前生效</Tag>}</Space> },
          { title: '操作', width: 380, render: (_, item) => <Space size={4} wrap>
            <Button type="link" icon={<Play size={14}/>} onClick={() => onModel(item)}>{item.modelingInUse ? '进入 Modeling' : '用于建模'}</Button>
            {item.modelingInUse && <Button type="text" icon={<RefreshCw size={14}/>} onClick={() => onSync(item)}>重新探测</Button>}
            <Button type="text" icon={<RefreshCw size={14}/>} loading={testing === item.name} onClick={() => void test(item)}>测试</Button>
            <Button type="text" icon={<Pencil size={14}/>} onClick={() => onEdit(item)}>编辑</Button>
            <Button type="text" danger icon={<Trash2 size={14}/>} disabled={!datasourceCanBeDeleted(item)} loading={deleting === item.name} title={datasourceCanBeDeleted(item) ? '删除数据源' : '建模内容或 Current MDL 正在使用，不能删除'} onClick={() => remove(item)}>删除</Button>
          </Space> },
        ]}
      />
    </Content>
  </Layout>;
}
