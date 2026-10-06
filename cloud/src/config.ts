// 站点配置解析：所有个人信息都来自 env vars，代码里不硬编码任何用户名/标题
// 这样别人部署后看到的是自己的信息

import type { Env, SiteConfig } from './types';

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
  };
}

/** DEFAULT_LANG 白名单，避免把任意字符串塞给前端 */
export function sanitizeLang(lang: string | undefined): string {
  const v = (lang ?? '').trim();
  return v === 'zh-Hans' || v === 'zh-Hant' || v === 'en' ? v : 'zh-Hans';
}