/*
 * Modeling information architecture follows Canner/WrenAI GenBI Classic v1-final.
 * Copyright (c) Canner. Licensed under AGPL-3.0; modified for enterprise-mcp.
 */
import { useCallback, useEffect, useMemo, useState } from 'react';
import { Alert, Button, Drawer, Form, Input, Layout, List, Modal, Select, Space, Spin, Table, Tag, Typography, message } from 'antd';
import { Database, GitBranch, KeyRound, Plus, RefreshCw, Rocket, Search, Settings2 } from 'lucide-react';
import ReactFlow, { Background, Controls, Edge, MiniMap, Node } from 'reactflow';
import { ApiError, api, login, logout } from './api';
import { createIdempotencyKey } from './idempotencyKey';
import { DataSourcesPage } from './DataSourcesPage';
import { DatasourceSwitcher } from './DatasourceSwitcher';
import { ConsoleHeader } from './ConsoleHeader';
import { resolveModelingDatasource, resolveImportDatasource } from './datasourceState';
import { resolveDatasourceSaveNavigation } from './datasourceWorkflow';
import { DefineRelationships } from './DefineRelationships';
import { SetupConnection, Wizard } from './setup';
import type { CatalogState, Datasource, Modeling, Model, Relationship, Snapshot, SnapshotTable } from './types';

const { Sider, Content } = Layout;

