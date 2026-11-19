export type AdminImageVariant = 'thumb' | 'display' | 'detail';

function appendImageVariant(value: string, variant?: AdminImageVariant): string {
  if (!variant || !/\/api\/v1\/files\/public\/[^?#]+\.(?:jpe?g|png)(?:[?#]|$)/i.test(value)) {
    return value;
  }
  if (/[?&]variant=[^&#]*/i.test(value)) return value;
  const queryAt = value.indexOf('?');
  const hashAt = value.indexOf('#');
  if (queryAt >= 0 && (hashAt < 0 || queryAt < hashAt)) {
    return `${value.slice(0, queryAt + 1)}variant=${encodeURIComponent(variant)}&${value.slice(queryAt + 1)}`;
  }
  const insertAt = hashAt >= 0 ? hashAt : value.length;
  return `${value.slice(0, insertAt)}?variant=${encodeURIComponent(variant)}${value.slice(insertAt)}`;
}

export function resolveAdminImageUrl(
  value: string | null | undefined,
  apiBaseUrl: string,
  variant?: AdminImageVariant
): string {
  const raw = String(value || '').trim();
  if (!raw.startsWith('/api/')) return raw;
  const base = apiBaseUrl.replace(/\/+$/, '');
  return appendImageVariant(base ? `${base}${raw}` : raw, variant);
}
