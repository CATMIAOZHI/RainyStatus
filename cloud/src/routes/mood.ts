// POST /api/mood —— 需 Bearer 鉴权
//
// 与心跳分开存（current_mood），心跳不会覆盖心情。
// 去重放在 App 端（内容没变就不发），服务端不做「读-比较-再写」，省一次读。
//
// 安全：text 由网页用 textContent 渲染，禁止 innerHTML（XSS 防线在渲染侧）

import { isAuthorized } from '../lib/auth';
import { writeCurrentMood } from '../lib/kv';
import { jsonError, jsonOk, methodNotAllowed } from '../lib/response';
import { readJson, validateMood } from '../lib/validate';
import { SCHEMA_VERSION, type CurrentMood, type Env } from '../types';

export async function handleMood(request: Request, env: Env): Promise<Response> {
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

  return jsonOk({ updatedAt: now });
}