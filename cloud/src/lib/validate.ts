// 请求体读取与字段白名单校验
// 设计原则：严格白名单 + 范围检查 + 未知字段直接拒绝（防止契约悄悄漂移）

import {
  MAX_BODY_BYTES,
  MAX_MOOD_EMOJI,
  MAX_MOOD_TEXT,
  type HeartbeatInput,
  type MoodInput,
} from '../types';

export type ValidationResult<T> =
  | { ok: true; value: T }
  | { ok: false; code: string; message: string };

const CHARGE_SOURCES = new Set(['ac', 'usb', 'wireless', 'none']);
const NETWORKS = new Set(['wifi', 'cellular', 'ethernet', 'none', 'unknown']);

const HEARTBEAT_KEYS = new Set([
  'schemaVersion',
  'batteryPercent',
  'charging',
  'chargeSource',
  'temperatureC',
  'network',
  'deviceName',
  'appVersion',
  'clientTs',
  'seq',
]);

const MOOD_KEYS = new Set(['schemaVersion', 'text', 'emoji']);

/** 读取 body 并解析 JSON，含两段长度防护 */
export async function readJson(request: Request): Promise<ValidationResult<Record<string, unknown>>> {
  const contentType = request.headers.get('content-type') ?? '';
  if (!contentType.toLowerCase().includes('application/json')) {
    return { ok: false, code: 'unsupported_media_type', message: 'Content-Type must be application/json' };
  }

  const declared = request.headers.get('content-length');
  if (declared && Number(declared) > MAX_BODY_BYTES) {
    return { ok: false, code: 'payload_too_large', message: `Body must be <= ${MAX_BODY_BYTES} bytes` };
  }

  let text: string;
  try {
    text = await request.text();
  } catch {
    return { ok: false, code: 'invalid_json', message: 'Could not read request body' };
  }

  // 二次校验：Content-Length 可能缺失或被伪造
  if (text.length > MAX_BODY_BYTES) {
    return { ok: false, code: 'payload_too_large', message: `Body must be <= ${MAX_BODY_BYTES} bytes` };
  }

  try {
    const parsed: unknown = JSON.parse(text);
    if (parsed === null || typeof parsed !== 'object' || Array.isArray(parsed)) {
      return { ok: false, code: 'invalid_json', message: 'Body must be a JSON object' };
    }
    return { ok: true, value: parsed as Record<string, unknown> };
  } catch {
    return { ok: false, code: 'invalid_json', message: 'Body is not valid JSON' };
  }
}

function unknownKeys(raw: Record<string, unknown>, allowed: Set<string>): string[] {
  return Object.keys(raw).filter((k) => !allowed.has(k));
}

function asInt(value: unknown): number | null | undefined {
  if (value === undefined || value === null) return null;
  if (typeof value !== 'number' || !Number.isFinite(value)) return undefined;
  return Math.trunc(value);
}

function asNumber(value: unknown): number | null | undefined {
  if (value === undefined || value === null) return null;
  if (typeof value !== 'number' || !Number.isFinite(value)) return undefined;
  return value;
}

function asBool(value: unknown): boolean | null | undefined {
  if (value === undefined || value === null) return null;
  if (typeof value !== 'boolean') return undefined;
  return value;
}

function asTrimmedString(value: unknown, maxLen: number): string | null | undefined {
  if (value === undefined || value === null) return null;
  if (typeof value !== 'string') return undefined;
  const s = value.trim();
  if (s.length === 0) return null;
  if (s.length > maxLen) return undefined;
  return s;
}

export function validateHeartbeat(raw: Record<string, unknown>): ValidationResult<HeartbeatInput> {
  const extra = unknownKeys(raw, HEARTBEAT_KEYS);
  if (extra.length > 0) {
    return { ok: false, code: 'invalid_payload', message: `Unknown field(s): ${extra.join(', ')}` };
  }

  const batteryPercent = asInt(raw.batteryPercent);
  const charging = asBool(raw.charging);
  const temperatureC = asNumber(raw.temperatureC);
  const clientTs = asInt(raw.clientTs);
  const seq = asInt(raw.seq);
  const chargeSource = asTrimmedString(raw.chargeSource, 16);
  const network = asTrimmedString(raw.network, 16);
  const deviceName = asTrimmedString(raw.deviceName, 32);
  const appVersion = asTrimmedString(raw.appVersion, 24);

  if (batteryPercent === undefined) {
    return { ok: false, code: 'invalid_payload', message: 'batteryPercent must be a number' };
  }
  if (batteryPercent !== null && (batteryPercent < 0 || batteryPercent > 100)) {
    return { ok: false, code: 'invalid_payload', message: 'batteryPercent must be within 0..100' };
  }
  if (charging === undefined) {
    return { ok: false, code: 'invalid_payload', message: 'charging must be a boolean' };
  }
  if (temperatureC === undefined) {
    return { ok: false, code: 'invalid_payload', message: 'temperatureC must be a number' };
  }
  if (temperatureC !== null && (temperatureC < -50 || temperatureC > 200)) {
    return { ok: false, code: 'invalid_payload', message: 'temperatureC must be within -50..200' };
  }
  if (chargeSource === undefined) {
    return { ok: false, code: 'invalid_payload', message: 'chargeSource must be a short string' };
  }
  if (chargeSource !== null && !CHARGE_SOURCES.has(chargeSource)) {
    return { ok: false, code: 'invalid_payload', message: `chargeSource must be one of ${[...CHARGE_SOURCES].join('/')}` };
  }
  if (network === undefined) {
    return { ok: false, code: 'invalid_payload', message: 'network must be a short string' };
  }
  if (network !== null && !NETWORKS.has(network)) {
    return { ok: false, code: 'invalid_payload', message: `network must be one of ${[...NETWORKS].join('/')}` };
  }
  if (deviceName === undefined) {
    return { ok: false, code: 'invalid_payload', message: 'deviceName must be a string of <= 32 chars' };
  }
  if (appVersion === undefined) {
    return { ok: false, code: 'invalid_payload', message: 'appVersion must be a string of <= 24 chars' };
  }
  if (clientTs === undefined || seq === undefined) {
    return { ok: false, code: 'invalid_payload', message: 'clientTs and seq must be numbers' };
  }

  return {
    ok: true,
    value: {
      batteryPercent,
      charging,
      chargeSource,
      temperatureC,
      network,
      deviceName,
      appVersion,
      clientTs,
      seq,
    },
  };
}

export function validateMood(raw: Record<string, unknown>): ValidationResult<MoodInput> {
  const extra = unknownKeys(raw, MOOD_KEYS);
  if (extra.length > 0) {
    return { ok: false, code: 'invalid_payload', message: `Unknown field(s): ${extra.join(', ')}` };
  }

  if (typeof raw.text !== 'string') {
    return { ok: false, code: 'invalid_payload', message: 'text is required' };
  }
  const text = raw.text.trim();
  if (text.length === 0) {
    return { ok: false, code: 'invalid_payload', message: 'text must not be empty' };
  }
  // 按码点计数（emoji / 中文都算 1 个）
  if ([...text].length > MAX_MOOD_TEXT) {
    return { ok: false, code: 'invalid_payload', message: `text must be <= ${MAX_MOOD_TEXT} characters` };
  }

  const emoji = asTrimmedString(raw.emoji, MAX_MOOD_EMOJI);
  if (emoji === undefined) {
    return { ok: false, code: 'invalid_payload', message: `emoji must be <= ${MAX_MOOD_EMOJI} chars` };
  }

  return { ok: true, value: { text, emoji } };
}
