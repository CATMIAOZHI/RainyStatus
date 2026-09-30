// 站点配置回归测试
//
// 重点是自定义状态文案（STATUS_TEXT_*）：它会被原样写进状态徽章，
// 解析规则错了不会报错、只会让网页上显示空白或变形，属于「静默失效」，
// 因此这里把语义钉死。

import { describe, expect, it } from 'vitest';

import { siteConfig } from '../src/config.ts';
import type { Env } from '../src/types.ts';

/** siteConfig 只读 vars，KV / AUTH_TOKEN 与本次断言无关 */
function envOf(vars: Record<string, string>): Env {
  return vars as unknown as Env;
}

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
