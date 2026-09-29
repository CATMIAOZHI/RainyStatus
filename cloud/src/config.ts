// 站点配置解析：所有个人信息都来自 env vars，代码里不硬编码任何用户名/标题
// 这样别人部署后看到的是自己的信息

import type { Env, SiteConfig } from './types';

const DEFAULT_OFFLINE_THRESHOLD_MS = 30 * 60 * 1000; // 30 分钟
const DEFAULT_NEXT_EXPECTED_MS = 10 * 60 * 1000; // 10 分钟（心跳间隔）
/** 时区默认 `auto`：网页按【访客浏览器所在时区】显示时间，部署者无需配置 */
const TIMEZONE_AUTO = 'auto';

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

/** 站点公开配置：由 /api/status 下发，网页据此渲染，无需硬编码 */
export function siteConfig(env: Env): SiteConfig {
  return {
    title: str(env.SITE_TITLE, 'RainyStatus'),
    owner: str(env.OWNER_NAME, 'WaterRainCat'),
    avatar: str(env.AVATAR_EMOJI, '☔'),
    showTemperature: bool(env.SHOW_TEMPERATURE, false),
    showNetwork: bool(env.SHOW_NETWORK, false),
    showMood: bool(env.SHOW_MOOD, true),
    timezoneOffsetMinutes: timezoneOffsetMinutes(env),
    defaultLang: sanitizeLang(env.DEFAULT_LANG),
    offlineThresholdMs: offlineThresholdMs(env),
  };
}

/** DEFAULT_LANG 白名单，避免把任意字符串塞给前端 */
export function sanitizeLang(lang: string | undefined): string {
  const v = (lang ?? '').trim();
  return v === 'zh-Hans' || v === 'zh-Hant' || v === 'en' ? v : 'zh-Hans';
}