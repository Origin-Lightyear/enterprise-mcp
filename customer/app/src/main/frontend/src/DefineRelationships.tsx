/*
 * Derived from Canner/WrenAI GenBI Classic v1-final DefineRelations.tsx.
 * Copyright (c) Canner. Licensed under AGPL-3.0; modified for enterprise-mcp.
 */
import { useMemo, useState } from 'react';
import { Alert, Button, Form, Modal, Popconfirm, Select, Space, Table, Tag, Typography, message } from 'antd';
import { Edit3, Plus, Trash2 } from 'lucide-react';
import { api } from './api';
import { relationshipName, removeRelationship, replaceRelationship } from './relationshipModeling';
import { Wizard } from './setup';
import type { Modeling, Relationship } from './types';

type EditorState = { fromModel: string; relationship?: Relationship } | null;

const CARDINALITIES = [
  { value: 'ONE_TO_ONE', label: '一对一' },
  { value: 'ONE_TO_MANY', label: '一对多' },
  { value: 'MANY_TO_ONE', label: '多对一' },
];

function cardinalityLabel(value: string) {
  return CARDINALITIES.find((item) => item.value === value)?.label || value;
}

export function DefineRelationships({ modeling, onBack, onComplete, onDatasources }: { modeling: Modeling; onBack: () => void; onComplete: (modeling: Modeling) => void; onDatasources: () => void }) {
  const [relationships, setRelationships] = useState<Relationship[]>(modeling.relationships);
  const [editor, setEditor] = useState<EditorState>(null);
  const [submitting, setSubmitting] = useState(false);
  const noRecommendations = modeling.relationships.length === 0;

  const submit = async (items: Relationship[]) => {
    setSubmitting(true);
    try {
      const result = await api<Modeling>('/modeling/relationships', { method: 'PUT', body: JSON.stringify({ revision: modeling.revision, profileName: modeling.profileName, relationships: items }) });
      onComplete(result);
    } catch (cause) {
      message.error(String(cause));
    } finally {
      setSubmitting(false);
    }
  };

  return <Wizard step={2} wide onDatasources={onDatasources}>
    <Typography.Title level={1}>定义关系</Typography.Title>
    <Typography.Paragraph>你可以在所选表之间创建关系。Wren 已根据数据库中的主键和外键给出建议，确认后的关系会加入数据模型。</Typography.Paragraph>
    {noRecommendations && <Alert className="relation-alert" type="info" showIcon message="没有推荐关系" description="所选表中未检测到可配对的主键和外键，你仍可手动添加关系。"/>}
    <div className="relation-groups">
      {modeling.models.map((model) => {
        const rows = relationships.filter((item) => item.fromModel === model.name);
        return <section className="relation-group" key={model.name}>
          <header><div><strong>{model.displayName}</strong><small>{model.name}</small></div><Button size="small" icon={<Plus size={14}/>} onClick={() => setEditor({ fromModel: model.name })}>添加</Button></header>
          <Table<Relationship> rowKey="name" size="small" pagination={false} dataSource={rows} locale={{ emptyText: '暂无关系' }} scroll={{ x: 720 }} columns={[
            { title: 'From', width: '29%', render: (_, item) => `${item.fromModel}.${item.fromColumns.join(' + ')}` },
            { title: 'To', width: '29%', render: (_, item) => `${item.toModel}.${item.toColumns.join(' + ')}` },
            { title: '类型', width: '24%', render: (_, item) => <Space size={6}><span>{cardinalityLabel(item.cardinality)}</span>{item.source === 'FOREIGN_KEY' && <Tag>自动生成</Tag>}</Space> },
            { title: '', width: 92, align: 'right', render: (_, item) => <Space size={4}>
              <Button type="text" aria-label={`编辑 ${item.name}`} title="编辑" icon={<Edit3 size={15}/>} onClick={() => setEditor({ fromModel: item.fromModel, relationship: item })}/>
              <Popconfirm title="确认删除此关系？" okText="删除" cancelText="取消" okButtonProps={{ danger: true }} onConfirm={() => setRelationships((current) => removeRelationship(current, item.name))}><Button danger type="text" aria-label={`删除 ${item.name}`} title="删除" icon={<Trash2 size={15}/>} /></Popconfirm>
            </Space> },
          ]}/>
        </section>;
      })}
    </div>
    <div className="wizard-actions relation-actions"><Button size="large" disabled={submitting} onClick={onBack}>返回</Button><Space wrap><Button type="text" size="large" disabled={submitting} onClick={() => void submit([])}>跳过此步骤</Button><Button type="primary" size="large" loading={submitting} onClick={() => void submit(relationships)}>完成</Button></Space></div>
    <RelationshipModal editor={editor} modeling={modeling} existing={relationships} onClose={() => setEditor(null)} onSave={(relationship) => {
      setRelationships((current) => replaceRelationship(current, relationship, editor?.relationship?.name));
      setEditor(null);
    }}/>
  </Wizard>;
}

function RelationshipModal({ editor, modeling, existing, onClose, onSave }: { editor: EditorState; modeling: Modeling; existing: Relationship[]; onClose: () => void; onSave: (relationship: Relationship) => void }) {
  const [form] = Form.useForm();
  const fromModel = Form.useWatch('fromModel', form);
  const toModel = Form.useWatch('toModel', form);
  const editing = Boolean(editor?.relationship);
  const modelOptions = useMemo(() => modeling.models.map((model) => ({ value: model.name, label: model.displayName })), [modeling.models]);
  const columnOptions = (name?: string) => modeling.models.find((model) => model.name === name)?.columns.map((column) => ({ value: column.name, label: column.displayName })) || [];

  return <Modal open={Boolean(editor)} title={editing ? '编辑关系' : '添加关系'} destroyOnClose onCancel={onClose} onOk={() => form.submit()} okText="保存" cancelText="取消">
    {editor && <Form form={form} layout="vertical" initialValues={{
      fromModel: editor.relationship?.fromModel || editor.fromModel,
      fromColumns: editor.relationship?.fromColumns,
      toModel: editor.relationship?.toModel,
      toColumns: editor.relationship?.toColumns,
      cardinality: editor.relationship?.cardinality || 'MANY_TO_ONE',
    }} onFinish={(values) => {
      if (values.fromColumns.length !== values.toColumns.length) {
        message.error('两端字段数量必须一致');
        return;
      }
      onSave({
        name: editor.relationship?.name || relationshipName(values.fromModel, values.toModel, existing.map((item) => item.name)),
        fromModel: values.fromModel,
        fromColumns: values.fromColumns,
        toModel: values.toModel,
        toColumns: values.toColumns,
        cardinality: values.cardinality,
        status: 'CONFIRMED',
        source: editing ? editor.relationship!.source : 'MANUAL',
      });
    }}>
      <div className="form-grid">
        <Form.Item name="fromModel" label="From model" rules={[{ required: true }]}><Select disabled options={modelOptions}/></Form.Item>
        <Form.Item name="fromColumns" label="From field" rules={[{ required: true }]}><Select mode="multiple" disabled={editing} options={columnOptions(fromModel)}/></Form.Item>
        <Form.Item name="toModel" label="To model" rules={[{ required: true }]}><Select disabled={editing} options={modelOptions.filter((item) => item.value !== fromModel)}/></Form.Item>
        <Form.Item name="toColumns" label="To field" rules={[{ required: true }]}><Select mode="multiple" disabled={editing} options={columnOptions(toModel)}/></Form.Item>
      </div>
      <Form.Item name="cardinality" label="类型" rules={[{ required: true }]}><Select options={CARDINALITIES}/></Form.Item>
    </Form>}
  </Modal>;
}
