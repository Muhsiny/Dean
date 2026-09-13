import { createClient, BetterAuthVanillaAdapter } from '@neondatabase/neon-js';

const AUTH_URL = 'https://ep-plain-cherry-b2cg0q4a.neonauth.c-6.eu-central-1.aws.neon.tech/academy/auth';
const DATA_API_URL = 'https://ep-plain-cherry-b2cg0q4a.apirest.c-6.eu-central-1.aws.neon.tech/academy/rest/v1';

const neon = createClient({
  auth: {
    adapter: BetterAuthVanillaAdapter(),
    url: AUTH_URL,
  },
  dataApi: {
    url: DATA_API_URL,
  },
});

const nativeFetch = window.fetch.bind(window);
const json = (payload, status = 200) => new Response(JSON.stringify(payload), {
  status,
  headers: { 'content-type': 'application/json; charset=utf-8' },
});

const initialState = () => ({
  version: 2,
  profile: {
    setupComplete: false,
    goal: 'General English',
    dailyMinutes: 45,
    accent: 'American',
    currentWeek: 1,
    currentDay: 1,
    startedAt: Date.now(),
  },
  placement: {
    completed: false,
    score: null,
    provisionalLevel: null,
    startingWeek: 1,
  },
  progress: {
    completed: [],
    scores: {},
    streak: 0,
    lastStudyDate: null,
    studySeconds: 0,
    weeklyMastery: {},
  },
  vocab: {},
  mistakes: {},
  artifacts: [],
  settings: { dariSupport: true, reducedMotion: false },
});

function authData(result) {
  if (result?.error) throw new Error(result.error.message || String(result.error));
  return result?.data ?? result;
}

async function currentUser() {
  const raw = authData(await neon.auth.getSession());
  const u = raw?.user || raw?.session?.user || raw?.data?.user;
  if (!u?.id) throw new Error('Authentication required');
  return { id: String(u.id), email: u.email || '', name: u.name || (u.email ? u.email.split('@')[0] : 'Learner') };
}

async function ensureState() {
  const u = await currentUser();
  const q = await neon.from('academy_state').select('state,updated_at').eq('user_id', u.id).limit(1);
  if (q.error) throw new Error(q.error.message || 'Could not load learning state');
  if (q.data?.[0]) return { state: q.data[0].state, updatedAt: q.data[0].updated_at };
  const fresh = initialState();
  const ins = await neon.from('academy_state').insert({ user_id: u.id, state: fresh, updated_at: new Date().toISOString() }).select('state,updated_at').single();
  if (ins.error) throw new Error(ins.error.message || 'Could not initialize learning state');
  return { state: ins.data.state, updatedAt: ins.data.updated_at };
}

async function saveState(nextState) {
  const u = await currentUser();
  const now = new Date().toISOString();
  const up = await neon.from('academy_state').update({ state: nextState, updated_at: now }).eq('user_id', u.id).select('state');
  if (up.error) throw new Error(up.error.message || 'Could not save learning state');
  if (!up.data?.length) {
    const ins = await neon.from('academy_state').insert({ user_id: u.id, state: nextState, updated_at: now }).select('state').single();
    if (ins.error) throw new Error(ins.error.message || 'Could not save learning state');
    return ins.data.state;
  }
  return up.data[0].state;
}

