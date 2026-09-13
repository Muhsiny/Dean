const APP = document.getElementById('app');
const CURRICULUM = window.BEHESHTI_CURRICULUM || [];
const SESSION_MARKER = 'beheshti_academy_session_v3';
const DAYS = ['Teach','Listening','Vocabulary','Grammar','Reading','Speaking + Writing','Mastery'];
let marker = localStorage.getItem(SESSION_MARKER) || '';
let user = null;
let state = null;
let view = 'dashboard';
let lessonRef = null;
let authMode = 'login';
let recognition = null;

const PLACEMENT = [
  ['I ___ from Afghanistan.',['am','is','are'],0],
  ['She ___ two brothers.',['have','has','having'],1],
  ['He ___ to work every day.',['go','goes','going'],1],
  ['Could you tell me where the bank ___?',['is','be','are'],0],
  ['There ___ two clinics near my home.',['is','are','has'],1],
  ['I need ___ information.',['a few','a little','many'],1],
  ['Did you ___ the report?',['finished','finish','finishing'],1],
  ['I am ___ my supervisor tomorrow.',['meet','meeting','met'],1],
  ['I was walking ___ it started to rain.',['when','because','although'],0],
  ['I see your point, ___ I disagree.',['so','but','because'],1],
  ['I have had this problem ___ Monday.',['for','since','from'],1],
  ['I enjoy ___ English every day.',['study','studying','to studied'],1],
  ['The report stated ___ demand had increased.',['what','that','than'],1],
  ['If we had more time, we ___ test both.',['can','could','will'],1],
  ['The evidence ___ that the change may help.',['suggests','proves absolutely','saying'],0],
  ['Based on the evidence, I ___ extending the pilot.',['recommend','am recommend','recommended to'],0]
];

