// 站点配置回归测试
//
// 重点是自定义状态文案（STATUS_TEXT_*）：它会被原样写进状态徽章，
// 解析规则错了不会报错、只会让网页上显示空白或变形，属于「静默失效」，
// 因此这里把语义钉死。

import { describe, expect, it } from 'vitest';

import { historyConfig, siteConfig } from '../src/config.ts';
import type { Env } from '../src/types.ts';

/** siteConfig 只读 vars，KV / AUTH_TOKEN 与本次断言无关 */
function envOf(vars: Record<string, string>): Env {
  return vars as unknown as Env;
}

/** 历史功能需要一个 D1 绑定（值本身不参与解析，只要不是 undefined） */
function envWithDb(vars: Record<string, string>): Env {
  return { ...vars, HISTORY_DB: {} } as unknown as Env;
}

const TURNSTILE_OK = { TURNSTILE_SECRET: 's3cret', TURNSTILE_SITE_KEY: '0xSITEKEY' };

describe('siteConfig.customText', () => {
  it('未配置时为 null（前端回退到内置三语文案）', () => {
    const { customText } = siteConfig(envOf({}));
    expect(customText).toEqual({ online: null, offline: null, gone: null, noData: null });
  });

  it('空串与纯空白都视为未配置，不会把徽章配成空白', () => {
    const { customText } = siteConfig(
      envOf({ STATUS_TEXT_ONLINE: '', STATUS_TEXT_OFFLINE: '   ', STATUS_TEXT_GONE: '\n' }),
    );
    expect(customText.online).toBeNull();
    expect(customText.offline).toBeNull();
    expect(customText.gone).toBeNull();
  });

  it('去掉换行/制表符并 trim：徽章是「圆点 + 一句话」的横排，换行会撑变形', () => {
    const { customText } = siteConfig(envOf({ STATUS_TEXT_ONLINE: '  还在\r\n冒泡\t喵  ' }));
    expect(customText.online).toBe('还在 冒泡 喵');
  });

  it('超长按码点截断到 40（emoji 不会被切成半个代理对）', () => {
    const emojis = '🐱'.repeat(50);
    const { customText } = siteConfig(envOf({ STATUS_TEXT_GONE: emojis }));
    expect([...(customText.gone ?? '')].length).toBe(40);
    expect(customText.gone).toBe('🐱'.repeat(40));

    const han = '喵'.repeat(45);
    expect(siteConfig(envOf({ STATUS_TEXT_OFFLINE: han })).customText.offline).toBe('喵'.repeat(40));
  });

  it('刚好 40 码点原样保留，不误伤边界', () => {
    const exact = '喵'.repeat(40);
    expect(siteConfig(envOf({ STATUS_TEXT_NO_DATA: exact })).customText.noData).toBe(exact);
  });

  it('控制字符与零宽字符视为未配置：它们在页面上渲染为空白，等于「配了看不出」', () => {
    const { customText } = siteConfig(
      envOf({ STATUS_TEXT_ONLINE: '\u0000\u200B\uFEFF', STATUS_TEXT_OFFLINE: '\u0000' }),
    );
    expect(customText.online).toBeNull();
    expect(customText.offline).toBeNull();
  });

  it('不误伤 emoji 组合序列：ZWJ（U+200D）必须保留', () => {
    const family = '👨\u200D👩\u200D👧';
    expect(siteConfig(envOf({ STATUS_TEXT_GONE: family })).customText.gone).toBe(family);
  });

  it('四个状态互不串台', () => {
    const { customText } = siteConfig(
      envOf({ STATUS_TEXT_ONLINE: '在线', STATUS_TEXT_OFFLINE: '掉线', STATUS_TEXT_GONE: '失联', STATUS_TEXT_NO_DATA: '没数据' }),
    );
    expect(customText).toEqual({ online: '在线', offline: '掉线', gone: '失联', noData: '没数据' });
  });
});

