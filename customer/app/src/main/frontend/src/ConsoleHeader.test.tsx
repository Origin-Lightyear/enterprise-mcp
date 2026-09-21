import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it, vi } from 'vitest';
import { ConsoleHeader } from './ConsoleHeader';

describe('console header', () => {
  it('keeps datasource switching available while Modeling is active', () => {
    const markup = renderToStaticMarkup(<ConsoleHeader active="modeling" onModeling={vi.fn()} onDatasources={vi.fn()}/>);

    expect(markup).toContain('Modeling');
    expect(markup).toContain('数据源');
    expect(markup).toContain('管理控制台主导航');
  });
});