export default function App() {
  const [authenticated, setAuthenticated] = useState(Boolean(sessionStorage.getItem('wren.csrf')));
  const [datasources, setDatasources] = useState<Datasource[]>([]);
  const [modeling, setModeling] = useState<Modeling>({ revision: 0, models: [], relationships: [] });
  const [catalogState, setCatalogState] = useState<CatalogState>({ readiness: 'NOT_READY', catalogDigest: '', mdlDigest: '', reasonCode: 'STARTING', checkedAt: '' });
  const [snapshot, setSnapshot] = useState<Snapshot | null>(null);
  const [jobId, setJobId] = useState('');
  const [loading, setLoading] = useState(false);
  const [loaded, setLoaded] = useState(false);
  const [loadError, setLoadError] = useState('');
  const [relationshipSetup, setRelationshipSetup] = useState(false);
  const [view, setView] = useState<'modeling' | 'datasources'>('modeling');
  const [addingDatasource, setAddingDatasource] = useState(false);
  const [editingDatasource, setEditingDatasource] = useState<Datasource>();
  const [importSourceName, setImportSourceName] = useState('');

  const load = useCallback(async () => {
    setLoading(true);
    setLoadError('');
    try {
      const [sourceResult, modelingResult, currentCatalogState] = await Promise.all([
        api<{ items: Datasource[] }>('/datasources'), api<Modeling>('/modeling'), api<CatalogState>('/catalog-state')
      ]);
      setDatasources(sourceResult.items); setModeling(modelingResult);
      setCatalogState(currentCatalogState);
      return sourceResult.items;
    } catch (error) {
      if (error instanceof ApiError && error.status === 401) setAuthenticated(false);
      else setLoadError(String(error));
    } finally { setLoading(false); setLoaded(true); }
  }, []);

  useEffect(() => { if (authenticated) void load(); }, [authenticated, load]);

  const openDatasources = () => {
    setView('datasources');
    setAddingDatasource(false);
    setEditingDatasource(undefined);
    setSnapshot(null);
    setRelationshipSetup(false);
    setImportSourceName('');
  };
  const openModeling = () => {
    setView('modeling');
    setAddingDatasource(false);
    setEditingDatasource(undefined);
  };
  const openDatasourceModeling = async (source: Datasource) => {
    setSnapshot(null);
    setRelationshipSetup(false);
    setAddingDatasource(false);
    setEditingDatasource(undefined);
    if (!source.modelingInUse) {
      setImportSourceName(source.name);
      setView('modeling');
      return;
    }
    const selectedModeling = await api<Modeling>(`/modeling?profileName=${encodeURIComponent(source.name)}`);
    setModeling(selectedModeling);
    setImportSourceName('');
    setView('modeling');
  };
  const startDatasourceImport = (source: Datasource) => {
    setSnapshot(null);
    setRelationshipSetup(false);
    setAddingDatasource(false);
    setEditingDatasource(undefined);
    setImportSourceName(source.name);
    setView('modeling');
  };

  if (!authenticated) return <Login onSuccess={() => setAuthenticated(true)} />;
  if (!loaded || loading) return <Wizard step={0}><div className="loading-state"><Spin size="large"/><span>正在加载配置...</span></div></Wizard>;
  if (loadError) return <Wizard step={0}><Alert type="error" showIcon message="Wren 管理服务不可用" description={loadError}/><div className="wizard-actions"><Button onClick={async () => { await logout(); setAuthenticated(false); }}>退出登录</Button><Button type="primary" onClick={() => void load()}>重试</Button></div></Wizard>;
  if (!datasources.length && !addingDatasource) return <SetupConnection onSaved={async (name) => { setImportSourceName(name); await load(); }} />;
  if (addingDatasource || editingDatasource) return <SetupConnection initial={editingDatasource} onModeling={openModeling} onCancel={openDatasources} onSaved={async (name) => {
    await load();
    setAddingDatasource(false);
    setEditingDatasource(undefined);
    const navigation = resolveDatasourceSaveNavigation(name);
    setImportSourceName(navigation.importSourceName);
    setView(navigation.view);
  }} />;
  if (view === 'datasources') return <DataSourcesPage datasources={datasources} loading={loading} onAdd={() => setAddingDatasource(true)} onEdit={setEditingDatasource} onModel={(source) => { void openDatasourceModeling(source).catch((error) => message.error(String(error))); }} onSync={startDatasourceImport} onReload={load} onModeling={openModeling} />;
  if (relationshipSetup || (!importSourceName && modeling.models.length > 0 && modeling.setupCompleted === false && !snapshot)) return <DefineRelationships modeling={modeling} onDatasources={openDatasources} onBack={() => { if (snapshot) setRelationshipSetup(false); else openDatasources(); }} onComplete={(result) => { setModeling(result); setRelationshipSetup(false); setSnapshot(null); setImportSourceName(''); }} />;
  const importSource = resolveImportDatasource(datasources, modeling, importSourceName)!;
  if (snapshot) return <SelectTables snapshot={snapshot} replacing={importSource.modelingInUse} onDatasources={openDatasources} onBack={() => setSnapshot(null)} onComplete={async (tableIds) => {
    const result = await api<Modeling>(`/modeling/from-snapshot/${jobId}`, { method: 'POST', body: JSON.stringify({ tableIds }) });
    setModeling(result); setRelationshipSetup(true);
  }} />;
  if (!modeling.models.length || importSourceName) return <StartImport datasource={importSource} loading={loading} onBack={openDatasources} onDatasources={openDatasources} onRefresh={load} onSnapshot={(id, value) => { setJobId(id); setSnapshot(value); }} />;
  return <Modeling modeling={modeling} setModeling={setModeling} catalogState={catalogState} setCatalogState={setCatalogState} datasources={datasources} onDatasources={openDatasources} onReimport={startDatasourceImport} onSwitchDatasource={openDatasourceModeling} />;
}

function Login({ onSuccess }: { onSuccess: () => void }) {
  const [busy, setBusy] = useState(false);
  return <main className="login-shell"><section className="login-panel"><div className="brand"><Database size={24}/><strong>Wren 数据建模</strong></div><Typography.Title level={2}>管理员登录</Typography.Title><Typography.Paragraph type="secondary">使用安装时生成的管理 Token 进入客户侧语义模型控制台。</Typography.Paragraph><Form layout="vertical" onFinish={async ({ token }) => { setBusy(true); try { await login(token); onSuccess(); } catch (e) { message.error(String(e)); } finally { setBusy(false); } }}><Form.Item name="token" label="管理 Token" rules={[{ required: true, message: '请输入管理 Token' }]}><Input.Password prefix={<KeyRound size={16}/>} autoFocus/></Form.Item><Button type="primary" htmlType="submit" loading={busy} block>登录</Button></Form></section></main>;
}