async function handleApi(path, method, body) {
  if (path === '/api/auth/register' && method === 'POST') {
    const email = String(body.email || '').trim().toLowerCase();
    const password = String(body.password || '');
    const name = String(body.name || '').trim();
    if (!email || password.length < 8 || name.length < 2) return json({ error: 'Enter a valid name, email and password of at least 8 characters' }, 400);
    const out = await neon.auth.signUp.email({ email, password, name });
    authData(out);
    let u;
    try { u = await currentUser(); }
    catch {
      authData(await neon.auth.signIn.email({ email, password }));
      u = await currentUser();
    }
    const s = await ensureState();
    return json({ token: 'neon-managed-session', user: u, state: s.state }, 201);
  }

  if (path === '/api/auth/login' && method === 'POST') {
    const email = String(body.email || '').trim().toLowerCase();
    const password = String(body.password || '');
    const result = await neon.auth.signIn.email({ email, password });
    authData(result);
    const u = await currentUser();
    const s = await ensureState();
    return json({ token: 'neon-managed-session', user: u, state: s.state });
  }

  if (path === '/api/auth/logout' && method === 'POST') {
    authData(await neon.auth.signOut());
    return json({ ok: true });
  }

  if (path === '/api/me' && method === 'GET') {
    return json({ user: await currentUser() });
  }

  if (path === '/api/state' && method === 'GET') {
    return json(await ensureState());
  }

  if (path === '/api/state' && method === 'PUT') {
    if (!body.state || typeof body.state !== 'object') return json({ error: 'Invalid state' }, 400);
    return json({ state: await saveState(body.state) });
  }

  if (path === '/api/events' && method === 'POST') {
    const u = await currentUser();
    const lessonId = String(body.lessonId || '').slice(0, 20);
    const activity = String(body.activity || '').slice(0, 40);
    if (!/^w(?:[1-9]|1\d|2[0-4])d[1-7]$/.test(lessonId) || !activity) return json({ error: 'Invalid lesson event' }, 400);
    const score = body.score == null ? null : Math.max(0, Math.min(100, Number(body.score) || 0));
    const q = await neon.from('academy_events').insert({
      user_id: u.id,
      lesson_id: lessonId,
      activity,
      score,
      payload: body.payload || {},
    });
    if (q.error) throw new Error(q.error.message || 'Could not save event');
    return json({ ok: true }, 201);
  }

  if (path === '/api/events' && method === 'GET') {
    const u = await currentUser();
    const q = await neon.from('academy_events').select('id,lesson_id,activity,score,payload,created_at').eq('user_id', u.id).order('created_at', { ascending: false }).limit(200);
    if (q.error) throw new Error(q.error.message || 'Could not load events');
    return json({ events: q.data || [] });
  }

  if (path === '/api/artifacts' && method === 'POST') {
    const u = await currentUser();
    const lessonId = String(body.lessonId || '').slice(0, 20);
    const type = String(body.type || '').slice(0, 30);
    const content = String(body.content || '').trim().slice(0, 12000);
    if (!lessonId || !['writing', 'speaking', 'reflection'].includes(type) || !content) return json({ error: 'Invalid artifact' }, 400);
    const q = await neon.from('academy_artifacts').insert({
      user_id: u.id,
      lesson_id: lessonId,
      type,
      content,
      meta: body.meta || {},
    }).select('id,lesson_id,type,content,meta,created_at').single();
    if (q.error) throw new Error(q.error.message || 'Could not save evidence');
    return json({ artifact: {
      id: q.data.id,
      lessonId: q.data.lesson_id,
      type: q.data.type,
      content: q.data.content,
      meta: q.data.meta,
      createdAt: q.data.created_at,
    } }, 201);
  }

  if (path === '/api/artifacts' && method === 'GET') {
    const u = await currentUser();
    const q = await neon.from('academy_artifacts').select('id,lesson_id,type,content,meta,created_at').eq('user_id', u.id).order('created_at', { ascending: false }).limit(300);
    if (q.error) throw new Error(q.error.message || 'Could not load evidence');
    return json({ artifacts: (q.data || []).map(x => ({
      id: x.id,
      lessonId: x.lesson_id,
      type: x.type,
      content: x.content,
      meta: x.meta,
      createdAt: x.created_at,
    })) });
  }

  if (path === '/api/dictionary' && method === 'POST') {
    const term = String(body.term || '').trim().toLowerCase().replace(/[^a-z'-]/g, '').slice(0, 60);
    if (!term) return json({ error: 'Enter an English word' }, 400);
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), 6500);
    try {
      const r = await nativeFetch('https://api.dictionaryapi.dev/api/v2/entries/en/' + encodeURIComponent(term), { signal: controller.signal });
      if (!r.ok) return json({ error: 'Verified definition unavailable' }, 404);
      const arr = await r.json();
      const entry = Array.isArray(arr) ? arr[0] : null;
      const meaning = entry?.meanings?.find(x => x?.definitions?.length) || entry?.meanings?.[0];
      const def = meaning?.definitions?.[0];
      if (!entry || !def) return json({ error: 'Verified definition unavailable' }, 404);
      return json({ entry: {
        term,
        phonetic: entry.phonetic || entry.phonetics?.find(x => x.text)?.text || '',
        partOfSpeech: meaning?.partOfSpeech || '',
        definition: def.definition || '',
        example: def.example || '',
      }, cached: false });
    } finally { clearTimeout(timer); }
  }

  return json({ error: 'Unknown API route' }, 404);
}

window.fetch = async function academyFetch(input, init = {}) {
  const url = typeof input === 'string' ? new URL(input, location.origin) : new URL(input.url, location.origin);
  if (url.origin !== location.origin || !url.pathname.startsWith('/api/')) return nativeFetch(input, init);
  const method = String(init.method || (typeof input !== 'string' && input.method) || 'GET').toUpperCase();
  let body = {};
  if (init.body && typeof init.body === 'string') {
    try { body = JSON.parse(init.body); } catch { body = {}; }
  }
  try {
    return await handleApi(url.pathname, method, body);
  } catch (err) {
    const message = err?.message || 'Request failed';
    const status = /Authentication required|session|unauthorized/i.test(message) ? 401 : 500;
    console.error('Academy API bridge:', err);
    return json({ error: message }, status);
  }
};

// The legacy learning engine uses a local marker only to decide whether to boot
// the authenticated screen. The actual session remains an HttpOnly Neon Auth session.
document.addEventListener('click', (event) => {
  const target = event.target.closest?.('[data-action="logout"]');
  if (target) neon.auth.signOut().catch(() => {});
}, true);

window.BEHESHTI_BACKEND = Object.freeze({
  provider: 'Neon',
  auth: 'Neon Auth / Better Auth',
  data: 'PostgreSQL + Data API + RLS',
});
