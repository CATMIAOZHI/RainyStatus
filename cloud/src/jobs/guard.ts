// 配额熔断（可选功能，默认关闭，见 config.ts 的 guardConfig）
//
// 背景：R2 是计费产品，官方**没有**「免费用完自动停」的开关（只有预算提醒，
// 而且提醒本身明确写着 "does not cap usage"）。攻击者只要让请求绕过 CDN 缓存
// （随机路径 / 随机查询串 / 关掉缓存规则），账单就随请求数线性上涨——实测把
// 每月 1000 万次的免费额度打穿，只需要约 4 次/秒跑一个月。所以「超限自动拉闸」
// 只能自己实现。
//
// 做法：定时巡检 → 查 R2 操作数（GraphQL Analytics）→ 超阈值就**关掉数据域的两个门**：
//   · 自定义域（主路径）与 r2.dev 开发域（容易忘的后门）**一起关**，两者结果都记进 state；
//   · 停域是控制台里的开关，秒级生效；停掉之后请求到不了 R2，也就不再计费；
//   · 只停数据域，**不碰** status Worker / KV / D1（页面先显示旧数据、之后按限频回退 /api/status——不会变回全站轮询）；
//   · 恢复**必须人工**——自动恢复＝攻击一停就重新计费，等于没熔断。
//
// 失败策略（红队评审要求）：
//   · 查不到用量（API 挂、权限不足、token 过期）→ **不动手，也不写状态**：宁可漏报也不误伤，
//     控制台留一条 warning，下轮巡检再试；
//   · 任何异常都吞掉：熔断巡检失败绝不能拖垮心跳/导出这些主链路；
//   · 熔断动作失败会记进 `guard_state`，下次巡检会再试一次（幂等）。

import { guardConfig } from '../config';
import type { Env } from '../types';

const GRAPHQL_ENDPOINT = 'https://api.cloudflare.com/client/v4/graphql';
/** 熔断状态写在这个 KV key 里，便于事后核对「什么时候、因为多少用量被拉闸」 */
export const GUARD_STATE_KEY = 'guard_state';

export type GuardState = {
  at: number;
  /** 本月累计操作数（读+写，两者都算：宁可早拉闸，也不给钱包留口子） */
  monthly: number | null;
  /** 最近 1 小时操作数 */
  hourly: number | null;
  maxMonthly: number;
  maxHourly: number;
  tripped: boolean;
  /** disabled_domain = 自定义域已停用（主路径切断）；failed = 连它都没关成 */
  action: 'none' | 'disabled_domain' | 'failed';
  /** r2.dev 开发域是否也关成功（null = 本轮没尝试拉闸）——后门还开着就不算关干净 */
  managedDisabled: boolean | null;
};

type GraphQlResponse = {
  data?: {
    viewer?: {
      accounts?: Array<{
        r2OperationsAdaptiveGroups?: Array<{ sum?: { requests?: number } }>;
      }>;
    };
  };
};

/**
 * 查某个时间点之后的 R2 操作数。
 *
 * 用 `r2OperationsAdaptiveGroups`（账号级 dataset，按 bucketName 过滤）——
 * 它是**计费口径**的那份数据。注意官方没有承诺它的新鲜度：可能有小时级延迟，
 * 所以熔断不是「实时刹车」，而是「按小时兜底的止损」。查不到就返回 null。
 *
 * limit 与分组照官方 R2 文档的示例（limit: 10000 + dimensions.actionType，
 * 再对分组求和）：limit 截断的是「分组」，调小会**低估**用量——低估=漏拉闸，
 * 比高估危险，别动它。
 */
async function queryOperations(token: string, account: string, bucket: string, since: number): Promise<number | null> {
  const query = `query ($account: String!, $bucket: String!, $since: String!) {
    viewer {
      accounts(filter: { accountTag: $account }) {
        r2OperationsAdaptiveGroups(limit: 10000, filter: { bucketName: $bucket, datetime_geq: $since }) {
          sum { requests }
          dimensions { actionType }
        }
      }
    }
  }`;
  try {
    const res = await fetch(GRAPHQL_ENDPOINT, {
      method: 'POST',
      headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
      body: JSON.stringify({ query, variables: { account, bucket, since: new Date(since).toISOString() } }),
    });
    if (!res.ok) return null;
    const body = (await res.json()) as GraphQlResponse;
    const groups = body.data?.viewer?.accounts?.[0]?.r2OperationsAdaptiveGroups;
    if (!Array.isArray(groups)) return null;
    return groups.reduce((total, group) => total + (group.sum?.requests ?? 0), 0);
  } catch {
    return null;
  }
}

