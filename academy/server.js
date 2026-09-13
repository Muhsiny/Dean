const express = require('express');
const path = require('path');
const crypto = require('crypto');
const bcrypt = require('bcryptjs');
const jwt = require('jsonwebtoken');
const helmet = require('helmet');
const rateLimit = require('express-rate-limit');
const { Pool } = require('pg');

const app = express();
const PORT = Number(process.env.PORT || 10000);
const DATABASE_URL = process.env.DATABASE_URL;
const JWT_SECRET = process.env.JWT_SECRET;
if (!DATABASE_URL) throw new Error('DATABASE_URL is required');
if (!JWT_SECRET || JWT_SECRET.length < 32) throw new Error('JWT_SECRET must be at least 32 characters');

const pool = new Pool({ connectionString: DATABASE_URL, ssl: { rejectUnauthorized: false }, max: 8, idleTimeoutMillis: 30000 });

app.set('trust proxy', 1);
app.use(helmet({ contentSecurityPolicy: false, crossOriginEmbedderPolicy: false }));
app.use(express.json({ limit: '1mb' }));
app.use('/api/', rateLimit({ windowMs: 60_000, limit: 180, standardHeaders: 'draft-7', legacyHeaders: false }));
app.use(express.static(path.join(__dirname, 'public'), { maxAge: '1h', etag: true }));

const initialState = () => ({
  version: 2,
  profile: { setupComplete: false, goal: 'General English', dailyMinutes: 45, accent: 'American', currentWeek: 1, currentDay: 1, startedAt: Date.now() },
  placement: { completed: false, score: null, provisionalLevel: null, startingWeek: 1 },
  progress: { completed: [], scores: {}, streak: 0, lastStudyDate: null, studySeconds: 0, weeklyMastery: {} },
  vocab: {}, mistakes: {}, artifacts: [], settings: { dariSupport: true, reducedMotion: false }
});

function normalizeState(raw) {
  const f = initialState();
  const s = raw && typeof raw === 'object' ? raw : {};
  const p = s.profile || {}, pl = s.placement || {}, pr = s.progress || {};
  f.profile = { ...f.profile, ...p, currentWeek: Math.max(1, Math.min(24, Number(p.currentWeek) || 1)), currentDay: Math.max(1, Math.min(7, Number(p.currentDay) || 1)), dailyMinutes: Math.max(15, Math.min(180, Number(p.dailyMinutes) || 45)) };
  f.placement = { ...f.placement, ...pl, startingWeek: Math.max(1, Math.min(24, Number(pl.startingWeek) || 1)) };
  const validId = x => /^w(?:[1-9]|1\d|2[0-4])d[1-7]$/.test(String(x));
  f.progress = {
    ...f.progress, ...pr,
    completed: Array.isArray(pr.completed) ? [...new Set(pr.completed.filter(validId))] : [],
    scores: pr.scores && typeof pr.scores === 'object' ? pr.scores : {},
    weeklyMastery: pr.weeklyMastery && typeof pr.weeklyMastery === 'object' ? pr.weeklyMastery : {}
  };
  f.vocab = s.vocab && typeof s.vocab === 'object' ? s.vocab : {};
  f.mistakes = s.mistakes && typeof s.mistakes === 'object' ? s.mistakes : {};
  f.artifacts = Array.isArray(s.artifacts) ? s.artifacts.slice(-500) : [];
  f.settings = { ...f.settings, ...(s.settings || {}) };
  return f;
}

async function initDb() {
  await pool.query(`
    CREATE TABLE IF NOT EXISTS academy_users (
      id UUID PRIMARY KEY,
      email TEXT UNIQUE NOT NULL,
      name TEXT NOT NULL,
      password_hash TEXT NOT NULL,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
      last_login_at TIMESTAMPTZ
    );
    CREATE TABLE IF NOT EXISTS academy_state (
      user_id UUID PRIMARY KEY REFERENCES academy_users(id) ON DELETE CASCADE,
      state JSONB NOT NULL,
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );
    CREATE TABLE IF NOT EXISTS academy_events (
      id BIGSERIAL PRIMARY KEY,
      user_id UUID NOT NULL REFERENCES academy_users(id) ON DELETE CASCADE,
      lesson_id TEXT NOT NULL,
      activity TEXT NOT NULL,
      score INTEGER,
      payload JSONB NOT NULL DEFAULT '{}'::jsonb,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );
    CREATE INDEX IF NOT EXISTS academy_events_user_created_idx ON academy_events(user_id, created_at DESC);
    CREATE TABLE IF NOT EXISTS academy_artifacts (
      id UUID PRIMARY KEY,
      user_id UUID NOT NULL REFERENCES academy_users(id) ON DELETE CASCADE,
      lesson_id TEXT NOT NULL,
      type TEXT NOT NULL,
      content TEXT NOT NULL,
      meta JSONB NOT NULL DEFAULT '{}'::jsonb,
      created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );
    CREATE INDEX IF NOT EXISTS academy_artifacts_user_created_idx ON academy_artifacts(user_id, created_at DESC);
    CREATE TABLE IF NOT EXISTS academy_dictionary_cache (
      term TEXT PRIMARY KEY,
      payload JSONB NOT NULL,
      updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );
  `);
}

