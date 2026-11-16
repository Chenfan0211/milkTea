import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const require = createRequire(import.meta.url);
function loadPage(relative, overrides, globals = {}) {
  const filename = path.join(root, relative);
  let definition;
  vm.runInNewContext(
    fs.readFileSync(filename, 'utf8'),
    {
      require(name) {
        if (name === '../../utils/share') return { withShare: value => value };
        return name in overrides ? overrides[name] : require(path.resolve(path.dirname(filename), name));
      },
      Page(value) {
        definition = value;
      },
      console,
      ...globals
    },
    { filename }
  );
  return definition;
}

for (const [pageName, method] of [
  ['home', 'handleLoginTap'],
  ['profile', 'openLogin']
]) {
  test(`${pageName} 主动登录复用统一守卫，不在页内打开弹层`, () => {
    const calls = [];
    const page = loadPage(`pages/${pageName}/${pageName}.js`, {
      '../../utils/login-guard': {
        openLoginSheet(options) {
          calls.push(options);
        }
      }
    });
    page[method].call({
      data: { ...page.data },
      setData() {
        assert.fail('不应设置登录弹层状态');
      }
    });
    assert.equal(calls.length, 1);
    assert.ok(calls[0].reason);
    assert.equal('loginSheetVisible' in page.data, false);
  });
}

test('所有页面都不再挂载或注册旧登录弹层，组件文件仍保留', () => {
  function scan(directory) {
    for (const entry of fs.readdirSync(directory, { withFileTypes: true })) {
      const filename = path.join(directory, entry.name);
      if (entry.isDirectory()) scan(filename);
      else if (/\.(?:wxml|json)$/.test(filename)) {
        assert.doesNotMatch(fs.readFileSync(filename, 'utf8'), /login-sheet/, filename);
      }
    }
  }
  for (const directory of ['pages', 'packageRole', 'packageHealth', 'packageMall']) scan(path.join(root, directory));
  assert.ok(fs.existsSync(path.join(root, 'components/login-sheet/login-sheet.js')));
});

test('授权页恢复旧文案和绿色常态按钮，未勾选时不渲染原生授权按钮', () => {
  const wxml = fs.readFileSync(path.join(root, 'pages/auth-login/auth-login.wxml'), 'utf8');
  const wxss = fs.readFileSync(path.join(root, 'pages/auth-login/auth-login.wxss'), 'utf8');
  assert.match(wxml, />拒绝仅浏览<\/view>/);
  assert.match(wxml, />同意<\/button>/);
  assert.doesNotMatch(wxml, /auth-entry-tip|手机号授权登录<|is-disabled|disabled=/);
  const phoneButton = wxml.match(/<button\b[^>]*open-type="getPhoneNumber"[^>]*>/s)?.[0] || '';
  assert.match(phoneButton, /wx:if="\{\{agreementChecked\}\}"/);
  assert.match(wxml, /<view\s+wx:else[\s\S]*?class="auth-actions__primary"[\s\S]*?bindtap="handleAgreementRequired"/);
  assert.doesNotMatch(wxss, /is-disabled|opacity:/);
});

test('未勾选的绿色同意控件只提示，不准备或提交手机号授权', () => {
  const messages = [];
  const page = loadPage('pages/auth-login/auth-login.js', {
    '../../utils/login-guard': {
      toast(message) {
        messages.push(message);
      }
    },
    '../../utils/auth': {},
    '../../utils/api': {}
  });
  assert.equal(page.data.agreementChecked, false);
  page.handleAgreementRequired();
  page.handleAgree.call({ data: { agreementChecked: false } }, { detail: {} });
  assert.deepEqual(messages, ['请先阅读并勾选同意协议', '请先阅读并勾选同意协议']);
});

test('授权成功先返回来源页，导航完成后仅续跑一次原操作', () => {
  let navigation;
  let resumed = 0;
  const page = loadPage(
    'pages/auth-login/auth-login.js',
    {
      '../../utils/login-guard': {
        toast() {},
        flushPendingAction() {
          resumed += 1;
        }
      }
    },
    {
      getCurrentPages: () => [{ route: 'pages/profile/profile' }, { route: 'pages/auth-login/auth-login' }],
      wx: {
        navigateBack(options) {
          navigation = options;
        }
      }
    }
  );
  const instance = { data: { entryMode: false }, completed: false };
  page.afterBound.call(instance);
  assert.equal(instance.completed, true);
  assert.equal(navigation.delta, 1);
  assert.equal(resumed, 0, '来源页恢复前不得续跑');
  navigation.complete();
  assert.equal(resumed, 1);
});

test('登录或绑定后返回我的，远端手机号到达时立即刷新完整登录态', async () => {
  let phone = '';
  const profile = { nickname: '测试用户', points: 0, balance: 0, totalSpend: 0, growth: 0, phone: '' };
  const definition = loadPage(
    'pages/profile/profile.js',
    {
      '../../utils/user-profile': {
        DEFAULT_AVATAR: 'avatar',
        getUserProfile: () => profile,
        maskPhone: value => value,
        roundMoney: value => value,
        saveUserProfile: value => Object.assign(profile, value)
      },
      '../../utils/profile-banner': { DEFAULT_PROFILE_BANNERS: [], normalizeBanners: value => value },
      '../../utils/member-level': { buildLevelMeta: () => ({}), refreshMemberLevelsFromRemote: async () => {} },
      '../../utils/roles': {
        getCurrentBusinessRole: () => null,
        getPendingRoles: () => [],
        syncRolesFromRemote: async () => {}
      },
      '../../utils/auth': { getCachedUser: () => ({ token: 'test-token', phone }) },
      '../../utils/auth-state': {
        getAuthState: () => ({
          hasToken: true,
          level: phone ? 'full' : 'authorized',
          label: phone ? '已登录' : '未绑定手机号',
          phone
        })
      },
      '../../utils/api': {
        async fetchMe() {
          phone = '13800138000';
          return { phone };
        },
        fetchMyGiftCards: async () => [],
        fetchProfileConfig: async () => ({})
      },
      '../../utils/coupons': { getUsableCouponCount: () => 0, refreshCouponsFromRemote: async () => {} },
      '../../utils/points': { getPoints: () => 0, notifyPointsChanged() {} }
    },
    { getApp: () => ({ globalData: {} }) }
  );
  const instance = {
    ...definition,
    data: { ...definition.data },
    setData(patch) {
      Object.assign(this.data, patch);
    }
  };
  instance.onShow();
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(instance.data.phoneMasked, phone);
  assert.equal(instance.data.authStateLevel, 'full');
  assert.equal(instance.data.authLabel, '已登录');
});
