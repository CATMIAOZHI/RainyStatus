// 统一响应构造。错误体固定为 { ok:false, error:{ code, message } }
// 注意：绝不回显内部异常细节（避免泄露 KV 绑定名等实现信息）

const BASE_HEADERS: Record<string, string> = {
  'content-type': 'application/json; charset=utf-8',
  // 网页与 API 同源，因此不发任何 CORS 头（也挡掉浏览器跨域调用）
  'x-content-type-options': 'nosniff',
  'referrer-policy': 'no-referrer',
  'cache-control': 'no-store',
};

export function jsonOk(body: Record<string, unknown>, status = 200): Response {
  return new Response(JSON.stringify({ ok: true, ...body }), {
    status,
    headers: BASE_HEADERS,
  });
}

export function jsonError(status: number, code: string, message: string): Response {
  return new Response(JSON.stringify({ ok: false, error: { code, message } }), {
    status,
    headers: BASE_HEADERS,
  });
}

export function methodNotAllowed(allow: string): Response {
  const res = jsonError(405, 'method_not_allowed', `Use ${allow}`);
  res.headers.set('allow', allow);
  return res;
}
