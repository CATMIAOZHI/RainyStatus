// 环境绑定与共享类型定义
// 契约变更需同步：docs/api.md、App 端 DTO（app/src/main/java/com/rainy/status/data/remote/）

export interface Env {
  /** KV：存 device_status 与 current_mood 两个 key */
  STATUS_KV: KVNamespace;

  /** 心跳鉴权 token（wrangler secret put AUTH_TOKEN），绝不放 vars */
  AUTH_TOKEN: string;

  /**
   * 历史数据的 D1 数据库。**可选**：没绑定时历史功能整体关闭，
   * 当前状态与心跳照常工作（模板默认不带 D1，见 docs/design.md）。
   */
  HISTORY_DB?: D1Database;

  /**
   * Turnstile 密钥（wrangler secret put TURNSTILE_SECRET）。
   * 配了就要求图表接口先过人机验证；没配则按 HISTORY_REQUIRE_TURNSTILE 决定。
   */
  TURNSTILE_SECRET?: string;

  /** 静态资源绑定（wrangler.jsonc 的 assets.binding） */
  ASSETS?: Fetcher;

  /** ── 以下均为可选 vars，由部署者按需覆盖 ── */
  SITE_TITLE?: string;
  OWNER_NAME?: string;
  AVATAR_EMOJI?: string;
  /** 头像图片（https 外链 / 站内 `/xxx.png` / 小尺寸 data:image）。配了就用图片，没配或加载失败用 AVATAR_EMOJI */
  AVATAR_URL?: string;
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

  /** ── 历史与图表（可选，默认关闭）── */
  /** `true` 才对外提供 `/api/history`。默认 false：不配置就不暴露历史 */
  HISTORY_ENABLED?: string;
  /** `false` 可停止采集但保留已有历史；默认跟随 HISTORY_ENABLED */
  HISTORY_COLLECT?: string;
  /** 对外开放的档位，逗号分隔；目前只支持 `24h` */
  HISTORY_RANGES?: string;
  /** Turnstile 的 site key（公开值，会下发给前端渲染验证组件） */
  TURNSTILE_SITE_KEY?: string;
  /** 要求人机验证；未设置时按「配了 TURNSTILE_SECRET 就要求」处理 */
  TURNSTILE_REQUIRED?: string;

  /** ── 静态数据域（可选，默认关闭）：把 status.json 推给 R2，由独立域直出 ── */
  /** `true` 才发布；还要同时绑定 DATA_BUCKET 且 DATA_BASE_URL 合法才真正生效 */
  DATA_ENABLED?: string;
  /** 数据域基址，例如 `https://data.example.com`（只收 https、无尾斜杠） */
  DATA_BASE_URL?: string;
  /** R2 绑定（wrangler.jsonc 的 r2_buckets）；没绑定就自动不发布 */
  DATA_BUCKET?: R2Bucket;

  /** ── 配额熔断（可选，默认关闭）：R2 没有「用完停」，超限只能自己拉闸 ── */
  /** `true` 才启用巡检 */
  GUARD_ENABLED?: string;
  /** 月累计操作数阈值（默认 6_000_000，即免费额度 1000 万的 60%） */
  GUARD_MAX_MONTHLY?: string;
  /** 滚动 1 小时操作数阈值（默认 2_000_000） */
  GUARD_MAX_HOURLY?: string;
  /** 超限时要停用的数据域主机名（如 data.example.com） */
  GUARD_HOSTNAME?: string;
  /** 账号 id 与桶名（停域 API 需要） */
  GUARD_ACCOUNT_ID?: string;
  GUARD_BUCKET_NAME?: string;
  /** Cloudflare API token：Account Analytics Read + Workers R2 Storage Write（wrangler secret put CF_GUARD_TOKEN） */
  CF_GUARD_TOKEN?: string;
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
  /** 头像图片地址；null = 用 `avatar` 的 emoji 字符 */
  avatarUrl: string | null;
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
  /** 历史图表能力：前端据此决定显示「加载图表」按钮还是直接隐藏 */
  history: HistoryCapability;
  /**
   * 静态数据域基址（启用 DATA_* 时下发）；null = 数据走 `/api/status`。
   *
   * 前端在页面里找不到 `<meta name="rainystatus-data-base">` 时就用它，
   * 这样部署者只要配好 vars 就能让网页直读静态数据，不必再改 HTML。
   */
  dataBaseUrl: string | null;
}

/**
 * 历史能力描述（随 /api/status 下发）。
 *
 * `enabled` 为 false 时前端**不应**渲染图表卡：与其让访客点一个永远失败的按钮，
 * 不如一开始就不显示。
 */
export interface HistoryCapability {
  enabled: boolean;
  ranges: string[];
  /** `turnstile` = 需要人机验证；`none` = 只靠手动点击（较弱） */
  challenge: 'turnstile' | 'none';
  /** 公开的 site key；没有验证时为 null */
  siteKey: string | null;
  /**
   * 非 null = 因为**配置不完整**所以没开（例如要求人机验证但缺密钥）。
   *
   * 为什么要下发这个原因：配置不全时如果只是把图表卡藏起来，部署者只会看到
   * 「什么都没有」，既不知道哪里配错了、也不知道该去看文档。前端会把这句话
   * 直白地显示出来。
   */
  reason: HistoryBlockedReason | null;
}

/** 历史功能被配置挡住的原因（可枚举，前端按它选文案） */
export type HistoryBlockedReason = 'turnstile_not_configured';

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
