// 站点配置解析：所有个人信息都来自 env vars，代码里不硬编码任何用户名/标题
// 这样别人部署后看到的是自己的信息

import { RANGE_SPECS, type RangeKey } from './lib/history';
import type { Env, HistoryBlockedReason, SiteConfig } from './types';

const DEFAULT_OFFLINE_THRESHOLD_MS = 30 * 60 * 1000; // 30 分钟
const DEFAULT_NEXT_EXPECTED_MS = 10 * 60 * 1000; // 10 分钟（心跳间隔）
/** 时区默认 `auto`：网页按【访客浏览器所在时区】显示时间，部署者无需配置 */
const TIMEZONE_AUTO = 'auto';
/** 自定义状态文案上限（码点）。状态徽章是一行短句，过长会撑破布局 */
const MAX_CUSTOM_TEXT = 40;

/** 头像地址长度上限。data: URL 也算在内——Workers 的变量值本身有大小限制，别当图床用 */
const MAX_AVATAR_URL = 4096;

function num(value: string | undefined, fallback: number): number {
  if (value === undefined || value.trim() === '') return fallback;
  const parsed = Number(value);
  return Number.isFinite(parsed) ? parsed : fallback;
}

function bool(value: string | undefined, fallback: boolean): boolean {
  if (value === undefined) return fallback;
  const v = value.trim().toLowerCase();
  if (v === 'true' || v === '1' || v === 'yes') return true;
  if (v === 'false' || v === '0' || v === 'no') return false;
  return fallback;
}

function str(value: string | undefined, fallback: string): string {
  if (value === undefined) return fallback;
  const v = value.trim();
  return v === '' ? fallback : v;
}

/**
 * 自定义文案解析：空/未设置 → null（前端回退到内置三语文案）。
 *
 * 限长 40 码点并去掉换行：它会被直接写进状态徽章，过长会撑破布局、
 * 换行则会让「一个圆点 + 一句话」的徽章变形。超出部分截断而不是丢弃整条配置。
 *
 * 控制字符与零宽字符（`\u0000`、BOM、零宽空格）也一并折成空格：它们在页面上
 * 渲染为空白，会让「配了文案」看起来像「没配」，正是这里要避免的情况。
 * 注意别把 ZWJ（`\u200D`）算进去——它是 emoji 组合序列的连接符，去掉会拆散 👨‍👩‍👧。
 */
function custom(value: string | undefined): string | null {
  if (value === undefined) return null;
  const v = value.replace(/[\u0000-\u001F\u007F-\u009F\u200B\uFEFF]+/g, ' ').trim();
  if (v === '') return null;
  return [...v].slice(0, MAX_CUSTOM_TEXT).join('');
}

export function offlineThresholdMs(env: Env): number {
  const value = num(env.OFFLINE_THRESHOLD_MS, DEFAULT_OFFLINE_THRESHOLD_MS);
  // 下限 1 分钟，防止把「永远在线」配出来
  return value >= 60_000 ? value : DEFAULT_OFFLINE_THRESHOLD_MS;
}

export function nextExpectedMs(env: Env): number {
  return num(env.NEXT_EXPECTED_MS, DEFAULT_NEXT_EXPECTED_MS);
}

/**
 * 网页时间显示方式：
 * - `auto`（默认）→ 返回 null，前端用**访客自己的时区**渲染
 * - 数字（分钟偏移，如 480）→ 固定时区，所有人看到同一时钟
 *
 * 之所以默认 auto：状态页是给别人看的，访客看到「自己时区的时间」最不易读错；
 * 想统一口径（比如对外公示的固定时区）再填数字。
 */
export function timezoneOffsetMinutes(env: Env): number | null {
  const raw = env.TIMEZONE_OFFSET_MINUTES?.trim().toLowerCase();
  if (raw === undefined || raw === '' || raw === TIMEZONE_AUTO) return null;
  const parsed = Number(raw);
  // 合法性：UTC-12:00 ~ UTC+14:00
  if (!Number.isFinite(parsed) || parsed < -720 || parsed > 840) return null;
  return parsed;
}

/**
 * 头像图片地址：白名单三种形态，其余一律当「没配」（回退 emoji）。
 *
 * - 站内路径 `/avatar.png`（把图放进 `cloud/public/`）——推荐，零外部依赖
 * - `https://…` 外链
 * - `data:image/…;base64,…` 内联小图标（不想额外挂文件时用，注意别塞太大）
 *
 * 为什么排除 `http://`：状态页是 https，浏览器会把它当**混合内容**直接拦掉，
 * 配了也是空白，不如老老实实回退到 `AVATAR_EMOJI`。
 * `//host/x.png` 这种协议相对地址等于外站，一并拒绝；`javascript:`/`file:` 同理。
 */
export function avatarImageUrl(value: string | undefined): string | null {
  if (value === undefined) return null;
  const v = value.trim();
  if (v === '' || v.length > MAX_AVATAR_URL) return null;
  // 反斜杠一律拒绝：`/\evil.com/x.png` 会被 URL 规范归一成 `//evil.com/x.png`，
  // 正是下面那条「协议相对地址」要挡的东西；正常写法里也不会出现反斜杠
  if (v.includes('\\')) return null;
  if (v.startsWith('data:')) {
    return /^data:image\/[a-z0-9.+-]+;base64,/i.test(v) ? v : null;
  }
  if (v.startsWith('/') && !v.startsWith('//')) return v;
  return /^https:\/\/\S+$/i.test(v) ? v : null;
}

