import http from 'node:http';
import { readFileSync } from 'node:fs';

const PORT = Number(process.env.PORT || 10000);
const UPSTREAM = process.env.UPSTREAM_ORIGIN || 'https://beheshti-university-prod-v410.onrender.com';
const HOTFIX = readFileSync(new URL('./edge-assets/home-4293.css', import.meta.url),'utf8');
const HERO_JS = readFileSync(new URL('./edge-assets/home-hero-4293.js', import.meta.url),'utf8');
const CERT_JS = readFileSync(new URL('./edge-assets/home-certificate-4293.js', import.meta.url),'utf8');
const BISMILLAH_URL = 'https://cdn.creativeclaw.co/u/7d931a3c/images/282df546-d39b-43bc-85b3-1e1fc2ae23dc.webp';
const OFFICE_URL = 'https://images.unsplash.com/photo-1770053506723-c96a4379873b?auto=format&fit=crop&w=1800&q=86';

const hop = new Set(['connection','keep-alive','proxy-authenticate','proxy-authorization','te','trailers','transfer-encoding','upgrade','content-length','content-encoding']);
const assetCache = new Map();

function publicHost(req){return String(req.headers['x-forwarded-host']||req.headers.host||'').split(',')[0].trim()}
function publicProto(req){return String(req.headers['x-forwarded-proto']||'https').split(',')[0].trim()}

function copyHeaders(from,res,req){
  for(const [k,v] of from.headers){
    const key=k.toLowerCase();
    if(hop.has(key)) continue;
    if(key==='location'){
      res.setHeader(k,String(v).replace(UPSTREAM,publicProto(req)+'://'+publicHost(req)));
      continue;
    }
    if(key==='set-cookie') continue;
    res.setHeader(k,v);
  }
  const setCookies=from.headers.getSetCookie?.()||[];
  if(setCookies.length) res.setHeader('set-cookie',setCookies);
  res.setHeader('x-beheshti-hotfix','4.29.3');
}

async function serveAsset(res,key,url,type){
  try{
    let buf=assetCache.get(key);
    if(!buf){
      const r=await fetch(url,{headers:{'user-agent':'Mozilla/5.0'}});
      if(!r.ok) throw new Error('asset_fetch');
      buf=Buffer.from(await r.arrayBuffer());
      assetCache.set(key,buf);
    }
    res.statusCode=200;
    res.setHeader('content-type',type);
    res.setHeader('cache-control','public,max-age=604800,immutable');
    res.setHeader('content-length',String(buf.length));
    res.end(buf);
  }catch{
    res.statusCode=404;
    res.end();
  }
}

const server=http.createServer(async(req,res)=>{
  try{
    const edgeUrl=new URL(req.url||'/','https://edge.invalid');
    if(edgeUrl.pathname==='/edge-assets/bismillah.webp'){
      await serveAsset(res,'bismillah',BISMILLAH_URL,'image/webp');
      return;
    }
    if(edgeUrl.pathname==='/edge-assets/hero-office.jpg'){
      await serveAsset(res,'office',OFFICE_URL,'image/jpeg');
      return;
    }

    const target=new URL(req.url||'/',UPSTREAM);
    const headers=new Headers();
    for(const [k,v] of Object.entries(req.headers)){
      if(!v||hop.has(k.toLowerCase())||k.toLowerCase()==='host') continue;
      headers.set(k,Array.isArray(v)?v.join(', '):v);
    }
    headers.set('host',new URL(UPSTREAM).host);
    headers.set('x-forwarded-host',publicHost(req));
    headers.set('x-forwarded-proto',publicProto(req));

    let body;
    if(!['GET','HEAD'].includes(req.method||'GET')){
      const chunks=[];
      for await(const c of req) chunks.push(c);
      body=Buffer.concat(chunks);
    }

    const upstream=await fetch(target,{method:req.method,headers,body,redirect:'manual'});
    copyHeaders(upstream,res,req);
    res.statusCode=upstream.status;
    if(req.method==='HEAD'){res.end();return}

    let buf=Buffer.from(await upstream.arrayBuffer());
    const ct=(upstream.headers.get('content-type')||'').toLowerCase();

    if(target.pathname.endsWith('/beheshti.css')||target.pathname==='/beheshti.css'){
      buf=Buffer.concat([buf,Buffer.from('\n'+HOTFIX+'\n')]);
      res.setHeader('content-type','text/css; charset=utf-8');
    }else if(target.pathname.endsWith('/app.js')||target.pathname==='/app.js'){
      buf=Buffer.concat([buf,Buffer.from('\n'+HERO_JS+'\n'+CERT_JS+'\n')]);
      res.setHeader('content-type','text/javascript; charset=utf-8');
    }else if(ct.includes('text/html')){
      const html=buf.toString('utf8').replace(/4\.29\.2/g,'4.29.3');
      buf=Buffer.from(html);
      res.setHeader('content-type','text/html; charset=utf-8');
    }

    res.setHeader('content-length',String(buf.length));
    res.end(buf);
  }catch{
    res.statusCode=502;
    res.setHeader('content-type','application/json; charset=utf-8');
    res.end(JSON.stringify({error:'upstream_proxy_error'}));
  }
});

server.listen(PORT,'0.0.0.0',()=>console.log('beheshti hotfix proxy 4.29.3 listening',PORT));
