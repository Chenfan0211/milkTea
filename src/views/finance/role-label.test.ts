import assert from 'node:assert/strict';
import test from 'node:test';
import { financeRoleLabel, financeRoleOptions } from './role-label';

test('财务角色对大小写团长类型使用同一名称并支持查询', () => {
  assert.equal(financeRoleLabel('LEADER'), '团长');
  assert.equal(financeRoleLabel('leader'), '团长');
  assert.deepEqual(
    financeRoleOptions.find(role => role.value === 'leader'),
    { value: 'leader', label: '团长' }
  );
});

test('保留资源方别名及未知角色的展示', () => {
  assert.equal(financeRoleLabel('CHANNEL'), '资源方');
  assert.equal(financeRoleLabel('resource'), '资源方');
  assert.equal(financeRoleLabel('CUSTOM'), 'CUSTOM');
  assert.equal(financeRoleLabel(null), '—');
});