function sign(user) { return jwt.sign({ sub: user.id, email: user.email, name: user.name }, JWT_SECRET, { expiresIn: '30d', issuer: 'beheshti-academy' }); }
function auth(req, res, next) {
  const h = req.headers.authorization || '';
  const token = h.startsWith('Bearer ') ? h.slice(7) : null;
  if (!token) return res.status(401).json({ error: 'Authentication required' });
  try { req.user = jwt.verify(token, JWT_SECRET, { issuer: 'beheshti-academy' }); next(); }
  catch { return res.status(401).json({ error: 'Invalid or expired session' }); }
}
const cleanEmail = v => String(v || '').trim().toLowerCase().slice(0, 254);
const cleanName = v => String(v || '').trim().replace(/\s+/g, ' ').slice(0, 80);

app.get('/api/health', async (_req, res) => {
  try { const r = await pool.query('SELECT NOW() AS now'); res.json({ ok: true, service: 'beheshti-academy', database: 'ok', now: r.rows[0].now }); }
  catch (e) { res.status(503).json({ ok: false, error: 'database_unavailable' }); }
});

app.post('/api/auth/register', async (req, res) => {
  const email = cleanEmail(req.body.email), name = cleanName(req.body.name), password = String(req.body.password || '');
  if (!/^\S+@\S+\.\S+$/.test(email)) return res.status(400).json({ error: 'Enter a valid email address' });
  if (name.length < 2) return res.status(400).json({ error: 'Name is too short' });
  if (password.length < 8) return res.status(400).json({ error: 'Password must be at least 8 characters' });
  const id = crypto.randomUUID();
  try {
    const hash = await bcrypt.hash(password, 12);
    await pool.query('BEGIN');
    await pool.query('INSERT INTO academy_users(id,email,name,password_hash) VALUES($1,$2,$3,$4)', [id, email, name, hash]);
    const state = initialState();
    await pool.query('INSERT INTO academy_state(user_id,state) VALUES($1,$2::jsonb)', [id, JSON.stringify(state)]);
    await pool.query('COMMIT');
    res.status(201).json({ token: sign({ id, email, name }), user: { id, email, name }, state });
  } catch (e) {
    await pool.query('ROLLBACK').catch(() => {});
    if (e.code === '23505') return res.status(409).json({ error: 'An account with this email already exists' });
    console.error(e); res.status(500).json({ error: 'Could not create account' });
  }
});

app.post('/api/auth/login', async (req, res) => {
  const email = cleanEmail(req.body.email), password = String(req.body.password || '');
  const r = await pool.query('SELECT id,email,name,password_hash FROM academy_users WHERE email=$1', [email]);
  const user = r.rows[0];
  if (!user || !(await bcrypt.compare(password, user.password_hash))) return res.status(401).json({ error: 'Email or password is incorrect' });
  await pool.query('UPDATE academy_users SET last_login_at=NOW() WHERE id=$1', [user.id]);
  const s = await pool.query('SELECT state FROM academy_state WHERE user_id=$1', [user.id]);
  const state = normalizeState(s.rows[0]?.state);
  res.json({ token: sign(user), user: { id: user.id, email: user.email, name: user.name }, state });
});

app.get('/api/me', auth, async (req, res) => {
  const r = await pool.query('SELECT id,email,name,created_at,last_login_at FROM academy_users WHERE id=$1', [req.user.sub]);
  if (!r.rows[0]) return res.status(404).json({ error: 'Account not found' });
  res.json({ user: r.rows[0] });
});

app.get('/api/state', auth, async (req, res) => {
  const r = await pool.query('SELECT state,updated_at FROM academy_state WHERE user_id=$1', [req.user.sub]);
  if (!r.rows[0]) return res.status(404).json({ error: 'State not found' });
  res.json({ state: normalizeState(r.rows[0].state), updatedAt: r.rows[0].updated_at });
});

