'use strict';

const fs = require('node:fs');
const http = require('node:http');

const VERSION = '4.32.0';
const STYLE_HREF = `/runtime-polish.css?v=${VERSION}`;

// Inject the lightweight global polish stylesheet before the application reads its HTML shell.
const originalReadFileSync = fs.readFileSync;
fs.readFileSync = function patchedReadFileSync(file, ...args) {
  const out = originalReadFileSync.call(fs, file, ...args);
  const normalized = String(file || '').replace(/\\/g, '/');
  if (!normalized.endsWith('/public/index.html') && !normalized.endsWith('public/index.html')) return out;
  const asBuffer = Buffer.isBuffer(out);
  let html = asBuffer ? out.toString('utf8') : String(out);
  if (!html.includes('/runtime-polish.css')) {
    html = html.replace('</head>', `<link rel="stylesheet" href="${STYLE_HREF}"></head>`);
  }
  return asBuffer ? Buffer.from(html) : html;
};

function requestPath(req) {
  try { return new URL(req?.url || '/', 'http://runtime.local').pathname; }
  catch { return '/'; }
}

function clientKey(req) {
  const chain = String(req?.headers?.['x-forwarded-for'] || '').split(',').map(x => x.trim()).filter(Boolean);
  return chain.at(-1) || req?.socket?.remoteAddress || 'unknown';
}

function hardenCookie(value) {
  let c = String(value || '');
  if (!/^beheshti_session=/i.test(c)) return c;
  c = /;\s*SameSite=/i.test(c) ? c.replace(/;\s*SameSite=[^;]*/i, '; SameSite=Strict') : `${c}; SameSite=Strict`;
  if (!/;\s*Secure\b/i.test(c)) c += '; Secure';
  if (!/;\s*HttpOnly\b/i.test(c)) c += '; HttpOnly';
  if (!/;\s*Priority=/i.test(c)) c += '; Priority=High';
  return c;
}

function hardenCsp(current) {
  const map = new Map();
  for (const raw of String(current || '').split(';')) {
    const part = raw.trim();
    if (!part) continue;
    const [name, ...rest] = part.split(/\s+/);
    map.set(name.toLowerCase(), `${name.toLowerCase()}${rest.length ? ` ${rest.join(' ')}` : ''}`);
  }
  const force = {
    'default-src': "default-src 'self'",
    'base-uri': "base-uri 'self'",
    'form-action': "form-action 'self'",
    'frame-src': "frame-src 'none'",
    'frame-ancestors': "frame-ancestors 'none'",
    'object-src': "object-src 'none'",
    'img-src': "img-src 'self' data:",
    'media-src': "media-src 'self'",
    'manifest-src': "manifest-src 'self'",
    'worker-src': "worker-src 'self'",
    'font-src': "font-src 'self' data:",
    'connect-src': "connect-src 'self'",
    'script-src-attr': "script-src-attr 'none'"
  };
  for (const [k, v] of Object.entries(force)) map.set(k, v);
  if (!map.has('script-src')) map.set('script-src', "script-src 'self'");
  if (!map.has('style-src')) map.set('style-src', "style-src 'self' 'unsafe-inline'");
  map.set('upgrade-insecure-requests', 'upgrade-insecure-requests');
  return [...map.values()].join('; ');
}

const sensitivePrefix = /^(?:\/api\/(?:account|admin|studio|internal|admissions|registrar|student|dashboard|me|dr)|\/api\/auth\/)/;
const downloadPath = /^(?:\/api\/submission-attachments\/[^/]+\/file|\/api\/resources\/[^/]+\/file|\/api\/admissions\/documents\/[^/]+\/file)$/;