function StartImport({ datasource, loading, onBack, onDatasources, onRefresh, onSnapshot }: { datasource: Datasource; loading: boolean; onBack: () => void; onDatasources: () => void; onRefresh: () => Promise<unknown>; onSnapshot: (id:string,s:Snapshot)=>void }) {
  const [busy, setBusy] = useState(false);
  const start = async () => { setBusy(true); try { const schemas=datasource.datasource==='mssql'?['dbo']:datasource.datasource==='postgres'?['public']:[]; const job = await api<{jobId:string}>('/introspection-jobs',{method:'POST',body:JSON.stringify({profileName:datasource.name,schemas})}); for(let i=0;i<120;i++){ await new Promise(r=>setTimeout(r,500)); const state=await api<{status:string;error?:string}>(`/introspection-jobs/${job.jobId}`); if(state.status==='SUCCEEDED'){ const snap=await api<Snapshot>(`/introspection-jobs/${job.jobId}/snapshot`); onSnapshot(job.jobId,snap); return;} if(state.status==='FAILED') throw new Error(state.error || '探测失败'); } throw new Error('探测超时'); } catch(e){message.error(String(e));} finally{setBusy(false);} };
  return <Wizard step={1} onDatasources={onDatasources}><Typography.Title level={2}>选择数据表</Typography.Title><Typography.Paragraph type="secondary">已选择 <strong>{datasource.name}</strong>（{datasource.database}）。先探测 Schema，再选择需要进入语义模型的表。</Typography.Paragraph><Space wrap><Button onClick={onBack}>返回数据源</Button><Button type="primary" icon={<Search size={16}/>} loading={busy} onClick={start}>探测数据库结构</Button><Button icon={<RefreshCw size={16}/>} loading={loading} onClick={() => void onRefresh()}>刷新</Button></Space></Wizard>;
}

function SelectTables({ snapshot, replacing, onBack, onComplete, onDatasources }: { snapshot:Snapshot; replacing:boolean; onBack:()=>void; onComplete:(ids:string[])=>void; onDatasources:()=>void }) {
  const [selected, setSelected] = useState<React.Key[]>(snapshot.tables.map(t=>t.id));
  return <Wizard step={1} wide onDatasources={onDatasources}><Typography.Title level={2}>选择表以创建 Models</Typography.Title><Typography.Paragraph type="secondary">基于所选表生成模型，并在下一步审核主外键推导的关系。</Typography.Paragraph>{replacing && <Alert className="replace-warning" type="warning" showIcon message="继续将替换当前建模内容" description="Current MDL 不会立即改变；完成关系配置并点击应用更新后才会生效。"/>}<Table rowKey="id" dataSource={snapshot.tables} pagination={{pageSize:12}} rowSelection={{selectedRowKeys:selected,onChange:setSelected}} columns={[{title:'Schema',dataIndex:'schema',width:160},{title:'表名',dataIndex:'name'},{title:'说明',dataIndex:'comment',ellipsis:true},{title:'字段',render:(_,r:SnapshotTable)=>r.columns.length,width:100},{title:'主键',render:(_,r:SnapshotTable)=>r.primaryKey.join(', ')||'-'}]} scroll={{x:720}}/><div className="wizard-actions"><Button onClick={onBack}>返回</Button><Button type="primary" disabled={!selected.length} onClick={()=>onComplete(selected.map(String))}>下一步：配置关系</Button></div></Wizard>;
}

