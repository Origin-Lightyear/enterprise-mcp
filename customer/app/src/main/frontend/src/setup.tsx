/*
 * Derived from Canner/WrenAI GenBI Classic v1-final setup components.
 * Copyright (c) Canner. Licensed under AGPL-3.0; modified for enterprise-mcp.
 */
import { useState } from 'react';
import { Alert, Button, Form, Input, InputNumber, Layout, Select, Steps, Switch, Typography } from 'antd';
import { Database, Server } from 'lucide-react';
import { api } from './api';
import { ConsoleHeader } from './ConsoleHeader';
import type { Datasource } from './types';

type DatasourceKind = 'mysql' | 'postgres' | 'mssql';

const SOURCE_OPTIONS: Array<{ value: DatasourceKind; label: string; description: string; port: number }> = [
  { value: 'mysql', label: 'MySQL', description: 'MySQL 8.0 或兼容数据库', port: 3306 },
  { value: 'postgres', label: 'PostgreSQL', description: 'PostgreSQL 13 及以上版本', port: 5432 },
  { value: 'mssql', label: 'SQL Server', description: 'SQL Server 与 Azure SQL', port: 1433 },
];

export function Wizard({ step, wide = false, activeView = 'modeling', onModeling, onDatasources, children }: { step: number; wide?: boolean; activeView?: 'modeling' | 'datasources'; onModeling?: () => void; onDatasources?: () => void; children: React.ReactNode }) {
  const content = <main className={`wizard-shell ${onDatasources ? 'with-console-header' : ''}`}><div className={`wizard ${wide ? 'wide' : ''}`}>
    <div className="brand"><Database size={24}/><strong>Wren 数据建模</strong></div>
    <Steps current={step} items={[{ title: '添加数据库' }, { title: '选择 Models' }, { title: '配置关系' }]}/>
    <div className="wizard-body">{children}</div>
  </div></main>;
  if (!onDatasources) return content;
  return <Layout className="app"><ConsoleHeader active={activeView} onModeling={onModeling || (() => undefined)} onDatasources={onDatasources}/>{content}</Layout>;
}

export function SetupConnection({ onSaved, onCancel, onModeling, initial }: { onSaved: (name: string) => Promise<void>; onCancel?: () => void; onModeling?: () => void; initial?: Datasource }) {
  const [selected, setSelected] = useState<DatasourceKind | undefined>(initial?.datasource as DatasourceKind | undefined);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');

  if (!selected) {
    return <Wizard step={0} wide activeView="datasources" onModeling={onModeling} onDatasources={onCancel}>
      <Typography.Title level={1}>连接数据源</Typography.Title>
      <Typography.Paragraph>选择要用于创建语义模型的数据库类型。</Typography.Paragraph>
      <div className="source-grid">
        {SOURCE_OPTIONS.map((source) => <button className="source-option" type="button" key={source.value} onClick={() => setSelected(source.value)}>
          <span className={`source-logo ${source.value}`}><Server size={24}/></span>
          <span><strong>{source.label}</strong><small>{source.description}</small></span>
        </button>)}
      </div>
      {onCancel && <div className="wizard-actions"><Button size="large" onClick={onCancel}>取消</Button></div>}
    </Wizard>;
  }

  const source = SOURCE_OPTIONS.find((item) => item.value === selected)!;
  return <Wizard step={0} wide activeView="datasources" onModeling={onModeling} onDatasources={onCancel}>
    <Typography.Title level={1}>{initial ? '编辑数据源' : '连接数据源'}</Typography.Title>
    <Typography.Paragraph>填写连接信息。保存时会先测试连接，成功后才会写入配置。</Typography.Paragraph>
    <div className="connection-source"><span className={`source-logo ${source.value}`}><Server size={24}/></span><strong>{source.label}</strong></div>
    <Form className="connection-form" layout="vertical" initialValues={{ ...initial, datasource: selected, port: initial?.port ? Number(initial.port) : source.port, active: initial?.active ?? true, ssl: true }} onFinish={async (values) => {
      setBusy(true); setError('');
      try {
        await api(initial ? `/datasources/${encodeURIComponent(initial.name)}` : '/datasources', { method: initial ? 'PUT' : 'POST', body: JSON.stringify({
          ...values,
          active: initial ? initial.active : true,
          sslMode: values.ssl ? 'ENABLED' : 'DISABLED',
          driver: selected === 'mssql' ? 'ODBC Driver 18 for SQL Server' : undefined,
        }) });
        await onSaved(initial?.name || values.name);
      } catch (cause) {
        setError(String(cause));
      } finally {
        setBusy(false);
      }
    }} requiredMark>
      <Form.Item name="datasource" hidden><Input/></Form.Item>
      <Form.Item name="name" label="显示名称" rules={[{ required: true, message: '请输入连接名称' }, { pattern: /^[A-Za-z][A-Za-z0-9_-]{1,63}$/, message: '以英文字母开头，仅使用字母、数字、下划线或短横线' }]}><Input disabled={Boolean(initial)} placeholder="customer-primary"/></Form.Item>
      <Form.Item name="host" label="主机" rules={[{ required: true, message: '请输入数据库主机' }]}><Input placeholder="10.1.1.1"/></Form.Item>
      <Form.Item name="port" label="端口" rules={[{ required: true, message: '请输入端口' }]}><InputNumber min={1} max={65535} style={{ width: '100%' }}/></Form.Item>
      <Form.Item name="user" label="用户名" rules={[{ required: true, message: '请输入用户名' }]}><Input/></Form.Item>
      <Form.Item name="password" label="密码" extra={initial ? '留空则保留当前密码' : undefined} rules={initial ? [] : [{ required: true, message: '请输入密码' }]}><Input.Password placeholder={initial ? '留空保持不变' : undefined}/></Form.Item>
      <Form.Item name="database" label="数据库名称" rules={[{ required: true, message: '请输入数据库名称' }]}><Input/></Form.Item>
      {selected !== 'mssql' && <Form.Item name="ssl" label="使用 SSL" valuePropName="checked"><Switch/></Form.Item>}
      {error && <Alert className="connection-error" type="error" showIcon message="连接失败" description={error}/>} 
      <div className="wizard-actions"><Button size="large" disabled={busy} onClick={() => { if (initial) onCancel?.(); else { setSelected(undefined); setError(''); } }}>返回</Button><Button size="large" type="primary" htmlType="submit" loading={busy}>{initial ? '保存并测试' : '下一步'}</Button></div>
    </Form>
  </Wizard>;
}
