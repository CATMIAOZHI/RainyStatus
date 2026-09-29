// 站点配置解析：所有个人信息都来自 env vars，代码里不硬编码任何用户名/标题
// 这样别人部署后看到的是自己的信息

import type { Env, SiteConfig } from './types';

const DEFAULT_OFFLINE_THRESHOLD_MS = 30 * 60 * 1000; // 30 分钟
const DEFAULT_NEXT_EXPECTED_MS = 10 * 60 * 1000; // 10 分钟（心跳间隔）
const DEFAULT_TIMEZONE_OFFSET_MINUTES = 480; // UTC+8

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

/** 站点公开配置：由 /api/status 下发，网页据此渲染，无需硬编码 */
export function siteConfig(env: Env): SiteConfig {
  return {
    title: str(env.SITE_TITLE, 'RainyStatus'),
    owner: str(env.OWNER_NAME, 'WaterRainCat'),
    avatar: str(env.AVATAR_EMOJI, '☔'),
    showTemperature: bool(env.SHOW_TEMPERATURE, false),
    showNetwork: bool(env.SHOW_NETWORK, false),
    showMood: bool(env.SHOW_MOOD, true),
    timezoneOffsetMinutes: num(env.TIMEZONE_OFFSET_MINUTES, DEFAULT_TIMEZONE_OFFSET_MINUTES),
    defaultLang: str(env.DEFAULT_LANG, 'zh-Hans'),
    offlineThresholdMs: offlineThresholdMs(env),
  };
}

/** DEFAULT_LANG 白名单，避免把任意字符串塞给前端 */
export function sanitizeLang(lang: string | undefined): string {
  const v = (lang ?? '').trim();
  return v === 'zh-Hans' || v === 'zh-Hant' || v === 'en' ? v : 'zh-Hans';
}