function Modeling({ modeling, setModeling, catalogState, setCatalogState, datasources, onDatasources, onReimport, onSwitchDatasource }: { modeling:Modeling; setModeling:(d:Modeling)=>void; catalogState:CatalogState; setCatalogState:(state:CatalogState)=>void; datasources:Datasource[]; onDatasources:()=>void; onReimport:(datasource:Datasource)=>void; onSwitchDatasource:(datasource:Datasource)=>Promise<void> }) {
  const [selected, setSelected] = useState<Model|null>(null); const [edit, setEdit] = useState(false); const [relationOpen,setRelationOpen]=useState(false); const [applying,setApplying]=useState(false); const [switchingDatasource,setSwitchingDatasource]=useState(false); const [search,setSearch]=useState('');
  const nodes:Node[] = useMemo(()=>modeling.models.map((m,i)=>({id:m.name,position:{x:(i%3)*330,y:Math.floor(i/3)*310},data:{label:<ModelNode model={m} relationships={modeling.relationships.filter(r=>r.fromModel===m.name||r.toModel===m.name)}/>},style:{width:280,padding:0,border:'none',background:'transparent'}})),[modeling]);
  const edges:Edge[] = useMemo(()=>modeling.relationships.map(r=>({id:r.name,source:r.fromModel,target:r.toModel,label:r.cardinality,animated:r.status==='CANDIDATE'})),[modeling]);
  const visibleModels=modeling.models.filter(m=>`${m.name} ${m.displayName}`.toLowerCase().includes(search.trim().toLowerCase()));
  const modelingDatasource = resolveModelingDatasource(datasources, modeling);
  const applyActions = <div className="apply-actions"><DatasourceSwitcher datasources={datasources} currentProfileName={modeling.profileName} loading={switchingDatasource} onSwitch={async(source)=>{setSwitchingDatasource(true);setSelected(null);try{await onSwitchDatasource(source);}catch(e){message.error(String(e));}finally{setSwitchingDatasource(false);}}}/><Tag icon={<Settings2 size={13}/>} color={catalogState.readiness==='READY'?'green':'red'}>{catalogState.readiness==='READY'?'当前生效':'规划未就绪'}</Tag><Button size="small" type="primary" icon={<Rocket size={14}/>} loading={applying} onClick={async()=>{setApplying(true);try{const selection=JSON.stringify({profileName:modeling.profileName});const check=await api<{valid:boolean;errors:unknown[]}>('/modeling/validation',{method:'POST',body:selection});if(!check.valid)throw new Error('建模内容校验失败');const result=await api<{binding:{catalogDigest:string;mdlDigest:string};appliedAt:string}>('/mdl-update',{method:'POST',body:JSON.stringify({profileName:modeling.profileName,expectedMdlDigest:catalogState.mdlDigest,idempotencyKey:createIdempotencyKey()})});setCatalogState({readiness:'READY',catalogDigest:result.binding.catalogDigest,mdlDigest:result.binding.mdlDigest,reasonCode:'',checkedAt:result.appliedAt});message.success('更新已应用');}catch(e){message.error(String(e));}finally{setApplying(false);}}}>应用更新</Button></div>;
  return <Layout className="app"><ConsoleHeader active="modeling" onModeling={() => undefined} onDatasources={onDatasources} actions={applyActions}/><Layout className="modeling-body"><Sider width={280} className="model-sider"><div className="tree-head"><strong>Models</strong><Space><Button type="text" size="small" aria-label="添加关系" title="添加关系" icon={<GitBranch size={15}/>} onClick={()=>setRelationOpen(true)}/><Button type="text" size="small" aria-label="重新探测数据源结构" icon={<RefreshCw size={15}/>} title="重新探测数据源结构" disabled={!modelingDatasource} onClick={()=>{if(modelingDatasource)onReimport(modelingDatasource);}}/></Space></div><Input prefix={<Search size={14}/>} placeholder="搜索模型" className="tree-search" value={search} onChange={event=>setSearch(event.target.value)}/><List dataSource={visibleModels} locale={{emptyText:'没有匹配的模型'}} renderItem={m=><List.Item className={selected?.name===m.name?'selected':''} onClick={()=>setSelected(m)}><Database size={15}/><span>{m.displayName}</span><small>{m.columns.length}</small></List.Item>}/><div className="source-foot">{modelingDatasource ? `${modelingDatasource.name} · ${modelingDatasource.database}` : modeling.profileName || '未绑定数据源'}</div></Sider><Content className="canvas"><ReactFlow nodes={nodes} edges={edges} minZoom={0.2} fitView onNodeClick={(_,n)=>setSelected(modeling.models.find(m=>m.name===n.id)||null)}><MiniMap/><Controls/><Background gap={16}/></ReactFlow></Content></Layout><Drawer width={760} open={Boolean(selected)} onClose={()=>setSelected(null)} title={selected?.displayName} extra={<Button onClick={()=>setEdit(true)}>编辑 Metadata</Button>}>{selected&&<ModelDetails model={selected}/>}</Drawer>{selected&&<EditModel open={edit} model={selected} revision={modeling.revision} profileName={modeling.profileName} onClose={()=>setEdit(false)} onSaved={d=>{setModeling(d);setSelected(d.models.find(m=>m.name===selected.name)||null);setEdit(false);}}/>}<RelationEditor open={relationOpen} modeling={modeling} onClose={()=>setRelationOpen(false)} onSaved={d=>{setModeling(d);setRelationOpen(false);}}/></Layout>;
}