/** 站点公开配置：由 /api/status 下发，网页据此渲染，无需硬编码 */
export function siteConfig(env: Env): SiteConfig {
  return {
    title: str(env.SITE_TITLE, 'RainyStatus'),
    owner: str(env.OWNER_NAME, 'WaterRainCat'),
    avatar: str(env.AVATAR_EMOJI, '☔'),
    avatarUrl: avatarImageUrl(env.AVATAR_URL),
    showTemperature: bool(env.SHOW_TEMPERATURE, false),
    showNetwork: bool(env.SHOW_NETWORK, false),
    showMood: bool(env.SHOW_MOOD, true),
    timezoneOffsetMinutes: timezoneOffsetMinutes(env),
    defaultLang: sanitizeLang(env.DEFAULT_LANG),
    offlineThresholdMs: offlineThresholdMs(env),
    customText: {
      online: custom(env.STATUS_TEXT_ONLINE),
      offline: custom(env.STATUS_TEXT_OFFLINE),
      gone: custom(env.STATUS_TEXT_GONE),
      noData: custom(env.STATUS_TEXT_NO_DATA),
    },
    history: historyCapability(env),
  };
}

/** DEFAULT_LANG 白名单，避免把任意字符串塞给前端 */
export function sanitizeLang(lang: string | undefined): string {
  const v = (lang ?? '').trim();
  return v === 'zh-Hans' || v === 'zh-Hant' || v === 'en' ? v : 'zh-Hans';
}

/** 历史接口支持的档位 = **实现里真正有的档位**（唯一来源：lib/history.ts 的 RANGE_SPECS）。
 *  单独再写一份白名单迟早会和实现对不上：配置里宣告了 1y、接口却只认 24h，
 *  前端就会渲染一个必然报错的档位。 */
const KNOWN_RANGES = new Set<string>(Object.keys(RANGE_SPECS));

/**
 * 历史能力解析。
 *
 * 默认**全关**：模板不配置就没有历史接口，也不会有人因为「忘了关」而公开自己的作息。
 * 要开就同时配 `HISTORY_ENABLED=true`，档位由 `HISTORY_RANGES` 决定。
 *
 * 人机验证**默认就是必须的**（`TURNSTILE_REQUIRED` 默认 true）：公开的图表接口
 * 只要能被脚本反复拉，就能把 Worker 10 万请求/天打光——这不是「可选加固」，
 * 而是这个接口能存在的前提。确实想关（比如只在内网用）再显式写 `false`。
 *
 * 缺密钥时**公开关闭**（fail-closed），但采集照旧：把「没配好」变成「悄悄不验证」
 * 是最坏的一种失败方式，会让人以为已经受保护了。
 */
export function historyConfig(env: Env): {
  enabled: boolean;
  collect: boolean;
  ranges: RangeKey[];
  requireTurnstile: boolean;
  secret: string | null;
  siteKey: string | null;
  /** 非 null = 被配置挡住没开（前端会显示原因，而不是静默消失） */
  blocked: HistoryBlockedReason | null;
} {
  const publishWanted = bool(env.HISTORY_ENABLED, false) && env.HISTORY_DB !== undefined;
  // 采集与公开是两个开关：可以只记不晒（先攒数据、等想公开时再开），
  // 也可以只晒不记（停止记录但保留已有历史）。默认跟随 enabled，配置最少。
  const collect = bool(env.HISTORY_COLLECT, publishWanted) && env.HISTORY_DB !== undefined;
  const secret = env.TURNSTILE_SECRET !== undefined && env.TURNSTILE_SECRET.trim() !== '' ? env.TURNSTILE_SECRET.trim() : null;
  const siteKey =
    env.TURNSTILE_SITE_KEY !== undefined && env.TURNSTILE_SITE_KEY.trim() !== '' ? env.TURNSTILE_SITE_KEY.trim() : null;
  const wantsTurnstile = bool(env.TURNSTILE_REQUIRED, true);

  // 两样缺一不可：secret 用于服务端校验，siteKey 用于浏览器出令牌。
  // 只配了 secret 的话前端根本渲染不出验证组件，请求必然 403——等于开了个永远失败的开关。
  const canVerify = secret !== null && siteKey !== null;
  const blocked: HistoryBlockedReason | null = publishWanted && wantsTurnstile && !canVerify
    ? 'turnstile_not_configured'
    : null;

  const enabled = publishWanted && blocked === null;
  const requested = (env.HISTORY_RANGES ?? '')
    .split(',')
    .map((item) => item.trim())
    .filter((item): item is RangeKey => KNOWN_RANGES.has(item));

  return {
    enabled,
    collect,
    // 去重并保持稳定顺序，便于前端一次性渲染
    ranges: [...new Set(requested)],
    requireTurnstile: enabled && wantsTurnstile,
    secret,
    siteKey,
    blocked,
  };
}

/** 下发给前端的部分（不含密钥） */
function historyCapability(env: Env): SiteConfig['history'] {
  const config = historyConfig(env);
  // 一个档位都没配 = 图表卡无处可点，按未启用处理
  const enabled = config.enabled && config.ranges.length > 0;
  return {
    enabled,
    // 没启用就不宣告档位：宣告了也只是给前端一个必然失败的选项
    ranges: enabled ? config.ranges : [],
    challenge: enabled && config.requireTurnstile ? 'turnstile' : 'none',
    siteKey: enabled && config.requireTurnstile ? config.siteKey : null,
    reason: config.blocked,
  };
}