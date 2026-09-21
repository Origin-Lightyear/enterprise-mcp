/*
 * Data source management follows Canner/WrenAI GenBI Classic settings patterns.
 * Copyright (c) Canner. Licensed under AGPL-3.0; modified for enterprise-mcp.
 */
import { useState } from 'react';
import { Button, Layout, Modal, Space, Table, Tag, Typography, message } from 'antd';
import { Pencil, Play, Plus, RefreshCw, Trash2, Unplug } from 'lucide-react';
import { api } from './api';
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

  return <Layout className="app datasource-app">
    <ConsoleHeader active="datasources" onModeling={onModeling} onDatasources={() => undefined}/>
    <Content className="datasource-content">
      <div className="page-heading">
        <div><Typography.Title level={2}>数据源</Typography.Title><Typography.Paragraph type="secondary">管理可用于语义建模的数据库连接。应用更新后，建模内容会成为 Current MDL。</Typography.Paragraph></div>
        <Space wrap><Button icon={<RefreshCw size={16}/>} loading={loading} onClick={() => void onReload()}>刷新</Button><Button type="primary" icon={<Plus size={16}/>} onClick={onAdd}>添加数据库</Button></Space>
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