function ModelNode({model,relationships}:{model:Model;relationships:Relationship[]}) { return <div className="model-node"><header><Database size={15}/><strong>{model.displayName}</strong></header><label>Columns</label>{model.columns.slice(0,6).map(c=><div className="node-row" key={c.name}><span>{c.primaryKey&&<KeyRound size={12}/>} {c.displayName}</span><small>{c.type}</small></div>)}{model.columns.length>6&&<div className="more">and {model.columns.length-6} more</div>}<label>Relationships</label>{relationships.slice(0,3).map(relation=><div className="node-row" key={relation.name}><span><GitBranch size={12}/> {relation.name}</span><small>{relation.cardinality}</small></div>)}{relationships.length>3&&<div className="more">and {relationships.length-3} more</div>}</div>; }
function ModelDetails({model}:{model:Model}) { return <><div className="meta-grid"><div><label>Name</label><p>{model.name}</p></div><div><label>Alias</label><p>{model.displayName}</p></div></div><label>Description</label><p>{model.description||'-'}</p><Typography.Title level={5}>Columns ({model.columns.length})</Typography.Title><Table rowKey="name" size="small" pagination={false} dataSource={model.columns} columns={[{title:'Name',dataIndex:'name'},{title:'Alias',dataIndex:'displayName'},{title:'Type',dataIndex:'type'},{title:'Key',render:(_,c)=>c.primaryKey?<KeyRound size={14}/>:null}]} scroll={{x:600,y:400}}/></>; }

function EditModel({open,model,revision,profileName,onClose,onSaved}:{open:boolean;model:Model;revision:number;profileName?:string;onClose:()=>void;onSaved:(d:Modeling)=>void}) { const [form]=Form.useForm(); return <Modal open={open} width={800} title="Edit metadata" onCancel={onClose} onOk={()=>form.submit()} destroyOnClose><Form form={form} layout="vertical" initialValues={{displayName:model.displayName,description:model.description}} onFinish={async values=>{try{const d=await api<Modeling>(`/modeling/models/${model.name}`,{method:'PUT',body:JSON.stringify({...values,revision,profileName})});onSaved(d);}catch(e){message.error(String(e));}}}><Form.Item name="displayName" label="Alias" rules={[{required:true}]}><Input/></Form.Item><Form.Item name="description" label="Description"><Input.TextArea rows={3}/></Form.Item></Form></Modal>; }

function RelationEditor({open,modeling,onClose,onSaved}:{open:boolean;modeling:Modeling;onClose:()=>void;onSaved:(d:Modeling)=>void}) { const [form]=Form.useForm(); const modelOptions=modeling.models.map(m=>({value:m.name,label:m.displayName})); const from=Form.useWatch('fromModel',form); const to=Form.useWatch('toModel',form); const columns=(name?:string)=>modeling.models.find(m=>m.name===name)?.columns.map(c=>({value:c.name,label:c.displayName}))||[]; return <Modal open={open} title="Add relationship" onCancel={onClose} onOk={()=>form.submit()} destroyOnClose><Form form={form} layout="vertical" initialValues={{cardinality:'MANY_TO_ONE',status:'CONFIRMED'}} onFinish={async v=>{const name=`${v.fromModel}_${v.toModel}_${Date.now()}`;try{const d=await api<Modeling>(`/modeling/relationships/${name}`,{method:'PUT',body:JSON.stringify({...v,revision:modeling.revision,profileName:modeling.profileName,fromColumns:[v.fromColumn],toColumns:[v.toColumn],source:'MANUAL'})});onSaved(d);}catch(e){message.error(String(e));}}}><div className="form-grid"><Form.Item name="fromModel" label="From model" rules={[{required:true}]}><Select options={modelOptions}/></Form.Item><Form.Item name="fromColumn" label="From field" rules={[{required:true}]}><Select options={columns(from)}/></Form.Item><Form.Item name="toModel" label="To model" rules={[{required:true}]}><Select options={modelOptions}/></Form.Item><Form.Item name="toColumn" label="To field" rules={[{required:true}]}><Select options={columns(to)}/></Form.Item></div><Form.Item name="cardinality" label="Type"><Select options={['ONE_TO_ONE','ONE_TO_MANY','MANY_TO_ONE'].map(value=>({value,label:value}))}/></Form.Item></Form></Modal>; }
