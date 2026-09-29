// GET /api/health —— Worker 自身存活探针，与「设备是否在线」无关
// 注意：此接口不需要鉴权，也不碰 KV（0 成本）

import { jsonOk, methodNotAllowed } from '../lib/response';
import type { Env } from '../types';

export function handleHealth(request: Request, _env: Env): Response {
  if (request.method !== 'GET' && request.method !== 'HEAD') {
    return methodNotAllowed('GET, HEAD');
  }
  return jsonOk({
    status: 'ok',
    service: 'rainystatus',
    time: Date.now(),
  });
}