type BrowserCrypto = {
  randomUUID?: () => string;
  getRandomValues: (target: Uint8Array) => Uint8Array;
};

/**
 * 生成 MDL 更新幂等键，并兼容不开放 randomUUID 的 HTTP 管理页面。
 */
export function createIdempotencyKey(browserCrypto: BrowserCrypto = crypto): string {
  if (typeof browserCrypto.randomUUID === 'function') {
    return browserCrypto.randomUUID();
  }

  const bytes = browserCrypto.getRandomValues(new Uint8Array(16));
  bytes[6] = (bytes[6] & 0x0f) | 0x40;
  bytes[8] = (bytes[8] & 0x3f) | 0x80;
  const hex = Array.from(bytes, (value) => value.toString(16).padStart(2, '0'));
  return `${hex.slice(0, 4).join('')}-${hex.slice(4, 6).join('')}-${hex.slice(6, 8).join('')}-${hex.slice(8, 10).join('')}-${hex.slice(10).join('')}`;
}
