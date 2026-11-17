export function resolveAdminImageUrl(value: string | null | undefined, apiBaseUrl: string): string {
  const raw = String(value || '').trim();
  if (!raw.startsWith('/api/')) return raw;
  const base = apiBaseUrl.replace(/\/+$/, '');
  return base ? `${base}${raw}` : raw;
}
