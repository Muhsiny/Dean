const express = require('express');
const path = require('path');

const app = express();
const PORT = Number(process.env.PORT || 10000);
const PUBLIC = path.join(__dirname, 'public');

app.disable('x-powered-by');
app.use((req, res, next) => {
  res.setHeader('X-Content-Type-Options', 'nosniff');
  res.setHeader('Referrer-Policy', 'strict-origin-when-cross-origin');
  res.setHeader('Permissions-Policy', 'camera=(), geolocation=(), microphone=(self)');
  next();
});
app.get('/health', (_req, res) => res.json({ ok: true, service: 'beheshti-academy', version: '2.0.0' }));
app.use(express.static(PUBLIC, { maxAge: '1h', etag: true }));
app.get('*', (_req, res) => res.sendFile(path.join(PUBLIC, 'index.html')));

app.listen(PORT, '0.0.0.0', () => console.log(`Beheshti Academy v2 listening on ${PORT}`));
