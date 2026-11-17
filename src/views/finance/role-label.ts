const roleLabels: Record<string, string> = {
  platform: '平台',
  store: '门店',
  resource: '资源方',
  investor: '投资人',
  supplier: '供应商',
  leader: '团长'
};

export const financeRoleOptions = Object.entries(roleLabels).map(([value, label]) => ({ value, label }));

export function financeRoleLabel(role: string | null | undefined): string {
  if (!role) return '—';
  const key = role.toLowerCase();
  return roleLabels[key === 'channel' ? 'resource' : key] ?? role;
}
