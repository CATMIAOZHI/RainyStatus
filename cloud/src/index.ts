// Worker 入口：路由分发 + 定时任务 + 错误兜底
//
// 路由表：
//   GET  /                 → 静态资源（免费不限量，不经此代码）
//   GET  /api/health       → 探活（无需鉴权，不碰 KV）
//   GET  /api/status       → 公开只读状态（30 秒机房内缓存，见 routes/status.ts）
//   GET  /api/history      → 公开只读图表数据（需 Turnstile，读预生成导出 1 行）
//   POST /api/heartbeat    → 上报心跳（需 Bearer）
//   POST /api/mood         → 上报心情（需 Bearer）
//   scheduled              → 生成历史导出 + 清理过期数据
//
// 静态资源优先（run_worker_first 默认 false）：只有未命中静态资源的请求才进来。
// 好处：免费额度耗尽（错误码 1027）时网页仍能打开，只有 API 报错。

import { historyConfig, siteConfig } from './config';
import { runQuotaGuard } from './jobs/guard';
import { runHistoryMaintenance } from './jobs/history';
import { publishStatusFile } from './jobs/status-file';
import { handleHealth } from './routes/health';
import { handleHeartbeat } from './routes/heartbeat';
import { handleHistory } from './routes/history';
import { handleMood } from './routes/mood';
import { handleStatus } from './routes/status';
import { jsonError, methodNotAllowed } from './lib/response';
import type { Env } from './types';

export default {
  async fetch(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
    const url = new URL(request.url);
    const path = url.pathname.replace(/\/+$/, '') || '/';

    /**
     * HEAD 一律不带 body（协议要求）。
     *
     * 在入口统一收口，而不是让每个路由各自记得去处理：路由多一条、错误分支多一个，
     * 漏一个就是「HEAD 返回了 body」这种只在特定客户端上才暴露的问题。
     * （routes/status.ts 里还针对缓存命中单独做过一次，两层都无害。）
     */
    const finish = (response: Response): Response =>
      request.method === 'HEAD' ? new Response(null, { status: response.status, headers: response.headers }) : response;

    // 同源部署，不发任何 CORS 头；非浏览器客户端由 Bearer token 挡住
    if (request.method === 'OPTIONS') {
      return finish(methodNotAllowed('GET, POST'));
    }

    try {
      // 这里必须 await：不 await 的话处理函数内部抛出的异步异常不会回到这个 try 里，
      // 而是变成运行时的未处理拒绝——那就绕过了「绝不泄露内部细节」的兜底。
      switch (path) {
        case '/api/health':
          return finish(await handleHealth(request, env));
        case '/api/status':
          return finish(await handleStatus(request, env, ctx));
        case '/api/history':
          return finish(await handleHistory(request, env));
        case '/api/heartbeat':
          return finish(await handleHeartbeat(request, env, ctx));
        case '/api/mood':
          return finish(await handleMood(request, env, ctx));
        default:
          break;
      }

      if (path.startsWith('/api/')) {
        return finish(jsonError(404, 'not_found', 'Unknown API endpoint'));
      }

      // 理论上到不了这里（静态资源优先命中）；兜底返回 404 而不是抛异常
      return finish(jsonError(404, 'not_found', 'Not found'));
    } catch {
      // 绝不回显内部异常细节
      return finish(jsonError(500, 'internal_error', 'Unexpected error'));
    }
  },

  /**
   * 定时任务（wrangler.jsonc 的 triggers.crons）。
   *
   * 三件事，**互不拖累**（每步各自 try/catch）：发布静态数据文件、把当前窗口的
   * 图表数据预生成成 JSON 并清理过期数据、配额熔断巡检。
   * **聚合不在这里**——它跟着心跳走，避免「原始数据已清理但还没汇总」的窗口。
   *
   * 顺序有讲究：先发静态文件（最便宜、页面最需要），再干重活（D1），最后才是
   * 熔断（要打外部 API，最慢）。任何一步失败都不该让后面的事不跑。
   */
  async scheduled(_event: ScheduledController, env: Env, _ctx: ExecutionContext): Promise<void> {
    const now = Date.now();

    try {
      // 兜底发布：心跳每次成功也会发一份，这一份负责「设备掉线后把 online 翻转过去」
      await publishStatusFile(env, now);
    } catch {
      // 静态数据只是加速手段，失败不影响下面的主链路
    }

    const config = historyConfig(env);
    // 即使一个档位都没公开，采集开着也要跑：清理必须继续，否则原始数据会无限增长
    if (config.enabled || config.collect) {
      try {
        // mood 是否进导出跟着站点的公开配置走：服务端决定，不能只靠前端隐藏
        await runHistoryMaintenance(env, now, config.ranges, siteConfig(env).showMood);
      } catch {
        // 单轮维护失败不改变任何公开状态，下一轮重建窗口就好
      }
    }

    try {
      // 配额熔断：超限就把数据域停掉（R2 没有「用完停」，只能自己拉闸）
      await runQuotaGuard(env, now);
    } catch {
      // 熔断巡检失败绝不能影响心跳与导出
    }
  },
} satisfies ExportedHandler<Env>;