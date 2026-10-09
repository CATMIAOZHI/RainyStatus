// Cloudflare Turnstile 人机验证。
//
// 为什么需要它：状态页的图表接口是公开只读的。没有验证时，一个脚本就能把
// 「加载图表」按到 Worker 请求额度（10 万/天）耗尽 —— 那是免费版最硬、也最容易被
// 低价打掉的天花板。加了验证后，攻击者必须先过关，批量刷的成本从「发请求」变成「过验证」。
//
// 注意：Turnstile 的令牌是一次性的、几分钟就过期，所以验证通过的响应**不能**走 CDN 缓存。
// 这也是为什么历史接口必须读「预生成的 1 行」——否则每次点击都会真去查库。

const VERIFY_URL = 'https://challenges.cloudflare.com/turnstile/v0/siteverify';
const TIMEOUT_MS = 5000;

export type TurnstileResult = { ok: true } | { ok: false; reason: string };

/**
 * 校验令牌。任何网络异常都按「未通过」处理（fail-closed）——
 * 验证服务不可用时放行，等于攻击者只要把验证服务打挂就绕过了验证。
 */
export async function verifyTurnstile(
  secret: string,
  token: string,
  remoteIp: string | null,
): Promise<TurnstileResult> {
  if (token === '') return { ok: false, reason: 'missing_token' };

  const body = new URLSearchParams();
  body.set('secret', secret);
  body.set('response', token);
  // 带上 IP 能提高判定质量；拿不到就不带（Cloudflare 侧仍有很多信号）
  if (remoteIp !== null) body.set('remoteip', remoteIp);

  try {
    const res = await fetch(VERIFY_URL, {
      method: 'POST',
      body,
      signal: AbortSignal.timeout(TIMEOUT_MS),
    });
    if (!res.ok) return { ok: false, reason: `verify_http_${res.status}` };
    const data = (await res.json()) as { success?: unknown };
    return data.success === true ? { ok: true } : { ok: false, reason: 'challenge_failed' };
  } catch {
    return { ok: false, reason: 'verify_unreachable' };
  }
}
