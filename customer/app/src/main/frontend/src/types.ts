export type Datasource = { name: string; datasource: string; host: string; port: string; database: string; user: string; active: boolean; modelingInUse: boolean; currentInUse: boolean };
export type Column = { name: string; displayName: string; description: string; type: string; rawType: string; notNull: boolean; primaryKey: boolean; visible: boolean };
export type Model = { name: string; displayName: string; description: string; source: { catalog: string; schema: string; table: string }; primaryKey: string[]; columns: Column[] };
export type Relationship = { name: string; fromModel: string; fromColumns: string[]; toModel: string; toColumns: string[]; cardinality: string; status: string; source: string };
export type Modeling = { revision: number; profileName?: string; setupCompleted?: boolean; models: Model[]; relationships: Relationship[]; updatedAt?: number };
export type CatalogState = { readiness: 'READY' | 'NOT_READY'; catalogDigest: string; mdlDigest: string; reasonCode: string; checkedAt: string };
export type SnapshotTable = { id: string; schema: string; name: string; comment: string; columns: Array<{ name: string; type: string; comment: string }>; primaryKey: string[] };
export type Snapshot = { tables: SnapshotTable[]; foreignKeys: unknown[] };
