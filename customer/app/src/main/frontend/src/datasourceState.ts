import type { Datasource, Modeling } from './types';

export function resolveModelingDatasource(datasources: Datasource[], modeling: Modeling): Datasource | undefined {
  return datasources.find((item) => item.name === modeling.profileName);
}

export function resolveImportDatasource(datasources: Datasource[], modeling: Modeling, requestedName?: string): Datasource | undefined {
  return datasources.find((item) => item.name === requestedName)
    || resolveModelingDatasource(datasources, modeling)
    || datasources.find((item) => item.active)
    || datasources[0];
}

export function datasourceCanBeDeleted(datasource: Datasource): boolean {
  return !datasource.modelingInUse && !datasource.currentInUse;
}
