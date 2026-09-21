import { describe, expect, it, vi } from 'vitest';

import { createIdempotencyKey } from './idempotencyKey';

describe('createIdempotencyKey', () => {
  it('uses crypto.randomUUID when the browser provides it', () => {
    const randomUUID = vi.fn(() => '5b3ea8a3-340a-43da-8bf6-bd0776b97548');

    expect(createIdempotencyKey({ randomUUID, getRandomValues: vi.fn() })).toBe(
      '5b3ea8a3-340a-43da-8bf6-bd0776b97548',
    );
    expect(randomUUID).toHaveBeenCalledOnce();
  });

  it('generates a UUID v4 when randomUUID is unavailable on an HTTP origin', () => {
    const bytes = Uint8Array.from({ length: 16 }, (_, index) => index);
    const cryptoWithoutRandomUUID = {
      getRandomValues: vi.fn((target: Uint8Array) => {
        target.set(bytes);
        return target;
      }),
    };

    const key = createIdempotencyKey(cryptoWithoutRandomUUID);

    expect(key).toBe('00010203-0405-4607-8809-0a0b0c0d0e0f');
    expect(key).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/);
  });
});
