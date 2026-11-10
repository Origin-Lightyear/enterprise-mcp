import { describe, expect, it } from 'vitest';
import { resolveModelingDatasource, resolveImportDatasource } from './datasourceState';
import type { Datasource, Modeling } from './types';

const sources: Datasource[] = [
  { name: 'first', displayName: '第一个数据库', datasource: 'mysql', host: 'db', port: '3306', database: 'one', user: 'app', active: true, modelingInUse: false, currentInUse: true },
  { name: 'second', displayName: '第二个数据库', datasource: 'postgres', host: 'db', port: '5432', database: 'two', user: 'app', active: false, modelingInUse: true, currentInUse: false },
];

describe('datasource state helpers', () => {
  it('resolves the datasource bound to the Modeling instead of the first or active item', () => {
    const modeling: Modeling = { revision: 1, profileName: 'second', models: [], relationships: [] };
    expect(resolveModelingDatasource(sources, modeling)?.database).toBe('two');
  });

  it('uses the explicitly requested datasource after adding or selecting a connection', () => {
    const modeling: Modeling = { revision: 1, profileName: 'first', models: [], relationships: [] };
    expect(resolveImportDatasource(sources, modeling, 'second')?.name).toBe('second');
  });
});