app.put('/api/state', auth, async (req, res) => {
  const state = normalizeState(req.body.state);
  await pool.query('UPDATE academy_state SET state=$2::jsonb, updated_at=NOW() WHERE user_id=$1', [req.user.sub, JSON.stringify(state)]);
  res.json({ state });
});

app.post('/api/events', auth, async (req, res) => {
  const lessonId = String(req.body.lessonId || '').slice(0, 20), activity = String(req.body.activity || '').slice(0, 40);
  const score = req.body.score == null ? null : Math.max(0, Math.min(100, Number(req.body.score) || 0));
  if (!/^w(?:[1-9]|1\d|2[0-4])d[1-7]$/.test(lessonId) || !activity) return res.status(400).json({ error: 'Invalid lesson event' });
  await pool.query('INSERT INTO academy_events(user_id,lesson_id,activity,score,payload) VALUES($1,$2,$3,$4,$5::jsonb)', [req.user.sub, lessonId, activity, score, JSON.stringify(req.body.payload || {})]);
  res.status(201).json({ ok: true });
});

app.get('/api/events', auth, async (req, res) => {
  const r = await pool.query('SELECT id,lesson_id,activity,score,payload,created_at FROM academy_events WHERE user_id=$1 ORDER BY created_at DESC LIMIT 200', [req.user.sub]);
  res.json({ events: r.rows });
});

app.post('/api/artifacts', auth, async (req, res) => {
  const id = crypto.randomUUID(), lessonId = String(req.body.lessonId || '').slice(0, 20), type = String(req.body.type || '').slice(0, 30), content = String(req.body.content || '').trim().slice(0, 12000);
  if (!lessonId || !['writing','speaking','reflection'].includes(type) || !content) return res.status(400).json({ error: 'Invalid artifact' });
  await pool.query('INSERT INTO academy_artifacts(id,user_id,lesson_id,type,content,meta) VALUES($1,$2,$3,$4,$5,$6::jsonb)', [id, req.user.sub, lessonId, type, content, JSON.stringify(req.body.meta || {})]);
  res.status(201).json({ artifact: { id, lessonId, type, content, meta: req.body.meta || {}, createdAt: new Date().toISOString() } });
});

app.get('/api/artifacts', auth, async (req, res) => {
  const r = await pool.query('SELECT id,lesson_id AS "lessonId",type,content,meta,created_at AS "createdAt" FROM academy_artifacts WHERE user_id=$1 ORDER BY created_at DESC LIMIT 300', [req.user.sub]);
  res.json({ artifacts: r.rows });
});

app.post('/api/dictionary', auth, async (req, res) => {
  const term = String(req.body.term || '').trim().toLowerCase().replace(/[^a-z'-]/g, '').slice(0, 60);
  if (!term) return res.status(400).json({ error: 'Enter an English word' });
  const cached = await pool.query('SELECT payload FROM academy_dictionary_cache WHERE term=$1 AND updated_at > NOW() - INTERVAL \'90 days\'', [term]);
  if (cached.rows[0]) return res.json({ entry: cached.rows[0].payload, cached: true });
  try {
    const ctrl = new AbortController(); const t = setTimeout(() => ctrl.abort(), 6500);
    const r = await fetch('https://api.dictionaryapi.dev/api/v2/entries/en/' + encodeURIComponent(term), { signal: ctrl.signal }); clearTimeout(t);
    if (!r.ok) return res.status(404).json({ error: 'Verified definition unavailable' });
    const arr = await r.json(), e = Array.isArray(arr) ? arr[0] : null;
    const m = e?.meanings?.find(x => x?.definitions?.length), d = m?.definitions?.[0];
    if (!e || !d) return res.status(404).json({ error: 'Verified definition unavailable' });
    const entry = { term, phonetic: e.phonetic || e.phonetics?.find(x => x.text)?.text || '', partOfSpeech: m.partOfSpeech || '', definition: d.definition || '', example: d.example || '' };
    await pool.query('INSERT INTO academy_dictionary_cache(term,payload,updated_at) VALUES($1,$2::jsonb,NOW()) ON CONFLICT(term) DO UPDATE SET payload=EXCLUDED.payload,updated_at=NOW()', [term, JSON.stringify(entry)]);
    res.json({ entry, cached: false });
  } catch { res.status(504).json({ error: 'Dictionary service timed out; try again' }); }
});

app.get('*', (_req, res) => res.sendFile(path.join(__dirname, 'public', 'index.html')));

initDb().then(() => app.listen(PORT, '0.0.0.0', () => console.log(`Beheshti Academy listening on ${PORT}`))).catch(err => { console.error('Database initialization failed', err); process.exit(1); });
