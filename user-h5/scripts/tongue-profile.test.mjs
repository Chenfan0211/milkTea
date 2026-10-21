import assert from 'node:assert/strict';
import test from 'node:test';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';

const require = createRequire(import.meta.url);
const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const storage = {};

globalThis.wx = {
  getStorageSync: key => (storage[key] === undefined ? '' : storage[key]),
  setStorageSync: (key, value) => {
    storage[key] = value;
  },
  removeStorageSync: key => {
    delete storage[key];
  }
};

const tongueProfile = require(path.join(root, 'utils/tongue-profile.js'));
const now = new Date(2026, 9, 19);

function resetStorage() {
  Object.keys(storage).forEach(key => delete storage[key]);
}

test('合法舌诊缓存优先于账号资料', async () => {
  resetStorage();
  storage[tongueProfile.TONGUE_PROFILE_STORAGE_KEY] = { gender: 'female', birthYear: '1991' };
  let fetchCount = 0;

  const result = await tongueProfile.resolveTongueProfile({
    now,
    fetchMe: () => {
      fetchCount += 1;
      return Promise.resolve({ gender: 'male', birthday: '2000-01-02' });
    }
  });

  assert.deepEqual(result, { gender: 'female', birthYear: '1991' });
  assert.equal(fetchCount, 0, '有合法缓存时不得请求账号资料');
});

test('无缓存时账号资料的完整性别和生日生效', async () => {
  resetStorage();

  const result = await tongueProfile.resolveTongueProfile({
    now,
    fetchMe: () => Promise.resolve({ gender: 'female', birthday: '2001-05-06' })
  });

  assert.deepEqual(result, { gender: 'female', birthYear: '2001' });
});

test('账号缺少性别时只兜底性别', async () => {
  resetStorage();

  const result = await tongueProfile.resolveTongueProfile({
    now,
    fetchMe: () => Promise.resolve({ gender: '', birthday: '1988-03-02' })
  });

  assert.deepEqual(result, { gender: 'male', birthYear: '1988' });
});

test('账号缺少生日时只兜底出生年', async () => {
  resetStorage();

  const result = await tongueProfile.resolveTongueProfile({
    now,
    fetchMe: () => Promise.resolve({ gender: 'female', birthday: '' })
  });

  assert.deepEqual(result, { gender: 'female', birthYear: '2006' });
});

test('账号资料请求失败时回落到男性和 20 岁', async () => {
  resetStorage();

  const result = await tongueProfile.resolveTongueProfile({
    now,
    fetchMe: () => Promise.reject(new Error('network down'))
  });

  assert.deepEqual(result, { gender: 'male', birthYear: '2006' });
});

test('损坏缓存不能阻止账号资料回退', async () => {
  resetStorage();
  storage[tongueProfile.TONGUE_PROFILE_STORAGE_KEY] = { gender: 'unknown', birthYear: '18' };

  const result = await tongueProfile.resolveTongueProfile({
    now,
    fetchMe: () => Promise.resolve({ gender: 'female', birthday: '1999-08-07' })
  });

  assert.deepEqual(result, { gender: 'female', birthYear: '1999' });
});

test('保存只写舌诊缓存，不修改用户资料缓存', () => {
  resetStorage();
  storage['milkTea:user-profile'] = { gender: 'female', birthday: '1990-01-01' };

  const saved = tongueProfile.saveTongueProfile({ gender: 'male', birthYear: '2006' });

  assert.deepEqual(saved, { gender: 'male', birthYear: '2006' });
  assert.deepEqual(storage[tongueProfile.TONGUE_PROFILE_STORAGE_KEY], saved);
  assert.deepEqual(storage['milkTea:user-profile'], { gender: 'female', birthday: '1990-01-01' });
});

test('出生年选项覆盖 1900 到当前年份并定位合法值', () => {
  const options = tongueProfile.birthYearOptions(now);
  assert.equal(options.length, 127);
  assert.equal(options[0], '1900');
  assert.equal(options[options.length - 1], '2026');
  assert.equal(tongueProfile.birthYearIndex('2008', options, now), options.indexOf('2008'));
});

test('出生年缺失或损坏时定位到当前年份减 20 岁', () => {
  const options = tongueProfile.birthYearOptions(now);
  assert.equal(tongueProfile.birthYearIndex('18', options, now), options.indexOf('2006'));
  assert.equal(tongueProfile.birthYearIndex('', options, now), options.indexOf('2006'));
});
