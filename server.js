import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
import pg from 'pg';
const Pool=pg.Pool;
const root=path.dirname(fileURLToPath(import.meta.url));
const staticRoot=path.join(root,'dist');
const pool=new Pool({connectionString:process.env.DATABASE_URL,ssl:{rejectUnauthorized:false}});
const PORT=process.env.PORT||10000;
const defaults={siteTitle:'خبرگزاری سایه',brandName:'SAYEH NEWS',tagline:'پنهان از نگاه‌ها، مسلط بر رویدادها',newsroomLabel:'تحریریه',homeTitle:'پنهان از نگاه‌ها',newsTitle:'آخرین خبرها',analysisTitle:'تحلیل رویدادها',archiveTitle:'آرشیف خبرها',englishTitle:'English News',englishDeskLabel:'English Desk',navHome:'خانه',navNews:'خبرها',navAnalysis:'تحلیل',navArchive:'آرشیف',navEnglish:'English',breakingLabel:'خبر فوری',footerText:'تمام حقوق این خبرگزاری برای سایه محفوظ می‌باشد.',footerYear:'۲۰۲۳',searchPlaceholder:'جست‌وجوی خبر و تحلیل...',defaultLanguage:'دری',breakingBar:true,watermark:true,versioning:true,autoShare:true,viewCountThreshold:500,archiveDays:20,visualRequired:true,sourceMonitorEnabled:true,forceArianaSource:true,primaryIntervalMinutes:5,primaryItemsPerCycle:3,secondarySourcesPerCycle:1,secondaryHighItemsPerCycle:3,secondaryNormalItemsPerCycle:2,autoWordLimit:1000,openverseFallback:true,bbcReplaceSourceImage:true,logoUrl:'/resources/sayeh-news-logo.png'};
const send=(res,obj,status=200)=>{res.writeHead(status,{'content-type':'application/json; charset=utf-8','cache-control':'no-store'});res.end(JSON.stringify(obj))};
const readBody=req=>new Promise((ok,fail)=>{let d='';req.on('data',c=>{d+=c;if(d.length>5000000)req.destroy()});req.on('end',()=>{try{ok(d?JSON.parse(d):{})}catch(e){fail(e)}});req.on('error',fail)});
const rand=()=>crypto.randomBytes(32).toString('base64url');
const visitor=id=>({userId:id,email:'',name:'مهمان'});
async function init(){
  await pool.query('create table if not exists sayeh_settings(id int primary key,data jsonb not null)');
  await pool.query('create table if not exists sayeh_articles(id text primary key,data jsonb not null)');
  await pool.query('create table if not exists sayeh_sessions(token text primary key,visitor jsonb not null,expires_at bigint not null)');
  await pool.query('create table if not exists sayeh_events(id bigserial primary key,visitor_id text,event text,article_id text,created_at bigint not null)');
  await pool.query('create table if not exists sayeh_records(entity text not null,id text not null,data jsonb not null,primary key(entity,id))');
  await pool.query('create table if not exists sayeh_migrations(id bigserial primary key,source text not null,imported_at bigint not null,summary jsonb not null)');
  await pool.query('insert into sayeh_settings(id,data) values(1,$1::jsonb) on conflict(id) do nothing',[JSON.stringify(defaults)]);
}
async function settings(){const r=await pool.query('select data from sayeh_settings where id=1');return r.rows[0]?.data||defaults}
async function allArticles(){const r=await pool.query("select id,data from sayeh_articles order by coalesce((data->>'publishedAt')::bigint,(data->>'createdAt')::bigint,(data->>'updatedAt')::bigint,0) desc limit 500");return r.rows.map(x=>({...x.data,id:x.id}))}
async function validSession(t){if(!t)return null;const r=await pool.query('select visitor,expires_at from sayeh_sessions where token=$1',[t]);const x=r.rows[0];return x&&Number(x.expires_at)>Date.now()?x.visitor:null}
const storagePublicBase='https://br-empty-star-b2m88i8y.storage.c-6.eu-central-1.aws.neon.tech/sayeh-public';
function mediaPublicUrl(pathValue){const p=String(pathValue||'').replace(/^\\/+/, '');return p?storagePublicBase+'/'+p.split('/').map(encodeURIComponent).join('/'):''}
function hydrateItem(x){return x&&x.mediaPath?{...x,mediaUrl:mediaPublicUrl(x.mediaPath)}:x}\nasync function pub(kind){let a=(await allArticles()).filter(x=>x.status==='published'||!x.status);if(kind==='analysis')a=a.filter(x=>x.section==='analysis'||x.category==='تحلیل');if(kind==='english')a=a.filter(x=>x.language==='English'||x.section==='english');if(kind==='archive')a=a.filter(x=>x.archived);return a.map(hydrateItem)}
async function logEvent(v,e,a){await pool.query('insert into sayeh_events(visitor_id,event,article_id,created_at) values($1,$2,$3,$4)',[v?.userId||'',e,a||'',Date.now()]).catch(()=>{})}
function mime(f){if(f.endsWith('.js'))return'application/javascript; charset=utf-8';if(f.endsWith('.css'))return'text/css; charset=utf-8';if(f.endsWith('.png'))return'image/png';if(f.endsWith('.webmanifest'))return'application/manifest+json';return'text/html; charset=utf-8'}
function staticFile(res,rel){const f=path.join(staticRoot,rel);if(!f.startsWith(staticRoot)||!fs.existsSync(f)||fs.statSync(f).isDirectory())return false;res.writeHead(200,{'content-type':mime(f),'cache-control':rel.includes('assets/')?'public, max-age=31536000, immutable':'public, max-age=300'});fs.createReadStream(f).pipe(res);return true}
const app=http.createServer(async(req,res)=>{try{const u=new URL(req.url,'http://x'),p=u.pathname;
  if(p==='/health')return send(res,{ok:true,service:'sayeh-independent',database:'neon'});
  if(p==='/migration/status'&&req.method==='GET'){
    const [a,e,m]=await Promise.all([pool.query('select count(*)::int as n from sayeh_articles'),pool.query('select count(*)::int as n from sayeh_events'),pool.query('select source,imported_at,summary from sayeh_migrations order by id desc limit 1')]);
    return send(res,{ready:true,articles:a.rows[0].n,events:e.rows[0].n,lastMigration:m.rows[0]||null});
  }
  if(p==='/migration/import'&&req.method==='POST'){
    if(!process.env.MIGRATION_TOKEN||req.headers['x-migration-token']!==process.env.MIGRATION_TOKEN)return send(res,{error:'forbidden'},403);
    const b=await readBody(req),articles=Array.isArray(b.articles)?b.articles:[],entities=b.entities&&typeof b.entities==='object'?b.entities:{},incomingSettings=b.settings&&typeof b.settings==='object'?b.settings:null;
    const client=await pool.connect();try{await client.query('begin');
      if(incomingSettings)await client.query('insert into sayeh_settings(id,data) values(1,$1::jsonb) on conflict(id) do update set data=excluded.data',[JSON.stringify({...defaults,...incomingSettings})]);
      let articleCount=0;for(const item of articles){if(!item||typeof item!=='object')continue;const id=String(item.id||item._id||crypto.randomUUID());const data={...item};delete data.id;delete data._id;await client.query('insert into sayeh_articles(id,data) values($1,$2::jsonb) on conflict(id) do update set data=excluded.data',[id,JSON.stringify(data)]);articleCount++;}
      let entityCount=0;for(const [entity,rows] of Object.entries(entities)){if(!Array.isArray(rows))continue;for(const item of rows){if(!item||typeof item!=='object')continue;const id=String(item.id||item._id||crypto.randomUUID());const data={...item};delete data.id;delete data._id;await client.query('insert into sayeh_records(entity,id,data) values($1,$2,$3::jsonb) on conflict(entity,id) do update set data=excluded.data',[entity,id,JSON.stringify(data)]);entityCount++;}}
      const summary={articles:articleCount,entities:entityCount,settings:Boolean(incomingSettings)};await client.query('insert into sayeh_migrations(source,imported_at,summary) values($1,$2,$3::jsonb)',[String(b.source||'appdeploy'),Date.now(),JSON.stringify(summary)]);await client.query('commit');return send(res,{ok:true,...summary});
    }catch(e){await client.query('rollback');throw e}finally{client.release()}
  }
  if(p==='/migration/pull-public'&&req.method==='POST'){
    if(!process.env.MIGRATION_TOKEN||req.headers['x-migration-token']!==process.env.MIGRATION_TOKEN)return send(res,{error:'forbidden'},403);
    const base='https://api-v2.appdeploy.ai/app/605a5f030a6d2cec77';
    const post=async(path,payload)=>{const rr=await fetch(base+path,{method:'POST',headers:{'content-type':'application/json'},body:JSON.stringify(payload)});const txt=await rr.text();if(!rr.ok)throw new Error('old_app_'+rr.status+':'+txt.slice(0,180));return JSON.parse(txt)};
    const visitorId=crypto.randomUUID();
    const session=await post('/api/visitor-auth/anonymous',{visitorId});
    const sessionToken=session.sessionToken;if(!sessionToken)throw new Error('old_app_missing_session');
    const boot=await post('/api/public/bootstrap',{sessionToken,countEntry:false});
    const map=new Map();for(const item of Array.isArray(boot.items)?boot.items:[])if(item?.id)map.set(String(item.id),item);
    for(const route of ['/api/public/news-page','/api/public/analysis','/api/public/archive','/api/public/english']){
      for(let offset=0;offset<2400;offset+=24){const page=await post(route,{sessionToken,offset,limit:24});const items=Array.isArray(page.items)?page.items:[];for(const item of items)if(item?.id)map.set(String(item.id),item);if(!page.hasMore||items.length<24)break;}
    }
    const client=await pool.connect();try{await client.query('begin');if(boot.settings)await client.query('insert into sayeh_settings(id,data) values(1,$1::jsonb) on conflict(id) do update set data=excluded.data',[JSON.stringify({...defaults,...boot.settings})]);let n=0;for(const [id,item] of map){const data={...item};delete data.id;await client.query('insert into sayeh_articles(id,data) values($1,$2::jsonb) on conflict(id) do update set data=excluded.data',[id,JSON.stringify(data)]);n++;}const summary={articles:n,settings:Boolean(boot.settings),mode:'public-pull'};await client.query('insert into sayeh_migrations(source,imported_at,summary) values($1,$2,$3::jsonb)',['appdeploy-public',Date.now(),JSON.stringify(summary)]);await client.query('commit');return send(res,{ok:true,...summary});}catch(e){await client.query('rollback');throw e}finally{client.release()}
  }
  if(p==='/api/visitor-auth/status'&&req.method==='GET')return send(res,{configured:false});
  if(p==='/api/visitor-auth/anonymous'&&req.method==='POST'){const b=await readBody(req),id=String(b.visitorId||crypto.randomUUID()),v=visitor(id),t=rand(),exp=Date.now()+2592000000;await pool.query('insert into sayeh_sessions(token,visitor,expires_at) values($1,$2::jsonb,$3)',[t,JSON.stringify(v),exp]);return send(res,{ok:true,sessionToken:t,visitor:v,expiresAt:exp})}
  if(p==='/api/visitor-auth/session'&&req.method==='POST'){const b=await readBody(req),v=await validSession(b.sessionToken);return v?send(res,{ok:true,visitor:v}):send(res,{ok:false},401)}
  if(p==='/api/visitor-auth/signout'&&req.method==='POST'){const b=await readBody(req);if(b.sessionToken)await pool.query('delete from sayeh_sessions where token=$1',[b.sessionToken]);return send(res,{ok:true})}
  if(p==='/api/public/bootstrap'&&req.method==='POST'){const b=await readBody(req),v=await validSession(b.sessionToken);if(!v)return send(res,{error:'نشست ورود معتبر نیست.'},401);const s=await settings(),items=await pub();if(b.countEntry)await logEvent(v,'site_open');return send(res,{visitor:v,items,settings:{...s,logoUrl:'/resources/sayeh-news-logo.png'},entryRecorded:true})}
  if(p==='/api/public/news'&&req.method==='POST'){const b=await readBody(req),v=await validSession(b.sessionToken);return v?send(res,{items:await pub()}):send(res,{error:'نشست ورود معتبر نیست.'},401)}
  if(p==='/api/public/search'&&req.method==='POST'){const b=await readBody(req),v=await validSession(b.sessionToken);if(!v)return send(res,{error:'نشست ورود معتبر نیست.'},401);const q=String(b.q||'').toLowerCase(),items=(await pub()).filter(x=>[x.title,x.summary,x.body].join(' ').toLowerCase().includes(q)).slice(0,Number(b.limit||60));return send(res,{items})}
  if(['/api/public/news-page','/api/public/analysis','/api/public/english','/api/public/archive'].includes(p)&&req.method==='POST'){const b=await readBody(req),v=await validSession(b.sessionToken);if(!v)return send(res,{error:'نشست ورود معتبر نیست.'},401);const kind=p.endsWith('analysis')?'analysis':p.endsWith('english')?'english':p.endsWith('archive')?'archive':undefined,arr=await pub(kind),o=Number(b.offset||0),l=Number(b.limit||24);return send(res,{items:arr.slice(o,o+l),offset:o,limit:l,hasMore:o+l<arr.length})}
  if(p==='/api/public/settings'&&req.method==='POST'){const b=await readBody(req),v=await validSession(b.sessionToken);return v?send(res,await settings()):send(res,{error:'نشست ورود معتبر نیست.'},401)}
  const m=p.match(/^\/api\/public\/news\/([^/]+)(\/view)?$/);if(m&&req.method==='POST'){const b=await readBody(req),v=await validSession(b.sessionToken);if(!v)return send(res,{error:'نشست ورود معتبر نیست.'},401);const id=decodeURIComponent(m[1]),r=await pool.query('select data from sayeh_articles where id=$1',[id]);if(!r.rows[0])return send(res,{error:'مطلب یافت نشد.'},404);const a=hydrateItem({...r.rows[0].data,id});if(m[2]){a.viewCount=Number(a.viewCount||0)+1;await pool.query('update sayeh_articles set data=$2::jsonb where id=$1',[id,JSON.stringify(a)]);await logEvent(v,'article_view',id)}return send(res,a)}
  if(['/api/public/visitor/click','/api/public/visitor/enter','/api/public/visitor/event'].includes(p)&&req.method==='POST'){const b=await readBody(req),v=(await validSession(b.sessionToken))||visitor(String(b.visitorId||''));await logEvent(v,String(b.event||p.split('/').pop()));return send(res,{ok:true})}
  if(p==='/api/public/news-subscription'&&req.method==='POST')return send(res,{ok:true});
  if(p==='/rss.xml'||p==='/facebook-rss.xml'){const arr=await pub(),esc=s=>String(s||'').replace(/[<>&'"]/g,c=>({'<':'&lt;','>':'&gt;','&':'&amp;',"'":'&apos;','"':'&quot;'}[c])),host='https://'+req.headers.host;const items=arr.slice(0,50).map(a=>'<item><title>'+esc(a.title)+'</title><link>'+host+'/#article='+encodeURIComponent(a.id)+'</link><guid>'+esc(a.id)+'</guid><description>'+esc(a.summary)+'</description></item>').join('');res.writeHead(200,{'content-type':'application/rss+xml; charset=utf-8'});return res.end('<?xml version="1.0" encoding="UTF-8"?><rss version="2.0"><channel><title>SAYEH NEWS</title><link>'+host+'</link>'+items+'</channel></rss>')}
  if(p.startsWith('/api/'))return send(res,{error:'migration_stage_endpoint_not_available'},503);
  if(p==='/'||p==='/index.html')return staticFile(res,'index.html');
  if(staticFile(res,p.slice(1)))return;
  return staticFile(res,'index.html');
}catch(e){console.error(e);return send(res,{error:'server_error'},500)}});
init().then(()=>app.listen(PORT,'0.0.0.0',()=>console.log('SAYEH independent listening '+PORT))).catch(e=>{console.error(e);process.exit(1)});