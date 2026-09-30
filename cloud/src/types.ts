// 环境绑定与共享类型定义
// 契约变更需同步：docs/api.md、App 端 DTO（app/src/main/java/com/rainy/status/data/remote/）

export interface Env {
  /** KV：存 device_status 与 current_mood 两个 key */
  STATUS_KV: KVNamespace;

  /** 心跳鉴权 token（wrangler secret put AUTH_TOKEN），绝不放 vars */
  AUTH_TOKEN: string;

  /** 静态资源绑定（wrangler.jsonc 的 assets.binding） */
  ASSETS?: Fetcher;

  /** ── 以下均为可选 vars，由部署者按需覆盖 ── */
  SITE_TITLE?: string;
  OWNER_NAME?: string;
  AVATAR_EMOJI?: string;
  OFFLINE_THRESHOLD_MS?: string;
  SHOW_TEMPERATURE?: string;
  SHOW_NETWORK?: string;
  SHOW_MOOD?: string;
  TIMEZONE_OFFSET_MINUTES?: string;
  DEFAULT_LANG?: string;
  NEXT_EXPECTED_MS?: string;
  /** 自定义状态文案，留空则用内置三语文案 */
  STATUS_TEXT_ONLINE?: string;
  STATUS_TEXT_OFFLINE?: string;
  STATUS_TEXT_GONE?: string;
  STATUS_TEXT_NO_DATA?: string;
}

/** KV: device_status */
export interface DeviceStatus {
  schemaVersion: number;
  /** 服务端写入时刻（epoch ms, UTC）—— 掉线判定只用这个 */
  lastSeenAt: number;
  /** 客户端上报时刻（epoch ms），仅作诊断 */
  clientTs: number | null;
  batteryPercent: number | null;
  charging: boolean | null;
  chargeSource: string | null;
  temperatureC: number | null;
  network: string | null;
  deviceName: string | null;
  appVersion: string | null;
  seq: number | null;
}

/** KV: current_mood */
export interface CurrentMood {
  schemaVersion: number;
  text: string;
  emoji: string | null;
  updatedAt: number;
}

export interface HeartbeatInput {
  batteryPercent: number | null;
  charging: boolean | null;
  chargeSource: string | null;
  temperatureC: number | null;
  network: string | null;
  deviceName: string | null;
  appVersion: string | null;
  clientTs: number | null;
  seq: number | null;
}

export interface MoodInput {
  text: string;
  emoji: string | null;
}

/** 站点公开配置：由 /api/status 下发，避免静态页硬编码个人信息 */
export interface SiteConfig {
  title: string;
  owner: string;
  avatar: string;
  showTemperature: boolean;
  showNetwork: boolean;
  showMood: boolean;
  /** null = 按访客浏览器时区显示（TIMEZONE_OFFSET_MINUTES=auto，默认）；数字 = 固定时区偏移（分钟） */
  timezoneOffsetMinutes: number | null;
  defaultLang: string;
  offlineThresholdMs: number;
  /**
   * 自定义状态文案；缺省（null）表示用内置的三语文案。
   *
   * 只有中文一种语言：内置文案会自动跟着访客语言切换，而自定义文案是部署者
   * 自己写的一句话，机器翻译只会更差。因此自定义了就固定用它，三语切换对它不生效。
   */
  customText: StatusCustomText;
}

/** 自定义状态文案。null = 未配置，前端回退到内置三语文案 */
export interface StatusCustomText {
  online: string | null;
  offline: string | null;
  gone: string | null;
  noData: string | null;
}

export const SCHEMA_VERSION = 1;
export const KV_KEY_DEVICE = 'device_status';
export const KV_KEY_MOOD = 'current_mood';
/** KV 读缓存最小值 30 秒（官方 limits 表 Minimum cacheTtl = 30s，默认 60s） */
export const KV_CACHE_TTL = 30;
export const MAX_BODY_BYTES = 4096;
export const MAX_MOOD_TEXT = 140;
export const MAX_MOOD_EMOJI = 8;
