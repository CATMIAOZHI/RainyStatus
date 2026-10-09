// 电量图表卡：**默认不加载**，点按钮才取数据，并且要过 Cloudflare Turnstile。
//
// 为什么这么设计：图表接口是公开只读的，若自动加载，一个脚本就能反复触发请求，
// 把 Worker 的 10 万请求/天额度打光（免费版最硬的天花板）。手动 + 人机验证之后，
// 批量刷的成本从「发请求」变成「过验证」。
//
// 数据来源：/api/history?range=24h —— 服务端读的是**预生成的 1 行 JSON**，
// 所以即使有人反复点，数据库成本也只有一行，不会去扫历史表。
//
// 安全：所有文本（心情、错误信息）一律 textContent/innerText 写入，绝不 innerHTML。

'use strict';

(function () {
  const RANGE = '24h';
  const CACHE_KEY = 'rainystatus.history.24h';
  const MAX_CACHE_BYTES = 64 * 1024;
  const TURNSTILE_SRC = 'https://challenges.cloudflare.com/turnstile/v0/api.js?render=explicit';

  const I18N = {
    'zh-Hans': {
      title: '电量 · 24 小时',
      load: '加载图表',
      loading: '正在验证…',
      hint: '图表需要手动加载，并会先做一次人机验证。',
      cached: '当前显示的是本地缓存，点「刷新图表」可获取最新数据。',
      stale: '云端数据可能不是最新（最近一次生成：{t}）。',
      empty: '这段时间还没有记录。',
      partial: '记录不完整：手机可能有一段时间没有上报。',
      failed: '图表读取失败，请稍后重试。',
      denied: '人机验证没通过，请重试。',
      unavailable: '图表服务暂时不可用，稍后再试。',
      summary: '最新 {last} · 最低 {min} · 最高 {max}',
      sessions: '充电 {n} 次',
      legendCharging: '绿色竖条 = 充电时段',
      legendGap: '曲线断开 = 那段时间没有记录',
      refresh: '刷新图表',
      moodsTitle: '这段时间的心情',
      blockedTurnstile: '图表还没开起来：这个站点要求人机验证，但还没配 Turnstile 密钥。部署者需要配置 TURNSTILE_SITE_KEY 与 TURNSTILE_SECRET 后重新部署。',
      noValue: '—',
    },
    'zh-Hant': {
      title: '電量 · 24 小時',
      load: '載入圖表',
      loading: '正在驗證…',
      hint: '圖表需要手動載入，並會先做一次人機驗證。',
      cached: '目前顯示的是本機快取，點「重新整理圖表」可取得最新資料。',
      stale: '雲端資料可能不是最新（最近一次產生：{t}）。',
      empty: '這段時間還沒有紀錄。',
      partial: '紀錄不完整：手機可能有一段時間沒有上報。',
      failed: '圖表讀取失敗，請稍後重試。',
      denied: '人機驗證沒有通過，請重試。',
      unavailable: '圖表服務暫時無法使用，請稍後再試。',
      summary: '最新 {last} · 最低 {min} · 最高 {max}',
      sessions: '充電 {n} 次',
      legendCharging: '綠色直條 = 充電時段',
      legendGap: '曲線斷開 = 那段時間沒有紀錄',
      refresh: '重新整理圖表',
      moodsTitle: '這段時間的心情',
      blockedTurnstile: '圖表還沒開起來：這個站點要求人機驗證，但還沒設定 Turnstile 金鑰。部署者需要設定 TURNSTILE_SITE_KEY 與 TURNSTILE_SECRET 後重新部署。',
      noValue: '—',
    },
    en: {
      title: 'Battery · 24 hours',
      load: 'Load chart',
      loading: 'Verifying…',
      hint: 'The chart loads on demand and asks for a quick human check first.',
      cached: 'Showing a locally cached copy. Use “Refresh chart” for the latest data.',
      stale: 'The cloud copy may be out of date (last built: {t}).',
      empty: 'No samples in this window yet.',
      partial: 'Incomplete: the phone may have been offline for a while.',
      failed: 'Could not load the chart, please retry.',
      denied: 'Human verification failed, please retry.',
      unavailable: 'Chart service is temporarily unavailable.',
      summary: 'Latest {last} · Low {min} · High {max}',
      sessions: '{n} charge sessions',
      legendCharging: 'Green bars = charging',
      legendGap: 'Gaps = no data reported',
      refresh: 'Refresh chart',
      moodsTitle: 'Mood in this window',
      blockedTurnstile:
        'The chart is not enabled yet: this site requires human verification but has no Turnstile keys. The owner needs to set TURNSTILE_SITE_KEY and TURNSTILE_SECRET, then redeploy.',
      noValue: '—',
    },
  };

  const el = (id) => document.getElementById(id);
  /** 服务端下发的「为什么没开」→ 文案键。新增原因时在这里加一行即可 */
  const BLOCKED_KEYS = { turnstile_not_configured: 'blockedTurnstile' };
  let lang = 'zh-Hans';
  let site = null;
  let payload = null;      // 最近一次成功读到的导出数据
  let fromCache = false;
  let busy = false;
  let turnstileId = null;
  let turnstileLoading = null;
  let pendingToken = '';
  /** 云端导出超过 45 分钟没更新时的时间戳；0 = 不提示 */
  let localStale = 0;
  /** 非 null = 站点配置不完整（服务端给了原因），此时只显示说明、不给按钮 */
  let blockedReason = null;

  function t(key, vars) {
    let text = (I18N[lang] || I18N['zh-Hans'])[key] || I18N['zh-Hans'][key] || key;
    if (vars) {
      for (const [k, v] of Object.entries(vars)) text = text.replace(`{${k}}`, String(v));
    }
    return text;
  }

  /** 借 app.js 的格式化函数；拿不到就退化成一个朴素实现，保证图表不会因此整块不显示 */
  function bridge() {
    return window.RS || null;
  }

  function clock(ms) {
    const rs = bridge();
    if (rs && typeof rs.formatClock === 'function') return rs.formatClock(ms);
    const d = new Date(ms);
    const pad = (n) => String(n).padStart(2, '0');
    return `${pad(d.getHours())}:${pad(d.getMinutes())}`;
  }

  function clockFull(ms) {
    const d = new Date(ms);
    const pad = (n) => String(n).padStart(2, '0');
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  }

  // ── 本地缓存：让「被打了 / 断网」时还能看到上一次的数据 ──
  function readCache() {
    try {
      const raw = localStorage.getItem(CACHE_KEY);
      if (!raw || raw.length > MAX_CACHE_BYTES) return null;
      const parsed = JSON.parse(raw);
      if (!parsed || parsed.schemaVersion !== 1 || !Array.isArray(parsed.points)) return null;
      return parsed;
    } catch {
      return null;
    }
  }

  function writeCache(data) {
    try {
      const raw = JSON.stringify(data);
      if (raw.length > MAX_CACHE_BYTES) return;
      localStorage.setItem(CACHE_KEY, raw);
    } catch {
      // 存储被禁用或已满：安静退化，图表照常显示本次结果
    }
  }

  // ── 图表绘制（SVG，按实际像素宽高画，避免拉伸变形）──
  function drawChart(data) {
    const svg = el('chartSvg');
    if (!svg) return;
    const width = Math.max(240, Math.round(svg.clientWidth || svg.parentElement?.clientWidth || 320));
    const height = 150;
    svg.setAttribute('viewBox', `0 0 ${width} ${height}`);
    svg.setAttribute('preserveAspectRatio', 'none');
    while (svg.firstChild) svg.removeChild(svg.firstChild);

    const padL = 30;
    const padR = 8;
    const padT = 10;
    const padB = 18;
    const plotW = width - padL - padR;
    const plotH = height - padT - padB;
    const from = data.from;
    const to = data.to;
    const span = Math.max(1, to - from);

    const x = (ms) => padL + ((ms - from) / span) * plotW;
    const y = (pct) => padT + (1 - Math.max(0, Math.min(100, pct)) / 100) * plotH;

    const ns = 'http://www.w3.org/2000/svg';
    const add = (tag, attrs, text) => {
      const node = document.createElementNS(ns, tag);
      for (const [k, v] of Object.entries(attrs)) node.setAttribute(k, String(v));
      if (text !== undefined) node.textContent = text;
      svg.appendChild(node);
      return node;
    };

    // 网格与 Y 轴刻度
    for (const pct of [0, 50, 100]) {
      add('line', { x1: padL, y1: y(pct), x2: width - padR, y2: y(pct), class: 'chart-grid' });
      add('text', { x: padL - 5, y: y(pct) + 3, class: 'chart-axis', 'text-anchor': 'end' }, `${pct}`);
    }

    // X 轴刻度：起点 / 1/3 / 2/3 / 终点
    for (const ratio of [0, 1 / 3, 2 / 3, 1]) {
      const ms = from + span * ratio;
      add(
        'text',
        {
          x: Math.min(width - padR, Math.max(padL, x(ms))),
          y: height - 5,
          class: 'chart-axis',
          'text-anchor': ratio === 0 ? 'start' : ratio === 1 ? 'end' : 'middle',
        },
        clock(ms),
      );
    }

    const points = data.points.filter((p) => typeof p.t === 'number' && p.t >= from && p.t <= to);

    // 充电时段：连续 c===1 的区间画一条淡绿色竖带
    let bandStart = null;
    for (let i = 0; i < points.length; i += 1) {
      const charging = points[i].c === 1;
      if (charging && bandStart === null) bandStart = points[i].t;
      const next = points[i + 1];
      if (bandStart !== null && (!next || !charging || next.c !== 1)) {
        const endT = next && charging ? next.t : points[i].t;
        add('rect', {
          x: x(bandStart),
          y: padT,
          width: Math.max(1.5, x(endT) - x(bandStart)),
          height: plotH,
          class: 'chart-band',
        });
        bandStart = null;
      }
    }

    // 折线：断档超过 30 分钟就断开，不跨缺口连线
    const segments = [];
    let current = [];
    let prevT = null;
    for (const p of points) {
      if (p.p === null || p.p === undefined) {
        if (current.length > 0) segments.push(current);
        current = [];
        prevT = p.t;
        continue;
      }
      if (prevT !== null && p.t - prevT > 30 * 60 * 1000 && current.length > 0) {
        segments.push(current);
        current = [];
      }
      current.push(p);
      prevT = p.t;
    }
    if (current.length > 0) segments.push(current);

    for (const segment of segments) {
      if (segment.length === 1) {
        add('circle', { cx: x(segment[0].t), cy: y(segment[0].p), r: 2, class: 'chart-dot' });
        continue;
      }
      const line = segment.map((p, i) => `${i === 0 ? 'M' : 'L'}${x(p.t).toFixed(1)},${y(p.p).toFixed(1)}`).join(' ');
      const area = `${line} L${x(segment[segment.length - 1].t).toFixed(1)},${(padT + plotH).toFixed(1)} L${x(segment[0].t).toFixed(1)},${(padT + plotH).toFixed(1)} Z`;
      add('path', { d: area, class: 'chart-area' });
      add('path', { d: line, class: 'chart-line' });
    }

    // 最新一点：标出来，方便一眼看到当前电量
    const withValue = points.filter((p) => p.p !== null && p.p !== undefined);
    const last = withValue[withValue.length - 1];
    if (last) {
      add('circle', { cx: x(last.t), cy: y(last.p), r: 3, class: 'chart-dot' });
      add(
        'text',
        {
          x: Math.min(width - padR - 2, x(last.t) + 6),
          y: Math.max(padT + 9, y(last.p) - 6),
          class: 'chart-value',
          'text-anchor': x(last.t) > width - 60 ? 'end' : 'start',
        },
        `${last.p}%`,
      );
    }
  }

  function renderSummary(data) {
    const withValue = data.points.filter((p) => p.p !== null && p.p !== undefined).map((p) => p.p);
    const lastValue = withValue.length > 0 ? withValue[withValue.length - 1] : null;
    const min = withValue.length > 0 ? Math.min(...withValue) : null;
    const max = withValue.length > 0 ? Math.max(...withValue) : null;

    const parts = [];
    parts.push(t('summary', {
      last: lastValue === null ? t('noValue') : `${lastValue}%`,
      min: min === null ? t('noValue') : `${min}%`,
      max: max === null ? t('noValue') : `${max}%`,
    }));
    // 充电次数按水晴的要求直接给数字，不画成小柱子
    parts.push(t('sessions', { n: data.chargeSessions ?? 0 }));
    el('chartSummary').textContent = parts.join('　·　');
  }

  function renderMoods(data) {
    const list = el('moodTimeline');
    const box = el('chartMoods');
    const moods = Array.isArray(data.mood) ? data.mood : [];
    if (moods.length === 0) {
      box.hidden = true;
      return;
    }
    box.hidden = false;
    el('chartMoodsTitle').textContent = t('moodsTitle');
    list.textContent = '';
    for (const mood of moods) {
      const li = document.createElement('li');
      const time = document.createElement('span');
      time.className = 'mood-timeline-time';
      time.textContent = clockFull(mood.at);
      const text = document.createElement('span');
      text.className = 'mood-timeline-text';
      // textContent：心情文案是用户输入，绝不能当 HTML 解析
      text.textContent = `${mood.emoji || ''}${mood.text || ''}`.trim();
      li.appendChild(time);
      li.appendChild(text);
      list.appendChild(li);
    }
  }

  function renderLegend() {
    const items = [t('legendCharging'), t('legendGap')];
    const list = el('chartLegend');
    list.textContent = '';
    for (const item of items) {
      const li = document.createElement('li');
      li.textContent = item;
      list.appendChild(li);
    }
  }

  function renderNote() {
    const note = el('chartNote');
    const notes = [];
    if (fromCache) notes.push(t('cached'));
    if (payload && payload.availability === 'empty') notes.push(t('empty'));
    if (payload && payload.availability === 'partial') notes.push(t('partial'));
    if (localStale) notes.push(t('stale', { t: clockFull(localStale) }));
    note.textContent = notes.join(' ');
  }

  function render() {
    if (!payload) return;
    el('chartBody').hidden = false;
    renderSummary(payload);
    drawChart(payload);
    renderLegend();
    renderMoods(payload);
    renderNote();
  }

  // ── 取数：手动触发，必要时先过 Turnstile ──
  function loadTurnstileScript() {
    if (turnstileLoading) return turnstileLoading;
    turnstileLoading = new Promise((resolve, reject) => {
      const existing = document.querySelector('script[data-rainystatus-turnstile]');
      if (existing) {
        resolve();
        return;
      }
      const script = document.createElement('script');
      script.src = TURNSTILE_SRC;
      script.async = true;
      script.defer = true;
      script.dataset.rainystatusTurnstile = '1';
      script.addEventListener('load', () => resolve());
      script.addEventListener('error', () => reject(new Error('turnstile script failed')));
      document.head.appendChild(script);
    });
    return turnstileLoading;
  }

  async function fetchChart(token) {
    const headers = { accept: 'application/json' };
    // Turnstile 令牌放自定义头：同源请求，不放进 URL（URL 会进日志与 Referer）
    if (token) headers['cf-turnstile-response'] = token;

    const res = await fetch(`/api/history?range=${RANGE}`, { headers });
    if (res.status === 403) throw new Error('denied');
    if (res.status === 503) throw new Error('unavailable');
    if (!res.ok) throw new Error('failed');

    const body = await res.json();
    if (!body || body.ok !== true || !body.data) throw new Error('failed');
    return { data: body.data, staleAt: body.stale ? body.exportGeneratedAt : 0 };
  }

  async function doLoad() {
    if (busy) return;
    busy = true;
    const button = el('chartLoad');
    const label = el('chartLoadText');
    label.textContent = t('loading');
    button.classList.add('busy');

    try {
      const result = await fetchChart(pendingToken);
      payload = result.data;
      localStale = result.staleAt;
      fromCache = false;
      writeCache(payload);
      render();
      hideChallenge();
    } catch (error) {
      const code = error instanceof Error ? error.message : 'failed';
      el('chartNote').textContent =
        code === 'denied' ? t('denied') : code === 'unavailable' ? t('unavailable') : t('failed');
      // 失败时保留旧图（如果有），只是不动它
    } finally {
      busy = false;
      pendingToken = '';
      button.classList.remove('busy');
      label.textContent = payload ? t('refresh') : t('load');
    }
  }

  function showChallenge() {
    const box = el('chartChallenge');
    box.hidden = false;
    box.textContent = '';
    return box;
  }

  function hideChallenge() {
    const box = el('chartChallenge');
    box.hidden = true;
    box.textContent = '';
    turnstileId = null;
  }

  async function startLoad() {
    const config = site && site.history ? site.history : null;
    if (!config || !config.enabled) return;

    if (config.challenge !== 'turnstile' || !config.siteKey) {
      // 没有配置验证：仍保留「手动点击」，但如实告诉访客保护较弱
      await doLoad();
      return;
    }

    try {
      el('chartLoadText').textContent = t('loading');
      await loadTurnstileScript();
      const box = showChallenge();
      if (!window.turnstile) throw new Error('failed');
      turnstileId = window.turnstile.render(box, {
        sitekey: config.siteKey,
        theme: document.documentElement.dataset.theme === 'dark' ? 'dark' : 'auto',
        callback: (token) => {
          pendingToken = token;
          void doLoad();
        },
        'error-callback': () => {
          el('chartNote').textContent = t('denied');
          el('chartLoadText').textContent = payload ? t('refresh') : t('load');
        },
      });
    } catch {
      el('chartNote').textContent = t('failed');
      el('chartLoadText').textContent = payload ? t('refresh') : t('load');
    }
  }

  function applyStatic() {
    el('chartTitle').textContent = t('title');
    // 配置不完整时固定显示原因（切语言也跟着切），并且**不再渲染上一次取到的数据**：
    // 那会把已经藏起来的心情/备注又显示出来（心情区是图表体的兄弟节点，不跟着一起藏）。
    if (blockedReason) {
      el('chartHint').textContent = blockedText(blockedReason);
      el('chartNote').textContent = '';
      return;
    }
    el('chartHint').textContent = t('hint');
    if (!payload) el('chartLoadText').textContent = t('load');
    renderNote();
    if (payload) {
      renderSummary(payload);
      renderLegend();
      renderMoods(payload);
    }
  }

  /** 配置被挡住时的说明：只给一句人话，不给按钮（点了也必然失败） */
  function blockedText(reason) {
    const key = BLOCKED_KEYS[reason];
    // 未知原因（将来新增的）：至少别显示空白，退回普通提示
    return key ? t(key) : t('hint');
  }

  function showBlocked(reason) {
    blockedReason = reason;
    const section = el('chartSection');
    section.hidden = false;
    el('chartLoad').hidden = true;
    el('chartBody').hidden = true;
    el('chartMoods').hidden = true;
    el('chartNote').textContent = '';
    // 已经渲染过的人机验证组件也要收掉，别留一个「点了没用」的框
    const challenge = el('chartChallenge');
    challenge.hidden = true;
    challenge.textContent = '';
    turnstileId = null;
    applyStatic();
  }

  function setSite(nextSite) {
    site = nextSite;
    const config = site && site.history ? site.history : null;

    // 配置不完整（例如要求人机验证却没配密钥）：**不能静默消失**。
    // 部署者看到「什么都没有」只会以为页面坏了，所以要直说原因；
    // 但这张卡也不该给按钮——点了必然失败。
    if (config && config.reason) {
      showBlocked(config.reason);
      return;
    }

    blockedReason = null;
    const section = el('chartSection');
    const supported = !!(config && config.enabled && Array.isArray(config.ranges) && config.ranges.includes(RANGE));
    section.hidden = !supported;
    if (!supported) return;

    applyStatic();

    // 页面一打开先用本地缓存渲染（不产生任何请求），要看最新再点按钮
    if (!payload) {
      const cached = readCache();
      if (cached) {
        payload = cached;
        fromCache = true;
        localStale = 0;
        render();
      }
    }

    const button = el('chartLoad');
    if (!button.dataset.bound) {
      button.dataset.bound = '1';
      button.addEventListener('click', () => void startLoad());
    }
    button.hidden = false;
  }

  function setLang(nextLang) {
    lang = nextLang;
    applyStatic();
  }

  window.addEventListener('resize', () => {
    if (payload) drawChart(payload);
  });

  window.RSChart = { setSite, setLang };
})();