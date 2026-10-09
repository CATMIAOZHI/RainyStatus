// 状态网页前端：三语 + 自动刷新
//
// 安全要点：所有来自接口的文本（心情、设备名）一律用 textContent 写入，
// 绝不使用 innerHTML —— 这是 XSS 防线。

'use strict';

const POLL_INTERVAL_MS = 60 * 1000; // 60 秒轮询一次
const LANG_STORAGE_KEY = 'rainystatus.lang';
/** 上一次成功读到的状态：接口挂了（含额度耗尽）时用它兜底，而不是显示「从未上报」 */
const STATUS_CACHE_KEY = 'rainystatus.status';
const MAX_STATUS_CACHE_BYTES = 32 * 1024;

const I18N = {
  'zh-Hans': {
    online: '还在冒泡',
    offline: '已掉线',
    gone: '好像很久没消息了',
    noData: '还没有收到过任何心跳',
    charging: '充电中',
    notCharging: '未充电',
    chargeFull: '已充满',
    battery: '电量',
    temperature: '温度',
    network: '网络',
    device: '设备',
    lastSeen: '最后在线',
    mood: '现在的心情',
    moodUpdated: '心情更新',
    justNow: '刚刚',
    minutesAgo: '{n} 分钟前',
    hoursAgo: '{n} 小时 {m} 分钟前',
    daysAgo: '{n} 天前',
    offlineFor: '已掉线 {d}',
    offlineHint: '超过 {n} 分钟没有心跳就会显示掉线',
    freshnessHint: '数据来自手机定时上报，最多可能滞后约 1 分钟。',
    tzAuto: '本页时间按你的时区显示（{tz}）',
    tzFixed: '本页时间按固定时区显示（{tz}）',
    refreshedAt: '本页刷新于 {t}',
    loadFailed: '无法读取状态，请稍后重试',
    neverSeen: '从未上报',
    unknown: '未知',
    networkWifi: 'Wi-Fi',
    networkCellular: '移动网络',
    networkEthernet: '有线网络',
    networkNone: '无连接',
  },
  'zh-Hant': {
    online: '還在冒泡',
    offline: '已離線',
    gone: '好像很久沒消息了',
    noData: '還沒有收到過任何心跳',
    charging: '充電中',
    notCharging: '未充電',
    chargeFull: '已充滿',
    battery: '電量',
    temperature: '溫度',
    network: '網路',
    device: '裝置',
    lastSeen: '最後在線',
    mood: '現在的心情',
    moodUpdated: '心情更新',
    justNow: '剛剛',
    minutesAgo: '{n} 分鐘前',
    hoursAgo: '{n} 小時 {m} 分鐘前',
    daysAgo: '{n} 天前',
    offlineFor: '已離線 {d}',
    offlineHint: '超過 {n} 分鐘沒有心跳就會顯示離線',
    freshnessHint: '資料來自手機定時上報，最多可能延遲約 1 分鐘。',
    tzAuto: '本頁時間依你的時區顯示（{tz}）',
    tzFixed: '本頁時間依固定時區顯示（{tz}）',
    refreshedAt: '本頁重新整理於 {t}',
    loadFailed: '無法讀取狀態，請稍後重試',
    neverSeen: '從未上報',
    unknown: '未知',
    networkWifi: 'Wi-Fi',
    networkCellular: '行動網路',
    networkEthernet: '有線網路',
    networkNone: '無連線',
  },
  en: {
    online: 'Alive and kicking',
    offline: 'Offline',
    gone: 'No signal for a long time',
    noData: 'No heartbeat received yet',
    charging: 'Charging',
    notCharging: 'Not charging',
    chargeFull: 'Fully charged',
    battery: 'Battery',
    temperature: 'Temperature',
    network: 'Network',
    device: 'Device',
    lastSeen: 'Last seen',
    mood: 'Current mood',
    moodUpdated: 'Mood updated',
    justNow: 'just now',
    minutesAgo: '{n} min ago',
    hoursAgo: '{n} h {m} min ago',
    daysAgo: '{n} d ago',
    offlineFor: 'Offline for {d}',
    offlineHint: 'Shows offline after {n} minutes without a heartbeat',
    freshnessHint: 'Reported by the phone on a timer; may lag up to ~1 minute.',
    tzAuto: 'Times are shown in your timezone ({tz})',
    tzFixed: 'Times are shown in a fixed timezone ({tz})',
    refreshedAt: 'Refreshed at {t}',
    loadFailed: 'Could not load status, please retry',
    neverSeen: 'never reported',
    unknown: 'unknown',
    networkWifi: 'Wi-Fi',
    networkCellular: 'Cellular',
    networkEthernet: 'Ethernet',
    networkNone: 'No connection',
  },
};

