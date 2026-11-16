import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import vm from 'node:vm';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
function load(relative, modules, globals = {}) {
  const context = {
    module: { exports: {} },
    require(name) {
      assert.ok(name in modules, `未配置的依赖：${name}`);
      return modules[name];
    },
    console,
    setTimeout,
    clearTimeout,
    ...globals
  };
  vm.runInNewContext(fs.readFileSync(path.join(root, relative), 'utf8'), context, { filename: relative });
  return context.module.exports;
}

test('游客在首页选择堂食或自取时直接浏览菜单，不触发登录', async () => {
  let page;
  let loginCalls = 0;
  const routes = [];
  const app = { globalData: {} };
  load(
    'pages/home/home.js',
    {
      '../../utils/share': { withShare: value => value },
      '../../utils/api': {},
      '../../utils/user-profile': { getUserProfile: () => ({}) },
      '../../utils/coupons': {},
      '../../utils/login-guard': {
        requireLogin() {
          loginCalls += 1;
        }
      },
      '../../utils/auth-state': { isLoggedIn: () => false },
      '../../utils/feature-policy': {},
      '../../utils/home-hero': { DEFAULT_HOME_BANNERS: [] },
      '../../utils/banner-target': {}
    },
    {
      Page: value => {
        page = value;
      },
      getApp: () => app,
      wx: { switchTab: ({ url }) => routes.push(url) }
    }
  );
  for (const mode of ['dinein', 'pickup']) {
    await page.selectOrderMode({ currentTarget: { dataset: { mode } } });
    assert.equal(app.globalData.orderMode, mode);
  }
  assert.equal(loginCalls, 0);
  assert.deepEqual(routes, ['/pages/menu/menu', '/pages/menu/menu']);
});

function requestHarness({ token = '', refreshToken = '', retryCode = 0, promptLogin = false } = {}) {
  const modals = [];
  let cleared = 0;
  let attempts = 0;
  const request = load(
    'utils/request.js',
    {
      '../config': { USE_MOCK: false, BASE_URL: 'https://example.invalid' },
      './auth': {
        ensureLogin: async () => {
          if (refreshToken) token = refreshToken;
          return { token: refreshToken };
        },
        clearSession() {
          cleared += 1;
          token = '';
        }
      }
    },
    {
      wx: {
        getStorageSync: () => token,
        showModal: options => modals.push(options),
        request({ success }) {
          attempts += 1;
          success({ data: { code: attempts === 1 ? 8888 : retryCode, message: '登录失效' } });
        }
      }
    }
  );
  return {
    run: () => request.request({ url: '/api/v1/app/auth/me', promptLogin }),
    modals,
    get cleared() {
      return cleared;
    }
  };
}
for (const token of ['', 'expired-token']) {
  test(`后台刷新失败不弹登录窗（${token ? '旧会话' : '游客'}）`, async () => {
    const harness = requestHarness({ token });
    const result = await harness.run();
    assert.equal(result.code, 8888, '仍将错误交还调用方处理');
    assert.equal(harness.modals.length, 0);
    if (token) assert.equal(harness.cleared, 1, '失效会话应清理');
  });
}
test('重登后重试仍失效时清理会话，不打断浏览', async () => {
  const harness = requestHarness({ token: 'expired', refreshToken: 'also-expired', retryCode: 9999 });
  assert.equal((await harness.run()).code, 9999);
  assert.equal(harness.cleared, 1);
  assert.equal(harness.modals.length, 0);
});

test('用户主动操作可显式选择登录提示', async () => {
  const harness = requestHarness({ promptLogin: true });
  await harness.run();
  assert.equal(harness.modals.length, 1);
});

for (const scenario of ['network-failure', 'new-session']) {
  test(`刷新期间 ${scenario} 不得清理有效新会话`, async () => {
    let token = 'expired';
    let retry;
    let clears = 0;
    let attempts = 0;
    const request = load(
      'utils/request.js',
      {
        '../config': { USE_MOCK: false, BASE_URL: 'https://example.invalid' },
        './auth': {
          ensureLogin: async () => {
            token = 'refreshed';
            return { token };
          },
          clearSession() {
            token = '';
            clears += 1;
          }
        }
      },
      {
        wx: {
          getStorageSync: () => token,
          showModal() {
            assert.fail('后台不得弹登录窗');
          },
          request(options) {
            attempts += 1;
            if (attempts === 1) options.success({ data: { code: 8888 } });
            else retry = options;
          }
        }
      }
    );
    const pending = request.request({ url: '/api/v1/app/auth/me' });
    await Promise.resolve();
    await Promise.resolve();
    await Promise.resolve();
    assert.ok(retry, '应发出带刷新 token 的重试');
    if (scenario === 'network-failure') {
      retry.fail(new Error('offline'));
      await assert.rejects(pending, /offline/);
      assert.equal(token, 'refreshed');
    } else {
      token = 'new-phone-login';
      retry.success({ data: { code: 9999 } });
      assert.equal((await pending).code, 9999);
      assert.equal(token, 'new-phone-login');
    }
    assert.equal(clears, 0);
  });
}

test('登录弹层未同意协议也可跳过', () => {
  let component;
  const events = [];
  load(
    'components/login-sheet/login-sheet.js',
    {
      '../../utils/auth': {},
      '../../utils/api': {},
      '../../utils/login-guard': { toast() {} }
    },
    {
      Component: value => {
        component = value;
      }
    }
  );
  component.methods.handleSkip.call({ data: { agreementChecked: false }, triggerEvent: name => events.push(name) });
  assert.deepEqual(events, ['skip']);
});

for (const relative of ['pages/auth-login/auth-login.wxml', 'components/login-sheet/login-sheet.wxml']) {
  test(`${relative} 在原生授权前拦截未勾选状态`, () => {
    const wxml = fs.readFileSync(path.join(root, relative), 'utf8');
    const phoneButton = wxml.match(/<button\b[^>]*open-type="getPhoneNumber"[^>]*>/s)?.[0] || '';
    if (relative.startsWith('pages/')) {
      assert.match(phoneButton, /wx:if="\{\{agreementChecked\}\}"/);
      assert.match(wxml, /bindtap="handleAgreementRequired"/);
    } else {
      assert.match(phoneButton, /disabled="\{\{!agreementChecked\}\}"/);
    }
  });
}

function authPageHarness() {
  let definition;
  let cleared = 0;
  let returned = 0;
  let wentHome = 0;
  load(
    'pages/auth-login/auth-login.js',
    {
      '../../utils/share': { withShare: value => value },
      '../../utils/api': {},
      '../../utils/auth': {},
      '../../utils/login-guard': {
        clearPendingAction() {
          cleared += 1;
        },
        toast() {}
      },
      '../../utils/navigate': {
        go() {
          wentHome += 1;
        }
      }
    },
    {
      Page: value => {
        definition = value;
      },
      getCurrentPages: () => [{ route: 'pages/menu/menu' }, { route: 'pages/auth-login/auth-login' }],
      wx: {
        navigateBack() {
          returned += 1;
        }
      }
    }
  );
  const page = { ...definition, data: { agreementChecked: true } };
  return {
    page,
    get result() {
      return { cleared, returned, wentHome };
    }
  };
}
for (const action of ['handleReject', 'handleAgree']) {
  test(`授权页 ${action} 取消后回原页且清理待执行动作`, () => {
    const harness = authPageHarness();
    harness.page[action]({ detail: { errMsg: 'getPhoneNumber:fail user deny' } });
    assert.deepEqual(harness.result, { cleared: 1, returned: 1, wentHome: 0 });
  });
}
