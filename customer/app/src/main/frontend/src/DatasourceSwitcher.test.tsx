import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { DatasourceSwitcher } from './DatasourceSwitcher';
import type { Datasource } from './types';

const datasources: Datasource[] = [
  { name: 'sales', displayName: '销售数据库', datasource: 'mysql', host: 'db', port: '3306', database: 'sales_db', user: 'app', active: true, modelingInUse: true, currentInUse: true },
  { name: 'finance', displayName: '财务数据库', datasource: 'postgres', host: 'db', port: '5432', database: 'finance_db', user: 'app', active: false, modelingInUse: true, currentInUse: false },
  { name: 'new_source', displayName: '新数据库', datasource: 'mysql', host: 'db', port: '3306', database: 'new_db', user: 'app', active: false, modelingInUse: false, currentInUse: false },
];

describe('datasource switcher', () => {
  it('shows the current modeled datasource as a direct switch control', () => {
    const markup = renderToStaticMarkup(<DatasourceSwitcher datasources={datasources} currentProfileName="sales" loading={false} onSwitch={vi.fn()}/>);

    expect(markup).toContain('aria-label="切换数据源"');
    expect(markup).toContain('销售数据库 · sales_db');
  });
});