function freshState(){
  return {
    version:3,
    profile:{planComplete:false,setupComplete:false,goal:'General English',dailyMinutes:45,accent:'American',currentWeek:1,currentDay:1,startedAt:Date.now()},
    placement:{completed:false,currentIndex:0,answers:[],score:0,provisionalLevel:null,startingWeek:1},
    progress:{completed:[],scores:{},streak:0,lastStudyDate:null,studySeconds:0,weeklyMastery:{}},
    vocab:{},mistakes:{},artifacts:[],settings:{dariSupport:true,reducedMotion:false}
  };
}
function normalizeState(raw){
  const f=freshState(),s=raw&&typeof raw==='object'?raw:{};
  f.profile={...f.profile,...(s.profile||{})};
  f.profile.currentWeek=Math.max(1,Math.min(24,Number(f.profile.currentWeek)||1));
  f.profile.currentDay=Math.max(1,Math.min(7,Number(f.profile.currentDay)||1));
  f.profile.dailyMinutes=Math.max(15,Math.min(180,Number(f.profile.dailyMinutes)||45));
  f.profile.planComplete=Boolean(f.profile.planComplete||f.profile.setupComplete);
  f.placement={...f.placement,...(s.placement||{})};
  f.placement.currentIndex=Math.max(0,Math.min(PLACEMENT.length,Number(f.placement.currentIndex)||0));
  f.placement.answers=Array.isArray(f.placement.answers)?f.placement.answers.slice(0,PLACEMENT.length):[];
  f.placement.score=Math.max(0,Math.min(PLACEMENT.length,Number(f.placement.score)||0));
  f.placement.startingWeek=Math.max(1,Math.min(24,Number(f.placement.startingWeek)||1));
  const p=s.progress||{};
  f.progress={...f.progress,...p};
  f.progress.completed=Array.isArray(p.completed)?[...new Set(p.completed.filter(x=>/^w(?:[1-9]|1\d|2[0-4])d[1-7]$/.test(String(x))))]:[];
  f.progress.scores=p.scores&&typeof p.scores==='object'?p.scores:{};
  f.progress.weeklyMastery=p.weeklyMastery&&typeof p.weeklyMastery==='object'?p.weeklyMastery:{};
  f.vocab=s.vocab&&typeof s.vocab==='object'?s.vocab:{};
  f.mistakes=s.mistakes&&typeof s.mistakes==='object'?s.mistakes:{};
  f.artifacts=Array.isArray(s.artifacts)?s.artifacts.slice(-500):[];
  f.settings={...f.settings,...(s.settings||{})};
  return f;
}
function esc(v){return String(v??'').replace(/[&<>'"]/g,m=>({'&':'&amp;','<':'&lt;','>':'&gt;',"'":'&#39;','"':'&quot;'}[m]))}
function lessonId(w,d){return `w${w}d${d}`}
function scoreKey(w,d){return lessonId(w,d)}
function today(){return new Date().toISOString().slice(0,10)}
function toast(message,type='normal'){
  const n=document.createElement('div');n.className=`toast ${type==='error'?'toast-error':''}`;n.textContent=message;document.body.appendChild(n);setTimeout(()=>n.remove(),3200)
}
async function api(url,opt={}){
  const headers={'Content-Type':'application/json',...(opt.headers||{})};
  const r=await fetch(url,{...opt,headers});
  let body={};try{body=await r.json()}catch{}
  if(!r.ok)throw new Error(body.error||'Request failed');
  return body;
}
async function save(){
  state=normalizeState(state);
  const out=await api('/api/state',{method:'PUT',body:JSON.stringify({state})});
  state=normalizeState(out.state);
  localStorage.setItem('beheshti_academy_last_state',JSON.stringify(state));
}
function isComplete(w,d){return state.progress.completed.includes(lessonId(w,d))}
function weekMastered(w){return Number(state.progress.weeklyMastery[w]||0)>=70}
function weekUnlocked(w){
  const start=state.placement.startingWeek||1;
  if(w<=start)return true;
  return weekMastered(w-1);
}
function dayUnlocked(w,d){
  if(!weekUnlocked(w))return false;
  if(d===1)return true;
  return isComplete(w,d-1);
}
function completion(){return Math.round((state.progress.completed.length/168)*100)}
function dueCards(){const now=Date.now();return Object.entries(state.vocab||{}).filter(([,v])=>!v.due||Number(v.due)<=now).sort((a,b)=>(a[1].due||0)-(b[1].due||0))}
function nextLesson(){
  const start=Math.max(1,state.placement.startingWeek||1);
  for(let w=start;w<=24;w++)for(let d=1;d<=7;d++)if(dayUnlocked(w,d)&&!isComplete(w,d))return {w,d};
  return {w:24,d:7};
}
function weakLessons(){
  return Object.entries(state.progress.scores||{}).filter(([,v])=>Number(v)<70).sort((a,b)=>a[1]-b[1]).slice(0,6);
}
function mistakeCount(){return Object.values(state.mistakes||{}).reduce((a,m)=>a+Number(m.count||0),0)}
function stage(){if(state.profile.setupComplete&&state.placement.completed)return 'active';if(state.profile.planComplete)return 'placement';return 'plan'}

function authScreen(){
  return `<div class="auth-wrap"><section class="auth-art"><div><div class="brand"><div class="brand-mark">B</div><div class="brand-copy"><strong>Beheshti Academy</strong><span>Learn English. Build Your Future.</span></div></div><h1>Build usable English, one verified step at a time.</h1><p>A complete six-month pathway for Dari/Persian speakers. Your account, placement, progress, mistakes, vocabulary schedule and evidence are stored in a real PostgreSQL learning record.</p><div class="auth-points"><div class="auth-point"><strong>24 weeks</strong><span>168 sequential study days from A1 foundations to B2 integration.</span></div><div class="auth-point"><strong>Evidence first</strong><span>Speaking and writing save the learner’s real work instead of fake AI grades.</span></div><div class="auth-point"><strong>Mastery gates</strong><span>Weeks unlock only after the previous week meets the mastery rule.</span></div></div></div><div class="small">Independent production deployment · Neon Auth · PostgreSQL · Row-Level Security</div></section><section class="auth-panel"><div class="auth-card"><div class="brand" style="color:var(--ink);margin-bottom:22px"><div class="brand-mark" style="background:var(--brand);color:white">B</div><div class="brand-copy"><strong>Beheshti Academy</strong><span style="color:var(--muted)">English learning system</span></div></div><div class="tabs"><button data-auth="login" class="${authMode==='login'?'active':''}">Sign in</button><button data-auth="register" class="${authMode==='register'?'active':''}">Create account</button></div>${authMode==='login'?`<form id="loginForm" class="stack"><div class="field"><label>Email</label><input name="email" type="email" required autocomplete="email"></div><div class="field"><label>Password</label><input name="password" type="password" required autocomplete="current-password"></div><button class="btn btn-primary" type="submit">Sign in</button></form>`:`<form id="registerForm" class="stack"><div class="field"><label>Name</label><input name="name" required minlength="2" autocomplete="name"></div><div class="field"><label>Email</label><input name="email" type="email" required autocomplete="email"></div><div class="field"><label>Password</label><input name="password" type="password" minlength="8" required autocomplete="new-password"></div><button class="btn btn-primary" type="submit">Create account</button></form>`}<p class="footer-note">Your session is managed by Neon Auth. No database password or API secret is embedded in this page.</p></div></section></div>`;
}
function planScreen(){
  return `<div class="onboarding-shell"><div class="onboarding-card card"><div class="row space"><span class="pill">Step 1 of 2</span><span class="small">Study plan</span></div><h2>Set your learning plan</h2><p class="small">This changes scheduling and support. It does not fabricate an “AI level”.</p><form id="planForm" class="grid grid-2" style="margin-top:22px"><div class="field"><label>Learning goal</label><select name="goal"><option ${state.profile.goal==='General English'?'selected':''}>General English</option><option ${state.profile.goal==='Professional English'?'selected':''}>Professional English</option><option ${state.profile.goal==='Academic English'?'selected':''}>Academic English</option><option ${state.profile.goal==='Travel & Daily Life'?'selected':''}>Travel & Daily Life</option></select></div><div class="field"><label>Daily study time</label><select name="minutes"><option value="30" ${state.profile.dailyMinutes===30?'selected':''}>30 minutes</option><option value="45" ${state.profile.dailyMinutes===45?'selected':''}>45 minutes</option><option value="60" ${state.profile.dailyMinutes===60?'selected':''}>60 minutes</option><option value="90" ${state.profile.dailyMinutes===90?'selected':''}>90 minutes</option></select></div><div class="field"><label>Reference accent</label><select name="accent"><option ${state.profile.accent==='American'?'selected':''}>American</option><option ${state.profile.accent==='British'?'selected':''}>British</option></select></div><div class="field"><label>Dari support</label><select name="dari"><option value="yes" ${state.settings.dariSupport?'selected':''}>On</option><option value="no" ${!state.settings.dariSupport?'selected':''}>Off</option></select></div><div style="grid-column:1/-1" class="row space"><span class="small">You can change these later.</span><button class="btn btn-primary" type="submit">Continue to placement</button></div></form></div></div>`;
}
function placementResult(){
  const s=state.placement.score||0;let start=1,level='A1';if(s>=14){start=17;level='B1+/B2'}else if(s>=11){start=13;level='B1'}else if(s>=8){start=9;level='A2'}else if(s>=5){start=5;level='A1+'}
  return `<div class="onboarding-shell"><div class="onboarding-card card" style="text-align:center"><span class="pill">Placement complete</span><h2>${s} / ${PLACEMENT.length}</h2><p>Your provisional routing level is <strong>${level}</strong>. This is not a certified CEFR examination; it chooses a starting point, while weekly mastery still controls advancement.</p><button class="btn btn-primary" data-finish-placement="${start}|${level}">Start at Week ${start}</button></div></div>`;
}
function placementScreen(){
  const i=state.placement.currentIndex||0;if(i>=PLACEMENT.length)return placementResult();const q=PLACEMENT[i];
  return `<div class="onboarding-shell"><div class="onboarding-card card"><div class="row space"><span class="pill">Step 2 of 2</span><span class="small">Question ${i+1} / ${PLACEMENT.length}</span></div><div class="progress-bar"><span style="width:${Math.round(i/PLACEMENT.length*100)}%"></span></div><h2>${esc(q[0])}</h2><div class="stack" style="margin-top:20px">${q[1].map((o,n)=>`<button class="option" data-placement-answer="${n}"><strong>${String.fromCharCode(65+n)}.</strong><span>${esc(o)}</span></button>`).join('')}</div><div class="row space" style="margin-top:18px"><button class="btn btn-ghost" data-action="edit-plan">Edit plan</button><span class="small">Your answer is saved after every question.</span></div></div></div>`;
}
function onboarding(){return stage()==='plan'?planScreen():placementScreen()}

function shell(content,title='Beheshti Academy',subtitle='Learn English. Build Your Future.'){
  const initial=esc((user?.name||'L').slice(0,1).toUpperCase());
  return `<div class="app-shell"><aside class="sidebar"><div class="brand"><div class="brand-mark">B</div><div class="brand-copy"><strong>Beheshti Academy</strong><span>Learn English. Build Your Future.</span></div></div><nav class="nav">${[['dashboard','Dashboard'],['course','Course map'],['review','Smart review'],['coach','Study coach'],['evidence','Evidence'],['profile','Profile']].map(([v,n])=>`<button data-nav="${v}" class="${view===v?'active':''}">${n}</button>`).join('')}</nav><div class="sidebar-foot">24 weeks · 168 study days<br>English-first with optional Dari support.<br><br>Mastery is evidence-based, not decorative.</div></aside><main class="main"><header class="topbar"><div class="topbar-left"><h1>${esc(title)}</h1><p>${esc(subtitle)}</p></div><div class="row"><button class="btn btn-ghost mobile-menu" data-action="mobile-menu">Menu</button><div class="user-chip"><div class="avatar">${initial}</div><span class="name small">${esc(user?.name||'Learner')}</span></div></div></header><div class="content">${content}</div></main></div><div id="mobileNav" class="mobile-drawer hidden"><div class="card"><div class="row space"><strong>Menu</strong><button class="btn btn-ghost" data-action="mobile-menu">Close</button></div><div class="nav" style="margin-top:12px">${[['dashboard','Dashboard'],['course','Course map'],['review','Smart review'],['coach','Study coach'],['evidence','Evidence'],['profile','Profile']].map(([v,n])=>`<button data-nav="${v}">${n}</button>`).join('')}</div></div></div>`;
}
function dashboard(){
  const n=nextLesson(),wk=CURRICULUM[n.w-1],pct=completion(),due=dueCards().length,weak=weakLessons().length;
  return shell(`<section class="hero"><div class="card hero-main"><div class="eyebrow">Next verified step</div><h2>Week ${n.w}: ${esc(wk.title)}</h2><p>${esc(wk.outcome)}</p><div class="row" style="margin-top:22px"><button class="btn hero-cta" data-open-lesson="${n.w},${n.d}">Continue · Day ${n.d} ${DAYS[n.d-1]}</button><span class="pill hero-pill">${esc(wk.level)}</span></div></div><div class="card hero-side"><div><div class="small">Program completion</div><div class="progress-ring" style="--p:${pct};margin-top:14px"><strong>${pct}%</strong></div></div><div class="small" style="text-align:center">${state.progress.completed.length} of 168 study days recorded</div></div></section><section class="grid grid-4" style="margin-top:18px"><div class="card metric"><div class="metric-label">Current streak</div><div class="metric-value">${state.progress.streak||0}</div><div class="metric-sub">study days</div></div><div class="card metric"><div class="metric-label">Vocabulary due</div><div class="metric-value">${due}</div><div class="metric-sub">spaced-review cards</div></div><div class="card metric"><div class="metric-label">Weak checks</div><div class="metric-value">${weak}</div><div class="metric-sub">scores below 70%</div></div><div class="card metric"><div class="metric-label">Evidence</div><div class="metric-value">${state.artifacts.length}</div><div class="metric-sub">speaking/writing artifacts</div></div></section><section class="grid grid-2" style="margin-top:18px"><div class="card"><div class="row space"><div><h3>Today’s plan</h3><div class="small">${state.profile.dailyMinutes} minutes · ${esc(state.profile.goal)}</div></div><span class="pill">Day ${n.d}</span></div><div class="divider"></div><div class="profile-list"><div class="profile-item"><span>Core activity</span><strong>${DAYS[n.d-1]}</strong></div><div class="profile-item"><span>Grammar focus</span><strong style="text-align:right;max-width:58%">${esc(wk.grammar)}</strong></div><div class="profile-item"><span>Review due</span><strong>${due} cards</strong></div></div></div><div class="card"><h3>Adaptive next action</h3>${coachSnippet()}<button class="btn btn-secondary" data-nav="coach">Open Study Coach</button></div></section>`,'Dashboard','Your persisted learning record');
}
function coachSnippet(){
  const due=dueCards(),weak=weakLessons();
  if(weak.length){const [id,score]=weak[0];return `<p class="small" style="line-height:1.8">Your weakest recorded check is <strong>${id}</strong> at <strong>${score}%</strong>. Retake that lesson before adding more difficulty.</p>`}
  if(due.length)return `<p class="small" style="line-height:1.8">You have <strong>${due.length}</strong> vocabulary cards due. Retrieval practice is the highest-priority next action.</p>`;
  return `<p class="small" style="line-height:1.8">No urgent remediation is due. Continue the next unlocked lesson and produce fresh evidence.</p>`;
}
function courseMap(){
  return shell(`<div class="curriculum-header"><span class="pill">A1 → B2</span><h2 style="font-size:30px;margin-bottom:8px">24-week course map</h2><p>Every week contains seven sequential days. Later days stay locked until the previous day is recorded; later weeks require the preceding mastery gate.</p></div><div class="week-grid">${CURRICULUM.map((wk,i)=>{const w=i+1,un=weekUnlocked(w),master=weekMastered(w),done=Array.from({length:7},(_,x)=>isComplete(w,x+1)).filter(Boolean).length;return `<article class="week-card ${!un?'locked':''} ${w===state.profile.currentWeek?'current':''}"><div class="row space"><span class="week-no">Week ${w}</span><span class="pill ${master?'':'gold'}">${master?'Mastered':esc(wk.level)}</span></div><h3>${esc(wk.title)}</h3><p>${esc(wk.outcome)}</p><div class="week-meter"><span style="width:${Math.round(done/7*100)}%"></span></div><div class="row space" style="margin-top:12px"><span class="small">${done}/7 days</span><button class="btn ${un?'btn-secondary':'btn-ghost'}" ${un?'data-open-lesson="'+w+',1"':'disabled'}>${un?'Open week':'Locked'}</button></div></article>`}).join('')}</div>`,'Course map','Sequential learning with mastery gates');
}
function dayTrack(w,d){
  return `<div class="day-track">${DAYS.map((name,i)=>{const day=i+1,done=isComplete(w,day),open=dayUnlocked(w,day);return `<button class="day-node ${done?'done':''} ${d===day?'current':''} ${!open?'locked':''}" ${open?`data-open-lesson="${w},${day}"`:'disabled'}>${day}<br>${esc(name)}</button>`}).join('')}</div>`;
}
function mcq(w,d,q,label){
  const id=lessonId(w,d),stored=state.progress.scores[id];
  return `<div class="stack"><div class="small">${esc(label||'Evidence check')}</div><h3>${esc(q[0])}</h3>${q[1].map((o,i)=>`<button class="option" data-mcq="${w}|${d}|${i}|${q[2]}"><strong>${String.fromCharCode(65+i)}.</strong><span>${esc(o)}</span></button>`).join('')}${stored!=null?`<div class="score-box"><strong>Recorded score: ${stored}%</strong><div class="small">You can retake this check; the newest score replaces the old score.</div></div>`:''}</div>`;
}
function lessonContent(w,d){
  const wk=CURRICULUM[w-1],id=lessonId(w,d);
  if(d===1)return `<div class="lesson-block"><h3>Learning outcome</h3><p>${esc(wk.outcome)}</p></div><div class="lesson-block"><h3>Teach</h3><p><strong>${esc(wk.grammar)}</strong></p><p>${esc(wk.teach)}</p>${state.settings.dariSupport?`<div class="dari-note">${esc(wk.dari)}</div>`:''}</div><div class="lesson-block">${mcq(w,d,wk.teachQ,'Quick check')}</div>`;
  if(d===2)return `<div class="lesson-block"><h3>Listening & dictation</h3><p>Listen without reading first, then type what you hear. The score is text similarity for this exact passage—not a certified listening score.</p><div class="row"><button class="btn btn-secondary" data-speak-text="${esc(wk.teach)}">Play passage</button><button class="btn btn-ghost" data-action="stop-audio">Stop</button></div><form id="dictationForm" class="stack" style="margin-top:16px"><input type="hidden" name="week" value="${w}"><input type="hidden" name="day" value="${d}"><div class="field"><label>Your dictation</label><textarea name="text" required placeholder="Type what you hear..."></textarea></div><button class="btn btn-primary">Check and record</button></form>${state.progress.scores[id]!=null?`<div class="score-box"><strong>Recorded similarity: ${state.progress.scores[id]}%</strong></div>`:''}</div>`;
  if(d===3)return `<div class="lesson-block"><h3>Vocabulary lab</h3><p>Look up focus words, hear them, then add them to spaced review.</p><div class="vocab-list">${wk.words.map(word=>`<div class="vocab"><div class="row space"><strong>${esc(word)}</strong><button class="sound-btn" data-speak-text="${esc(word)}" aria-label="Hear ${esc(word)}">Hear</button></div><span id="dict-${esc(word)}">Focus word for Week ${w}</span><div class="row" style="margin-top:8px"><button class="btn btn-ghost" data-dict="${esc(word)}">Definition</button><button class="btn btn-secondary" data-add-card="${esc(word)}">${state.vocab[word]?'In review':'Add'}</button></div></div>`).join('')}</div><div class="row space" style="margin-top:16px"><span class="small">Complete after saving the focus set.</span><button class="btn btn-primary" data-complete-vocab="${w}">Add all & complete</button></div></div>`;
  if(d===4)return `<div class="lesson-block"><h3>Grammar laboratory</h3><p><strong>${esc(wk.grammar)}</strong></p>${state.settings.dariSupport?`<div class="dari-note">${esc(wk.dari)}</div>`:''}<div class="divider"></div>${mcq(w,d,wk.teachQ,'Retest the week’s core form')}</div>`;
  if(d===5)return `<div class="lesson-block"><h3>Reading</h3><p>${esc(wk.reading)}</p><div class="divider"></div>${mcq(w,d,wk.readQ,'Reading evidence')}</div>`;
  if(d===6)return speakingWriting(w,wk);
  return masteryPanel(w);
}
function speakingWriting(w,wk){
  const speaking=state.artifacts.some(a=>(a.lessonId||a.lesson_id)===lessonId(w,6)&&a.type==='speaking');
  const writing=state.artifacts.some(a=>(a.lessonId||a.lesson_id)===lessonId(w,6)&&a.type==='writing');
  return `<div class="lesson-block"><h3>Speaking evidence</h3><p><strong>Prompt:</strong> ${esc(wk.speaking)}</p><div class="mic-box"><div class="row"><button class="btn btn-primary" data-action="start-mic">Start speech recognition</button><button class="btn btn-ghost" data-action="stop-mic">Stop</button><span class="small">If recognition is unsupported, type your own transcript below. It will be labelled manual.</span></div><div id="liveTranscript" class="transcript">No transcript captured yet.</div></div><form id="evidenceForm" class="stack" style="margin-top:16px"><input type="hidden" name="week" value="${w}"><div class="field"><label>Speaking transcript</label><textarea name="transcript" id="speechTranscript" required minlength="20" placeholder="Use speech recognition or type an accurate transcript of what you said."></textarea></div><div class="field"><label>Writing task</label><div class="small" style="margin-bottom:6px">${esc(wk.writing)}</div><textarea name="writing" required minlength="40" placeholder="Write your response here..."></textarea></div><div class="field"><label>Reflection (optional)</label><textarea name="reflection" placeholder="What did you repair or find difficult?"></textarea></div><button class="btn btn-primary">Save both evidence artifacts & complete Day 6</button></form><div class="row" style="margin-top:12px"><span class="pill ${speaking?'':'gold'}">Speaking ${speaking?'saved':'needed'}</span><span class="pill ${writing?'':'gold'}">Writing ${writing?'saved':'needed'}</span></div></div>`;
}
function masteryPanel(w){
  const prereq=Array.from({length:6},(_,i)=>isComplete(w,i+1));
  const ids=[1,2,4,5].map(d=>lessonId(w,d));
  const scores=ids.map(id=>Number(state.progress.scores[id])).filter(Number.isFinite);
  const avg=scores.length===4?Math.round(scores.reduce((a,b)=>a+b,0)/4):null;
  const evidenceOk=state.artifacts.some(a=>(a.lessonId||a.lesson_id)===lessonId(w,6)&&a.type==='speaking')&&state.artifacts.some(a=>(a.lessonId||a.lesson_id)===lessonId(w,6)&&a.type==='writing');
  const pass=prereq.every(Boolean)&&evidenceOk&&avg!==null&&avg>=70;
  return `<div class="lesson-block"><h3>Weekly mastery gate</h3><p>The gate requires all six learning days, both Day-6 evidence artifacts, and an average of at least 70% across Teach, Listening, Grammar and Reading.</p><div class="grid grid-3"><div class="score-box"><div class="metric-label">Days 1–6</div><div class="metric-value">${prereq.filter(Boolean).length}/6</div></div><div class="score-box"><div class="metric-label">Evidence</div><div class="metric-value">${evidenceOk?'2/2':'Incomplete'}</div></div><div class="score-box"><div class="metric-label">Scored average</div><div class="metric-value">${avg===null?'—':avg+'%'}</div></div></div><div style="margin-top:18px">${pass?`<button class="btn btn-primary" data-pass-mastery="${w}|${avg}">Pass mastery & unlock Week ${Math.min(24,w+1)}</button>`:`<div class="alert">Mastery is not yet proven. Complete missing days/evidence or retake checks below 70%.</div>`}</div>${weakLessons().filter(([id])=>id.startsWith('w'+w+'d')).length?`<div class="stack" style="margin-top:14px">${weakLessons().filter(([id])=>id.startsWith('w'+w+'d')).map(([id,score])=>{const d=Number(id.match(/d(\d)$/)[1]);return `<button class="btn btn-ghost" data-open-lesson="${w},${d}">Retake Day ${d} · ${score}%</button>`}).join('')}</div>`:''}</div>`;
}
function lessonPage(){
  const [w,d]=lessonRef||[state.profile.currentWeek,state.profile.currentDay],wk=CURRICULUM[w-1],open=dayUnlocked(w,d);
  if(!open)return shell(`<div class="card"><h2>Lesson locked</h2><p>Complete the preceding day or mastery gate first.</p><button class="btn btn-secondary" data-nav="course">Back to course map</button></div>`,'Lesson','Locked by sequence');
  return shell(`<div class="lesson-head"><div><span class="pill">Week ${w} · ${esc(wk.level)}</span><h2>${esc(wk.title)}</h2><div class="small">Day ${d}: ${esc(DAYS[d-1])} · ${esc(wk.outcome)}</div></div><div class="row"><button class="btn btn-ghost" data-nav="course">Course map</button></div></div>${dayTrack(w,d)}<div class="lesson-layout" style="margin-top:18px"><section class="lesson-main">${lessonContent(w,d)}</section><aside class="card lesson-side"><div class="small">Lesson record</div><div class="profile-list" style="margin-top:8px"><div class="profile-item"><span>ID</span><strong>${lessonId(w,d)}</strong></div><div class="profile-item"><span>Status</span><strong>${isComplete(w,d)?'Complete':'Open'}</strong></div><div class="profile-item"><span>Score</span><strong>${state.progress.scores[scoreKey(w,d)]!=null?state.progress.scores[scoreKey(w,d)]+'%':'—'}</strong></div></div><div class="divider"></div><div class="small">Completed lessons remain open for deliberate review and retakes.</div></aside></div>`,'Lesson',`Week ${w} · Day ${d}`);
}
function reviewPage(){
  const due=dueCards();
  return shell(`<div class="row space"><div><span class="pill">Spaced retrieval</span><h2>Smart review</h2><p class="small">Each recall rating schedules the next retrieval interval.</p></div><strong>${due.length} due</strong></div><div class="grid grid-2" style="margin-top:18px">${due.length?due.slice(0,30).map(([word,v])=>`<div class="card"><div class="row space"><h3>${esc(word)}</h3><span class="pill">Box ${v.box||1}</span></div><p class="small">Last result: ${esc(v.last||'new')}</p><div class="row"><button class="btn btn-danger" data-review="${esc(word)}|again">Again</button><button class="btn btn-ghost" data-review="${esc(word)}|hard">Hard</button><button class="btn btn-secondary" data-review="${esc(word)}|good">Good</button><button class="btn btn-primary" data-review="${esc(word)}|easy">Easy</button></div></div>`).join(''):`<div class="card empty">Nothing is due. Continue the course or add focus words from Vocabulary days.</div>`}</div>`,'Smart review','Spaced repetition without fake certainty');
}
function coachPage(){
  const due=dueCards(),weak=weakLessons(),mistakes=Object.entries(state.mistakes||{}).sort((a,b)=>(b[1].count||0)-(a[1].count||0));
  return shell(`<div class="curriculum-header"><span class="pill">Data-driven</span><h2>Study Coach</h2><p>This coach uses your actual scores, mistakes, due vocabulary and evidence gaps. It does not pretend to be a generative AI tutor.</p></div><div class="grid grid-3"><div class="card metric"><div class="metric-label">Due vocabulary</div><div class="metric-value">${due.length}</div></div><div class="card metric"><div class="metric-label">Weak scored checks</div><div class="metric-value">${weak.length}</div></div><div class="card metric"><div class="metric-label">Recorded mistakes</div><div class="metric-value">${mistakeCount()}</div></div></div><div class="grid grid-2" style="margin-top:18px"><div class="card"><h3>Priority queue</h3><div class="stack">${weak.length?weak.map(([id,score])=>{const m=id.match(/^w(\d+)d(\d)$/);return `<button class="btn btn-ghost row space" data-open-lesson="${m[1]},${m[2]}"><span>Retake ${id}</span><strong>${score}%</strong></button>`}).join(''):due.length?`<button class="btn btn-secondary" data-nav="review">Review ${due.length} due words</button>`:`<div class="small">No remediation is urgent. Continue your next lesson.</div>`}</div></div><div class="card"><h3>Error patterns</h3>${mistakes.length?`<div class="profile-list">${mistakes.slice(0,8).map(([k,m])=>`<div class="profile-item"><span>${esc(m.prompt||k)}</span><strong>${m.count}×</strong></div>`).join('')}</div>`:`<p class="small">No repeated error pattern has been recorded yet.</p>`}</div></div>`,'Study Coach','Recommendations derived from your own learning record');
}
function evidencePage(){
  const a=[...(state.artifacts||[])].sort((x,y)=>String(y.createdAt||y.created_at||'').localeCompare(String(x.createdAt||x.created_at||'')));
  return shell(`<div class="row space"><div><span class="pill">Portfolio</span><h2>Speaking & writing evidence</h2><p class="small">Your real work is preserved as inspectable evidence.</p></div><button class="btn btn-ghost" data-action="refresh-evidence">Refresh</button></div><div class="stack" style="margin-top:18px">${a.length?a.map(x=>`<article class="card"><div class="row space"><div><span class="pill">${esc(x.type)}</span><strong style="margin-left:8px">${esc(x.lessonId||x.lesson_id)}</strong></div><span class="small">${esc(String(x.createdAt||x.created_at||'').slice(0,10))}</span></div><p style="white-space:pre-wrap;line-height:1.75">${esc(x.content)}</p></article>`).join(''):`<div class="card empty">No evidence has been saved yet. Day 6 of each week creates speaking and writing artifacts.</div>`}</div>`,'Evidence','A persistent learner portfolio');
}
function profilePage(){
  return shell(`<div class="grid grid-2"><div class="card"><h2>${esc(user.name)}</h2><div class="small">${esc(user.email)}</div><div class="divider"></div><div class="profile-list"><div class="profile-item"><span>Goal</span><strong>${esc(state.profile.goal)}</strong></div><div class="profile-item"><span>Daily plan</span><strong>${state.profile.dailyMinutes} min</strong></div><div class="profile-item"><span>Reference accent</span><strong>${esc(state.profile.accent)}</strong></div><div class="profile-item"><span>Placement</span><strong>${esc(state.placement.provisionalLevel||'—')}</strong></div><div class="profile-item"><span>Starting week</span><strong>${state.placement.startingWeek}</strong></div></div></div><div class="card"><h3>Learning settings</h3><form id="settingsForm" class="stack"><div class="field"><label>Daily minutes</label><select name="minutes"><option value="30" ${state.profile.dailyMinutes===30?'selected':''}>30</option><option value="45" ${state.profile.dailyMinutes===45?'selected':''}>45</option><option value="60" ${state.profile.dailyMinutes===60?'selected':''}>60</option><option value="90" ${state.profile.dailyMinutes===90?'selected':''}>90</option></select></div><div class="field"><label>Dari support</label><select name="dari"><option value="yes" ${state.settings.dariSupport?'selected':''}>On</option><option value="no" ${!state.settings.dariSupport?'selected':''}>Off</option></select></div><button class="btn btn-secondary">Save settings</button></form><div class="divider"></div><div class="stack"><button class="btn btn-ghost" data-action="export-state">Export my learning record</button><button class="btn btn-danger" data-action="logout">Log out</button></div></div></div>`,'Profile','Account and learning controls');
}
function render(){
  if(!marker||!user||!state){APP.innerHTML=authScreen();return}
  if(stage()!=='active'){APP.innerHTML=onboarding();return}
  if(view==='dashboard')APP.innerHTML=dashboard();
  else if(view==='course')APP.innerHTML=courseMap();
  else if(view==='lesson')APP.innerHTML=lessonPage();
  else if(view==='review')APP.innerHTML=reviewPage();
  else if(view==='coach')APP.innerHTML=coachPage();
  else if(view==='evidence')APP.innerHTML=evidencePage();
  else APP.innerHTML=profilePage();
}
function updateStreak(){
  const t=today(),last=state.progress.lastStudyDate;if(last===t)return;const y=new Date(Date.now()-86400000).toISOString().slice(0,10);state.progress.streak=last===y?(state.progress.streak||0)+1:1;state.progress.lastStudyDate=t;
}
function recordMistake(w,d,q){
  const key=`${lessonId(w,d)}:${q[0]}`;const old=state.mistakes[key]||{count:0,prompt:q[0],lessonId:lessonId(w,d)};old.count+=1;old.lastAt=Date.now();state.mistakes[key]=old;
}
async function completeLesson(w,d,score=null,activity=DAYS[d-1]){
  const id=lessonId(w,d);if(!state.progress.completed.includes(id))state.progress.completed.push(id);if(score!=null)state.progress.scores[id]=Math.max(0,Math.min(100,Math.round(score)));updateStreak();
  if(d<7){state.profile.currentWeek=w;state.profile.currentDay=d+1;lessonRef=[w,d+1]}else if(w<24){state.profile.currentWeek=w+1;state.profile.currentDay=1;lessonRef=[w+1,1]}
  await save();await api('/api/events',{method:'POST',body:JSON.stringify({lessonId:id,activity,score,payload:{version:3}})});toast('Progress saved');view='lesson';render();
}
function similarity(a,b){
  const words=s=>s.toLowerCase().replace(/[^a-z0-9' ]/g,' ').split(/\s+/).filter(Boolean),A=words(a),B=words(b);if(!B.length)return 0;let matches=0;const used=new Set();for(const x of A){const i=B.findIndex((y,j)=>y===x&&!used.has(j));if(i>=0){used.add(i);matches++}}return Math.max(0,Math.min(100,Math.round((2*matches/(A.length+B.length))*100)||0));
}
async function boot(){
  if(!marker){render();return}
  try{const [me,s]=await Promise.all([api('/api/me'),api('/api/state')]);user=me.user;state=normalizeState(s.state);localStorage.setItem('beheshti_academy_last_state',JSON.stringify(state));render()}
  catch{localStorage.removeItem(SESSION_MARKER);marker='';user=null;state=null;render()}
}

APP.addEventListener('click',async event=>{
  const b=event.target.closest('button');if(!b)return;
  try{
    if(b.dataset.auth){authMode=b.dataset.auth;render();return}
    if(b.dataset.nav){view=b.dataset.nav;document.getElementById('mobileNav')?.classList.add('hidden');render();return}
    if(b.dataset.action==='mobile-menu'){document.getElementById('mobileNav')?.classList.toggle('hidden');return}
    if(b.dataset.action==='edit-plan'){state.profile.planComplete=false;state.placement.currentIndex=0;state.placement.answers=[];state.placement.score=0;await save();render();return}
    if(b.dataset.placementAnswer!=null){const i=state.placement.currentIndex,q=PLACEMENT[i],answer=Number(b.dataset.placementAnswer);state.placement.answers[i]=answer;if(answer===q[2])state.placement.score=(state.placement.score||0)+1;state.placement.currentIndex=i+1;await save();render();return}
    if(b.dataset.finishPlacement){const [startRaw,level]=b.dataset.finishPlacement.split('|'),start=Number(startRaw);state.placement.completed=true;state.placement.startingWeek=start;state.placement.provisionalLevel=level;state.profile.currentWeek=start;state.profile.currentDay=1;state.profile.setupComplete=true;state.profile.planComplete=true;await save();view='dashboard';toast('Placement and starting point saved');render();return}
    if(b.dataset.openLesson){const [w,d]=b.dataset.openLesson.split(',').map(Number);if(!dayUnlocked(w,d)){toast('Complete the preceding step first.','error');return}lessonRef=[w,d];view='lesson';render();return}
    if(b.dataset.speakText!=null){speechSynthesis.cancel();const u=new SpeechSynthesisUtterance(b.dataset.speakText);u.lang=state.profile.accent==='British'?'en-GB':'en-US';u.rate=.88;speechSynthesis.speak(u);return}
    if(b.dataset.action==='stop-audio'){speechSynthesis.cancel();return}
    if(b.dataset.dict){const word=b.dataset.dict,out=await api('/api/dictionary',{method:'POST',body:JSON.stringify({term:word})});const el=document.getElementById('dict-'+word);if(el)el.textContent=[out.entry.phonetic,out.entry.partOfSpeech,out.entry.definition].filter(Boolean).join(' · ');return}
    if(b.dataset.addCard){const word=b.dataset.addCard;state.vocab[word]=state.vocab[word]||{box:1,due:Date.now(),last:'new'};await save();toast(`${word} added to review`);render();return}
    if(b.dataset.completeVocab){const w=Number(b.dataset.completeVocab),wk=CURRICULUM[w-1];for(const word of wk.words)state.vocab[word]=state.vocab[word]||{box:1,due:Date.now(),last:'new'};await save();await completeLesson(w,3,100,'Vocabulary');return}
    if(b.dataset.mcq){const [w0,d0,choice0,correct0]=b.dataset.mcq.split('|'),w=Number(w0),d=Number(d0),choice=Number(choice0),correct=Number(correct0),q=d===5?CURRICULUM[w-1].readQ:CURRICULUM[w-1].teachQ,ok=choice===correct,score=ok?100:45;if(!ok)recordMistake(w,d,q);state.progress.scores[lessonId(w,d)]=score;await save();b.parentElement.querySelectorAll('.option').forEach(x=>x.disabled=true);b.classList.add(ok?'correct':'wrong');if(!ok)b.parentElement.querySelectorAll('.option')[correct]?.classList.add('correct');setTimeout(()=>completeLesson(w,d,score,DAYS[d-1]),650);return}
    if(b.dataset.review){const [word,rating]=b.dataset.review.split('|'),v=state.vocab[word]||{box:1};const days={again:.04,hard:1,good:3,easy:7}[rating];v.box=rating==='again'?1:Math.min(10,(v.box||1)+(rating==='easy'?2:1));v.last=rating;v.due=Date.now()+days*86400000;state.vocab[word]=v;await save();render();return}
    if(b.dataset.action==='start-mic'){
      const SR=window.SpeechRecognition||window.webkitSpeechRecognition;if(!SR){toast('Speech recognition is unavailable here. Type an accurate manual transcript instead.','error');return}
      recognition?.stop?.();recognition=new SR();recognition.lang=state.profile.accent==='British'?'en-GB':'en-US';recognition.interimResults=true;recognition.continuous=true;const live=document.getElementById('liveTranscript'),box=document.getElementById('speechTranscript');let finalText=box?.value||'';recognition.onresult=e=>{let interim='';for(let i=e.resultIndex;i<e.results.length;i++){const text=e.results[i][0].transcript;if(e.results[i].isFinal)finalText+=(finalText?' ':'')+text;else interim+=text}if(box)box.value=finalText.trim();if(live)live.textContent=(finalText+' '+interim).trim()||'Listening…'};recognition.onerror=e=>toast(`Speech recognition: ${e.error}`,'error');recognition.start();return
    }
    if(b.dataset.action==='stop-mic'){recognition?.stop?.();recognition=null;return}
    if(b.dataset.passMastery){const [w0,avg0]=b.dataset.passMastery.split('|'),w=Number(w0),avg=Number(avg0);state.progress.weeklyMastery[w]=avg;await save();await completeLesson(w,7,avg,'Mastery');return}
    if(b.dataset.action==='refresh-evidence'){const out=await api('/api/artifacts');state.artifacts=out.artifacts||[];await save();render();return}
    if(b.dataset.action==='export-state'){const blob=new Blob([JSON.stringify(state,null,2)],{type:'application/json'}),a=document.createElement('a');a.href=URL.createObjectURL(blob);a.download='beheshti-academy-learning-record.json';a.click();setTimeout(()=>URL.revokeObjectURL(a.href),500);return}
    if(b.dataset.action==='logout'){try{await api('/api/auth/logout',{method:'POST',body:'{}'})}catch{}localStorage.removeItem(SESSION_MARKER);marker='';user=null;state=null;view='dashboard';render();return}
  }catch(err){toast(err.message,'error')}
});

APP.addEventListener('submit',async event=>{
  event.preventDefault();const form=event.target,fd=new FormData(form);try{
    if(form.id==='loginForm'){const out=await api('/api/auth/login',{method:'POST',body:JSON.stringify({email:fd.get('email'),password:fd.get('password')})});marker='active';localStorage.setItem(SESSION_MARKER,marker);user=out.user;state=normalizeState(out.state);render();return}
    if(form.id==='registerForm'){const out=await api('/api/auth/register',{method:'POST',body:JSON.stringify({name:fd.get('name'),email:fd.get('email'),password:fd.get('password')})});marker='active';localStorage.setItem(SESSION_MARKER,marker);user=out.user;state=normalizeState(out.state);render();return}
    if(form.id==='planForm'){state.profile.goal=fd.get('goal');state.profile.dailyMinutes=Number(fd.get('minutes'));state.profile.accent=fd.get('accent');state.settings.dariSupport=fd.get('dari')==='yes';state.profile.planComplete=true;state.profile.setupComplete=false;state.placement.completed=false;state.placement.currentIndex=0;state.placement.answers=[];state.placement.score=0;await save();render();return}
    if(form.id==='dictationForm'){const w=Number(fd.get('week')),d=Number(fd.get('day')),score=similarity(fd.get('text'),CURRICULUM[w-1].teach);if(score<70){const key=`${lessonId(w,d)}:dictation`;const old=state.mistakes[key]||{count:0,prompt:'Listening / dictation accuracy',lessonId:lessonId(w,d)};old.count+=1;old.lastAt=Date.now();state.mistakes[key]=old}state.progress.scores[lessonId(w,d)]=score;await save();toast(`Dictation similarity: ${score}%`);await completeLesson(w,d,score,'Listening');return}
    if(form.id==='evidenceForm'){const w=Number(fd.get('week')),transcript=String(fd.get('transcript')||'').trim(),writing=String(fd.get('writing')||'').trim(),reflection=String(fd.get('reflection')||'').trim();if(transcript.length<20)throw new Error('Speaking transcript is too short to count as evidence.');if(writing.length<40)throw new Error('Writing evidence is too short.');const speakingContent=transcript+(reflection?`\n\nReflection: ${reflection}`:'');const s=await api('/api/artifacts',{method:'POST',body:JSON.stringify({lessonId:lessonId(w,6),type:'speaking',content:speakingContent,meta:{prompt:CURRICULUM[w-1].speaking,source:(window.SpeechRecognition||window.webkitSpeechRecognition)?'speech-or-manual':'manual-transcript'}})});const wr=await api('/api/artifacts',{method:'POST',body:JSON.stringify({lessonId:lessonId(w,6),type:'writing',content:writing,meta:{prompt:CURRICULUM[w-1].writing}})});state.artifacts.push(s.artifact,wr.artifact);await save();await completeLesson(w,6,null,'Speaking + Writing');return}
    if(form.id==='settingsForm'){state.profile.dailyMinutes=Number(fd.get('minutes'));state.settings.dariSupport=fd.get('dari')==='yes';await save();toast('Settings saved');render();return}
  }catch(err){toast(err.message,'error')}
});

if('serviceWorker' in navigator){window.addEventListener('load',()=>navigator.serviceWorker.register('/sw.js').catch(()=>{}))}
boot();
