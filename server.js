import http from 'node:http';

const PORT = Number(process.env.PORT || 10000);
const UPSTREAM = process.env.UPSTREAM_ORIGIN || 'https://beheshti-university-prod-v410.onrender.com';

const HOTFIX = `
/* Beheshti University emergency visual consistency hotfix v4.29.3 */
.library-grid{
  padding:8px;
  border:1px solid rgba(255,255,255,.70);
  border-radius:20px;
  background:rgba(255,255,255,.48);
  box-shadow:var(--bu-shadow-soft);
  backdrop-filter:blur(20px) saturate(128%);
  -webkit-backdrop-filter:blur(20px) saturate(128%);
  overflow:hidden;
}
.course-card .course-link,
.program-academic-list button.text-link,
.catalog-filters button,
.library-grid>article a,
main input:not([type="checkbox"]):not([type="radio"]):not([type="hidden"]){
  min-height:44px;
}
.course-card .course-link,
.program-academic-list button.text-link,
.library-grid>article a{
  display:inline-flex;
  align-items:center;
}
.course-card .course-link{justify-content:space-between}
@media(max-width:720px){
  .course-card .course-link,
  .program-academic-list button.text-link{
    min-height:44px;
    padding-block:10px;
  }
}
`;

const hop = new Set([
  'connection','keep-alive','proxy-authenticate','proxy-authorization',
  'te','trailers','transfer-encoding','upgrade','content-length','content-encoding'
]);

function copyHeaders(from, res, req){
  for (const [k,v] of from.headers){
    const key = k.toLowerCase();
    if (hop.has(key)) continue;
    if (key === 'location'){
      const host = req.headers.host;
      const proto = req.headers['x-forwarded-proto'] || 'https';
      res.setHeader(k, String(v).replace(UPSTREAM, `${proto}://${host}`));
      continue;
    }
    if (key === 'set-cookie') continue;
    res.setHeader(k,v);
  }
  const setCookies = from.headers.getSetCookie?.() || [];
  if (setCookies.length) res.setHeader('set-cookie', setCookies);
  res.setHeader('x-beheshti-hotfix','4.29.3');
}

const server = http.createServer(async (req,res)=>{
  try{
    const target = new URL(req.url || '/', UPSTREAM);
    const headers = new Headers();
    for (const [k,v] of Object.entries(req.headers)){
      if (!v || hop.has(k.toLowerCase()) || k.toLowerCase()==='host') continue;
      headers.set(k, Array.isArray(v)?v.join(', '):v);
    }
    headers.set('host', new URL(UPSTREAM).host);
    headers.set('x-forwarded-host', req.headers.host || '');
    headers.set('x-forwarded-proto', req.headers['x-forwarded-proto'] || 'https');

    let body;
    if (!['GET','HEAD'].includes(req.method || 'GET')){
      const chunks=[]; for await (const c of req) chunks.push(c); body=Buffer.concat(chunks);
    }

    const upstream = await fetch(target,{
      method:req.method,
      headers,
      body,
      redirect:'manual'
    });

    copyHeaders(upstream,res,req);
    res.statusCode=upstream.status;

    if (req.method === 'HEAD'){res.end();return;}

    let buf=Buffer.from(await upstream.arrayBuffer());
    const ct=(upstream.headers.get('content-type')||'').toLowerCase();

    if (target.pathname.endsWith('/beheshti.css') || target.pathname === '/beheshti.css'){
      buf = Buffer.concat([buf, Buffer.from('\n'+HOTFIX+'\n')]);
      res.setHeader('content-type','text/css; charset=utf-8');
    } else if (ct.includes('text/html')){
      let html=buf.toString('utf8');
      html=html.replace(/4\.29\.2/g,'4.29.3');
      buf=Buffer.from(html);
      res.setHeader('content-type','text/html; charset=utf-8');
    }

    res.setHeader('content-length',String(buf.length));
    res.end(buf);
  }catch(err){
    res.statusCode=502;
    res.setHeader('content-type','application/json; charset=utf-8');
    res.end(JSON.stringify({error:'upstream_proxy_error'}));
  }
});
server.listen(PORT,'0.0.0.0',()=>console.log('beheshti hotfix proxy listening',PORT));