const SUPPORTED_LANGS = ['zh-Hans', 'zh-Hant', 'en'];

let site = null;
let lang = detectLang();

const el = (id) => document.getElementById(id);

function hasStoredLang() {
  try {
    const stored = localStorage.getItem(LANG_STORAGE_KEY);
    return !!stored && SUPPORTED_LANGS.includes(stored);
  } catch {
    return false; // localStorage 可能被禁用
  }
}

/**
 * 浏览器语言是否命中本站支持的某一种语言。
 *
 * 用途：`site.defaultLang` 只在**没命中**时兜底。若用它覆盖「已命中」的情况，
 * 默认配置（`zh-Hans`）下英文访客会被强制切成中文——`navigator.language` 自动判定
 * 就形同虚设了，比「配置项不生效」更糟。
 */
function navLangMatched() {
  const nav = (navigator.language || '').toLowerCase();
  return nav.startsWith('zh') || nav.startsWith('en');
}

function detectLang() {
  const fromUrl = new URLSearchParams(location.search).get('lang');
  if (fromUrl && SUPPORTED_LANGS.includes(fromUrl)) return fromUrl;

  if (hasStoredLang()) return localStorage.getItem(LANG_STORAGE_KEY);

  const nav = (navigator.language || '').toLowerCase();
  if (nav.startsWith('zh')) {
    return nav.includes('hant') || nav.includes('tw') || nav.includes('hk') || nav.includes('mo')
      ? 'zh-Hant'
      : 'zh-Hans';
  }
  if (nav.startsWith('en')) return 'en';
  // 未命中任何支持的语言 → 交给 site.defaultLang 兜底（见 render）
  return 'zh-Hans';
}

function t(key, vars) {
  // 自定义状态文案优先：部署者显式配了就固定用它（不跟语言切换）。
  // 这是刻意的——自定义文案是他自己写的一句话，机器翻译只会更差；
  // 内置文案才会跟着访客语言自动切换。
  const fromSite = site?.customText?.[key];
  // 这里用 `||` 而不是 `??`：空串也应当回退到内置文案（服务端已把空串转成 null，
  // 这是第二道防线——文案渲染成空白是「看不出来的故障」）。
  let text = fromSite || (I18N[lang] || I18N['zh-Hans'])[key] || I18N['zh-Hans'][key] || key;
  if (vars) {
    for (const [k, v] of Object.entries(vars)) {
      text = text.replace(`{${k}}`, String(v));
    }
  }
  return text;
}

/** 按码点安全格式化时长（不显示秒——心跳 10 分钟粒度下显示秒是误导） */
function formatDuration(ms) {
  const totalMinutes = Math.floor(ms / 60000);
  if (totalMinutes < 1) return t('justNow');
  if (totalMinutes < 60) return t('minutesAgo', { n: totalMinutes });
  const hours = Math.floor(totalMinutes / 60);
  const minutes = totalMinutes % 60;
  if (hours < 24) return t('hoursAgo', { n: hours, m: minutes });
  return t('daysAgo', { n: Math.floor(hours / 24) });
}

/**
 * 时间格式化。
 *
 * 时区策略：
 * - `site.timezoneOffsetMinutes === null`（默认）→ 用**访客浏览器自己的时区**渲染，
 *   访客看到的是「自己钟表上的时间」，不需要任何配置。
 * - 数字 → 固定时区偏移（分钟），所有人看到同一时钟（由部署者显式配置时使用）。
 *
 * 注意：这里刻意不用 `toLocaleString` —— 它受浏览器 locale 影响，位数与分隔符不稳定；
 * 手工 pad 能保证 `YYYY-MM-DD HH:mm` 在任何语言/地区下都长一样。
 */
