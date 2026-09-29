// 状态网页前端：三语 + 自动刷新
//
// 安全要点：所有来自接口的文本（心情、设备名）一律用 textContent 写入，
// 绝不使用 innerHTML —— 这是 XSS 防线。

'use strict';

const POLL_INTERVAL_MS = 60 * 1000; // 60 秒轮询一次
const LANG_STORAGE_KEY = 'rainystatus.lang';

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

function detectLang() {
  const fromUrl = new URLSearchParams(location.search).get('lang');
  if (fromUrl && SUPPORTED_LANGS.includes(fromUrl)) return fromUrl;

  try {
    const stored = localStorage.getItem(LANG_STORAGE_KEY);
    if (stored && SUPPORTED_LANGS.includes(stored)) return stored;
  } catch {
    // localStorage 可能被禁用，忽略
  }

  const nav = (navigator.language || '').toLowerCase();
  if (nav.startsWith('zh')) {
    return nav.includes('hant') || nav.includes('tw') || nav.includes('hk') || nav.includes('mo')
      ? 'zh-Hant'
      : 'zh-Hans';
  }
  if (nav.startsWith('en')) return 'en';
  return 'zh-Hans';
}

function t(key, vars) {
  const dict = I18N[lang] || I18N['zh-Hans'];
  let text = dict[key] ?? I18N['zh-Hans'][key] ?? key;
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

function formatClock(epochMs) {
  const offset = site?.timezoneOffsetMinutes ?? 480;
  const d = new Date(epochMs + offset * 60000);
  const pad = (n) => String(n).padStart(2, '0');
  return `${d.getUTCFullYear()}-${pad(d.getUTCMonth() + 1)}-${pad(d.getUTCDate())} ` +
    `${pad(d.getUTCHours())}:${pad(d.getUTCMinutes())}`;
}

function setText(node, text) {
  if (node) node.textContent = text;
}

function applyStaticI18n() {
  setText(el('batteryLabel'), t('battery'));
  setText(el('moodTitle'), t('mood'));
  setText(el('metaTempLabel'), t('temperature'));
  setText(el('metaNetworkLabel'), t('network'));
  setText(el('metaDeviceLabel'), t('device'));
  setText(el('metaLastSeenLabel'), t('lastSeen'));
  setText(el('freshnessHint'), t('freshnessHint'));
  document.documentElement.lang = lang;

  for (const btn of document.querySelectorAll('.lang-btn')) {
    btn.setAttribute('aria-pressed', btn.dataset.lang === lang ? 'true' : 'false');
  }
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
    // 站点信息来自服务端 vars，部署者看到的是自己的
    setText(el('title'), site.title);
    setText(el('owner'), site.owner);
    setText(el('avatar'), site.avatar);
    document.title = site.title;
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

  if (!device || device.batteryPercent === null || device.batteryPercent === undefined) {
    batterySection.hidden = device !== null && device.batteryPercent === null && device.appVersion !== null;
    setText(el('batteryValue'), '—');
    setText(el('batterySub'), device === null ? t('neverSeen') : '');
    el('batteryFill').style.width = '0%';
    if (device === null) batterySection.hidden = false;
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
  } catch {
    setText(el('statusText'), t('loadFailed'));
    el('statusDot').className = 'dot gone';
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