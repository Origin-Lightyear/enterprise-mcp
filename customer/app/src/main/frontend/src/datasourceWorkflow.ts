export type DatasourceSaveNavigation = {
  importSourceName: string;
  view: 'modeling';
};

/**
 * 保存连接后始终进入结构探测，确保新增和编辑数据源使用同一条建模流程。
 */
export function resolveDatasourceSaveNavigation(sourceName: string): DatasourceSaveNavigation {
  return { importSourceName: sourceName, view: 'modeling' };
}
