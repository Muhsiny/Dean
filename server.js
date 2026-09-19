import http from 'node:http';
import {readFileSync} from 'node:fs';

const PORT=Number(process.env.PORT||10000);
const UPSTREAM=process.env.UPSTREAM_ORIGIN||'https://beheshti-university-prod-v410.onrender.com';
const PUBLIC_HOST=process.env.PUBLIC_HOST||'beheshti-university.un-beha.org';
const SITE_CSS=readFileSync(new URL('./edge-assets/site-wide-v4320.css',import.meta.url),'utf8');
const VERSION='4.32.0-secure-gateway';
const hop=new Set(['connection','keep-alive','proxy-authenticate','proxy-authorization','te','trailers','transfer-encoding','upgrade','content-length','content-encoding']);
const buckets=new Map();

function clientIp(req){
  return String(req.headers['cf-connecting-ip']||req.headers['x-forwarded-for']||req.socket.remoteAddress||'unknown').split(',')[0].trim();
}
function blocked(key,limit,windowMs){
  const now=Date.now(),old=buckets.get(key);
  if(!old||old.until<=now){buckets.set(key,{n:1,until:now+windowMs});return false}
  old.n++; if(buckets.size>5000)for(const[k,v]of buckets)if(v.until<=now)buckets.delete(k);
  return old.n>limit;
}
function decodeSession(token){
  try{
    const p=String(token||'').split('.')[1];if(!p)return null;
    return JSON.parse(Buffer.from(p,'base64url').toString('utf8'));
  }catch{return null}
}
function maxSessionAgeSeconds(payload){
  return ['admin','super_admin','reviewer','instructor'].includes(String(payload?.role||''))?8*3600:24*3600;
}
function secureCookie(v){
  let c=String(v||'');
  if(!/^beheshti_session=/i.test(c))return c;
  const token=(c.match(/^beheshti_session=([^;]+)/i)||[])[1]||'';
  const maxAge=maxSessionAgeSeconds(decodeSession(token));
  c=/;\s*SameSite=/i.test(c)?c.replace(/;\s*SameSite=[^;]*/i,'; SameSite=Strict'):`${c}; SameSite=Strict`;
  c=/;\s*Max-Age=/i.test(c)?c.replace(/;\s*Max-Age=[^;]*/i,`; Max-Age=${maxAge}`):`${c}; Max-Age=${maxAge}`;
  c=c.replace(/;\s*Expires=[^;]*/ig,'');
  if(!/;\s*Secure\b/i.test(c))c+='; Secure';
  if(!/;\s*HttpOnly\b/i.test(c))c+='; HttpOnly';
  if(!/;\s*Priority=/i.test(c))c+='; Priority=High';
  return c;
}
function boundedSessionCookie(raw){
  if(!raw)return raw;
  const parts=String(raw).split(';').map(x=>x.trim()).filter(Boolean);
  const kept=[];
  for(const part of parts){
    if(!/^beheshti_session=/i.test(part)){kept.push(part);continue}
    const token=part.slice(part.indexOf('=')+1),p=decodeSession(token);
    if(!p?.iat||Math.floor(Date.now()/1000)-Number(p.iat)<=maxSessionAgeSeconds(p))kept.push(part);
  }
  return kept.join('; ');
}
function hardenCsp(value){
  const map=new Map();
  for(const raw of String(value||'').split(';')){
    const p=raw.trim();if(!p)continue;
    const [name,...rest]=p.split(/\s+/);map.set(name.toLowerCase(),`${name.toLowerCase()}${rest.length?' '+rest.join(' '):''}`);
  }
  const force={
    'default-src':"default-src 'self'",'base-uri':"base-uri 'self'",'form-action':"form-action 'self'",
    'frame-src':"frame-src 'none'",'frame-ancestors':"frame-ancestors 'none'",'object-src':"object-src 'none'",
    'media-src':"media-src 'self'",'manifest-src':"manifest-src 'self'",'worker-src':"worker-src 'self'",
    'connect-src':"connect-src 'self'",'script-src-attr':"script-src-attr 'none'"
  };
  for(const[k,v]of Object.entries(force))map.set(k,v);
  map.set('img-src',"img-src 'self' data:");
  if(!map.has('font-src'))map.set('font-src',"font-src 'self' data:");
  if(!map.has('script-src'))map.set('script-src',"script-src 'self'");
  map.set('upgrade-insecure-requests','upgrade-insecure-requests');
  return [...map.values()].join('; ');
}
function copyHeaders(from,res,path){
  for(const[k,v]of from.headers){
    const key=k.toLowerCase();
    if(hop.has(key)||key==='set-cookie'||key==='x-powered-by')continue;
    if(key==='location'){res.setHeader(k,String(v).replace(UPSTREAM,'https://'+PUBLIC_HOST));continue}
    res.setHeader(k,v);
  }
  const cookies=from.headers.getSetCookie?.()||[];
  if(cookies.length)res.setHeader('set-cookie',cookies.map(secureCookie));
  res.setHeader('x-biu-gateway',VERSION);
  res.setHeader('strict-transport-security','max-age=63072000; includeSubDomains');
  res.setHeader('x-content-type-options','nosniff');
  res.setHeader('x-frame-options','DENY');
  res.setHeader('referrer-policy','no-referrer');
  res.setHeader('cross-origin-opener-policy','same-origin');
  res.setHeader('cross-origin-resource-policy','same-origin');
  res.setHeader('origin-agent-cluster','?1');
  res.setHeader('x-permitted-cross-domain-policies','none');
  res.setHeader('permissions-policy','camera=(), geolocation=(), payment=(), usb=(), microphone=(self), fullscreen=(self)');
  const csp=res.getHeader('content-security-policy');
  if(csp)res.setHeader('content-security-policy',hardenCsp(csp));
  if(/^\/api\/(?:auth|account|admin|studio|internal|admissions|registrar|student|dashboard|me|dr)(?:\/|$)/.test(path))
    res.setHeader('cache-control','private, no-store');
  if(/^(?:\/api\/submission-attachments\/[^/]+\/file|\/api\/resources\/[^/]+\/file|\/api\/admissions\/documents\/[^/]+\/file)$/.test(path)){
    res.setHeader('content-disposition','attachment; filename="secure-download"');
    res.setHeader('x-download-options','noopen');
    res.setHeader('content-security-policy',"sandbox; default-src 'none'");
  }
}
const server=http.createServer(async(req,res)=>{
  try{
    const method=String(req.method||'GET').toUpperCase();
    const edgeUrl=new URL(req.url||'/','https://edge.invalid');
    const path=edgeUrl.pathname;
    if(/^(TRACE|TRACK|CONNECT)$/.test(method)){res.writeHead(405,{allow:'GET, HEAD, POST, PUT, PATCH, DELETE, OPTIONS'});return res.end()}
    if(String(req.url||'').length>8192){res.statusCode=414;return res.end()}
    const declared=Number(req.headers['content-length']||0);
    if(Number.isFinite(declared)&&declared>22*1024*1024){res.statusCode=413;return res.end()}
    const ip=clientIp(req);
    if(method==='POST'&&/^\/api\/(?:auth\/(?:login|recover|password-reset\/)|setup\/)/.test(path)&&blocked('auth:'+ip,30,15*60*1000)){
      res.writeHead(429,{'content-type':'application/json; charset=utf-8','retry-after':'900'});return res.end('{"error":"too_many_requests"}')
    }
    if(method==='POST'&&path==='/api/support/tickets'&&blocked('support:'+ip,6,10*60*1000)){
      res.writeHead(429,{'content-type':'application/json; charset=utf-8','retry-after':'600'});return res.end('{"error":"too_many_requests"}')
    }
    const target=new URL(req.url||'/',UPSTREAM);
    const headers=new Headers();
    for(const[k,v]of Object.entries(req.headers)){
      if(!v||hop.has(k.toLowerCase())||k.toLowerCase()==='host')continue;
      headers.set(k,Array.isArray(v)?v.join(', '):v);
    }
    headers.set('host',new URL(UPSTREAM).host);
    const boundedCookie=boundedSessionCookie(req.headers.cookie);
    if(boundedCookie)headers.set('cookie',boundedCookie);else headers.delete('cookie');
    headers.set('x-forwarded-host',PUBLIC_HOST);
    headers.set('x-forwarded-proto','https');
    let body;
    if(!['GET','HEAD'].includes(method)){
      const chunks=[];let size=0;
      for await(const c of req){size+=c.length;if(size>22*1024*1024){res.statusCode=413;return res.end()}chunks.push(c)}
      body=Buffer.concat(chunks);
    }
    const upstream=await fetch(target,{method,headers,body,redirect:'manual',signal:AbortSignal.timeout(30000)});
    copyHeaders(upstream,res,path);
    res.statusCode=upstream.status;
    if(method==='HEAD'){return res.end()}
    let buf=Buffer.from(await upstream.arrayBuffer());
    const ct=(upstream.headers.get('content-type')||'').toLowerCase();
    if(path==='/beheshti.css'){
      buf=Buffer.concat([buf,Buffer.from('\n/* BIU_GATEWAY_4320 */\n'+SITE_CSS+'\n')]);
      res.setHeader('content-type','text/css; charset=utf-8');
      res.setHeader('cache-control','public,max-age=3600,stale-while-revalidate=86400');
    }else if(ct.includes('text/html')){
      const publicOrigin='https://'+PUBLIC_HOST;
      buf=Buffer.from(buf.toString('utf8').split(UPSTREAM).join(publicOrigin));
      res.setHeader('content-type','text/html; charset=utf-8');
    }
    res.setHeader('content-length',String(buf.length));
    res.end(buf);
  }catch(error){
    res.statusCode=502;res.setHeader('content-type','application/json; charset=utf-8');
    res.end(JSON.stringify({error:'upstream_proxy_error'}));
  }
});
server.listen(PORT,'0.0.0.0',()=>console.log('BIU secure gateway',VERSION,'listening',PORT));
