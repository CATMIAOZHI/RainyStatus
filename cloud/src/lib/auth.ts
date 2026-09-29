// Bearer token 校验：常数时间比较，避免时序侧信道
// 关键：先验 token 再碰 KV —— 非法请求 0 KV 成本

const encoder = new TextEncoder();

/**
 * 常数时间字符串比较。
 * 长度不同时也不提前返回，而是继续走完整循环，避免长度差异被计时探测。
 */
export function timingSafeEqual(a: string, b: string): boolean {
  const ab = encoder.encode(a);
  const bb = encoder.encode(b);
  const n = Math.max(ab.length, bb.length);
  let diff = ab.length ^ bb.length;
  for (let i = 0; i < n; i++) {
    diff |= (ab[i] ?? 0) ^ (bb[i] ?? 0);
  }
  return diff === 0;
}

/** 从 Authorization: Bearer <token> 取出 token；格式不符返回 null */
export function extractBearer(request: Request): string | null {
  const header = request.headers.get('authorization');
  if (!header) return null;
  const match = /^Bearer\s+(.+)$/i.exec(header.trim());
  if (!match) return null;
  const token = match[1].trim();
  return token.length > 0 ? token : null;
}

/** 鉴权通过返回 true。AUTH_TOKEN 未配置时一律拒绝（fail closed）。 */
export function isAuthorized(request: Request, env: { AUTH_TOKEN?: string }): boolean {
  const expected = env.AUTH_TOKEN;
  if (!expected || expected.length === 0) return false;
  const provided = extractBearer(request);
  if (!provided) return false;
  return timingSafeEqual(provided, expected);
}
