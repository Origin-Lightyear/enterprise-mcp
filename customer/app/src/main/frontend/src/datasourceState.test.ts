import { describe, expect, it } from 'vitest';
import { datasourceCanBeDeleted, resolveModelingDatasource, resolveImportDatasource } from './datasourceState';
import type { Datasource, Modeling } from './types';

const sources: Datasource[] = [
  { name: 'first', datasource: 'mysql', host: 'db', port: '3306', database: 'one', user: 'app', active: true, modelingInUse: false, currentInUse: true },
  { name: 'second', datasource: 'postgres', host: 'db', port: '5432', database: 'two', user: 'app', active: false, modelingInUse: true, currentInUse: false },
];

describe('datasource state helpers', () => {
  it('resolves the datasource bound to the Modeling instead of the first or active item', () => {
    const modeling: Modeling = { revision: 1, profileName: 'second', models: [], relationships: [] };
    expect(resolveModelingDatasource(sources, modeling)?.database).toBe('two');
  });

  it('prevents deleting a datasource used by either Modeling or Current MDL', () => {
    expect(datasourceCanBeDeleted(sources[0])).toBe(false);
    expect(datasourceCanBeDeleted(sources[1])).toBe(false);
    expect(datasourceCanBeDeleted({ ...sources[0], modelingInUse: false, currentInUse: false })).toBe(true);
  });

  it('uses the explicitly requested datasource after adding or selecting a connection', () => {
    const modeling: Modeling = { revision: 1, profileName: 'first', models: [], relationships: [] };
    expect(resolveImportDatasource(sources, modeling, 'second')?.name).toBe('second');
  });
});
