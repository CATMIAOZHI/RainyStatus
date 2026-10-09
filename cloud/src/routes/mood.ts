// POST /api/mood —— 需 Bearer 鉴权
//
// 与心跳分开存（current_mood），心跳不会覆盖心情。
// 去重放在 App 端（内容没变就不发），服务端不做「读-比较-再写」，省一次读。
//
// 安全：text 由网页用 textContent 渲染，禁止 innerHTML（XSS 防线在渲染侧）

import { historyConfig } from '../config';
import { insertMoodEvent } from '../lib/db';
import { isAuthorized } from '../lib/auth';
import { writeCurrentMood } from '../lib/kv';
import { jsonError, jsonOk, methodNotAllowed } from '../lib/response';
import { readJson, validateMood } from '../lib/validate';
import { SCHEMA_VERSION, type CurrentMood, type Env } from '../types';

export async function handleMood(request: Request, env: Env, ctx: ExecutionContext): Promise<Response> {
  if (request.method !== 'POST') {
    return methodNotAllowed('POST');
  }

  if (!isAuthorized(request, env)) {
    return jsonError(401, 'unauthorized', 'Missing or invalid bearer token');
  }

  const body = await readJson(request);
  if (!body.ok) {
    return jsonError(body.code === 'payload_too_large' ? 413 : body.code === 'unsupported_media_type' ? 415 : 400, body.code, body.message);
  }

  const parsed = validateMood(body.value);
  if (!parsed.ok) {
    return jsonError(400, parsed.code, parsed.message);
  }

  const now = Date.now();
  const mood: CurrentMood = {
    schemaVersion: SCHEMA_VERSION,
    text: parsed.value.text,
    emoji: parsed.value.emoji,
    updatedAt: now,
  };

  try {
    await writeCurrentMood(env.STATUS_KV, mood);
  } catch {
    return jsonError(500, 'internal_error', 'Failed to persist mood');
  }

  // 心情事件单独存一份历史（当前值会被覆盖，覆盖后就再也回不到「昨天的我」了）。
  // 只在内容变化时才走到这里（App 端已去重），所以这张表很稀疏。
  if (env.HISTORY_DB && historyConfig(env).collect) {
    const db = env.HISTORY_DB;
    ctx.waitUntil(
      insertMoodEvent(db, now, mood.text, mood.emoji).catch(() => {
        // 历史失败不影响这次上报：当前心情已经写进 KV 了
      }),
    );
  }

  return jsonOk({ updatedAt: now });
}