describe('siteConfig.avatarUrl', () => {
  it('未配置 / 空串 / 纯空白 → null（前端回退到 AVATAR_EMOJI）', () => {
    expect(siteConfig(envOf({})).avatarUrl).toBeNull();
    expect(siteConfig(envOf({ AVATAR_URL: '' })).avatarUrl).toBeNull();
    expect(siteConfig(envOf({ AVATAR_URL: '   ' })).avatarUrl).toBeNull();
  });

  it('站内路径与 https 外链原样放行，并 trim 掉首尾空白', () => {
    expect(siteConfig(envOf({ AVATAR_URL: '/avatar.png' })).avatarUrl).toBe('/avatar.png');
    expect(siteConfig(envOf({ AVATAR_URL: '  /img/me.jpg  ' })).avatarUrl).toBe('/img/me.jpg');
    const https = 'https://example.com/a.png?v=2';
    expect(siteConfig(envOf({ AVATAR_URL: https })).avatarUrl).toBe(https);
  });

  it('http:// 与协议相对地址被拒：前者会被浏览器当混合内容拦掉，后者等于外站', () => {
    expect(siteConfig(envOf({ AVATAR_URL: 'http://example.com/a.png' })).avatarUrl).toBeNull();
    expect(siteConfig(envOf({ AVATAR_URL: '//example.com/a.png' })).avatarUrl).toBeNull();
    // 反斜杠会被 URL 规范归一成 //host/x.png，等于绕开上一条，所以一律拒绝
    expect(siteConfig(envOf({ AVATAR_URL: '/\\evil.com/x.png' })).avatarUrl).toBeNull();
    expect(siteConfig(envOf({ AVATAR_URL: 'https://example.com\\a.png' })).avatarUrl).toBeNull();
  });

  it('只放行 data:image/*;base64,，其他 data: 协议一律拒绝', () => {
    const png = 'data:image/png;base64,iVBORw0KGgo=';
    expect(siteConfig(envOf({ AVATAR_URL: png })).avatarUrl).toBe(png);
    expect(siteConfig(envOf({ AVATAR_URL: 'data:text/html;base64,PHNjcmlwdD4=' })).avatarUrl).toBeNull();
    expect(siteConfig(envOf({ AVATAR_URL: 'data:image/png,notbase64' })).avatarUrl).toBeNull();
    // javascript: 这类明显不该出现在 img src 里的写法
    expect(siteConfig(envOf({ AVATAR_URL: 'javascript:alert(1)' })).avatarUrl).toBeNull();
  });

  it('超长地址被拒（data: URL 也不能当图床用）', () => {
    const long = 'data:image/png;base64,' + 'A'.repeat(4096);
    expect(siteConfig(envOf({ AVATAR_URL: long })).avatarUrl).toBeNull();
  });

  it('长度边界：恰好 4096 放行，多一个字符就拒', () => {
    const head = '/';
    const exact = head + 'A'.repeat(4095);
    expect(exact.length).toBe(4096);
    expect(siteConfig(envOf({ AVATAR_URL: exact })).avatarUrl).toBe(exact);
    expect(siteConfig(envOf({ AVATAR_URL: exact + 'A' })).avatarUrl).toBeNull();
  });

  it('配了图片也不影响 AVATAR_EMOJI：它是加载失败时的回退', () => {
    const { avatar, avatarUrl } = siteConfig(envOf({ AVATAR_URL: '/a.png', AVATAR_EMOJI: '🐱' }));
    expect(avatar).toBe('🐱');
    expect(avatarUrl).toBe('/a.png');
  });
});

// 历史功能是这个项目里唯一「配错了会静默失效或静默不设防」的部分，所以把它的
// 判定规则钉死在这里：默认全关、缺密钥一律不开、没实现的档位不许宣告。
describe('historyConfig', () => {
  it('默认全关：什么都不配时既没有接口也不采集', () => {
    const config = historyConfig(envOf({}));
    expect(config.enabled).toBe(false);
    expect(config.collect).toBe(false);
    expect(config.ranges).toEqual([]);
    expect(config.blocked).toBeNull();
  });

  it('开了 HISTORY_ENABLED 但没配 Turnstile：公开关闭（fail-closed），采集照旧', () => {
    const config = historyConfig(envWithDb({ HISTORY_ENABLED: 'true', HISTORY_RANGES: '24h' }));
    expect(config.enabled).toBe(false);
    expect(config.blocked).toBe('turnstile_not_configured');
    // 密钥没配好不该把数据也一起丢掉：配好后直接就能出图
    expect(config.collect).toBe(true);
    expect(siteConfig(envWithDb({ HISTORY_ENABLED: 'true' })).history).toMatchObject({
      enabled: false,
      ranges: [],
      siteKey: null,
      reason: 'turnstile_not_configured',
    });
  });

  it('只配了 secret 不够：前端没有 site key 就渲染不出验证组件，一样按没配好处理', () => {
    const config = historyConfig(envWithDb({ HISTORY_ENABLED: 'true', TURNSTILE_SECRET: 's3cret' }));
    expect(config.enabled).toBe(false);
    expect(config.blocked).toBe('turnstile_not_configured');
  });

  it('配全后开启，且默认要求人机验证（不是「配了才要求」）', () => {
    const config = historyConfig(envWithDb({ HISTORY_ENABLED: 'true', HISTORY_RANGES: '24h', ...TURNSTILE_OK }));
    expect(config.enabled).toBe(true);
    expect(config.requireTurnstile).toBe(true);
    expect(config.ranges).toEqual(['24h']);
    expect(config.blocked).toBeNull();
  });

  it('显式 TURNSTILE_REQUIRED=false 才允许免验证（默认绝不悄悄放过）', () => {
    const config = historyConfig(
      envWithDb({ HISTORY_ENABLED: 'true', HISTORY_RANGES: '24h', TURNSTILE_REQUIRED: 'false' }),
    );
    expect(config.enabled).toBe(true);
    expect(config.requireTurnstile).toBe(false);
    expect(config.blocked).toBeNull();
    expect(siteConfig(envWithDb({ HISTORY_ENABLED: 'true', HISTORY_RANGES: '24h' })).history.challenge).toBe('none');
  });

  it('只宣告实现里真正有的档位：7d/30d/1y 配了也被丢掉，避免前端点一个必然失败的档位', () => {
    const config = historyConfig(envWithDb({ HISTORY_ENABLED: 'true', HISTORY_RANGES: '7d, 24h ,1y,30d', ...TURNSTILE_OK }));
    expect(config.ranges).toEqual(['24h']);
  });

  it('只采集不公开：不开接口，但历史照记', () => {
    const config = historyConfig(envWithDb({ HISTORY_COLLECT: 'true', ...TURNSTILE_OK }));
    expect(config.enabled).toBe(false);
    expect(config.collect).toBe(true);
  });

  it('没有 D1 绑定时不采集也不公开（避免「开了开关却写不进任何地方」）', () => {
    const config = historyConfig(envOf({ HISTORY_ENABLED: 'true', HISTORY_COLLECT: 'true', ...TURNSTILE_OK }));
    expect(config.enabled).toBe(false);
    expect(config.collect).toBe(false);
  });
});
