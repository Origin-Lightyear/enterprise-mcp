import { describe, expect, it } from 'vitest';
import { relationshipName, removeRelationship, replaceRelationship } from './relationshipModeling';
import type { Relationship } from './types';

const relation: Relationship = {
  name: 'Order_Customer', fromModel: 'Order', fromColumns: ['customer_id'],
  toModel: 'Customer', toColumns: ['id'], cardinality: 'MANY_TO_ONE',
  status: 'CONFIRMED', source: 'FOREIGN_KEY',
};

describe('relationship Modeling helpers', () => {
  it('replaces one reviewed relationship without changing list order', () => {
    const changed = { ...relation, cardinality: 'ONE_TO_ONE' };
    expect(replaceRelationship([relation], changed, relation.name)).toEqual([changed]);
  });

  it('removes a skipped recommendation', () => {
    expect(removeRelationship([relation], relation.name)).toEqual([]);
  });

  it('allocates a stable unique relationship name', () => {
    expect(relationshipName('Order', 'Customer', ['Order_Customer'])).toBe('Order_Customer_2');
  });
});