const originalWriteHead = http.ServerResponse.prototype.writeHead;
http.ServerResponse.prototype.writeHead = function hardenedWriteHead(...args) {
  const p = requestPath(this.req);
  try {
    this.setHeader('Strict-Transport-Security', 'max-age=63072000; includeSubDomains');
    this.setHeader('X-Content-Type-Options', 'nosniff');
    this.setHeader('X-Frame-Options', 'DENY');
    this.setHeader('Referrer-Policy', 'no-referrer');
    this.setHeader('Cross-Origin-Opener-Policy', 'same-origin');
    this.setHeader('Cross-Origin-Resource-Policy', 'same-origin');
    this.setHeader('Origin-Agent-Cluster', '?1');
    this.setHeader('X-Permitted-Cross-Domain-Policies', 'none');
    this.setHeader('Permissions-Policy', 'camera=(), geolocation=(), payment=(), usb=(), microphone=(self), fullscreen=(self)');
    const csp = this.getHeader('content-security-policy');
    this.setHeader('Content-Security-Policy', hardenCsp(csp));
    const cookies = this.getHeader('set-cookie');
    if (cookies) this.setHeader('Set-Cookie', (Array.isArray(cookies) ? cookies : [cookies]).map(hardenCookie));
    if (sensitivePrefix.test(p)) this.setHeader('Cache-Control', 'private, no-store');
    if (downloadPath.test(p)) {
      this.setHeader('Content-Disposition', 'attachment; filename="secure-download"');
      this.setHeader('X-Download-Options', 'noopen');
      this.setHeader('Content-Security-Policy', "sandbox; default-src 'none'");
    }
  } catch {}
  return originalWriteHead.apply(this, args);
};

// Pin JWT signing/verification algorithm and bound session age without changing the application's issuer contract.
try {
  const jwt = require('jsonwebtoken');
  const originalSign = jwt.sign.bind(jwt);
  const originalVerify = jwt.verify.bind(jwt);
  const privileged = role => ['admin', 'super_admin', 'reviewer', 'instructor'].includes(String(role || ''));
  jwt.sign = function hardenedSign(payload, secret, options = {}) {
    const ttl = privileged(payload?.role) ? '8h' : '24h';
    return originalSign(payload, secret, { ...options, expiresIn: ttl, algorithm: 'HS256' });
  };
  jwt.verify = function hardenedVerify(token, secret, options = {}) {
    const decoded = originalVerify(token, secret, { ...options, algorithms: ['HS256'] });
    if (decoded?.iat) {
      const maxAge = privileged(decoded.role) ? 8 * 3600 : 24 * 3600;
      if (Math.floor(Date.now() / 1000) - Number(decoded.iat) > maxAge) {
        throw new jwt.TokenExpiredError('session_max_age_exceeded', new Date((Number(decoded.iat) + maxAge) * 1000));
      }
    }
    return decoded;
  };
} catch {}

// Small in-memory edge guard. Existing application-level per-account rate limits remain authoritative.
const buckets = new Map();
function blocked(key, limit, windowMs) {
  const now = Date.now();
  const hit = buckets.get(key);
  if (!hit || hit.until <= now) {
    buckets.set(key, { count: 1, until: now + windowMs });
    return false;
  }
  hit.count += 1;
  if (buckets.size > 5000) {
    for (const [k, v] of buckets) if (v.until <= now) buckets.delete(k);
  }
  return hit.count > limit;
}

const originalEmit = http.Server.prototype.emit;
http.Server.prototype.emit = function hardenedEmit(event, ...args) {
  if (event === 'request') {
    const [req, res] = args;
    const method = String(req?.method || 'GET').toUpperCase();
    const p = requestPath(req);
    if (/^(TRACE|TRACK|CONNECT)$/.test(method)) {
      res.statusCode = 405; res.setHeader('Allow', 'GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS'); res.end('Method Not Allowed'); return true;
    }
    if (String(req?.url || '').length > 8192) {
      res.statusCode = 414; res.end('URI Too Long'); return true;
    }
    const declared = Number(req?.headers?.['content-length'] || 0);
    if (Number.isFinite(declared) && declared > 22 * 1024 * 1024) {
      res.statusCode = 413; res.end('Payload Too Large'); return true;
    }
    const ip = clientKey(req);
    let limited = false;
    if (method === 'POST' && /^\/api\/(?:auth\/(?:login|recover|password-reset\/)|setup\/)/.test(p)) limited = blocked(`auth:${ip}`, 30, 15 * 60 * 1000);
    else if (method === 'POST' && p === '/api/support/tickets') limited = blocked(`support:${ip}`, 6, 10 * 60 * 1000);
    if (limited) {
      res.statusCode = 429; res.setHeader('Retry-After', '900'); res.setHeader('Content-Type', 'application/json; charset=utf-8'); res.end('{"error":"too_many_requests"}'); return true;
    }
  }
  return originalEmit.call(this, event, ...args);
};
