import { describe, expect, it } from 'vitest';
import { resolveDatasourceSaveNavigation } from './datasourceWorkflow';

describe('datasource modeling workflow', () => {
  it('enters schema introspection after an existing datasource is saved', () => {
    expect(resolveDatasourceSaveNavigation('sales')).toEqual({
      importSourceName: 'sales',
      view: 'modeling',
    });
  });
});
