import type { Relationship } from './types';

export function replaceRelationship(items: Relationship[], relationship: Relationship, editingName?: string): Relationship[] {
  if (!editingName) return [...items, relationship];
  return items.map((item) => item.name === editingName ? relationship : item);
}

export function removeRelationship(items: Relationship[], name: string): Relationship[] {
  return items.filter((item) => item.name !== name);
}

export function relationshipName(fromModel: string, toModel: string, existingNames: string[]): string {
  const base = `${fromModel}_${toModel}`.replace(/[^A-Za-z0-9_]/g, '_');
  let candidate = base;
  let suffix = 2;
  while (existingNames.includes(candidate)) {
    candidate = `${base}_${suffix}`;
    suffix += 1;
  }
  return candidate;
}