/** 停用数据域的自定义域（官方 API：Configure Custom Domain Settings） */
async function disableDataDomain(token: string, account: string, bucket: string, hostname: string): Promise<boolean> {
  try {
    const res = await fetch(
      `https://api.cloudflare.com/client/v4/accounts/${account}/r2/buckets/${bucket}/domains/custom/${hostname}`,
      {
        method: 'PUT',
        headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
        body: JSON.stringify({ enabled: false }),
      },
    );
    return res.ok;
  } catch {
    return false;
  }
}

/**
 * 关闭桶的 r2.dev 开发域（官方 API 名：managed domain）。
 *
 * 不关它等于留了后门：r2.dev 不走我们配在自定义域上的缓存规则，也过不了 WAF 白名单，
 * 被刷一样计费。停自定义域时顺手把它一起关掉，两个结果都记进 state。
 */
async function disableManagedDomain(token: string, account: string, bucket: string): Promise<boolean> {
  try {
    const res = await fetch(
      `https://api.cloudflare.com/client/v4/accounts/${account}/r2/buckets/${bucket}/domains/managed`,
      {
        method: 'PUT',
        headers: { authorization: `Bearer ${token}`, 'content-type': 'application/json' },
        body: JSON.stringify({ enabled: false }),
      },
    );
    return res.ok;
  } catch {
    return false;
  }
}

/**
 * 跑一轮熔断巡检。返回本轮状态（未启用或整个流程失败时返回 null）。
 *
 * 阈值判定：月累计 **或** 最近 1 小时任一超线就拉闸。取「或」是因为两种攻击形态
 * 不一样——慢速刷一个月、突发一小时打穿，都得拦。
 */
export async function runQuotaGuard(env: Env, now: number): Promise<GuardState | null> {
  const config = guardConfig(env);
  if (!config.enabled) return null;

  try {
    const date = new Date(now);
    const monthStart = Date.UTC(date.getUTCFullYear(), date.getUTCMonth(), 1);
    const monthly = await queryOperations(config.token, config.account, config.bucket, monthStart);
    const hourly = await queryOperations(config.token, config.account, config.bucket, now - 60 * 60 * 1000);
    // 两个窗口都查不到 = 没有可信依据：什么都不做（宁可漏报也不误伤），
    // 只留一条 warning 供排查「为什么没拉闸」。
    if (monthly === null && hourly === null) {
      console.warn('[guard] R2 usage unavailable; skip this round (no false trip)');
      return null;
    }

    const over =
      (monthly !== null && monthly >= config.maxMonthly) || (hourly !== null && hourly >= config.maxHourly);
    const state: GuardState = {
      at: now,
      monthly,
      hourly,
      maxMonthly: config.maxMonthly,
      maxHourly: config.maxHourly,
      tripped: false,
      action: 'none',
      managedDisabled: null,
    };
    if (!over) return state;

    // 两个门一起关：自定义域（主路径）+ r2.dev 开发域（后门）。都是幂等操作，
    // 只要还在超限窗口内，下一轮巡检会把没关成功的再试一次。
    const [custom, managed] = await Promise.all([
      disableDataDomain(config.token, config.account, config.bucket, config.hostname),
      disableManagedDomain(config.token, config.account, config.bucket),
    ]);
    state.tripped = true;
    state.action = custom ? 'disabled_domain' : 'failed';
    state.managedDisabled = managed;
    try {
      // 只在拉闸（或尝试拉闸）时写一次 KV：平时不写，省额度
      await env.STATUS_KV.put(GUARD_STATE_KEY, JSON.stringify(state));
    } catch {
      // 记不上也不影响已经拉下去的闸
    }
    return state;
  } catch {
    return null;
  }
}