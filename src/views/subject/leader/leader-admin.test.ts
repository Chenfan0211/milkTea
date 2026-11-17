import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import test from 'node:test';
import { generatedRoutes } from '../../../router/elegant/routes';

const read = (path: string) => readFileSync(new URL(path, import.meta.url), 'utf8');

test('团长管理菜单挂在主体管理下并授予运营角色', () => {
  const subject = generatedRoutes.find(route => route.name === 'subject');
  const leader = subject?.children?.find(route => route.name === 'subject_leader');
  assert.equal(leader?.path, '/subject/leader');
  assert.deepEqual(leader?.meta?.roles, ['R_SUPER', 'R_OPERATION']);
  const migration = read('../../../../server/src/main/resources/db/migration/V105__subject_leader_menu.sql');
  assert.match(migration, /'subject_leader'[\s\S]*?p\.code = 'subject'/);
  assert.match(migration, /WHERE r\.code = 'R_OPERATION'/);
});

test('团长管理复用用户归属接口，读取影响后才确认停用', () => {
  const page = read('./index.vue');
  assert.match(page, /await createLeader\(/);
  assert.match(page, /await assignUserLeader\(/);
  assert.match(page, /fetchUserLeaders\(\{[^}]*leaderUserId/);
  assert.match(
    page,
    /await fetchLeaderDeactivationPreview\([\s\S]*?boundUserCount[\s\S]*?pendingOrderCount[\s\S]*?pendingSettlementAmount[\s\S]*?onPositiveClick:[\s\S]*?await removeLeader\(/
  );
});
