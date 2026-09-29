// Worker 入口：路由分发 + 错误兜底
//
// 路由表：
//   GET  /                 → 静态资源（免费不限量，不经此代码）
//   GET  /api/health       → 探活（无需鉴权，不碰 KV）
//   GET  /api/status       → 公开只读状态
//   POST /api/heartbeat    → 上报心跳（需 Bearer）
//   POST /api/mood         → 上报心情（需 Bearer）
//
// 静态资源优先（run_worker_first 默认 false）：只有未命中静态资源的请求才进来。
// 好处：免费额度耗尽（错误码 1027）时网页仍能打开，只有 API 报错。

import { handleHealth } from './routes/health';
import { handleHeartbeat } from './routes/heartbeat';
import { handleMood } from './routes/mood';
import { handleStatus } from './routes/status';
import { jsonError, methodNotAllowed } from './lib/response';
import type { Env } from './types';

export default {
  async fetch(request: Request, env: Env): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, '') || '/';

    // 同源部署，不发任何 CORS 头；非浏览器客户端由 Bearer token 挡住
    if (request.method === 'OPTIONS') {
      return methodNotAllowed('GET, POST');
    }

    try {
      switch (path) {
        case '/api/health':
          return handleHealth(request, env);
        case '/api/status':
          return handleStatus(request, env);
        case '/api/heartbeat':
          return handleHeartbeat(request, env);
        case '/api/mood':
          return handleMood(request, env);
        default:
          break;
      }

      if (path.startsWith('/api/')) {
        return jsonError(404, 'not_found', 'Unknown API endpoint');
      }

      // 理论上到不了这里（静态资源优先命中）；兜底返回 404 而不是抛异常
      return jsonError(404, 'not_found', 'Not found');
    } catch {
      // 绝不回显内部异常细节
      return jsonError(500, 'internal_error', 'Unexpected error');
    }
  },
} satisfies ExportedHandler<Env>;