function formatClock(epochMs) {
  const fixedOffset = site?.timezoneOffsetMinutes;
  const d = new Date(epochMs);
  const pad = (n) => String(n).padStart(2, '0');

  if (typeof fixedOffset === 'number' && Number.isFinite(fixedOffset)) {
    // 固定时区：把 epoch 平移后再按 UTC 读数
    const shifted = new Date(epochMs + fixedOffset * 60000);
    return `${shifted.getUTCFullYear()}-${pad(shifted.getUTCMonth() + 1)}-${pad(shifted.getUTCDate())} ` +
      `${pad(shifted.getUTCHours())}:${pad(shifted.getUTCMinutes())}`;
  }

  // 访客本地时区
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ` +
    `${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

/** 当前生效的 UTC 偏移（分钟）；固定时区用配置值，否则取访客本地 */ 
function effectiveOffsetMinutes() {
  const fixedOffset = site?.timezoneOffsetMinutes;
  if (typeof fixedOffset === 'number' && Number.isFinite(fixedOffset)) return fixedOffset;
  // getTimezoneOffset() 返回「UTC - 本地」的分钟数（东八区为 -480），符号相反
  return -new Date().getTimezoneOffset();
}

/** 形如 `UTC+08:00` / `UTC-03:30` */
function formatOffset(minutes) {
  const sign = minutes < 0 ? '-' : '+';
  const abs = Math.abs(minutes);
  const pad = (n) => String(n).padStart(2, '0');
  return `UTC${sign}${pad(Math.floor(abs / 60))}:${pad(abs % 60)}`;
}

/** 页脚说明：时间到底按哪个时区显示的（避免访客误读） */
function timezoneNote() {
  const fixedOffset = site?.timezoneOffsetMinutes;
  const tz = formatOffset(effectiveOffsetMinutes());
  const isFixed = typeof fixedOffset === 'number' && Number.isFinite(fixedOffset);
  return isFixed ? t('tzFixed', { tz }) : t('tzAuto', { tz });
}

function setText(node, text) {
  if (node) node.textContent = text;
}

/** 页面是否已经渲染过一次真实数据（用于决定失败时要不要用缓存兜底） */
let rendered = false;

function readStatusCache() {
  try {
    const raw = localStorage.getItem(STATUS_CACHE_KEY);
    if (!raw || raw.length > MAX_STATUS_CACHE_BYTES) return null;
    const parsed = JSON.parse(raw);
    return parsed && parsed.schemaVersion === 1 ? parsed : null;
  } catch {
    return null;
  }
}

function writeStatusCache(data) {
  try {
    const raw = JSON.stringify(data);
    if (raw.length > MAX_STATUS_CACHE_BYTES) return;
    localStorage.setItem(STATUS_CACHE_KEY, raw);
  } catch {
    // 存储被禁用或已满：不影响正常显示
  }
}

/**
 * 头像：配了 `AVATAR_URL` 显示图片，否则显示 `AVATAR_EMOJI` 字符。
 *
 * 图片加载失败（外链挂了、路径写错、混合内容被拦）时退回 emoji——
 * 留一个空白方块比显示字符难受得多。
 *
 * 用 dataset 记住「已经渲染过的组合」：这函数每 60 秒轮询都会调到，
 * 每次都重建 <img> 会让图片重新解码闪一下，而失败的图还会被反复重试。
 * 代价是「URL 没变、但资源后来才可用」时不会自己重试（一直停在 emoji 直到刷新）——
 * 这属于有意取舍：地址写错了本来就该改地址（改完是新一轮部署 + 重新加载页面），
 * 而为了这种情形每分钟重试一次，会让整页多一个周期性 404 与一次可见闪烁。
 */
function renderAvatar() {
  const node = el('avatar');
  if (!node) return;
  const emoji = site?.avatar ?? '';
  const image = site?.avatarUrl ?? '';
  const key = `${image}\u0000${emoji}`;
  if (node.dataset.avatarKey === key) return;
  node.dataset.avatarKey = key;

  node.textContent = '';
  if (!image) {
    node.textContent = emoji;
    return;
  }
  const img = document.createElement('img');
  img.alt = '';            // 装饰性图片：紧邻的标题已经说明了这是谁
  img.decoding = 'async';
  img.addEventListener('error', () => {
    // 身份校验：img 被移出 DOM 后加载仍会继续、error 照样派发。
    // 少了这一行，上一轮的失败回调会在新一轮图片 append 之后才执行，
    // 把刚渲染好的图擦掉——而 dataset 已是新 key，之后轮询会早退，图就永久丢了。
    if (node.dataset.avatarKey !== key) return;
    node.textContent = emoji;
  });
  img.src = image;         // 用属性赋值而不是 innerHTML，天然免疫标签注入
  node.appendChild(img);
}

/** 标签页图标跟着头像走；没配图片时保持浏览器默认图标 */
function applyFavicon() {
  const href = site?.avatarUrl;
  if (!href) return;
  let link = document.querySelector('link[rel="icon"]');
  if (!link) {
    link = document.createElement('link');
    link.rel = 'icon';
    document.head.appendChild(link);
  }
  if (link.getAttribute('href') !== href) link.setAttribute('href', href);
}

function applyStaticI18n() {
  setText(el('batteryLabel'), t('battery'));
  setText(el('moodTitle'), t('mood'));
  setText(el('metaTempLabel'), t('temperature'));
  setText(el('metaNetworkLabel'), t('network'));
  setText(el('metaDeviceLabel'), t('device'));
  setText(el('metaLastSeenLabel'), t('lastSeen'));
  setText(el('freshnessHint'), t('freshnessHint'));
  setText(el('timezoneNote'), timezoneNote());
  document.documentElement.lang = lang;

  for (const btn of document.querySelectorAll('.lang-btn')) {
    btn.setAttribute('aria-pressed', btn.dataset.lang === lang ? 'true' : 'false');
  }

  // 图表模块的文案也跟着一起切；它可能还没加载，用可选链兜住
  window.RSChart?.setLang(lang);
}

function networkLabel(value) {
  switch (value) {
    case 'wifi': return t('networkWifi');
    case 'cellular': return t('networkCellular');
    case 'ethernet': return t('networkEthernet');
    case 'none': return t('networkNone');
    default: return t('unknown');
  }
}

function render(data) {
  if (data.site) {
    site = data.site;

    // DEFAULT_LANG 生效点：`site.defaultLang` 是**兜底**，不是覆盖。
    // 只有「访客没显式选过语言」且「浏览器语言没命中任何受支持语言」时才用它——
    // 否则默认值就是 zh-Hans，英文访客会被强制切成中文，自动判定形同虚设。
    const choseExplicitly = new URLSearchParams(location.search).has('lang') || hasStoredLang();
    if (!choseExplicitly && !navLangMatched() && SUPPORTED_LANGS.includes(site.defaultLang) && site.defaultLang !== lang) {
      lang = site.defaultLang;
      applyStaticI18n();
    }

    // 站点信息来自服务端 vars，部署者看到的是自己的
    setText(el('title'), site.title);
    setText(el('owner'), site.owner);
    renderAvatar();
    applyFavicon();
    document.title = site.title;
    // 图表能力随站点配置下发：enabled 为 false 时图表卡根本不显示
    window.RSChart?.setSite(site);
  }

  const lastSeenAt = data.lastSeenAt;
  const offlineThresholdMs = data.offlineThresholdMs ?? 1800000;

  const dot = el('statusDot');
  const statusText = el('statusText');
  const statusDetail = el('statusDetail');

  if (lastSeenAt === null || lastSeenAt === undefined) {
    dot.className = 'dot';
    setText(statusText, t('noData'));
    setText(statusDetail, '');
  } else if (data.online) {
    dot.className = 'dot online';
    setText(statusText, t('online'));
    setText(statusDetail, `${formatClock(lastSeenAt)}（${formatDuration(data.offlineForMs)}）`);
  } else {
    // 掉线：显示掉线时间与时长了多久
    const veryLong = (data.offlineForMs ?? 0) > 6 * 60 * 60 * 1000;
    dot.className = veryLong ? 'dot gone' : 'dot offline';
    setText(statusText, veryLong ? t('gone') : t('offline'));
    setText(
      statusDetail,
      `${t('lastSeen')} ${formatClock(lastSeenAt)} · ${t('offlineFor', { d: formatDuration(data.offlineForMs) })}`,
    );
  }

  const device = data.device;
  const batterySection = el('batterySection');
  const hasBattery = device !== null && device !== undefined && typeof device.batteryPercent === 'number';

  if (!hasBattery) {
    // 两种情况必须区分开：
    // - 从未上报（device === null）→ 显示占位「—」+「从未上报」，让访客知道是「还没有数据」
    // - 上报过但没带电量（用户在设置里关了电量）→ 整块隐藏，展示无意义的「—」只会让人以为出故障
    batterySection.hidden = device !== null && device !== undefined;
    setText(el('batteryValue'), '—');
    setText(el('batterySub'), device ? '' : t('neverSeen'));
    el('batteryFill').style.width = '0%';
  } else {
    batterySection.hidden = false;
    const pct = device.batteryPercent;
    setText(el('batteryValue'), `${pct}%`);
    const fill = el('batteryFill');
    fill.style.width = `${Math.max(0, Math.min(100, pct))}%`;
    fill.className = device.charging ? 'battery-fill charging' : 'battery-fill';
    if (device.charging) {
      setText(el('batterySub'), pct >= 100 ? t('chargeFull') : t('charging'));
    } else {
      setText(el('batterySub'), t('notCharging'));
    }
    const bar = el('batteryBar');
    bar.setAttribute('aria-valuenow', String(pct));
    bar.setAttribute('aria-label', `${t('battery')} ${pct}%`);
  }

  const moodSection = el('moodSection');
  const mood = data.mood;
  const showMood = site?.showMood !== false;
  if (showMood && mood && mood.text) {
    moodSection.hidden = false;
    setText(el('moodEmoji'), mood.emoji || '');
    setText(el('moodText'), mood.text); // textContent → 无 XSS 风险
    setText(el('moodTime'), `${t('moodUpdated')} ${formatClock(mood.updatedAt)}（${formatDuration(Date.now() - mood.updatedAt)}）`);
  } else {
    moodSection.hidden = true;
  }

  const showTemp = site?.showTemperature === true;
  const temp = device?.temperatureC;
  const tempRow = el('metaTempRow');
  if (showTemp && typeof temp === 'number') {
    tempRow.hidden = false;
    setText(el('metaTemp'), `${temp.toFixed(1)} °C`);
  } else {
    tempRow.hidden = true;
  }

  const showNetwork = site?.showNetwork === true;
  const networkRow = el('metaNetworkRow');
  if (showNetwork && device?.network) {
    networkRow.hidden = false;
    setText(el('metaNetwork'), networkLabel(device.network));
  } else {
    networkRow.hidden = true;
  }

  const deviceRow = el('metaDeviceRow');
  if (device?.deviceName) {
    deviceRow.hidden = false;
    setText(el('metaDevice'), device.deviceName);
  } else {
    deviceRow.hidden = true;
  }

  setText(
    el('metaLastSeen'),
    lastSeenAt ? `${formatClock(lastSeenAt)}（${formatDuration(data.offlineForMs)}）` : t('neverSeen'),
  );

  const now = new Date();
  setText(el('refreshedAt'), t('refreshedAt', { t: formatClock(now.getTime()) }));
  // site 配置是首次响应才拿到的，时区说明必须在这里再刷一次
  setText(el('timezoneNote'), timezoneNote());

  const hint = el('freshnessHint');
  if (lastSeenAt !== null && !data.online) {
    setText(hint, t('offlineHint', { n: Math.round(offlineThresholdMs / 60000) }));
    hint.classList.add('stale');
  } else {
    setText(hint, t('freshnessHint'));
    hint.classList.remove('stale');
  }
}

async function load() {
  try {
    const res = await fetch('/api/status', { headers: { accept: 'application/json' } });
    if (!res.ok) throw new Error(`HTTP ${res.status}`);
    const data = await res.json();
    render(data);
    rendered = true;
    writeStatusCache(data);
    // 成功后清掉「上次读取失败」的残留标记（否则恢复后仍显示失败）
    const refreshed = el('refreshedAt');
    refreshed.classList.remove('stale');
  } catch {
    // 第一次就失败时，用本地缓存顶上：显示旧数据 + 明确标注「上次读取失败」，
    // 比把「服务暂时不可用」误报成「从未上报」诚实得多。
    if (!rendered) {
      const cached = readStatusCache();
      if (cached) {
        render(cached);
        rendered = true;
      }
    }
    setText(el('statusText'), t('loadFailed'));
    el('statusDot').className = 'dot gone';
    // 关键：读取失败时页面上的电量/时间都是**上一次成功的数据**，
    // 若不标记，访客会把陈旧数值当成刚刚刷新的（误以为手机还活着）。
    const refreshed = el('refreshedAt');
    setText(refreshed, t('loadFailed'));
    refreshed.classList.add('stale');
  }
}

function bindLangSwitch() {
  for (const btn of document.querySelectorAll('.lang-btn')) {
    btn.addEventListener('click', () => {
      lang = btn.dataset.lang;
      try {
        localStorage.setItem(LANG_STORAGE_KEY, lang);
      } catch {
        // 忽略存储失败
      }
      applyStaticI18n();
      load();
    });
  }
}

applyStaticI18n();
bindLangSwitch();
load();
setInterval(load, POLL_INTERVAL_MS);
// 回到前台时立即刷新一次
document.addEventListener('visibilitychange', () => {
  if (!document.hidden) load();
});

/**
 * 给图表模块（history.js）用的小桥。
 *
 * 为什么不各自复制一份格式化逻辑：时区口径只有一处才对——
 * 「访客本地时区」这个决定要是实现成两份，迟早会漂移成两种显示。
 */
window.RS = {
  t,
  formatClock,
  formatDuration,
  effectiveOffsetMinutes,
  formatOffset,
  getSite: () => site,
  getLang: () => lang,
};