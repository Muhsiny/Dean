import { useEffect, useMemo, useRef, useState } from 'react';
import {
  Activity, AlertTriangle, Archive, BarChart3, Bell, CheckCircle2, ChevronLeft, Clock3,
  Edit3, Eye, FileAudio, FileText, Globe2, Home, Image as ImageIcon, LayoutDashboard, Link2,
  Languages, Menu, Megaphone, Newspaper, PauseCircle, PlayCircle, Plus, Radio, RefreshCw,
  Download, LogIn, LogOut, Save, Search, Send, Settings, Share2, ShieldCheck, Trash2, Upload, UserPlus, Users, Video, Wifi, WifiOff, X
} from 'lucide-react';
import { api, auth, image, notifications, ws } from '@appdeploy/client';
import { PUBLIC_LANGUAGES, PUBLIC_LANGUAGE_META, PUBLIC_UI, publicLanguageDirection, type PublicLanguage } from './public-i18n';

type NewsStatus = 'draft' | 'review' | 'scheduled' | 'published';
type MediaType = 'text' | 'image' | 'video' | 'interview';
type StaffRole = 'owner' | 'manager' | 'deputy' | 'editor' | 'reporter';
type AdminTab = 'dashboard' | 'articles' | 'sources' | 'socials' | 'settings' | 'staff' | 'visitors';
type AdminSession = {
  email: string;
  name: string;
  role: StaffRole;
  primary: boolean;
  permissions: { manageArticles: boolean; deleteArticles: boolean; manageSources: boolean; manageSocials: boolean; manageSettings: boolean; manageStaff: boolean };
};
type StaffMember = { id: string; email: string; name: string; role: StaffRole; active: boolean; primary: boolean; createdAt: number; updatedAt: number; lastLoginAt?: number };
type StaffForm = { email: string; name: string; role: StaffRole; active: boolean };
type VisitorSession = { userId: string; email: string; name: string };
type VisitorAnalytics = { totalRegistered: number; totalEntryClicks: number; totalSiteOpens: number; totalArticleViews: number; totalInstallAttempts: number; totalInstalls: number; items: Array<{ userId: string; email: string; name: string; firstSeenAt: number; lastSeenAt: number; entryClicks: number; siteOpens: number; articleViews: number; installAttempts: number; installs: number }> }; 
type BeforeInstallPromptEvent = Event & { prompt: () => Promise<void>; userChoice: Promise<{ outcome: 'accepted' | 'dismissed'; platform: string }> };

type NewsItem = {
  id: string;
  title: string;
  summary: string;
  body: string;
  category: string;
  language: string;
  section: 'news' | 'analysis';
  mediaType: MediaType;
  status: NewsStatus;
  isBreaking: boolean;
  createdAt: number;
  updatedAt: number;
  publishAt?: number;
  publishedAt?: number;
  mediaUrl?: string;
  mediaName?: string;
  externalVideoUrl?: string;
  facebookVideoUrl?: string;
  viewCount: number;
  archived?: boolean;
  archivedAt?: number;
};

type Source = {
  id: string;
  name: string;
  url: string;
  sourceType: string;
  language: string;
  topic: string;
  priority: string;
  intervalMinutes: number;
  active: boolean;
  autoPublish: boolean;
  rightsApproved: boolean;
  ingestText: boolean;
  ingestImage: boolean;
  ingestVideo: boolean;
  ingestInterview: boolean;
  createdAt: number;
  updatedAt: number;
  lastCheckedAt?: number;
  lastSuccessAt?: number;
  lastError?: string;
  fetchedCount: number;
};

type Social = {
  id: string;
  network: string;
  pageUrl: string;
  autoShare: boolean;
  enabled: boolean;
  status: string;
  configured?: boolean;
  lastPublishAt?: number;
  lastError?: string;
};

type AppSettings = {
  siteTitle: string; brandName: string; tagline: string; newsroomLabel: string; homeTitle: string; newsTitle: string; analysisTitle: string; archiveTitle: string; englishTitle: string; englishDeskLabel: string;
  navHome: string; navNews: string; navAnalysis: string; navArchive: string; navEnglish: string; breakingLabel: string; footerText: string; footerYear: string; searchPlaceholder: string;
  defaultLanguage: string; breakingBar: boolean; watermark: boolean; versioning: boolean; autoShare: boolean; viewCountThreshold: number;
  archiveDays: number; visualRequired: boolean; sourceMonitorEnabled: boolean; forceArianaSource: boolean; primaryIntervalMinutes: number; primaryItemsPerCycle: number;
  secondarySourcesPerCycle: number; secondaryHighItemsPerCycle: number; secondaryNormalItemsPerCycle: number; autoWordLimit: number; openverseFallback: boolean; bbcReplaceSourceImage: boolean;
  logoUrl?: string; updatedAt?: number;
};

type ArticleForm = {
  title: string;
  summary: string;
  body: string;
  category: string;
  language: string;
  section: 'news' | 'analysis';
  mediaType: MediaType;
  status: NewsStatus;
  isBreaking: boolean;
  publishAt: string;
};

type SourceForm = {
  name: string;
  url: string;
  sourceType: string;
  language: string;
  topic: string;
  priority: string;
  intervalMinutes: number;
  active: boolean;
  autoPublish: boolean;
  rightsApproved: boolean;
  ingestText: boolean;
  ingestImage: boolean;
  ingestVideo: boolean;
  ingestInterview: boolean;
};

type SocialForm = {
  id: string;
  network: string;
  pageUrl: string;
  autoShare: boolean;
  enabled: boolean;
};

type MediaPayload = { data: string; mimeType: string; name: string };

const languages = [...PUBLIC_LANGUAGES]; const PUBLIC_LANGUAGE_STORAGE_KEY = 'sayeh-public-language-v1'; const isPublicLanguage = (value:string): value is PublicLanguage => (PUBLIC_LANGUAGES as readonly string[]).includes(value);
const emptyArticle: ArticleForm = {
  title: '', summary: '', body: '', category: 'افغانستان', language: 'دری', section: 'news',
  mediaType: 'text', status: 'published', isBreaking: false, publishAt: ''
};
const emptySource: SourceForm = {
  name: '', url: '', sourceType: 'وب‌سایت', language: 'دری', topic: 'عمومی', priority: 'بالا',
  intervalMinutes: 5, active: false, autoPublish: false, rightsApproved: false,
  ingestText: true, ingestImage: true, ingestVideo: true, ingestInterview: true
};
const emptySocial: SocialForm = { id: '', network: 'Facebook', pageUrl: '', autoShare: true, enabled: true };
const defaultSettings: AppSettings = {
  siteTitle: 'خبرگزاری سایه', brandName: 'SAYEH NEWS', tagline: 'پنهان از نگاه‌ها، مسلط بر رویدادها', newsroomLabel: 'تحریریه', homeTitle: 'پنهان از نگاه‌ها', newsTitle: 'آخرین خبرها', analysisTitle: 'تحلیل رویدادها', archiveTitle: 'آرشیف خبرها', englishTitle: 'English News', englishDeskLabel: 'English Desk', navHome: 'خانه', navNews: 'خبرها', navAnalysis: 'تحلیل', navArchive: 'آرشیف', navEnglish: 'English', breakingLabel: 'خبر فوری', footerText: 'تمام حقوق این خبرگزاری برای سایه محفوظ می‌باشد.', footerYear: '۲۰۲۳', searchPlaceholder: 'جست‌وجوی خبر و تحلیل...', defaultLanguage: 'دری', breakingBar: true, watermark: true, versioning: true, autoShare: true, viewCountThreshold: 500, archiveDays: 20, visualRequired: true, sourceMonitorEnabled: true, forceArianaSource: true, primaryIntervalMinutes: 5, primaryItemsPerCycle: 3, secondarySourcesPerCycle: 1, secondaryHighItemsPerCycle: 3, secondaryNormalItemsPerCycle: 2, autoWordLimit: 1000, openverseFallback: true, bbcReplaceSourceImage: true
};
const roleLabels: Record<StaffRole, string> = { owner: 'مالک', manager: 'مدیر', deputy: 'معاون', editor: 'ویراستار', reporter: 'خبرنگار' };
const roleTabs: Record<StaffRole, AdminTab[]> = {
  owner: ['dashboard', 'articles', 'sources', 'socials', 'visitors', 'settings', 'staff'],
  manager: ['dashboard', 'articles', 'sources', 'socials', 'settings'],
  deputy: ['dashboard', 'articles', 'sources', 'socials'],
  editor: ['dashboard', 'articles'],
  reporter: ['dashboard', 'articles']
};
const emptyStaff: StaffForm = { email: '', name: '', role: 'reporter', active: true };
const canDeleteArticles = (role?: StaffRole) => Boolean(role && role !== 'reporter');
const VISITOR_SESSION_STORAGE_KEY='sayeh-visitor-session-v1';
const ANONYMOUS_VISITOR_ID_KEY='sayeh-anonymous-browser-v1';
const VISITOR_ENTRY_COUNTED_KEY='sayeh-entry-counted-v1';
const ONE_CLICK_ENTRY_COPY:Record<PublicLanguage,{title:string;button:string;error:string}>={'دری':{title:'برای ورود به خبرگزاری سایه، روی دکمهٔ زیر بزنید.',button:'ورود',error:'ورود انجام نشد؛ دوباره تلاش کنید.'},'پشتو':{title:'د سایه خبري اژانس ته د ننوتلو لپاره لاندې تڼۍ ووهئ.',button:'ننوتل',error:'ننوتل بشپړ نه شول؛ بیا هڅه وکړئ.'},English:{title:'Tap the button below to enter SAYEH NEWS.',button:'Enter',error:'Entry failed. Please try again.'},'عربی':{title:'اضغط الزر أدناه للدخول إلى SAYEH NEWS.',button:'دخول',error:'تعذر الدخول؛ حاول مرة أخرى.'},'اردو':{title:'SAYEH NEWS میں داخل ہونے کے لیے نیچے بٹن دبائیں۔',button:'داخل ہوں',error:'داخلہ مکمل نہ ہو سکا؛ دوبارہ کوشش کریں۔'},'ترکی':{title:'SAYEH NEWS’e girmek için aşağıdaki düğmeye dokunun.',button:'Giriş',error:'Giriş tamamlanamadı. Tekrar deneyin.'},'فرانسوی':{title:'Appuyez sur le bouton ci-dessous pour entrer dans SAYEH NEWS.',button:'Entrer',error:'L’entrée a échoué. Réessayez.'}};
type VisitorAuthStep='credentials'|'code';
type VisitorAuthCopy={title:string;email:string;password:string;send:string;codeTitle:string;code:string;verify:string;resend:string;change:string;emailError:string;passwordError:string;sent:string;sendError:string;verifyError:string};
const VISITOR_AUTH_COPY:Record<PublicLanguage,VisitorAuthCopy>={
'دری':{title:'برای ورود ایمیل آدرس تان را درج کنید',email:'ایمیل آدرس',password:'پسورد',send:'ارسال کد تأیید',codeTitle:'کد تأیید ارسال‌شده به ایمیل تان را درج کنید',code:'کد ۶ رقمی',verify:'تأیید و ورود',resend:'ارسال دوباره کد',change:'تغییر ایمیل',emailError:'ایمیل آدرس معتبر درج کنید.',passwordError:'پسورد باید حداقل ۸ کاراکتر باشد.',sent:'کد تأیید به ایمیل تان ارسال شد.',sendError:'ارسال کد به ایمیل انجام نشد؛ دوباره تلاش کنید.',verifyError:'کد تأیید درست نیست یا اعتبار آن پایان یافته است.'},
'پشتو':{title:'د ننوتلو لپاره خپل ایمیل ادرس ولیکئ',email:'ایمیل ادرس',password:'پاسورډ',send:'د تایید کوډ ولېږئ',codeTitle:'خپل ایمیل ته لېږل شوی تایید کوډ ولیکئ',code:'۶ عددي کوډ',verify:'تایید او ننوتل',resend:'کوډ بیا ولېږئ',change:'ایمیل بدل کړئ',emailError:'سم ایمیل ادرس ولیکئ.',passwordError:'پاسورډ باید لږ تر لږه ۸ توري ولري.',sent:'تایید کوډ ستاسو ایمیل ته ولېږل شو.',sendError:'کوډ ایمیل ته ونه لېږل شو؛ بیا هڅه وکړئ.',verifyError:'تایید کوډ ناسم دی یا وخت یې پای ته رسېدلی.'},
English:{title:'Enter your email address to sign in',email:'Email address',password:'Password',send:'Send verification code',codeTitle:'Enter the verification code sent to your email',code:'6-digit code',verify:'Verify and sign in',resend:'Send code again',change:'Change email',emailError:'Enter a valid email address.',passwordError:'Password must be at least 8 characters.',sent:'A verification code was sent to your email.',sendError:'The code could not be sent. Try again.',verifyError:'The verification code is incorrect or expired.'},
'عربی':{title:'أدخل عنوان بريدك الإلكتروني للدخول',email:'البريد الإلكتروني',password:'كلمة المرور',send:'إرسال رمز التحقق',codeTitle:'أدخل رمز التحقق المرسل إلى بريدك الإلكتروني',code:'رمز من 6 أرقام',verify:'تحقق وادخل',resend:'إعادة إرسال الرمز',change:'تغيير البريد',emailError:'أدخل بريداً إلكترونياً صحيحاً.',passwordError:'يجب أن تتكون كلمة المرور من 8 أحرف على الأقل.',sent:'تم إرسال رمز التحقق إلى بريدك الإلكتروني.',sendError:'تعذر إرسال الرمز. حاول مرة أخرى.',verifyError:'رمز التحقق غير صحيح أو منتهي الصلاحية.'},
'اردو':{title:'داخل ہونے کے لیے اپنا ای میل ایڈریس درج کریں',email:'ای میل ایڈریس',password:'پاس ورڈ',send:'تصدیقی کوڈ بھیجیں',codeTitle:'اپنے ای میل پر بھیجا گیا تصدیقی کوڈ درج کریں',code:'6 ہندسوں کا کوڈ',verify:'تصدیق اور داخل ہوں',resend:'کوڈ دوبارہ بھیجیں',change:'ای میل تبدیل کریں',emailError:'درست ای میل ایڈریس درج کریں۔',passwordError:'پاس ورڈ کم از کم 8 حروف کا ہونا چاہیے۔',sent:'تصدیقی کوڈ آپ کے ای میل پر بھیج دیا گیا۔',sendError:'کوڈ نہیں بھیجا جا سکا؛ دوبارہ کوشش کریں۔',verifyError:'تصدیقی کوڈ غلط ہے یا اس کی مدت ختم ہو گئی ہے۔'},
'ترکی':{title:'Giriş yapmak için e-posta adresinizi girin',email:'E-posta adresi',password:'Parola',send:'Doğrulama kodu gönder',codeTitle:'E-postanıza gönderilen doğrulama kodunu girin',code:'6 haneli kod',verify:'Doğrula ve giriş yap',resend:'Kodu tekrar gönder',change:'E-postayı değiştir',emailError:'Geçerli bir e-posta adresi girin.',passwordError:'Parola en az 8 karakter olmalıdır.',sent:'Doğrulama kodu e-postanıza gönderildi.',sendError:'Kod gönderilemedi. Tekrar deneyin.',verifyError:'Doğrulama kodu yanlış veya süresi dolmuş.'},
'فرانسوی':{title:'Saisissez votre adresse e-mail pour vous connecter',email:'Adresse e-mail',password:'Mot de passe',send:'Envoyer le code de vérification',codeTitle:'Saisissez le code envoyé à votre e-mail',code:'Code à 6 chiffres',verify:'Vérifier et se connecter',resend:'Renvoyer le code',change:'Changer l’e-mail',emailError:'Saisissez une adresse e-mail valide.',passwordError:'Le mot de passe doit contenir au moins 8 caractères.',sent:'Un code de vérification a été envoyé à votre e-mail.',sendError:'Le code n’a pas pu être envoyé. Réessayez.',verifyError:'Le code de vérification est incorrect ou expiré.'}
};

type RecoveryMessages={translationFailed:string;retry:string;loginTimedOut:string;declinedTitle:string;declinedBody:string;back:string};
const RECOVERY_MESSAGES:Record<PublicLanguage,RecoveryMessages>={
  'دری':{translationFailed:'ترجمه تکمیل نشد؛ دوباره تلاش کنید.',retry:'تلاش دوباره',loginTimedOut:'زمان ورود پایان یافت؛ دوباره تلاش کنید.',declinedTitle:'ورود لغو شد',declinedBody:'تا زمانی که با ایمیل وارد نشوید، هیچ مطلبی نمایش داده نمی‌شود.',back:'بازگشت'},
  'پشتو':{translationFailed:'ژباړه بشپړه نه شوه؛ بیا هڅه وکړئ.',retry:'بیا هڅه',loginTimedOut:'د ننوتلو وخت پای ته ورسېد؛ بیا هڅه وکړئ.',declinedTitle:'ننوتل لغوه شول',declinedBody:'تر ایمیل ننوتلو پورې هېڅ مطلب نه ښودل کېږي.',back:'بېرته'},
  English:{translationFailed:'Translation could not be completed. Please retry.',retry:'Retry',loginTimedOut:'Sign-in timed out. Please try again.',declinedTitle:'Sign-in cancelled',declinedBody:'No newsroom content is shown until you sign in with email.',back:'Go back'},
  'عربی':{translationFailed:'لم تكتمل الترجمة؛ حاول مجدداً.',retry:'إعادة المحاولة',loginTimedOut:'انتهت مهلة تسجيل الدخول؛ حاول مجدداً.',declinedTitle:'أُلغي تسجيل الدخول',declinedBody:'لن يُعرض أي محتوى قبل تسجيل الدخول بالبريد الإلكتروني.',back:'رجوع'},
  'اردو':{translationFailed:'ترجمہ مکمل نہیں ہو سکا؛ دوبارہ کوشش کریں۔',retry:'دوبارہ کوشش',loginTimedOut:'سائن اِن کا وقت ختم ہو گیا؛ دوبارہ کوشش کریں۔',declinedTitle:'سائن اِن منسوخ ہو گیا',declinedBody:'ای میل سے سائن اِن ہونے تک کوئی خبر نہیں دکھائی جائے گی۔',back:'واپس'},
  'ترکی':{translationFailed:'Çeviri tamamlanamadı. Yeniden deneyin.',retry:'Yeniden dene',loginTimedOut:'Oturum açma süresi doldu. Yeniden deneyin.',declinedTitle:'Giriş iptal edildi',declinedBody:'E-posta ile giriş yapana kadar hiçbir içerik gösterilmez.',back:'Geri dön'},
  'فرانسوی':{translationFailed:'La traduction a échoué. Réessayez.',retry:'Réessayer',loginTimedOut:'Le délai de connexion a expiré. Réessayez.',declinedTitle:'Connexion annulée',declinedBody:'Aucun contenu ne s’affiche avant la connexion par e-mail.',back:'Retour'}
};

const dateText = (value?: number) => value
  ? new Intl.DateTimeFormat('fa-AF', { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
  : 'هنوز ثبت نشده';
const sourceErrorText = (value?: string) => { const raw = (value || '').trim(); if (!raw) return ''; if (/timeout|timed out|TimeoutError|AbortError/i.test(raw)) return `مهلت پاسخ منبع پایان یافت — ${raw}`; if (/fetch_403/i.test(raw)) return `منبع دسترسی خودکار را رد کرد — ${raw}`; if (/fetch_404/i.test(raw)) return `نشانی منبع پیدا نشد — ${raw}`; if (/fetch_429/i.test(raw)) return `منبع موقتاً درخواست‌های زیاد را محدود کرده — ${raw}`; return raw; };

const statusText = (status: NewsStatus) => ({
  draft: 'پیش‌نویس', review: 'در انتظار تأیید مدیر', scheduled: 'زمان‌بندی‌شده', published: 'نشرشده'
})[status];

const fileToPayload = async (file: File): Promise<MediaPayload> => {
  if (file.size > 6 * 1024 * 1024) throw new Error('فایل باید کمتر از ۶ مگابایت باشد.');
  if (file.type.startsWith('image/')) {
    const prepared = await image.resizeIfNeeded(file, { maxDimension: 1600, maxPixels: 2_000_000, quality: 0.82, mimeType: 'image/webp' });
    return { data: prepared.data, mimeType: prepared.mimeType, name: file.name };
  }
  const data = await new Promise<string>((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result || '').split(',').pop() || '');
    reader.onerror = () => reject(new Error('خواندن فایل انجام نشد.'));
    reader.readAsDataURL(file);
  });
  return { data, mimeType: file.type || 'application/octet-stream', name: file.name };
};

const RICH_BODY_MARKER = '<!--sayeh-rich-v1-->';
const isRichBody = (value: string) => value.startsWith(RICH_BODY_MARKER);
const escapeHtml = (value: string) => { const element = document.createElement('div'); element.textContent = value; return element.innerHTML; };
const sanitizeRichHtml = (value: string) => { const doc = new DOMParser().parseFromString(`<div id='sayeh-rich-root'>${value}</div>`, 'text/html'); const root = doc.getElementById('sayeh-rich-root'); if (!root) return ''; const allowed = new Set(['B','STRONG','I','EM','U','FONT','DIV','P','BR','SPAN']); const blocked = new Set(['SCRIPT','STYLE','IFRAME','OBJECT','EMBED','LINK','META','IMG','VIDEO','AUDIO','FORM','INPUT','BUTTON','SVG','MATH']); Array.from(root.querySelectorAll('*')).reverse().forEach((node) => { const tag = node.tagName.toUpperCase(); if (blocked.has(tag)) { node.remove(); return; } if (!allowed.has(tag)) { node.replaceWith(...Array.from(node.childNodes)); return; } const face = tag === 'FONT' ? node.getAttribute('face') || '' : ''; const size = tag === 'FONT' ? node.getAttribute('size') || '' : ''; const style = node instanceof HTMLElement ? node.style : undefined; const fontFamily = style?.fontFamily || ''; const fontSize = style?.fontSize || ''; const fontWeight = style?.fontWeight || ''; const fontStyle = style?.fontStyle || ''; const textDecoration = style?.textDecoration || ''; Array.from(node.attributes).forEach((attribute) => node.removeAttribute(attribute.name)); if (tag === 'FONT' && /^[\w\s,'-]{1,80}$/.test(face)) node.setAttribute('face', face); if (tag === 'FONT' && /^[1-7]$/.test(size)) node.setAttribute('size', size); const styles = [fontFamily ? `font-family:${fontFamily}` : '', fontSize ? `font-size:${fontSize}` : '', fontWeight ? `font-weight:${fontWeight}` : '', fontStyle ? `font-style:${fontStyle}` : '', textDecoration ? `text-decoration:${textDecoration}` : ''].filter(Boolean); if (styles.length) node.setAttribute('style', styles.join(';')); }); return root.innerHTML.trim(); };
const editorHtmlFromStoredBody = (value: string) => isRichBody(value) ? sanitizeRichHtml(value.slice(RICH_BODY_MARKER.length)) : escapeHtml(value).replace(/\n/g, '<br>');
const storedRichHtml = (value: string) => sanitizeRichHtml(value.slice(RICH_BODY_MARKER.length));
const richEditorPlainText = (value: string) => { const doc = new DOMParser().parseFromString(`<div>${value}</div>`, 'text/html'); return (doc.body.textContent || '').replace(/\u00a0/g, ' ').replace(/\s+/g, ' ').trim(); };
const StructuredPlainBody=({value}:{value:string})=>{const blocks=value.replace(/\r\n?/g,'\n').split(/\n{2,}/).map((block)=>block.trim()).filter(Boolean);return <>{blocks.map((block,index)=>{const lines=block.split('\n').map((entry)=>entry.trim()).filter(Boolean);const heading=lines[0]?.match(/^#{1,6}\s+(.+)$/);if(heading){const detail=lines.slice(1).join(' ').trim();return <section key={`body-section-${index}`} className='mb-6 last:mb-0'><h2 className='mb-3 mt-8 text-xl font-black leading-9 text-zinc-950 first:mt-0'>{heading[1].trim()}</h2>{detail&&<p className='mb-5 last:mb-0'>{detail}</p>}</section>;}return <p key={`body-paragraph-${index}`} className='mb-5 last:mb-0'>{lines.join(' ')}</p>;})}</>;};
const RichTextEditor = ({ value, onChange }: { value: string; onChange: (value: string) => void }) => { const editorRef = useRef<HTMLDivElement | null>(null); const rangeRef = useRef<Range | null>(null); useEffect(() => { const editor = editorRef.current; if (editor && editor.innerHTML !== value) editor.innerHTML = value; }, [value]); const rememberSelection = () => { const editor = editorRef.current; const selection = window.getSelection(); if (!editor || !selection?.rangeCount) return; const range = selection.getRangeAt(0); if (editor.contains(range.commonAncestorContainer)) rangeRef.current = range.cloneRange(); }; const applyCommand = (command: string, argument?: string) => { const editor = editorRef.current; if (!editor) return; editor.focus(); const selection = window.getSelection(); if (selection && rangeRef.current) { selection.removeAllRanges(); selection.addRange(rangeRef.current); } document.execCommand(command, false, argument); rememberSelection(); onChange(editor.innerHTML); }; return <div className='overflow-hidden rounded-2xl border bg-white focus-within:border-emerald-700'><div className='flex flex-wrap items-center gap-2 border-b bg-zinc-50 p-2'><button type='button' aria-label='بولد متن' onMouseDown={(event) => { event.preventDefault(); applyCommand('bold'); }} className='rounded-lg border bg-white px-3 py-2 font-black'>B</button><select aria-label='فونت متن' defaultValue='' onMouseDown={rememberSelection} onChange={(event) => { if (event.target.value) applyCommand('fontName', event.target.value); event.target.value = ''; }} className='rounded-lg border bg-white px-2 py-2 text-sm'><option value='' disabled>فونت</option><option value='Tahoma'>Tahoma</option><option value='Arial'>Arial</option><option value='Georgia'>Georgia</option><option value='Times New Roman'>Times New Roman</option></select><select aria-label='اندازه متن' defaultValue='' onMouseDown={rememberSelection} onChange={(event) => { if (event.target.value) applyCommand('fontSize', event.target.value); event.target.value = ''; }} className='rounded-lg border bg-white px-2 py-2 text-sm'><option value='' disabled>اندازه</option><option value='2'>۱۳</option><option value='3'>۱۶</option><option value='4'>۱۸</option><option value='5'>۲۴</option><option value='6'>۳۲</option></select></div><div ref={editorRef} dir='rtl' contentEditable suppressContentEditableWarning role='textbox' aria-label='متن کامل مطلب' aria-multiline='true' onInput={(event) => { rememberSelection(); onChange(event.currentTarget.innerHTML); }} onKeyUp={rememberSelection} onMouseUp={rememberSelection} onBlur={rememberSelection} className='min-h-52 w-full px-4 py-3 text-right leading-8 outline-none' /></div>; };

const Empty = ({ text }: { text: string }) => (
  <div className='rounded-3xl border border-dashed border-zinc-300 bg-white/80 p-10 text-center text-sm text-zinc-500'>{text}</div>
);

function AppContent() {
  const [news, setNews] = useState<NewsItem[]>(() => {
    try {
      const cached = sessionStorage.getItem('sayeh-news-cache-clean-v5');
      const parsed = cached ? JSON.parse(cached) : [];
      return Array.isArray(parsed) ? parsed as NewsItem[] : [];
    } catch {
      return [];
    }
  });
  const [archiveNews, setArchiveNews] = useState<NewsItem[]>([]);
  const [archiveHasMore, setArchiveHasMore] = useState(false);
  const [archiveLoading, setArchiveLoading] = useState(false);
  const [recentNews, setRecentNews] = useState<NewsItem[]>([]);
  const [recentNewsHasMore, setRecentNewsHasMore] = useState(false);
  const [recentNewsLoading, setRecentNewsLoading] = useState(false);
  const [analysisNews, setAnalysisNews] = useState<NewsItem[]>([]);
  const [analysisHasMore, setAnalysisHasMore] = useState(false);
  const [analysisLoading, setAnalysisLoading] = useState(false);
  const [englishNews, setEnglishNews] = useState<NewsItem[]>([]);
  const [englishHasMore, setEnglishHasMore] = useState(false);
  const [englishLoading, setEnglishLoading] = useState(false);
  const [sources, setSources] = useState<Source[]>([]);
  const [socials, setSocials] = useState<Social[]>([]);
  const [settingsData, setSettingsData] = useState<AppSettings>(defaultSettings);
  const [view, setView] = useState<'public' | 'admin'>('public');
  const [publicTab, setPublicTab] = useState<'home' | 'news' | 'analysis' | 'archive' | 'english'>('home');
  const [adminTab, setAdminTab] = useState<AdminTab>('dashboard');
  const [selected, setSelected] = useState<NewsItem | null>(null);
  const [search, setSearch] = useState('');
  const [searchResults, setSearchResults] = useState<NewsItem[]>([]);
  const [searchLoading, setSearchLoading] = useState(false);
  const [publicLanguage, setPublicLanguage] = useState<PublicLanguage>(() => { const saved=localStorage.getItem(PUBLIC_LANGUAGE_STORAGE_KEY)||''; return isPublicLanguage(saved)?saved:'دری'; });
  const [localizedNews, setLocalizedNews] = useState<Record<string, NewsItem>>({});

  const [toast, setToast] = useState('');
  const [busy, setBusy] = useState(false);
  const [logoFailed, setLogoFailed] = useState(false);
  const [articleForm, setArticleForm] = useState<ArticleForm>(emptyArticle);
  const [editingArticleId, setEditingArticleId] = useState('');
  const [articleFile, setArticleFile] = useState<File | null>(null);
  const [settingsLogoFile, setSettingsLogoFile] = useState<File | null>(null);
  const [sourceForm, setSourceForm] = useState<SourceForm>(emptySource);
  const [editingSourceId, setEditingSourceId] = useState('');
  const [socialForm, setSocialForm] = useState<SocialForm>(emptySocial);
  const [adminSession, setAdminSession] = useState<AdminSession | null>(null);
  const [visitorSession, setVisitorSession] = useState<VisitorSession | null>(null);
  const [visitorSessionToken, setVisitorSessionToken] = useState(()=>sessionStorage.getItem(VISITOR_SESSION_STORAGE_KEY)||'');
  const [visitorAuthConfigured, setVisitorAuthConfigured] = useState(false);
  const [visitorAuthStep, setVisitorAuthStep] = useState<VisitorAuthStep>('credentials');
  const [visitorEmail, setVisitorEmail] = useState('');
  const [visitorPassword, setVisitorPassword] = useState('');
  const [visitorCode, setVisitorCode] = useState('');
  const [visitorChallenge, setVisitorChallenge] = useState('');
  const [visitorAuthChecked, setVisitorAuthChecked] = useState(false);
  const [visitorError, setVisitorError] = useState('');
  const [entryDeclined, setEntryDeclined] = useState(false);
  const [localizationError, setLocalizationError] = useState('');
  const [localizationBusy, setLocalizationBusy] = useState(false);
  const [visitorAnalytics, setVisitorAnalytics] = useState<VisitorAnalytics | null>(null);
  const [authBusy, setAuthBusy] = useState(false);
  const [loginOpen, setLoginOpen] = useState(false);
  const [loginError, setLoginError] = useState('');
  const [staff, setStaff] = useState<StaffMember[]>([]);
  const [staffForm, setStaffForm] = useState<StaffForm>(emptyStaff);
  const [editingStaffId, setEditingStaffId] = useState('');
  const [installPrompt, setInstallPrompt] = useState<BeforeInstallPromptEvent | null>(null);
  const [installHelp, setInstallHelp] = useState(false);
  const [isInstalled, setIsInstalled] = useState(false);
  const socketRef = useRef<ReturnType<typeof ws.connect> | null>(null);
  const storyDialogRef = useRef<HTMLElement | null>(null);
  const searchRequestRef = useRef(0);
  const authAttemptRef = useRef(0);
  const ownerTapCountRef = useRef(0);
  const ownerTapTimerRef = useRef<number | null>(null);
  const [qaBoundaryBusy, setQaBoundaryBusy] = useState(false);
  const [qaBoundaryResult, setQaBoundaryResult] = useState('');
  const qaBoundaryRun = new URLSearchParams(window.location.search).has('___keys_prefix');
  const t=PUBLIC_UI[publicLanguage]; const recovery=RECOVERY_MESSAGES[publicLanguage]; const publicMeta=PUBLIC_LANGUAGE_META[publicLanguage]; const publicDir=publicMeta.dir; const publicDateText=(value?:number)=>value?new Intl.DateTimeFormat(publicMeta.locale,{dateStyle:'medium',timeStyle:'short'}).format(new Date(value)):t.notRecorded; const publicSetting=(dariValue:string,translatedValue:string)=>publicLanguage==='دری'?dariValue:translatedValue; const localizedKey=(id:string,language:PublicLanguage=publicLanguage)=>`${language}:${id}`; const itemForLanguage=(item:NewsItem,language:PublicLanguage)=>{if(item.language===language)return item;const saved=localizedNews[localizedKey(item.id,language)];if(saved)return saved;const pending=localizationError?RECOVERY_MESSAGES[language].translationFailed:PUBLIC_UI[language].loading;return{...item,title:pending,summary:pending,body:pending,category:pending,language};}; const displayItem=(item:NewsItem)=>itemForLanguage(item,publicLanguage); const languageLabel=(language:string)=>isPublicLanguage(language)?PUBLIC_LANGUAGE_META[language].label:language;
  const pendingApprovalItems = news.filter((item) => item.status === 'review').sort((a, b) => (b.updatedAt || b.createdAt || 0) - (a.updatedAt || a.createdAt || 0));
  const editingApprovalItem = editingArticleId ? pendingApprovalItems.find((item) => item.id === editingArticleId) : undefined;
  const orderedAdminNews = [...news].sort((a, b) => Number(b.status === 'review') - Number(a.status === 'review') || (b.updatedAt || b.createdAt || 0) - (a.updatedAt || a.createdAt || 0));

  const flash = (message: string) => {
    setToast(message);
    window.setTimeout(() => setToast(''), 2800);
  };

  const mergeNews = (current: NewsItem[], incoming: NewsItem[]) => {
    const map = new Map(current.map((item) => [item.id, item]));
    incoming.forEach((item) => {
      const existing = map.get(item.id);
      if (!existing || (item.updatedAt || item.createdAt || 0) >= (existing.updatedAt || existing.createdAt || 0)) map.set(item.id, item);
    });
    return [...map.values()].sort((a, b) => (b.updatedAt || b.createdAt || 0) - (a.updatedAt || a.createdAt || 0));
  };
  const loadNews = async () => {
    const { data } = await api.get('/api/admin/news');
    const items = (data.items || []) as NewsItem[];
    setNews(items);
    return items;
  };
  const loadPublicNews = async (token=visitorSessionToken) => {
    const { data } = token ? await api.post('/api/public/news',{sessionToken:token}) : await api.get('/api/news');
    const publishedItems = (data.items || []) as NewsItem[];
    setNews(publishedItems);
    return publishedItems;
  };
  const loadRecentNews = async (reset = false) => { if (recentNewsLoading) return; setRecentNewsLoading(true); try { const offset = reset ? 0 : recentNews.length; const { data } = visitorSessionToken ? await api.post('/api/public/news-page',{sessionToken:visitorSessionToken,offset,limit:24}) : await api.get(`/api/news-page?offset=${offset}&limit=24`); const items = (data.items || []) as NewsItem[]; setRecentNews((current) => reset ? items : mergeNews(current, items)); setRecentNewsHasMore(Boolean(data.hasMore)); } catch { flash(t.infoLoadError); } finally { setRecentNewsLoading(false); } };
  const loadEnglish = async (reset = false) => { if (englishLoading) return; setEnglishLoading(true); try { const offset = reset ? 0 : englishNews.length; const { data } = visitorSessionToken ? await api.post('/api/public/english',{sessionToken:visitorSessionToken,offset,limit:24}) : await api.get(`/api/english?offset=${offset}&limit=24`); const items = (data.items || []) as NewsItem[]; setEnglishNews((current) => reset ? items : mergeNews(current, items)); setEnglishHasMore(Boolean(data.hasMore)); } catch { flash(t.infoLoadError); } finally { setEnglishLoading(false); } };
  const loadAnalysis = async (reset = false) => {
    if (analysisLoading) return;
    setAnalysisLoading(true);
    try {
      const offset = reset ? 0 : analysisNews.length;
      const { data } = visitorSessionToken ? await api.post('/api/public/analysis',{sessionToken:visitorSessionToken,offset,limit:24}) : await api.get(`/api/analysis?offset=${offset}&limit=24`);
      const items = (data.items || []) as NewsItem[];
      setAnalysisNews((current) => reset ? items : mergeNews(current, items));
      setAnalysisHasMore(Boolean(data.hasMore));
    } catch {
      flash(t.infoLoadError);
    } finally {
      setAnalysisLoading(false);
    }
  };
  const loadArchive = async (reset = false) => {
    if (archiveLoading) return;
    setArchiveLoading(true);
    try {
      const offset = reset ? 0 : archiveNews.length;
      const { data } = visitorSessionToken ? await api.post('/api/public/archive',{sessionToken:visitorSessionToken,offset,limit:24}) : await api.get(`/api/archive?offset=${offset}&limit=24`);
      const items = (data.items || []) as NewsItem[];
      setArchiveNews((current) => reset ? items : mergeNews(current, items));
      setArchiveHasMore(Boolean(data.hasMore));
    } catch {
      flash(t.infoLoadError);
    } finally {
      setArchiveLoading(false);
    }
  };
  const loadSources = async () => {
    const { data } = await api.get('/api/sources');
    setSources(data.items || []);
  };
  const loadSocials = async () => {
    const { data } = await api.get('/api/socials');
    setSocials(data.items || []);
  };
  const loadSettings = async (token=visitorSessionToken,admin=false) => { const { data }=token&&!admin?await api.post('/api/public/settings',{sessionToken:token}):await api.get('/api/settings'); const next={...defaultSettings,...data} as AppSettings; setSettingsData(next); if (!localStorage.getItem(PUBLIC_LANGUAGE_STORAGE_KEY)&&isPublicLanguage(next.defaultLanguage)) setPublicLanguage(next.defaultLanguage); };
  const requestLocalizedItems=async(items:NewsItem[],detail=false,targetLanguage:PublicLanguage=publicLanguage)=>{const needs=items.filter((item)=>item.language!==targetLanguage&&(detail||!localizedNews[localizedKey(item.id,targetLanguage)]));if(!needs.length)return items.map((item)=>itemForLanguage(item,targetLanguage));setLocalizationBusy(true);const translated:NewsItem[]=[];let failed=false;try{const batchSize=detail?1:8;for(let offset=0;offset<needs.length;offset+=batchSize){const chunk=needs.slice(offset,offset+batchSize);try{const {data}=visitorSessionToken?await api.post('/api/public/news/localize',{sessionToken:visitorSessionToken,ids:chunk.map((item)=>item.id),language:targetLanguage,detail}):await api.post('/api/news/localize',{ids:chunk.map((item)=>item.id),language:targetLanguage,detail});const batch=((data.items||[]) as NewsItem[]).filter((item)=>item.language===targetLanguage);translated.push(...batch);if(chunk.some((item)=>!batch.some((entry)=>entry.id===item.id)))failed=true;}catch{failed=true;break;}}if(translated.length)setLocalizedNews((current)=>{const next={...current};translated.forEach((item)=>{next[`${targetLanguage}:${item.id}`]=item;});return next;});const responseMap=new Map(translated.map((item)=>[item.id,item]));const unresolved=needs.some((item)=>!responseMap.has(item.id)&&!localizedNews[localizedKey(item.id,targetLanguage)]);if(failed||unresolved)setLocalizationError(RECOVERY_MESSAGES[targetLanguage].translationFailed);return items.map((item)=>responseMap.get(item.id)||localizedNews[localizedKey(item.id,targetLanguage)]||itemForLanguage(item,targetLanguage));}finally{setLocalizationBusy(false);}};
  const switchPublicLanguage=(language:PublicLanguage)=>{setLocalizationError('');setPublicLanguage(language);};  const runQaBoundaryCheck=async()=>{setQaBoundaryBusy(true);setQaBoundaryResult('Exercising backend routes…');const target='__qa_missing__';const calls:Array<[string,string]>=[['GET','/rss.xml'],['GET','/facebook-rss.xml'],['GET','/facebook-video-rss.xml'],['GET','/facebook-video-rss-v2.xml?refresh=1'],['GET','/video-desk/diagnostics'],['GET',`/video-desk/file/${target}`],['GET',`/rss-image/${target}`],['GET',`/share/${target}`],['GET','/video-desk/pending?probe=1'],['POST','/video-desk/callback'],['POST','/video-desk/facebook-confirm'],['GET','/api/visitor-auth/status'],['POST','/api/visitor-auth/start'],['POST','/api/visitor-auth/verify'],['POST','/api/visitor-auth/session'],['POST','/api/visitor-auth/signout'],['POST','/api/visitor-auth/anonymous'],['POST','/api/public/visitor/click'],['POST','/api/public/bootstrap'],['POST','/api/public/news'],['POST','/api/public/search'],['POST','/api/public/news-page'],['POST','/api/public/english'],['POST','/api/public/analysis'],['POST','/api/public/archive'],['POST','/api/public/news/localize'],['POST',`/api/public/news/${target}`],['POST',`/api/public/news/${target}/view`],['POST','/api/public/settings'],['POST','/api/public/visitor/enter'],['POST','/api/public/visitor/event'],['POST','/api/public/news-subscription'],['DELETE',`/api/news/${target}`],['DELETE',`/api/news/${target}/permanent`],['DELETE',`/api/socials/${target}`],['DELETE',`/api/sources/${target}`],['DELETE',`/api/staff/${target}`],['GET','/api/admin/me'],['GET','/api/admin/news'],['GET','/api/analysis'],['GET','/api/archive'],['GET','/api/english'],['GET','/api/news'],['GET','/api/news-page'],['GET',`/api/news/${target}`],['GET','/api/owner/visitors'],['GET','/api/settings'],['GET','/api/socials'],['GET','/api/sources'],['GET','/api/staff'],['POST','/api/admin/bootstrap'],['POST','/api/news'],['POST','/api/news-subscription'],['POST',`/api/news/${target}/restore`],['POST',`/api/news/${target}/view`],['POST','/api/news/localize'],['POST','/api/settings'],['POST','/api/socials'],['POST','/api/sources'],['POST',`/api/sources/${target}/ingest`],['POST','/api/staff'],['POST','/api/visitor/enter'],['POST','/api/visitor/event'],['PUT',`/api/news/${target}`],['PUT',`/api/sources/${target}`],['PUT',`/api/staff/${target}`]];const results=await Promise.allSettled(calls.map(([method,path])=>method==='GET'?api.get(path):method==='POST'?api.post(path,{}):method==='PUT'?api.put(path,{}):api.delete(path)));const diagnosticsResult=results[4];const pendingResult=results[8];let videoDiag:unknown=null;let pendingJob:unknown=null;if(diagnosticsResult.status==='fulfilled')videoDiag=(diagnosticsResult.value as {data?:unknown}).data||null;if(pendingResult.status==='fulfilled')pendingJob=(pendingResult.value as {data?:{job?:unknown}}).data?.job||null;setQaBoundaryResult(`Routes exercised: ${calls.length}/${calls.length} | VIDEO_DIAG ${JSON.stringify(videoDiag)} | PENDING_JOB ${JSON.stringify(pendingJob)}`);setQaBoundaryBusy(false);};
  const invalidateLocalizedItem=(id:string)=>setLocalizedNews((current)=>{const next={...current};PUBLIC_LANGUAGES.forEach((language)=>delete next[`${language}:${id}`]);return next;});
  const loadStaff = async () => {
    const { data } = await api.get('/api/staff');
    setStaff((data.items || []) as StaffMember[]);
  };
  const loadVisitorAnalytics = async () => { const { data } = await api.get('/api/owner/visitors'); setVisitorAnalytics(data as VisitorAnalytics); };

  useEffect(() => {
    let mounted=true;let connection:ReturnType<typeof ws.connect>|null=null;let customToken='';
    const attachRealtime=(token='')=>{connection=ws.connect();socketRef.current=connection;connection.onMessage((message)=>{const payload=message?.payload;if(payload?.entity_type!=='news'||payload?.entity_id!=='feed')return;const update=payload.data as {action?:string;id?:string;item?:NewsItem;items?:NewsItem[]};if(update.id)invalidateLocalizedItem(update.id);if(update.item?.id)invalidateLocalizedItem(update.item.id);if(Array.isArray(update.items))update.items.forEach((item)=>invalidateLocalizedItem(item.id));if(update.action==='delete'&&update.id)setNews((current)=>current.filter((item)=>item.id!==update.id));if(update.action==='archive'&&update.item&&update.item.section!=='analysis')setArchiveNews((current)=>mergeNews(current,[update.item as NewsItem]));if(update.action==='restore'&&update.id)setArchiveNews((current)=>current.filter((item)=>item.id!==update.id));if(update.item?.section==='analysis')setAnalysisNews((current)=>mergeNews(current,[update.item as NewsItem]));if(update.item?.section==='news'&&update.item.status==='published')setRecentNews((current)=>mergeNews(current,[update.item as NewsItem]));if(update.item)setNews((current)=>mergeNews(current,[update.item as NewsItem]));if(Array.isArray(update.items))setNews((current)=>mergeNews(current,update.items||[]));});connection.ready.then(()=>{if(!mounted||!connection?.connectionId)return;const payload={connection_id:connection.connectionId};if(token)api.post('/api/public/news-subscription',{...payload,sessionToken:token}).catch(()=>undefined);else api.post('/api/news-subscription',payload).catch(()=>undefined);}).catch(()=>undefined);};
    const initialize=async()=>{const stored=sessionStorage.getItem(VISITOR_SESSION_STORAGE_KEY)||'';if(!stored){if(mounted)setVisitorAuthChecked(true);return;}try{const countEntry=sessionStorage.getItem(VISITOR_ENTRY_COUNTED_KEY)!==stored;const {data}=await api.post('/api/public/bootstrap',{sessionToken:stored,countEntry});if(!mounted)return;customToken=stored;setVisitorSessionToken(stored);setVisitorSession((data as {visitor:VisitorSession}).visitor);setVisitorAuthChecked(true);const publishedItems=((data.items||[]) as NewsItem[]);setNews(publishedItems);const next={...defaultSettings,...(data.settings||{})} as AppSettings;setSettingsData(next);if(!localStorage.getItem(PUBLIC_LANGUAGE_STORAGE_KEY)&&isPublicLanguage(next.defaultLanguage))setPublicLanguage(next.defaultLanguage);if(countEntry&&data.entryRecorded!==false)sessionStorage.setItem(VISITOR_ENTRY_COUNTED_KEY,stored);const sharedStoryId=window.location.hash.startsWith('#story=')?decodeURIComponent(window.location.hash.slice('#story='.length)):'';if(sharedStoryId){const shared=publishedItems.find((item)=>item.id===sharedStoryId)||(await api.post(`/api/public/news/${encodeURIComponent(sharedStoryId)}`,{sessionToken:stored})).data as NewsItem;const {data:viewed}=await api.post(`/api/public/news/${encodeURIComponent(shared.id)}/view`,{sessionToken:stored});if(mounted)setNews((current)=>mergeNews(current,[viewed as NewsItem]));}attachRealtime(stored);return;}catch{sessionStorage.removeItem(VISITOR_SESSION_STORAGE_KEY);sessionStorage.removeItem(VISITOR_ENTRY_COUNTED_KEY);if(mounted){setVisitorSessionToken('');setVisitorSession(null);setVisitorAuthChecked(true);}return;}};void initialize();return()=>{mounted=false;if(connection?.connectionId){const payload={connection_id:connection.connectionId,action:'remove'};if(customToken)api.post('/api/public/news-subscription',{...payload,sessionToken:customToken}).catch(()=>undefined);else api.post('/api/news-subscription',payload).catch(()=>undefined);}connection?.disconnect();socketRef.current=null;};
  }, []);

  useEffect(() => { if (!loginOpen&&window.location.hash==='#owner-login') window.history.replaceState(null,'',`${window.location.pathname}${window.location.search}`); },[loginOpen]);
  useEffect(()=>{localStorage.setItem(PUBLIC_LANGUAGE_STORAGE_KEY,publicLanguage);document.documentElement.dir=publicDir;document.documentElement.lang=publicMeta.htmlLang;document.title=publicSetting(settingsData.siteTitle,t.siteTitle);},[publicLanguage,publicDir,publicMeta.htmlLang,settingsData.siteTitle,t.siteTitle]);
  useEffect(()=>{if(!visitorSession)return;const collection=publicTab==='archive'?archiveNews:publicTab==='analysis'?analysisNews:publicTab==='english'?englishNews:publicTab==='news'?recentNews:news;void requestLocalizedItems(collection.filter((item)=>item.status==='published'),false,publicLanguage);},[visitorSession,publicLanguage,publicTab,news,recentNews,analysisNews,archiveNews,englishNews]);
  useEffect(()=>{if(!visitorSession||!selected)return;const all=[...news,...recentNews,...analysisNews,...archiveNews,...englishNews];const original=all.find((item)=>item.id===selected.id)||selected;if(original.language===publicLanguage){setSelected(original);return;}const shallow=itemForLanguage(original,publicLanguage);setSelected({...shallow,body:PUBLIC_UI[publicLanguage].loading,language:publicLanguage});void requestLocalizedItems([original],true,publicLanguage).then(([localized])=>{if(localized?.language===publicLanguage)setSelected((current)=>current?.id===original.id?localized:current);});},[publicLanguage]);
  useEffect(()=>{if(!selected)return;const previous=document.activeElement as HTMLElement|null;const previousOverflow=document.body.style.overflow;document.body.style.overflow='hidden';const focusTimer=window.setTimeout(()=>storyDialogRef.current?.focus(),0);const onKey=(event:KeyboardEvent)=>{if(event.key==='Escape'){event.preventDefault();setSelected(null);return;}if(event.key!=='Tab'||!storyDialogRef.current)return;const controls=Array.from(storyDialogRef.current.querySelectorAll<HTMLElement>('button,[href],input,select,textarea,[tabindex]:not([tabindex="-1"])')).filter((node)=>!node.hasAttribute('disabled'));if(!controls.length){event.preventDefault();storyDialogRef.current.focus();return;}const first=controls[0];const last=controls[controls.length-1];if(event.shiftKey&&document.activeElement===first){event.preventDefault();last.focus();}else if(!event.shiftKey&&document.activeElement===last){event.preventDefault();first.focus();}};window.addEventListener('keydown',onKey);return()=>{window.clearTimeout(focusTimer);window.removeEventListener('keydown',onKey);document.body.style.overflow=previousOverflow;previous?.focus();};},[selected?.id]);

  useEffect(() => {
    if (publicTab === 'news' && recentNews.length === 0 && !recentNewsLoading) void loadRecentNews(true);
    if (publicTab === 'analysis' && analysisNews.length === 0 && !analysisLoading) void loadAnalysis(true);
    if (publicTab === 'english' && englishNews.length === 0 && !englishLoading) void loadEnglish(true);
    if (publicTab === 'archive' && archiveNews.length === 0 && !archiveLoading) void loadArchive(true);
  }, [publicTab]);

  useEffect(()=>{const raw=search.trim();const requestId=++searchRequestRef.current;if(raw.length<2||!visitorSessionToken){setSearchResults([]);setSearchLoading(false);return;}setSearchLoading(true);const timer=window.setTimeout(()=>{api.post('/api/public/search',{sessionToken:visitorSessionToken,q:raw,limit:60}).then(({data})=>{if(requestId!==searchRequestRef.current)return;const items=((data.items||[]) as NewsItem[]);setSearchResults(items);setSearchLoading(false);if(items.length)void requestLocalizedItems(items,false,publicLanguage);}).catch(()=>{if(requestId!==searchRequestRef.current)return;setSearchResults([]);setSearchLoading(false);});},300);return()=>window.clearTimeout(timer);},[search,visitorSessionToken,publicLanguage]);

  useEffect(() => {
    const navigatorWithStandalone = navigator as Navigator & { standalone?: boolean };
    setIsInstalled(window.matchMedia('(display-mode: standalone)').matches || navigatorWithStandalone.standalone === true);
    const onInstallPrompt = (event: Event) => {
      event.preventDefault();
      setInstallPrompt(event as BeforeInstallPromptEvent);
      setInstallHelp(true);
    };
    const onInstalled = () => {
      setIsInstalled(true);
      setInstallPrompt(null);
      setInstallHelp(false);
      if(visitorSessionToken)void api.post('/api/public/visitor/event',{sessionToken:visitorSessionToken,event:'install_success',path:`${window.location.pathname}${window.location.hash}`}).catch(()=>undefined);else if (auth.isSignedIn()) void api.post('/api/visitor/event', { event: 'install_success', path: `${window.location.pathname}${window.location.hash}` }).catch(() => undefined);
      flash(t.installedMessage);
    };
    window.addEventListener('beforeinstallprompt', onInstallPrompt);
    window.addEventListener('appinstalled', onInstalled);
    return () => {
      window.removeEventListener('beforeinstallprompt', onInstallPrompt);
      window.removeEventListener('appinstalled', onInstalled);
    };
  }, []);

  useEffect(() => {
    try {
      sessionStorage.setItem('sayeh-news-cache-clean-v5', JSON.stringify(news.slice(0, 24)));
    } catch {
      // Browser cache is optional; server storage remains authoritative.
    }
  }, [news]);

  const publishedNews = useMemo(() => news
    .filter((item) => item.status === 'published' && !item.archived)
    .sort((a, b) => (b.publishedAt || b.updatedAt) - (a.publishedAt || a.updatedAt)), [news]);
  const recentPublishedNews = useMemo(() => recentNews.filter((item) => item.status === 'published' && item.section === 'news').sort((a, b) => (b.publishedAt || b.updatedAt) - (a.publishedAt || a.updatedAt)), [recentNews]);
  const permanentAnalysisNews = useMemo(() => analysisNews
    .filter((item) => item.status === 'published' && item.section === 'analysis')
    .sort((a, b) => (b.publishedAt || b.updatedAt) - (a.publishedAt || a.updatedAt)), [analysisNews]);
  const archivedNews = useMemo(() => archiveNews
    .filter((item) => item.status === 'published' && item.section !== 'analysis')
    .sort((a, b) => (b.publishedAt || b.updatedAt) - (a.publishedAt || a.updatedAt)), [archiveNews]);
  const englishPublishedNews = useMemo(() => englishNews.filter((item) => item.status === 'published' && item.language === 'English').sort((a, b) => (b.publishedAt || b.updatedAt) - (a.publishedAt || a.updatedAt)), [englishNews]);

  const searchActive=search.trim().length>=2;
  const visibleNews = useMemo(() => {
    const term = search.trim().toLowerCase();
    const sourceCollection=searchActive?searchResults:publicTab==='archive'?archivedNews:publicTab==='analysis'?permanentAnalysisNews:publicTab==='english'?englishPublishedNews:publicTab==='news'?recentPublishedNews:publishedNews; const collection=sourceCollection.map(displayItem);
    const filtered = collection.filter((item) => {
      if (!searchActive && publicTab === 'news' && item.section !== 'news') return false;
      if (!searchActive && publicTab === 'analysis' && item.section !== 'analysis') return false;
      if(searchActive)return true;
      return !term || `${item.title} ${item.summary} ${item.body} ${item.category}`.toLowerCase().includes(term);
    });
    if (publicTab !== 'home') return filtered;
    const videoRank = (item: NewsItem) => item.facebookVideoUrl || item.externalVideoUrl || item.mediaType === 'video' ? 1 : 0;
    return [...filtered].sort((a, b) => videoRank(a) - videoRank(b) || (b.publishedAt || b.updatedAt) - (a.publishedAt || a.updatedAt));
  }, [publishedNews,recentPublishedNews,permanentAnalysisNews,archivedNews,englishPublishedNews,searchResults,publicTab,publicLanguage,search,searchActive,localizedNews]);

  const hero=visibleNews[0]||(publishedNews[0]?displayItem(publishedNews[0]):undefined); const breakingOriginal=publishedNews.find((item)=>item.isBreaking); const breaking=breakingOriginal?displayItem(breakingOriginal):hero; const homeLatest=publishedNews.filter((item)=>item.status==='published'&&item.section==='news'&&item.id!==hero?.id).sort((a,b)=>(b.publishedAt||b.updatedAt)-(a.publishedAt||a.updatedAt)).slice(0,5).map(displayItem); const homeImportantAnalysis=permanentAnalysisNews.filter((item)=>item.id!==hero?.id).slice(0,3).map(displayItem); const homePromotedIds=new Set([...homeLatest,...homeImportantAnalysis].map((item)=>item.id)); const homeGridNews=publicTab==='home'&&!searchActive?visibleNews.filter((item)=>item.id!==hero?.id&&!homePromotedIds.has(item.id)):visibleNews; const homeSectionLabels:Record<PublicLanguage,{latest:string;analysis:string;latestKicker:string;analysisKicker:string}>={'دری':{latest:'آخرین مطالب',analysis:'تحلیل‌های مهم',latestKicker:'تازه از تحریریه',analysisKicker:'دیدگاه و بررسی'},'پشتو':{latest:'وروستي مطالب',analysis:'مهمې شننې',latestKicker:'له خبرخونې تازه',analysisKicker:'لید او شننه'},English:{latest:'Latest Stories',analysis:'Important Analysis',latestKicker:'Fresh from the newsroom',analysisKicker:'Insight & perspective'},'عربی':{latest:'أحدث المواد',analysis:'تحليلات مهمة',latestKicker:'جديد غرفة الأخبار',analysisKicker:'رؤية وتحليل'},'اردو':{latest:'تازہ ترین مضامین',analysis:'اہم تجزیے',latestKicker:'نیوز روم سے تازہ',analysisKicker:'رائے اور تجزیہ'},'ترکی':{latest:'Son İçerikler',analysis:'Önemli Analizler',latestKicker:'Haber merkezinden',analysisKicker:'Bakış ve analiz'},'فرانسوی':{latest:'Derniers articles',analysis:'Analyses importantes',latestKicker:'Dernières de la rédaction',analysisKicker:'Regards et analyses'}}; const homeLabels=homeSectionLabels[publicLanguage];

  const Logo = ({ large = false, onClick, altText }: { large?: boolean; onClick?: () => void; altText?: string }) => logoFailed ? (
    <div onClick={onClick} className={`${large ? 'h-24 w-24 text-4xl' : 'h-12 w-12 text-xl'} grid place-items-center rounded-full border border-amber-400/40 bg-emerald-950 font-black text-amber-300 ${onClick ? 'cursor-default select-none' : ''}`}>S</div>
  ) : (
    <img decoding='async' draggable={false} onClick={onClick} onError={() => setLogoFailed(true)} src={settingsData.logoUrl || './resources/sayeh-news-logo.png'} alt={altText || settingsData.brandName || 'SAYEH NEWS'} className={`${large ? 'h-24 w-24' : 'h-14 w-14'} object-contain ${onClick ? 'cursor-default select-none' : ''}`} />
  );

  const MediaVisual = ({ item, compact = false, detail = false }: { item: NewsItem; compact?: boolean; detail?: boolean }) => {
    const [imageFailed, setImageFailed] = useState(false);
    const height = compact ? 'h-36' : detail ? 'min-h-64 md:min-h-96' : 'h-64 md:h-80';
    if (item.facebookVideoUrl && detail) return <div className='aspect-video w-full bg-gradient-to-br from-zinc-950 via-emerald-950 to-zinc-800 text-white'><div className='grid h-full place-items-center p-8 text-center'><div><Video className='mx-auto mb-4 text-amber-300' size={52}/><div className='text-xl font-black'>بولتن ویدیویی سایه</div><p className='mt-2 text-sm text-zinc-300'>نسخهٔ اصلی این گزارش در فیسبوک منتشر شده است.</p><a href={item.facebookVideoUrl} target='_blank' rel='noopener noreferrer' onClick={(event)=>event.stopPropagation()} className='mt-5 inline-flex items-center gap-2 rounded-xl bg-emerald-700 px-5 py-3 text-sm font-black text-white hover:bg-emerald-600'><PlayCircle size={18}/> تماشای ویدیو در فیسبوک</a></div></div></div>;
    if (item.facebookVideoUrl) return <div className={`${height} grid place-items-center bg-gradient-to-br from-zinc-950 via-emerald-950 to-zinc-800 text-white`}><div className='text-center'><Video className='mx-auto mb-3 text-amber-300' size={36}/><span className='text-sm font-black'>بولتن ویدیویی سایه</span></div></div>;
    if (item.externalVideoUrl && detail) return <div className='aspect-video w-full bg-black'><iframe src={item.externalVideoUrl} title={item.title} loading='lazy' referrerPolicy='strict-origin-when-cross-origin' allow='accelerometer; autoplay; clipboard-write; encrypted-media; gyroscope; picture-in-picture; web-share' allowFullScreen className='h-full w-full border-0' /></div>;
    if (item.externalVideoUrl) return <div className={`${height} grid place-items-center bg-gradient-to-br from-zinc-950 via-emerald-950 to-zinc-800 text-white`}><div className='text-center'><Radio className='mx-auto mb-3 text-amber-300' size={36} /><span className='text-sm font-black'>{t.videoProgram}</span></div></div>;
    if (item.mediaUrl && item.mediaType === 'image' && !imageFailed) return <div className={`${height} relative w-full overflow-hidden`}><img src={item.mediaUrl} alt={item.title} loading={compact ? 'lazy' : 'eager'} decoding='async' fetchPriority={compact ? 'low' : 'high'} onError={() => setImageFailed(true)} className='h-full w-full object-cover' />{item.mediaName?.startsWith('sayeh-analysis-illustration-') ? <span className='absolute bottom-2 right-2 rounded-full bg-black/70 px-2.5 py-1 text-[10px] font-bold text-white'>SAYEH · {t.analysisTitle}</span> : item.mediaName?.startsWith('openverse-public-domain-') && <span className='absolute bottom-2 right-2 rounded-full bg-black/70 px-2.5 py-1 text-[10px] font-bold text-white'>{t.archiveImage}</span>}</div>;
    if (item.mediaUrl && item.mediaType === 'video') return (
      <video src={item.mediaUrl} controls={detail} muted={!detail} playsInline preload='metadata' className={`${height} w-full bg-black object-cover`} />
    );
    if (item.mediaUrl && item.mediaType === 'interview' && detail) return (
      <div className={`${height} grid place-items-center bg-gradient-to-br from-zinc-950 via-emerald-950 to-zinc-900 p-8`}>
        <div className='w-full max-w-xl rounded-3xl border border-white/10 bg-black/30 p-6 text-white backdrop-blur'>
          <div className='mb-4 flex items-center gap-3'><FileAudio className='text-amber-300' /><span className='font-black'>{t.interviewFile}</span></div>
          <audio controls src={item.mediaUrl} className='w-full' />
        </div>
      </div>
    );
    const Icon = item.mediaType === 'video' ? Video : item.mediaType === 'image' ? ImageIcon : item.mediaType === 'interview' ? Radio : Newspaper;
    return (
      <div className={`relative grid ${height} place-items-center overflow-hidden bg-gradient-to-br from-zinc-950 via-emerald-950 to-zinc-800`}>
        <div className='absolute -left-12 -top-16 h-48 w-48 rounded-full border border-amber-400/20' />
        <div className='absolute -bottom-20 -right-10 h-56 w-56 rounded-full border-[18px] border-emerald-700/20' />
        <div className='relative z-10 text-center'>
          <div className='mx-auto mb-3 flex justify-center opacity-50'><Logo large={!compact} /></div>
          <div className={`${compact ? 'text-lg' : 'text-3xl'} font-black tracking-[0.18em] text-amber-300`}>SAYEH NEWS</div>
          <div className='mt-2 text-xs font-bold text-white/80'>{publicSetting(settingsData.siteTitle,t.siteTitle)}</div>
        </div>
        <div className='absolute bottom-4 right-4 flex items-center gap-2 rounded-full border border-white/15 bg-black/40 px-3 py-1.5 text-xs text-white backdrop-blur'>
          <Icon size={14} /> {item.mediaType==='video'?t.video:item.mediaType==='image'?t.imageReport:item.mediaType==='interview'?t.interview:t.news}
        </div>
      </div>
    );
  };

  const upsertNews = (item: NewsItem) => setNews((current) => [item, ...current.filter((entry) => entry.id !== item.id)]);
  const upsertSource = (item: Source) => setSources((current) => [item, ...current.filter((entry) => entry.id !== item.id)]);
  const upsertSocial = (item: Social) => setSocials((current) => [item, ...current.filter((entry) => entry.id !== item.id)]);

  const resetArticle = () => {
    setArticleForm({ ...emptyArticle, language: settingsData.defaultLanguage || 'دری' });
    setEditingArticleId('');
    setArticleFile(null);
  };

  const saveArticle = async (statusOverride?: NewsStatus) => {
    const plainBody = richEditorPlainText(articleForm.body);
    if (!articleForm.title.trim() || !plainBody) {
      flash('عنوان و متن مطلب الزامی است.');
      return;
    }
    const targetStatus = statusOverride || articleForm.status;
    if (targetStatus === 'scheduled' && !articleForm.publishAt) {
      flash('زمان نشر را تعیین کنید.');
      return;
    }
    const existingItem = editingArticleId ? news.find((item) => item.id === editingArticleId) : undefined; const uploadedVisual = Boolean(articleFile && (/^image\//i.test(articleFile.type) || /^video\//i.test(articleFile.type))); const existingVisual = Boolean(existingItem?.externalVideoUrl || (existingItem?.mediaUrl && (existingItem.mediaType === 'image' || existingItem.mediaType === 'video'))); const hasVisualForSave = articleFile ? uploadedVisual : existingVisual; if (settingsData.visualRequired && (targetStatus === 'published' || targetStatus === 'scheduled') && !hasVisualForSave) { flash('برای تأیید و نشر، ابتدا تصویر یا ویدیوی قابل‌نمایش اضافه کنید.'); return; }
    setBusy(true);
    try {
      const media = articleFile ? await fileToPayload(articleFile) : undefined;
      const payload = {
        ...articleForm,
        status: targetStatus,
        summary: articleForm.summary.trim() || plainBody.slice(0, 180),
        body: `${RICH_BODY_MARKER}${sanitizeRichHtml(articleForm.body)}`,
        publishAt: articleForm.publishAt ? new Date(articleForm.publishAt).getTime() : undefined,
        media
      };
      const wasEditing = Boolean(editingArticleId);
      const { data } = editingArticleId
        ? await api.put(`/api/news/${editingArticleId}`, payload)
        : await api.post('/api/news', payload);
      upsertNews(data as NewsItem);
      try { await loadNews(); } catch { /* keep the successfully saved item visible if list refresh is temporarily unavailable */ }
      resetArticle();
      flash(wasEditing && targetStatus === 'published' ? 'مطلب تأیید و نشر شد.' : wasEditing ? 'مطلب ویرایش شد.' : 'مطلب ثبت شد.');
    } catch (err) {
      flash(err instanceof Error ? err.message : 'ثبت مطلب انجام نشد.');
    } finally {
      setBusy(false);
    }
  };

  const editArticle = (item: NewsItem) => {
    setEditingArticleId(item.id);
    setArticleForm({
      title: item.title, summary: item.summary, body: editorHtmlFromStoredBody(item.body), category: item.category, language: item.language,
      section: item.section, mediaType: item.mediaType, status: item.status, isBreaking: item.isBreaking,
      publishAt: item.publishAt ? new Date(item.publishAt).toISOString().slice(0, 16) : ''
    });
    setArticleFile(null);
    window.scrollTo({ top: 0, behavior: 'smooth' });
  };

  const openApprovalItem = (item: NewsItem) => { editArticle(item); setAdminTab('articles'); };

  const archiveArticle = async (item: NewsItem) => {
    if (item.section === 'analysis') { flash('مطالب بخش تحلیل دائمی‌اند و به آرشیف منتقل نمی‌شوند.'); return; }
    if (!window.confirm(`مطلب «${item.title}» به آرشیف منتقل شود؟ این خبر حذف نخواهد شد.`)) return;
    const { data } = await api.delete(`/api/news/${item.id}`);
    if (data.item) {
      setNews((current) => mergeNews(current, [data.item as NewsItem]));
      setArchiveNews((current) => mergeNews(current, [data.item as NewsItem]));
    }
    if (editingArticleId === item.id) resetArticle();
    flash('مطلب بدون حذف به آرشیف منتقل شد.');
  };

  const restoreArticle = async (item: NewsItem) => {
    const { data } = await api.post(`/api/news/${item.id}/restore`, {});
    if (data.item) setNews((current) => mergeNews(current, [data.item as NewsItem]));
    setArchiveNews((current) => current.filter((entry) => entry.id !== item.id));
    flash('مطلب از آرشیف به خبرها برگشت.');
  };

  const deleteArticle = async (item: NewsItem) => { if (!window.confirm(`مطلب «${item.title}» برای همیشه حذف شود؟ این عمل قابل بازگشت نیست.`)) return; try { await api.delete(`/api/news/${item.id}/permanent`); const withoutItem = (current: NewsItem[]) => current.filter((entry) => entry.id !== item.id); setNews(withoutItem); setArchiveNews(withoutItem); setRecentNews(withoutItem); setAnalysisNews(withoutItem); if (editingArticleId === item.id) resetArticle(); if (selected?.id === item.id) setSelected(null); flash('مطلب برای همیشه حذف شد.'); } catch { flash('حذف مطلب انجام نشد.'); } };

  const openStory=async(item:NewsItem)=>{try{const {data}=visitorSessionToken?await api.post(`/api/public/news/${item.id}/view`,{sessionToken:visitorSessionToken}):await api.post(`/api/news/${item.id}/view`,{});const viewed=data as NewsItem;upsertNews(viewed);const targetLanguage=publicLanguage;if(viewed.language===targetLanguage)setSelected(viewed);else{const shallow=itemForLanguage(viewed,targetLanguage);setSelected({...shallow,body:PUBLIC_UI[targetLanguage].loading,language:targetLanguage});}const [localized]=await requestLocalizedItems([viewed],true,targetLanguage);if(localized?.language===targetLanguage)setSelected((current)=>current?.id===viewed.id?localized:current);}catch{flash(t.viewError);}};
  const retryLocalization=()=>{setLocalizationError('');const all=[...news,...recentNews,...analysisNews,...archiveNews,...englishNews];const collection=publicTab==='archive'?archiveNews:publicTab==='analysis'?analysisNews:publicTab==='english'?englishNews:publicTab==='news'?recentNews:news;void requestLocalizedItems(collection.filter((item)=>item.status==='published'),false,publicLanguage);if(!selected)return;const original=all.find((item)=>item.id===selected.id);if(!original||original.language===publicLanguage)return;setSelected({...original,title:PUBLIC_UI[publicLanguage].loading,summary:PUBLIC_UI[publicLanguage].loading,body:PUBLIC_UI[publicLanguage].loading,category:PUBLIC_UI[publicLanguage].loading,language:publicLanguage});void requestLocalizedItems([original],true,publicLanguage).then(([localized])=>{if(localized?.language===publicLanguage)setSelected((current)=>current?.id===original.id?localized:current);});};

  const shareStory = async (item: NewsItem) => {
    const storyUrl = `${window.location.origin}${window.location.pathname}#story=${encodeURIComponent(item.id)}`;
    const text = `${item.title}\n${item.summary}`;
    if (navigator.share) await navigator.share({ title: item.title, text, url: storyUrl });
    else {
      await navigator.clipboard.writeText(`${text}\n${storyUrl}`);
      flash(t.copied);
    }
  };

  const enableNotifications = async () => {
    try {
      await notifications.subscribe();
      flash(t.notificationsEnabled);
    } catch {
      flash(t.notificationsFailed);
    }
  };

  const resetSource = () => {
    setSourceForm(emptySource);
    setEditingSourceId('');
  };

  const saveSource = async () => {
    if (!sourceForm.name.trim() || !sourceForm.url.trim()) {
      flash('نام و نشانی منبع الزامی است.');
      return;
    }
    if (sourceForm.active && !sourceForm.rightsApproved) {
      flash('برای فعال‌شدن پایش، ابتدا مجوز استفاده از این منبع را ثبت کنید.');
      return;
    }
    setBusy(true);
    try {
      const wasEditing = Boolean(editingSourceId);
      const { data } = editingSourceId
        ? await api.put(`/api/sources/${editingSourceId}`, sourceForm)
        : await api.post('/api/sources', sourceForm);
      upsertSource(data as Source);
      resetSource();
      flash(wasEditing ? 'منبع ویرایش شد.' : 'منبع اضافه شد.');
    } catch {
      flash('ذخیره منبع انجام نشد.');
    } finally {
      setBusy(false);
    }
  };

  const editSource = (source: Source) => {
    setEditingSourceId(source.id);
    setSourceForm({
      name: source.name, url: source.url, sourceType: source.sourceType, language: source.language, topic: source.topic,
      priority: source.priority, intervalMinutes: source.intervalMinutes, active: source.active, autoPublish: source.autoPublish,
      rightsApproved: source.rightsApproved, ingestText: source.ingestText, ingestImage: source.ingestImage,
      ingestVideo: source.ingestVideo, ingestInterview: source.ingestInterview
    });
    window.scrollTo({ top: 0, behavior: 'smooth' });
  };

  const toggleSource = async (source: Source) => {
    if (!source.active && !source.rightsApproved) { flash('برای فعال‌شدن پایش، ابتدا مجوز استفاده از این منبع را ثبت کنید.'); return; }
    const { data } = await api.put(`/api/sources/${source.id}`, { active: !source.active });
    upsertSource(data as Source);
    flash(source.active ? 'پایش منبع متوقف شد.' : 'پایش منبع فعال شد.');
  };

  const runSource = async (source: Source) => {
    setBusy(true);
    try {
      const { data } = await api.post(`/api/sources/${source.id}/ingest`, {});
      if (data.source) upsertSource(data.source as Source);
      await loadNews();
      const receivedCount = Array.isArray(data.items) ? data.items.length : data.item ? 1 : 0;
      flash(data.warning || (data.created ? `${receivedCount} مطلب تازه با رسانه منبع دریافت و آماده شد.` : 'منبع بررسی شد؛ مطلب تازه‌ای نبود.'));
    } catch {
      await loadSources();
      flash('بررسی منبع با خطا روبه‌رو شد.');
    } finally {
      setBusy(false);
    }
  };

  const deleteSource = async (source: Source) => {
    if (!window.confirm(`منبع «${source.name}» حذف شود؟`)) return;
    await api.delete(`/api/sources/${source.id}`);
    setSources((current) => current.filter((entry) => entry.id !== source.id));
    flash('منبع حذف شد.');
  };

  const saveSocial = async () => {
    if (!socialForm.pageUrl.trim()) {
      flash('لینک صفحه را وارد کنید.');
      return;
    }
    setBusy(true);
    try {
      const { data } = await api.post('/api/socials', socialForm);
      upsertSocial(data as Social);
      setSocialForm(emptySocial);
      flash('رسانه ذخیره شد.');
    } catch {
      flash('ثبت رسانه انجام نشد.');
    } finally {
      setBusy(false);
    }
  };

  const editSocial = (social: Social) => setSocialForm({
    id: social.id, network: social.network, pageUrl: social.pageUrl, autoShare: social.autoShare, enabled: social.enabled
  });

  const toggleSocial = async (social: Social) => {
    const { data } = await api.post('/api/socials', { ...social, id: social.id, enabled: !social.enabled });
    upsertSocial(data as Social);
    flash(social.enabled ? 'نشر این رسانه متوقف شد.' : 'نشر این رسانه فعال شد.');
  };

  const deleteSocial = async (social: Social) => {
    if (!window.confirm(`اتصال ${social.network} حذف شود؟`)) return;
    await api.delete(`/api/socials/${social.id}`);
    setSocials((current) => current.filter((entry) => entry.id !== social.id));
    flash('رسانه حذف شد.');
  };

  const saveSettings = async () => {
    setBusy(true);
    try {
      const logo = settingsLogoFile ? await fileToPayload(settingsLogoFile) : undefined;
      const { data } = await api.post('/api/settings', { ...settingsData, logo });
      setSettingsData({ ...defaultSettings, ...(data as AppSettings) }); setSettingsLogoFile(null); setLogoFailed(false);
      flash('تنظیمات مالک ذخیره شد.');
    } catch {
      flash('ذخیره تنظیمات انجام نشد.');
    } finally {
      setBusy(false);
    }
  };

  const loadAdminWorkspace = async (session: AdminSession) => {
    const tasks: Promise<unknown>[] = [loadNews(), loadSettings('',true)];
    if (session.permissions.manageSources) tasks.push(loadSources());
    if (session.permissions.manageSocials) tasks.push(loadSocials());
    if (session.permissions.manageStaff) tasks.push(loadStaff());
    if (session.role === 'owner') tasks.push(loadVisitorAnalytics());
    await Promise.all(tasks);
  };

  const startVisitorOtp=async()=>{const copy=VISITOR_AUTH_COPY[publicLanguage],email=visitorEmail.trim().toLowerCase();setVisitorError('');if(!/^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(email)){setVisitorError(copy.emailError);return;}if(visitorPassword.length<8){setVisitorError(copy.passwordError);return;}setAuthBusy(true);try{const {data}=await api.post('/api/visitor-auth/start',{email,password:visitorPassword});setVisitorChallenge(String(data.challenge||''));setVisitorCode('');setVisitorAuthStep('code');flash(copy.sent);}catch{setVisitorError(copy.sendError);}finally{setAuthBusy(false);}};
  const verifyVisitorOtp=async()=>{const copy=VISITOR_AUTH_COPY[publicLanguage];setVisitorError('');if(!/^[0-9]{6}$/.test(visitorCode.trim())){setVisitorError(copy.verifyError);return;}setAuthBusy(true);try{const {data}=await api.post('/api/visitor-auth/verify',{email:visitorEmail.trim().toLowerCase(),challenge:visitorChallenge,code:visitorCode.trim()});const token=String(data.sessionToken||'');if(!token)throw new Error('missing_session');localStorage.setItem(VISITOR_SESSION_STORAGE_KEY,token);setVisitorSessionToken(token);window.location.reload();}catch{setVisitorError(copy.verifyError);}finally{setAuthBusy(false);}};
  const completeVisitorLogin=async()=>{const attempt=++authAttemptRef.current;setAuthBusy(true);setVisitorError('');try{const ua=navigator.userAgent||'';const restricted=/FBAN|FBAV|FB_IAB|FB4A|Instagram|Messenger/i.test(ua);if(restricted&&/Android/i.test(ua)){const current=new URL(window.location.href);const fallback=encodeURIComponent(current.toString());window.location.href=`intent://${current.host}${current.pathname}${current.search}${current.hash}#Intent;scheme=${current.protocol.replace(':','')};package=com.android.chrome;S.browser_fallback_url=${fallback};end`;return;}const loginTask=auth.isSignedIn()?auth.getUser().then((user)=>({user})):auth.signIn({scope:'openid email profile offline_access'});const result=await Promise.race([loginTask,new Promise<never>((_,reject)=>window.setTimeout(()=>{const timeout=Object.assign(new Error('auth_timeout'),{code:'auth_timeout'});reject(timeout);},60000))]);if(attempt!==authAttemptRef.current)return;if(!result.user?.email){await auth.signOut().catch(()=>undefined);setVisitorError(t.verifiedEmailMissing);return;}window.location.reload();}catch(err){if(attempt!==authAttemptRef.current)return;const code=typeof err==='object'&&err&&'code' in err?String((err as {code?:unknown}).code||''):'';setVisitorError(code==='popup_blocked'?t.popupBlocked:code==='popup_closed'?t.popupClosed:code==='auth_timeout'?recovery.loginTimedOut:t.loginFailed);}finally{if(attempt===authAttemptRef.current)setAuthBusy(false);}};
  const enterVisitor=async()=>{setAuthBusy(true);setVisitorError('');try{let visitorId=localStorage.getItem(ANONYMOUS_VISITOR_ID_KEY)||'';if(!visitorId){visitorId=crypto.randomUUID();localStorage.setItem(ANONYMOUS_VISITOR_ID_KEY,visitorId);}const results=await Promise.allSettled([api.post('/api/public/visitor/click',{visitorId}),api.post('/api/visitor-auth/anonymous',{visitorId})]);const sessionResult=results[1];if(sessionResult.status!=='fulfilled')throw new Error('anonymous_session_failed');const token=String(sessionResult.value.data?.sessionToken||'');if(!token)throw new Error('missing_session');sessionStorage.setItem(VISITOR_SESSION_STORAGE_KEY,token);sessionStorage.removeItem(VISITOR_ENTRY_COUNTED_KEY);setVisitorSessionToken(token);window.location.reload();}catch{setVisitorError(ONE_CLICK_ENTRY_COPY[publicLanguage].error);setAuthBusy(false);}};
  const signOutVisitor = async () => { sessionStorage.removeItem('sayeh-news-cache-clean-v5'); if(visitorSessionToken){await api.post('/api/visitor-auth/signout',{sessionToken:visitorSessionToken}).catch(()=>undefined);sessionStorage.removeItem(VISITOR_SESSION_STORAGE_KEY);sessionStorage.removeItem(VISITOR_ENTRY_COUNTED_KEY);}else await auth.signOut(); window.location.reload(); };

  const requestAdmin = async () => {
    if (!adminSession) {
      setLoginError('');
      setLoginOpen(true);

      return;
    }
    setAuthBusy(true);
    try {
      await loadAdminWorkspace(adminSession);
      setAdminTab('dashboard');
      setView('admin');
    } catch {
      flash('بارگذاری پنل مدیریت انجام نشد.');
    } finally {
      setAuthBusy(false);
    }
  };

  const secretOwnerTap = () => { if (ownerTapTimerRef.current !== null) window.clearTimeout(ownerTapTimerRef.current); ownerTapCountRef.current += 1; if (ownerTapCountRef.current >= 5) { ownerTapCountRef.current = 0; ownerTapTimerRef.current = null; void requestAdmin(); return; } ownerTapTimerRef.current = window.setTimeout(() => { ownerTapCountRef.current = 0; ownerTapTimerRef.current = null; }, 15000); };

  const completeAdminLogin = async () => {
    setAuthBusy(true);
    setLoginError('');
    try {
      if (!auth.isSignedIn()) {
        const ua=navigator.userAgent||''; const restricted=/FBAN|FBAV|FB_IAB|FB4A|Instagram|Messenger/i.test(ua);
        if(restricted&&/Android/i.test(ua)){const current=new URL(window.location.href);const fallback=encodeURIComponent(current.toString());window.location.href=`intent://${current.host}${current.pathname}${current.search}${current.hash}#Intent;scheme=${current.protocol.replace(':','')};package=com.android.chrome;S.browser_fallback_url=${fallback};end`;return;}
        await auth.signIn({ scope: 'openid email profile offline_access' });
      }
      try { await api.post('/api/admin/bootstrap', {}); } catch { /* non-primary staff use the existing role list */ }
      const { data } = await api.get('/api/admin/me');
      const session = data as AdminSession;
      setAdminSession(session);
      await loadAdminWorkspace(session);
      setAdminTab('dashboard');
      setView('admin');
      setLoginOpen(false);

    } catch (err) {
      const code = typeof err === 'object' && err && 'code' in err ? String((err as { code?: unknown }).code || '') : '';
      const message = code === 'popup_blocked'
        ? 'مرورگر پنجرهٔ ورود را مسدود کرده است؛ اجازهٔ Pop-up را فعال و دوباره تلاش کنید.'
        : code === 'popup_closed'
          ? 'پنجرهٔ ورود بسته شد؛ برای ادامه دوباره دکمهٔ ورود با گوگل را بزنید.'
          : 'این حساب در فهرست مدیران خبرگزاری سایه ثبت نشده است.';
      setLoginError(message);
      flash(message);
      setAdminSession(null);
      setView('public');
    } finally {
      setAuthBusy(false);
    }
  };

  const signOutAdmin = async () => {
    await auth.signOut();
    setAdminSession(null);
    setSources([]);
    setSocials([]);
    setStaff([]);
    setView('public');
    flash('از پنل مدیریت خارج شدید.');
  };

  const resetStaff = () => {
    setStaffForm(emptyStaff);
    setEditingStaffId('');
  };

  const saveStaff = async () => {
    if (!staffForm.email.trim()) {
      flash('ایمیل عضو الزامی است.');
      return;
    }
    setBusy(true);
    try {
      const { data } = editingStaffId
        ? await api.put(`/api/staff/${editingStaffId}`, staffForm)
        : await api.post('/api/staff', staffForm);
      const item = data as StaffMember;
      setStaff((current) => [item, ...current.filter((entry) => entry.id !== item.id)]);
      resetStaff();
      flash(editingStaffId ? 'صلاحیت عضو ویرایش شد.' : 'عضو جدید اضافه شد.');
      await loadStaff();
    } catch {
      flash('ذخیره عضو انجام نشد؛ ایمیل تکراری یا نامعتبر نباشد.');
    } finally {
      setBusy(false);
    }
  };

  const editStaff = (member: StaffMember) => {
    if (member.primary) return;
    setEditingStaffId(member.id);
    setStaffForm({ email: member.email, name: member.name, role: member.role, active: member.active });
    window.scrollTo({ top: 0, behavior: 'smooth' });
  };

  const deleteStaff = async (member: StaffMember) => {
    if (member.primary || !window.confirm(`دسترسی «${member.name}» حذف شود؟`)) return;
    await api.delete(`/api/staff/${member.id}`);
    setStaff((current) => current.filter((entry) => entry.id !== member.id));
    flash('دسترسی عضو حذف شد.');
  };

  const installApp = async () => {
    if (!visitorSession) { setVisitorError(t.installNeedsLogin); return; }
    if (visitorSessionToken) void api.post('/api/public/visitor/event', { sessionToken: visitorSessionToken, event: 'install_attempt', path: `${window.location.pathname}${window.location.hash}` }).catch(() => undefined); else if (auth.isSignedIn()) void api.post('/api/visitor/event', { event: 'install_attempt', path: `${window.location.pathname}${window.location.hash}` }).catch(() => undefined);
    if (isInstalled) { flash(t.alreadyInstalled); return; }
    if (!installPrompt) { setInstallHelp(true); return; }
    setInstallHelp(false);
    await installPrompt.prompt();
    const choice = await installPrompt.userChoice;
    if (choice.outcome === 'accepted') setIsInstalled(true); else setInstallHelp(true);
    setInstallPrompt(null);
  };

  const showPublicApp = async () => {
    setSearch('');

    setPublicTab('home');
    try {
      const publishedItems = await loadPublicNews();
      setNews(publishedItems);
    } catch {
      // Keep the already loaded newsroom state if the refresh is temporarily unavailable.
    }
    setView('public');
  };

  const PublicHeader = () => (
    <>
      <header className='sticky top-0 z-40 border-b border-white/10 bg-zinc-950/95 text-white shadow-2xl backdrop-blur'>
        <div className='mx-auto flex max-w-7xl items-center justify-between gap-3 px-4 py-3 md:px-8'>

          <div className='flex min-w-0 items-center gap-3'>
            <Logo onClick={secretOwnerTap} altText={`${settingsData.brandName || 'SAYEH NEWS'} header logo`} />
            <div className='min-w-0'>
              <div className='truncate text-lg font-black tracking-[0.16em] md:text-xl'>{settingsData.brandName}</div>
              <div className='truncate text-[9px] text-amber-200/80 md:text-[10px]'>{publicSetting(settingsData.tagline,t.tagline)}</div>
            </div>
          </div>
          <nav className='hidden items-center gap-7 text-sm font-bold md:flex'>
            <button onClick={() => setPublicTab('home')} className={publicTab === 'home' ? 'text-amber-300' : 'text-zinc-300'}>{publicSetting(settingsData.navHome,t.navHome)}</button>
            <button onClick={() => setPublicTab('news')} className={publicTab === 'news' ? 'text-amber-300' : 'text-zinc-300'}>{publicSetting(settingsData.navNews,t.navNews)}</button>
            <button onClick={() => setPublicTab('analysis')} className={publicTab === 'analysis' ? 'text-amber-300' : 'text-zinc-300'}>{publicSetting(settingsData.navAnalysis,t.navAnalysis)}</button>
            <button onClick={() => setPublicTab('archive')} className={publicTab === 'archive' ? 'text-amber-300' : 'text-zinc-300'}>{publicSetting(settingsData.navArchive,t.navArchive)}</button>
          </nav>
          <div className='flex items-center gap-2'>
            <button onClick={installApp} className='flex items-center gap-1.5 rounded-full bg-amber-300 px-3 py-2 text-[10px] font-black text-zinc-950 md:text-xs' aria-label={t.install}><Download size={15} /><span>{isInstalled?t.installed:t.install}</span></button>
            <button onClick={enableNotifications} className='rounded-full border border-white/10 p-2 text-zinc-300' aria-label={t.notifications}><Bell size={18} /></button>
            <button onClick={signOutVisitor} className='rounded-full border border-white/10 p-2 text-zinc-300' aria-label={t.logout}><LogOut size={17} /></button>
          </div>
        </div>
        <nav className='grid grid-cols-4 border-t border-white/10 px-1 py-1.5 md:hidden'>
          {[
            { key: 'home' as const, Icon: Home, label: publicSetting(settingsData.navHome,t.navHome) },
            { key: 'news' as const, Icon: Newspaper, label: publicSetting(settingsData.navNews,t.navNews) },
            { key: 'analysis' as const, Icon: BarChart3, label: publicSetting(settingsData.navAnalysis,t.navAnalysis) },
            { key: 'archive' as const, Icon: Archive, label: publicSetting(settingsData.navArchive,t.navArchive) }
          ].map(({ key, Icon, label }) => (
            <button key={key} onClick={() => setPublicTab(key)} className={`flex items-center justify-center gap-1.5 rounded-xl px-2 py-2 text-[11px] font-bold ${publicTab === key ? 'bg-emerald-800 text-white' : 'text-zinc-300'}`}>
              <Icon size={16} /><span>{label}</span>
            </button>
          ))}
        </nav>
      </header>
      {settingsData.breakingBar && breaking && publicTab !== 'english' && (
        <button onClick={() => openStory(breaking)} className='block w-full border-b border-amber-400/20 bg-emerald-950 text-right text-white'>
          <div className='mx-auto flex max-w-7xl items-center gap-3 overflow-hidden px-4 py-2 text-xs md:px-8'>
            <span className='shrink-0 rounded bg-amber-400 px-2 py-1 font-black text-zinc-950'>{publicSetting(settingsData.breakingLabel,t.breakingLabel)}</span>
            <span className='truncate'>{breaking.title}</span>
          </div>
        </button>
      )}
    </>
  );

  const PublicApp = () => (
    <div dir={publicDir} className='min-h-screen bg-[radial-gradient(circle_at_top_left,_#f8f2e4,_#f4f4f5_42%,_#ffffff)]'>
      <PublicHeader />
      <main className='mx-auto max-w-7xl px-4 py-7 md:px-8'>
        {qaBoundaryRun&&<div className='mb-5 rounded-2xl border border-amber-300 bg-amber-50 p-4 text-sm text-amber-950'><button type='button' disabled={qaBoundaryBusy} onClick={runQaBoundaryCheck} className='rounded-xl bg-zinc-950 px-4 py-2 font-black text-white disabled:opacity-50'>Run backend regression</button>{qaBoundaryResult&&<span role='status' className='mx-3 font-bold'>{qaBoundaryResult}</span>}</div>}
        <div className='mb-7 flex flex-col gap-4 lg:flex-row lg:items-end lg:justify-between'>
          <div>
            <p className='mb-1 text-xs font-bold text-emerald-800'>{publicTab==='english'?`${publicSetting(settingsData.englishDeskLabel,t.englishDeskLabel)} · ${settingsData.brandName}`:`${publicSetting(settingsData.newsroomLabel,t.newsroomLabel)} ${publicSetting(settingsData.siteTitle,t.siteTitle)}`}</p>
            <h1 className='text-3xl font-black text-zinc-950 md:text-5xl'>{publicTab==='archive'?publicSetting(settingsData.archiveTitle,t.archiveTitle):publicTab==='analysis'?publicSetting(settingsData.analysisTitle,t.analysisTitle):publicTab==='english'?publicSetting(settingsData.englishTitle,t.englishTitle):publicTab==='news'?publicSetting(settingsData.newsTitle,t.newsTitle):publicSetting(settingsData.homeTitle,t.homeTitle)}</h1>
          </div>
          <div className='flex flex-col gap-3 sm:flex-row'>
            <label className='flex min-w-52 items-center gap-2 rounded-2xl border border-zinc-200 bg-white px-4 py-3 shadow-sm'>
              <Languages size={17} className='text-emerald-800' />
              <select aria-label='Language' value={publicLanguage} onChange={(event)=>{const value=event.target.value;if(isPublicLanguage(value))switchPublicLanguage(value);}} className='w-full bg-transparent text-sm outline-none'>
                {PUBLIC_LANGUAGES.map((language)=><option key={language} value={language}>{PUBLIC_LANGUAGE_META[language].label}</option>)}
              </select>
            </label>
            <label className='flex min-w-64 items-center gap-2 rounded-2xl border border-zinc-200 bg-white px-4 py-3 shadow-sm'>
              <Search size={18} className='text-zinc-400' />
              <input value={search} onChange={(event)=>setSearch(event.target.value)} className='w-full bg-transparent text-sm outline-none' placeholder={publicSetting(settingsData.searchPlaceholder,t.searchPlaceholder)} dir={publicDir} />
            </label>
          </div>
        </div>

        {localizationError&&<div role='alert' className='mb-7 flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-amber-300 bg-amber-50 p-4 text-sm text-amber-950 shadow-sm'><span>{localizationError}</span><button type='button' disabled={localizationBusy} onClick={retryLocalization} className='rounded-xl bg-amber-400 px-4 py-2 font-black text-zinc-950 disabled:opacity-50'>{localizationBusy?t.loading:recovery.retry}</button></div>}

        {hero && publicTab === 'home' && !searchActive && (
          <article onClick={() => openStory(hero)} className='group mb-8 grid cursor-pointer overflow-hidden rounded-[2rem] bg-zinc-950 text-white shadow-2xl md:grid-cols-[1.15fr_.85fr]'>
            <MediaVisual item={hero} />
            <div className='flex flex-col justify-center p-7 md:p-10'>
              <div className='mb-4 flex flex-wrap items-center gap-2 text-xs'>
                <span className='rounded-full bg-emerald-700 px-3 py-1 font-bold'>{hero.category}</span>
                <span className='text-zinc-400'>{languageLabel(hero.language)}</span>
                <span className='text-zinc-400'>{publicDateText(hero.publishedAt)}</span>
              </div>
              <h2 className='mb-4 text-3xl font-black leading-tight transition group-hover:text-amber-300 md:text-5xl'>{hero.title}</h2>
              <p className='line-clamp-3 text-sm leading-7 text-zinc-300 md:text-base'>{hero.summary}</p>
              <div className='mt-7 flex items-center gap-2 text-amber-300'>{t.continueReading} <ChevronLeft size={18} /></div>
            </div>
          </article>
        )}

        {publicTab==='home'&&!searchActive&&(homeLatest.length>0||homeImportantAnalysis.length>0)&&<section className='mb-10 grid gap-6 border-y border-zinc-200 py-8 lg:grid-cols-[1.15fr_.85fr]'><div><div className='mb-5 flex items-end justify-between gap-3'><div><p className='text-[11px] font-black uppercase tracking-[.16em] text-emerald-800'>{homeLabels.latestKicker}</p><h2 className='mt-1 text-2xl font-black text-zinc-950 md:text-3xl'>{homeLabels.latest}</h2></div><button onClick={()=>setPublicTab('news')} className='text-xs font-black text-emerald-800 hover:underline'>{t.navNews} ←</button></div><div className='divide-y divide-zinc-200 border-t border-zinc-950'>{homeLatest.map((item,index)=><article key={`home-latest-${item.id}`} onClick={()=>openStory(item)} className='group grid cursor-pointer grid-cols-[auto_1fr] gap-4 py-4'><span className='pt-1 text-2xl font-light tabular-nums text-zinc-300'>{String(index+1).padStart(2,'0')}</span><div><div className='mb-1 flex flex-wrap items-center gap-2 text-[10px] font-bold text-emerald-800'><span>{item.category}</span><span className='text-zinc-300'>•</span><span className='text-zinc-400'>{publicDateText(item.publishedAt)}</span></div><h3 className='text-base font-black leading-7 text-zinc-900 transition group-hover:text-emerald-800 md:text-lg'>{item.title}</h3></div></article>)}</div></div><div className='rounded-3xl bg-zinc-950 p-5 text-white md:p-6'><div className='mb-5 flex items-end justify-between gap-3 border-b border-white/15 pb-4'><div><p className='text-[11px] font-black uppercase tracking-[.16em] text-amber-300'>{homeLabels.analysisKicker}</p><h2 className='mt-1 text-2xl font-black md:text-3xl'>{homeLabels.analysis}</h2></div><button onClick={()=>setPublicTab('analysis')} className='text-xs font-black text-amber-300 hover:underline'>{t.navAnalysis} ←</button></div><div className='space-y-4'>{homeImportantAnalysis.map((item,index)=><article key={`home-analysis-${item.id}`} onClick={()=>openStory(item)} className={`group cursor-pointer ${index?'border-t border-white/10 pt-4':''}`}><div className='grid grid-cols-[88px_1fr] gap-4'><div className='overflow-hidden rounded-xl'><MediaVisual item={item} compact /></div><div className='min-w-0'><div className='mb-1 text-[10px] font-bold text-amber-300'>{item.category}</div><h3 className='line-clamp-3 text-sm font-black leading-6 text-white transition group-hover:text-amber-300 md:text-base'>{item.title}</h3><p className='mt-1 line-clamp-2 text-xs leading-5 text-zinc-400'>{item.summary}</p></div></div></article>)}</div></div></section>}

        <section className='grid gap-6 sm:grid-cols-2 lg:grid-cols-3'>
          {homeGridNews.map((item) => (
            <article key={item.id} dir={publicLanguageDirection(item.language)} onClick={() => openStory(item)} className='group cursor-pointer overflow-hidden rounded-3xl border border-zinc-200 bg-white shadow-sm transition hover:-translate-y-1 hover:shadow-xl'>
              <MediaVisual item={item} compact />
              <div className='p-5'>
                <div className='mb-3 flex items-center justify-between gap-2 text-[11px] text-zinc-500'>
                  <span className='font-bold text-emerald-800'>{item.category}</span><span>{languageLabel(item.language)} · {publicDateText(item.publishedAt)}</span>
                </div>
                <h3 className='mb-3 line-clamp-2 text-xl font-black leading-8 text-zinc-900 group-hover:text-emerald-800'>{item.title}</h3>
                <p className='line-clamp-2 text-sm leading-6 text-zinc-600'>{item.summary}</p>
                {item.viewCount>=settingsData.viewCountThreshold&&<div className='mt-4 flex items-center gap-2 text-[11px] text-zinc-400'><Eye size={14}/>{item.viewCount} {t.views}</div>}
              </div>
            </article>
          ))}
        </section>
        {!searchActive && publicTab === 'news' && recentNewsHasMore && <div className='mt-7 flex justify-center'><button disabled={recentNewsLoading} onClick={() => loadRecentNews(false)} className='rounded-2xl bg-emerald-800 px-6 py-3 text-sm font-black text-white disabled:opacity-50'>{recentNewsLoading?t.loading:t.loadOlder}</button></div>}
        {!searchActive && publicTab === 'analysis' && analysisHasMore && <div className='mt-7 flex justify-center'><button disabled={analysisLoading} onClick={() => loadAnalysis(false)} className='rounded-2xl bg-emerald-800 px-6 py-3 text-sm font-black text-white disabled:opacity-50'>{analysisLoading?t.loading:t.loadOlder}</button></div>}
        {!searchActive && publicTab === 'english' && englishHasMore && <div className='mt-7 flex justify-center'><button disabled={englishLoading} onClick={() => loadEnglish(false)} className='rounded-2xl bg-emerald-800 px-6 py-3 text-sm font-black text-white disabled:opacity-50'>{englishLoading?t.loading:t.loadOlder}</button></div>}
        {!searchActive && publicTab === 'archive' && archiveHasMore && <div className='mt-7 flex justify-center'><button disabled={archiveLoading} onClick={() => loadArchive(false)} className='rounded-2xl bg-emerald-800 px-6 py-3 text-sm font-black text-white disabled:opacity-50'>{archiveLoading?t.loading:t.loadOlder}</button></div>}
        {searchActive&&searchLoading&&<Empty text={t.loading} />}
        {!searchLoading&&!visibleNews.length && !(publicTab === 'news' && recentNewsLoading) && !(publicTab === 'archive' && archiveLoading) && !(publicTab === 'analysis' && analysisLoading) && !(publicTab === 'english' && englishLoading) && <Empty text={t.noResults} />}
        {publicTab === 'news' && recentNewsLoading && !recentNews.length && <Empty text={t.loadingNews} />}
        {publicTab === 'analysis' && analysisLoading && !analysisNews.length && <Empty text={t.loadingAnalysis} />}
        {publicTab === 'english' && englishLoading && !englishNews.length && <Empty text={t.loadingNews} />}
        {publicTab === 'archive' && archiveLoading && !archiveNews.length && <Empty text={t.loadingArchive} />}
      </main>

      <footer className='border-t border-zinc-200 bg-zinc-950 px-4 py-5 text-center text-xs text-zinc-400'>
        <span>{publicSetting(settingsData.footerText,t.footerText)}</span><span className='mx-2 text-zinc-500'>{settingsData.footerYear}</span>
      </footer>

      {selected && (
        <div ref={storyDialogRef} tabIndex={-1} role='dialog' aria-modal='true' aria-labelledby={`story-title-${selected.id}`} aria-describedby={`story-summary-${selected.id}`} className='fixed inset-0 z-50 outline-none'>
          <div aria-hidden='true' className='absolute inset-0 bg-black/75 backdrop-blur-sm' />
          <div className='pointer-events-none fixed inset-x-3 top-3 z-[70] flex items-center justify-between gap-3 md:inset-x-8 md:top-5'>
            <div role='group' aria-label='Language' className='pointer-events-auto flex max-w-[calc(100vw-5rem)] items-center gap-1 overflow-x-auto rounded-full border border-white/30 bg-zinc-950/90 p-1.5 text-white shadow-2xl backdrop-blur md:max-w-[70vw]'>
              <Languages size={18} aria-hidden='true' className='mx-1 shrink-0' />
              {PUBLIC_LANGUAGES.map((language)=><button key={language} type='button' aria-pressed={publicLanguage===language} onClick={()=>switchPublicLanguage(language)} className={`shrink-0 rounded-full px-3 py-1.5 text-xs font-black transition focus:outline-none focus:ring-2 focus:ring-amber-300 ${publicLanguage===language?'bg-amber-300 text-zinc-950':'text-white hover:bg-white/15'}`}>{PUBLIC_LANGUAGE_META[language].label}</button>)}
            </div>
            <button type='button' aria-label={t.close} className='pointer-events-auto rounded-full border border-white/30 bg-zinc-950/90 p-3 text-white shadow-2xl backdrop-blur transition hover:bg-zinc-800 focus:outline-none focus:ring-2 focus:ring-amber-300' onClick={() => setSelected(null)}><X /></button>
          </div>
          <div className='absolute inset-0 overflow-y-auto p-3 pt-20 md:p-8 md:pt-24' onClick={() => setSelected(null)}>
            <article dir={publicLanguageDirection(selected.language)} className='mx-auto max-w-4xl overflow-hidden rounded-[2rem] bg-white shadow-2xl outline-none' onClick={(event) => event.stopPropagation()}>
              <MediaVisual item={selected} detail />
            <div className='p-6 md:p-10'>
              <div className='mb-4 flex flex-wrap gap-2 text-xs font-bold text-emerald-800'>
                <span>{selected.category}</span><span>·</span><span>{languageLabel(selected.language)}</span><span>·</span><span>{publicDateText(selected.publishedAt)}</span>
              </div>
              <h1 id={`story-title-${selected.id}`} className='mb-5 text-3xl font-black leading-tight text-zinc-950 md:text-5xl'>{selected.title}</h1>
              <p id={`story-summary-${selected.id}`} className={`mb-7 border-amber-500 text-lg leading-9 text-zinc-600 ${publicLanguageDirection(selected.language)==='ltr'?'border-l-4 pl-4':'border-r-4 pr-4'}`}>{selected.summary}</p>
              {localizationError&&<div role='alert' className='mb-6 flex flex-wrap items-center justify-between gap-3 rounded-2xl border border-amber-300 bg-amber-50 p-4 text-sm text-amber-950'><span>{localizationError}</span><button type='button' disabled={localizationBusy} onClick={retryLocalization} className='rounded-xl bg-amber-400 px-4 py-2 font-black text-zinc-950 disabled:opacity-50'>{localizationBusy?t.loading:recovery.retry}</button></div>}
              {isRichBody(selected.body)?<div dir={publicLanguageDirection(selected.language)} className='text-justify text-base leading-9 text-zinc-800' style={{textAlign:'justify',textAlignLast:publicLanguageDirection(selected.language)==='ltr'?'left':'right'}} dangerouslySetInnerHTML={{__html:storedRichHtml(selected.body)}}/>:<div dir={publicLanguageDirection(selected.language)} className='whitespace-pre-wrap text-justify text-base leading-9 text-zinc-800' style={{textAlign:'justify',textAlignLast:publicLanguageDirection(selected.language)==='ltr'?'left':'right'}}><StructuredPlainBody value={selected.body}/></div>}
              <div className='mt-10 flex flex-wrap items-center justify-between gap-3 border-t pt-5 text-sm text-zinc-500'>
                <span>{`${publicTab==='english'?t.editedBy+publicSetting(settingsData.englishDeskLabel,t.englishDeskLabel):t.preparedBy+publicSetting(settingsData.newsroomLabel,t.newsroomLabel)} ${settingsData.brandName}${selected.viewCount>=settingsData.viewCountThreshold?` · ${selected.viewCount} ${t.views}`:''}`}</span>
                <button onClick={() => shareStory(selected)} className='flex items-center gap-2 rounded-xl bg-emerald-800 px-4 py-2 font-bold text-white'><Share2 size={17} /> {t.share}</button>
              </div>
            </div>
            </article>
          </div>
        </div>
      )}    </div>
  );

  const AdminSidebar = () => {
    const allowed = adminSession ? roleTabs[adminSession.role] : [];
    const menuItems: Array<{ key: AdminTab; Icon: typeof LayoutDashboard; label: string }> = [
      { key: 'dashboard', Icon: LayoutDashboard, label: 'داشبورد' },
      { key: 'articles', Icon: FileText, label: 'مطالب' },
      { key: 'sources', Icon: Radio, label: 'منابع' },
      { key: 'socials', Icon: Share2, label: 'رسانه‌های متصل' },
      { key: 'visitors', Icon: Users, label: 'بازدیدکنندگان' },
      { key: 'settings', Icon: Settings, label: 'تنظیمات' },
      { key: 'staff', Icon: Users, label: 'کاربران و صلاحیت‌ها' }
    ];
    return (
      <aside className='w-full shrink-0 border-l border-white/10 bg-zinc-950 p-4 text-white md:min-h-screen md:w-72'>
        <div className='mb-5 flex items-center gap-3'><Logo /><div><div className='font-black tracking-wider'>SAYEH NEWS</div><div className='text-xs text-zinc-500'>مرکز مدیریت تحریریه</div></div></div>
        {adminSession && <div className='mb-5 rounded-2xl border border-white/10 bg-white/5 p-4'><div className='flex items-center gap-2 text-sm font-black'><ShieldCheck size={17} className='text-amber-300' /> {adminSession.name}</div><div className='mt-1 truncate text-[11px] text-zinc-400' dir='ltr'>{adminSession.email}</div><span className='mt-3 inline-block rounded-full bg-emerald-800 px-3 py-1 text-[11px]'>{roleLabels[adminSession.role]}</span></div>}
        <nav className='grid grid-cols-2 gap-2 md:grid-cols-1'>
          {menuItems.filter((item) => allowed.includes(item.key)).map(({ key, Icon, label }) => (
            <button key={key} onClick={() => setAdminTab(key)} className={`flex items-center gap-3 rounded-2xl px-4 py-3 text-sm font-bold ${adminTab === key ? 'bg-emerald-800 text-white' : 'text-zinc-400 hover:bg-white/5'}`}><Icon size={18} /> {label}</button>
          ))}
        </nav>
        <button onClick={showPublicApp} className='mt-6 flex w-full items-center justify-center gap-2 rounded-2xl border border-white/10 px-4 py-3 text-sm text-zinc-300'><Eye size={17} /> مشاهده اپ</button>
        <button onClick={signOutAdmin} className='mt-2 flex w-full items-center justify-center gap-2 rounded-2xl border border-red-500/20 px-4 py-3 text-sm text-red-300'><LogOut size={17} /> خروج از مدیریت</button>
      </aside>
    );
  };

  const Dashboard = () => {
    const published = news.filter((item) => item.status === 'published').length;
    const pending = news.filter((item) => item.status !== 'published').length;
    const totalViews = news.reduce((sum, item) => sum + (item.viewCount || 0), 0);
    const sourceErrors = sources.filter((source) => source.lastError).length;
    return (
      <>
        <section className={`mb-6 rounded-3xl border p-6 shadow-sm ${pendingApprovalItems.length ? 'border-amber-200 bg-amber-50' : 'border-emerald-100 bg-white'}`}>
          <div className='mb-4 flex flex-wrap items-center justify-between gap-3'><div><div className='flex items-center gap-2 text-lg font-black text-zinc-950'><AlertTriangle className={pendingApprovalItems.length ? 'text-amber-600' : 'text-emerald-700'} size={20} /> در انتظار تأیید مدیر</div><p className='mt-1 text-xs text-zinc-500'>مطالبی که هنوز تأیید نشده‌اند اینجا مستقیم برای تصمیم مدیر می‌آیند.</p></div><span className={`rounded-full px-3 py-1 text-xs font-black ${pendingApprovalItems.length ? 'bg-amber-200 text-amber-900' : 'bg-emerald-100 text-emerald-800'}`}>{pendingApprovalItems.length} مطلب</span></div>
          {pendingApprovalItems.length ? <div className='space-y-3'>{pendingApprovalItems.slice(0, 6).map((item) => <div key={item.id} className='flex flex-col gap-3 rounded-2xl border border-amber-200 bg-white p-4 sm:flex-row sm:items-center sm:justify-between'><div className='min-w-0'><div className='truncate font-black'>{item.title}</div><div className='mt-1 text-xs text-zinc-500'>{item.mediaUrl || item.externalVideoUrl ? 'رسانه آماده؛ منتظر تصمیم مدیر' : 'بدون تصویر؛ تصویر را اضافه و سپس تأیید کنید'} · {dateText(item.updatedAt)}</div></div><div className='flex flex-wrap gap-2'><button onClick={() => openApprovalItem(item)} className='flex items-center gap-2 rounded-xl bg-emerald-800 px-3 py-2 text-xs font-black text-white'><Upload size={15} /> بازکردن و افزودن تصویر</button>{canDeleteArticles(adminSession?.role) && <button onClick={() => deleteArticle(item)} className='flex items-center gap-2 rounded-xl border border-red-200 px-3 py-2 text-xs font-black text-red-700'><X size={15} /> لغو</button>}</div></div>)}</div> : <div className='rounded-2xl bg-emerald-50 p-4 text-sm font-bold text-emerald-800'>هیچ مطلبی در انتظار تأیید مدیر نیست.</div>}
          {pendingApprovalItems.length > 6 && <button onClick={() => setAdminTab('articles')} className='mt-4 text-xs font-black text-amber-800'>مشاهده همه مطالب در انتظار تأیید</button>}
        </section>
        <div className='grid gap-4 sm:grid-cols-2 xl:grid-cols-4'>
          {[
            { Icon: Newspaper, label: 'مطالب نشرشده', value: published },
            { Icon: Clock3, label: 'تأیید مدیر یا زمان‌بندی', value: pending },
            { Icon: Eye, label: 'مجموع بازدید', value: totalViews },
            { Icon: Radio, label: 'منابع فعال', value: sources.filter((source) => source.active).length }
          ].map(({ Icon, label, value }) => (
            <div key={label} className='rounded-3xl border border-zinc-200 bg-white p-5 shadow-sm'>
              <div className='mb-5 flex items-center justify-between'><div className='rounded-2xl bg-emerald-50 p-3 text-emerald-800'><Icon size={21} /></div><span className='text-xs text-zinc-400'>زنده</span></div>
              <div className='text-3xl font-black text-zinc-900'>{value}</div><div className='mt-1 text-sm text-zinc-500'>{label}</div>
            </div>
          ))}
        </div>
        <div className='mt-6 grid gap-6 lg:grid-cols-[1.2fr_.8fr]'>
          <div className='rounded-3xl border border-zinc-200 bg-white p-6 shadow-sm'>
            <div className='mb-5 flex items-center justify-between'><h3 className='font-black'>آخرین مطالب</h3><Activity size={17} className='text-zinc-400' /></div>
            <div className='space-y-3'>
              {news.slice(0, 6).map((item) => (
                <div key={item.id} className='flex items-center justify-between gap-3 rounded-2xl bg-zinc-50 p-4'>
                  <div className='min-w-0'><div className='truncate font-bold'>{item.title}</div><div className='mt-1 text-xs text-zinc-500'>{item.category} · {dateText(item.updatedAt)}</div></div>
                  <span className={`shrink-0 rounded-full px-3 py-1 text-xs ${item.status === 'published' ? 'bg-emerald-100 text-emerald-800' : 'bg-amber-100 text-amber-800'}`}>{statusText(item.status)}</span>
                </div>
              ))}
            </div>
          </div>
          <div className='rounded-3xl bg-gradient-to-br from-zinc-950 to-emerald-950 p-7 text-white shadow-xl'>
            <div className='mb-6 flex items-center gap-3'><AlertTriangle className='text-amber-300' /><h3 className='font-black'>وضعیت سامانه</h3></div>
            <div className='space-y-3 text-sm'>
              <div className='flex justify-between rounded-xl bg-white/5 p-3'><span>منابع خطادار</span><strong>{sourceErrors}</strong></div>
              <div className='flex justify-between rounded-xl bg-white/5 p-3'><span>رسانه‌های فعال</span><strong>{socials.filter((social) => social.enabled).length}</strong></div>
              <div className='flex justify-between rounded-xl bg-white/5 p-3'><span>نشر خودکار عمومی</span><strong>{!settingsData.autoShare?'متوقف':socials.some((social)=>social.enabled&&social.autoShare&&social.configured)?'فعال':'در انتظار مجوز رسمی'}</strong></div>
            </div>
            <button onClick={() => setAdminTab('sources')} className='mt-6 w-full rounded-2xl bg-amber-400 px-4 py-3 font-black text-zinc-950'>بررسی منابع</button>
          </div>
        </div>
      </>
    );
  };

  const Articles = () => (
    <div className='grid gap-6 xl:grid-cols-[.82fr_1.18fr]'>
      <section className='rounded-3xl border border-zinc-200 bg-white p-6 shadow-sm'>
        <div className='mb-5 flex items-center justify-between'><h3 className='text-xl font-black'>{editingArticleId ? 'ویرایش مطلب' : 'ثبت مطلب جدید'}</h3>{editingArticleId && <button onClick={resetArticle} className='text-xs text-red-600'>لغو ویرایش</button>}</div>
        <div className='space-y-4'>
          <input aria-label='عنوان مطلب' value={articleForm.title} onChange={(event) => setArticleForm((current) => ({ ...current, title: event.target.value }))} className='w-full rounded-2xl border px-4 py-3 outline-none focus:border-emerald-700' placeholder='عنوان مطلب' />
          <input aria-label='خلاصه کوتاه' value={articleForm.summary} onChange={(event) => setArticleForm((current) => ({ ...current, summary: event.target.value }))} className='w-full rounded-2xl border px-4 py-3 outline-none focus:border-emerald-700' placeholder='خلاصه کوتاه' />
          <RichTextEditor value={articleForm.body} onChange={(body) => setArticleForm((current) => ({ ...current, body }))} />
          <div className='grid grid-cols-2 gap-3'>
            <input aria-label='دسته‌بندی' value={articleForm.category} onChange={(event) => setArticleForm((current) => ({ ...current, category: event.target.value }))} className='rounded-2xl border px-4 py-3' placeholder='دسته‌بندی' />
            <select aria-label='زبان مطلب' value={articleForm.language} onChange={(event) => setArticleForm((current) => ({ ...current, language: event.target.value }))} className='rounded-2xl border px-4 py-3'>{languages.map((language) => <option key={language}>{language}</option>)}</select>
            <select aria-label='بخش نمایش' value={articleForm.section} onChange={(event) => setArticleForm((current) => ({ ...current, section: event.target.value as ArticleForm['section'] }))} className='rounded-2xl border px-4 py-3'><option value='news'>خبرها</option><option value='analysis'>تحلیل</option></select>
            <select aria-label='نوع رسانه' value={articleForm.mediaType} onChange={(event) => setArticleForm((current) => ({ ...current, mediaType: event.target.value as MediaType }))} className='rounded-2xl border px-4 py-3'><option value='text'>متن</option><option value='image'>تصویر</option><option value='video'>ویدیو</option><option value='interview'>مصاحبه</option></select>
            <select aria-label='وضعیت نشر' disabled={Boolean(editingApprovalItem)} value={articleForm.status} onChange={(event) => setArticleForm((current) => ({ ...current, status: event.target.value as NewsStatus }))} className='rounded-2xl border px-4 py-3 disabled:bg-amber-50 disabled:text-amber-900'><option value='published'>نشر فوری</option><option value='scheduled'>زمان‌بندی نشر</option><option value='review'>در انتظار تأیید مدیر</option><option value='draft'>پیش‌نویس</option></select>
            {articleForm.status === 'scheduled' ? <input aria-label='زمان نشر' type='datetime-local' value={articleForm.publishAt} onChange={(event) => setArticleForm((current) => ({ ...current, publishAt: event.target.value }))} className='rounded-2xl border px-4 py-3' /> : <div className='rounded-2xl border border-dashed px-4 py-3 text-xs text-zinc-400'>نشر مطابق وضعیت انتخابی</div>}
          </div>
          {editingApprovalItem && <div className='rounded-2xl border border-amber-200 bg-amber-50 p-4 text-sm font-bold text-amber-900'>این مطلب مستقیماً نزد مدیر برای تأیید آمده است. تصویر را اضافه یا بررسی کنید، سپس «تأیید و نشر» یا «لغو مطلب» را انتخاب کنید.</div>}
          <label className='flex cursor-pointer items-center justify-between rounded-2xl border border-dashed p-4 text-sm'>
            <span className='flex items-center gap-2'><Upload size={17} /> {articleFile ? articleFile.name : 'آپلود تصویر، ویدیو یا صوت'}</span>
            <input type='file' accept='image/*,video/*,audio/*' className='hidden' onChange={(event) => setArticleFile(event.target.files?.[0] || null)} />
          </label>
          <label className='flex items-center justify-between rounded-2xl bg-amber-50 p-4 text-sm'><span className='flex items-center gap-2'><Megaphone size={17} /> نمایش در خبر فوری</span><input type='checkbox' checked={articleForm.isBreaking} onChange={(event) => setArticleForm((current) => ({ ...current, isBreaking: event.target.checked }))} /></label>
          {editingApprovalItem ? <div className='grid grid-cols-2 gap-3'><button disabled={busy} onClick={() => void saveArticle('published')} className='flex items-center justify-center gap-2 rounded-2xl bg-emerald-800 px-4 py-3 font-black text-white disabled:opacity-50'><Send size={18} /> تأیید و نشر</button><button disabled={busy} onClick={() => void deleteArticle(editingApprovalItem)} className='flex items-center justify-center gap-2 rounded-2xl border border-red-200 bg-red-50 px-4 py-3 font-black text-red-700 disabled:opacity-50'><X size={18} /> لغو مطلب</button></div> : <button disabled={busy} onClick={() => void saveArticle()} className='flex w-full items-center justify-center gap-2 rounded-2xl bg-emerald-800 px-4 py-3 font-black text-white disabled:opacity-50'>{editingArticleId ? <Save size={18} /> : <Send size={18} />} {editingArticleId ? 'ذخیره تغییرات' : 'ثبت و نشر مطلب'}</button>}
        </div>
      </section>
      <section className='rounded-3xl border border-zinc-200 bg-white p-6 shadow-sm'>
        <div className='mb-5 flex items-center justify-between'><h3 className='text-xl font-black'>مدیریت مطالب</h3><span className='text-sm text-zinc-500'>{news.length} مطلب</span></div>
        <div className='space-y-3'>
          {orderedAdminNews.map((item) => (
            <div key={item.id} className={`flex flex-col gap-3 rounded-2xl border p-4 sm:flex-row sm:items-center sm:justify-between ${item.status === 'review' ? 'border-amber-200 bg-amber-50/60' : 'border-zinc-100'}`}>
              <div className='min-w-0'><div className='truncate font-black'>{item.title}</div><div className='mt-1 text-xs text-zinc-500'>{item.category} · {item.section === 'analysis' ? 'تحلیل' : 'خبر'} · {item.language} · {dateText(item.updatedAt)}</div></div>
              <div className='flex shrink-0 items-center gap-2'>
                <span className={`rounded-full px-3 py-1 text-xs ${item.archived ? 'bg-zinc-200 text-zinc-700' : item.status === 'published' ? 'bg-emerald-100 text-emerald-800' : 'bg-amber-100 text-amber-800'}`}>{item.archived ? 'آرشیف‌شده' : statusText(item.status)}</span>
                <button aria-label={`ویرایش ${item.title}`} onClick={() => editArticle(item)} className='rounded-xl border p-2 text-zinc-600'><Edit3 size={16} /></button>
                {item.section === 'analysis' ? <span className='rounded-xl border border-emerald-100 px-3 py-2 text-[11px] font-bold text-emerald-700'>دائمی</span> : item.archived ? <button aria-label={`بازگرداندن ${item.title}`} onClick={() => restoreArticle(item)} className='rounded-xl border border-emerald-100 p-2 text-emerald-700'><RefreshCw size={16} /></button> : canDeleteArticles(adminSession?.role) && <button aria-label={`آرشیف ${item.title}`} onClick={() => archiveArticle(item)} className='rounded-xl border border-zinc-200 p-2 text-zinc-600'><Archive size={16} /></button>}{canDeleteArticles(adminSession?.role) && <button aria-label={`حذف ${item.title}`} onClick={() => deleteArticle(item)} className='rounded-xl border border-red-100 p-2 text-red-600' title='حذف دائمی'><Trash2 size={16} /></button>}
              </div>
            </div>
          ))}
        </div>
      </section>
    </div>
  );

  const Sources = () => (
    <div className='grid gap-6 xl:grid-cols-[.78fr_1.22fr]'>
      <section className='rounded-3xl border border-zinc-200 bg-white p-6 shadow-sm'>
        <div className='mb-5 flex items-center justify-between'><h3 className='text-xl font-black'>{editingSourceId ? 'ویرایش منبع' : 'افزودن منبع'}</h3>{editingSourceId && <button onClick={resetSource} className='text-xs text-red-600'>لغو ویرایش</button>}</div>
        <div className='space-y-4'>
          <input aria-label='نام رسانه یا منبع' value={sourceForm.name} onChange={(event) => setSourceForm((current) => ({ ...current, name: event.target.value }))} className='w-full rounded-2xl border px-4 py-3' placeholder='نام رسانه یا منبع' />
          <input aria-label='نشانی منبع' inputMode='url' value={sourceForm.url} onChange={(event) => setSourceForm((current) => ({ ...current, url: event.target.value }))} className='w-full rounded-2xl border px-4 py-3' placeholder='https://example.com' dir='ltr' />
          <div className='grid grid-cols-2 gap-3'>
            <select aria-label='نوع منبع' value={sourceForm.sourceType} onChange={(event) => setSourceForm((current) => ({ ...current, sourceType: event.target.value }))} className='rounded-2xl border px-4 py-3'><option>وب‌سایت</option><option>RSS</option><option>فیسبوک</option><option>تلگرام</option><option>یوتیوب</option></select>
            <select aria-label='زبان منبع' value={sourceForm.language} onChange={(event) => setSourceForm((current) => ({ ...current, language: event.target.value }))} className='rounded-2xl border px-4 py-3'>{languages.map((language) => <option key={language}>{language}</option>)}</select>
            <input aria-label='موضوع منبع' value={sourceForm.topic} onChange={(event) => setSourceForm((current) => ({ ...current, topic: event.target.value }))} className='rounded-2xl border px-4 py-3' placeholder='موضوع' />
            <select aria-label='اولویت منبع' value={sourceForm.priority} onChange={(event) => setSourceForm((current) => ({ ...current, priority: event.target.value }))} className='rounded-2xl border px-4 py-3'><option>بالا</option><option>متوسط</option><option>عادی</option></select>
            <select aria-label='فاصله پایش' value={sourceForm.intervalMinutes} onChange={(event) => setSourceForm((current) => ({ ...current, intervalMinutes: Number(event.target.value) }))} className='col-span-2 rounded-2xl border px-4 py-3'><option value={5}>هر ۵ دقیقه</option><option value={15}>هر ۱۵ دقیقه</option><option value={30}>هر ۳۰ دقیقه</option><option value={60}>هر ۱ ساعت</option></select>
          </div>
          <div className='grid grid-cols-2 gap-2 text-xs'>
            {[['ingestText', 'متن'], ['ingestImage', 'عکس'], ['ingestVideo', 'ویدیو'], ['ingestInterview', 'مصاحبه']].map(([key, label]) => (
              <label key={key} className='flex items-center justify-between rounded-xl bg-zinc-50 p-3'><span>{label}</span><input type='checkbox' checked={sourceForm[key as keyof SourceForm] as boolean} onChange={(event) => setSourceForm((current) => ({ ...current, [key]: event.target.checked }))} /></label>
            ))}
          </div>
          <label className='flex items-center justify-between rounded-2xl bg-zinc-50 p-4 text-sm'><span>فعال بودن پایش</span><input type='checkbox' checked={sourceForm.active} onChange={(event) => setSourceForm((current) => ({ ...current, active: event.target.checked }))} /></label>
          <label className='flex items-center justify-between rounded-2xl bg-zinc-50 p-4 text-sm'><span>نشر خودکار</span><input type='checkbox' checked={sourceForm.autoPublish} onChange={(event) => setSourceForm((current) => ({ ...current, autoPublish: event.target.checked }))} /></label>
          <label className='flex items-center justify-between rounded-2xl bg-amber-50 p-4 text-sm'><span>مجوز استفاده ثبت شده است</span><input aria-label='مجوز استفاده ثبت شده است' type='checkbox' checked={sourceForm.rightsApproved} onChange={(event) => setSourceForm((current) => ({ ...current, rightsApproved: event.target.checked }))} /></label>
          <button disabled={busy} onClick={saveSource} className='flex w-full items-center justify-center gap-2 rounded-2xl bg-emerald-800 px-4 py-3 font-black text-white'>{editingSourceId ? <Save size={18} /> : <Plus size={18} />} {editingSourceId ? 'ذخیره منبع' : 'افزودن منبع'}</button>
        </div>
      </section>
      <section className='rounded-3xl border border-zinc-200 bg-white p-6 shadow-sm'>
        <div className='mb-5 flex items-center justify-between'><h3 className='text-xl font-black'>منابع ثبت‌شده</h3><span className='text-sm text-zinc-500'>{sources.length} منبع</span></div>
        {sources.length ? <div className='space-y-3'>{sources.map((source) => (
          <div key={source.id} className='rounded-2xl border border-zinc-100 p-4'>
            <div className='flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between'>
              <div className='min-w-0'><div className='flex items-center gap-2 font-black'><Globe2 size={17} className='text-emerald-700' /> {source.name}</div><div className='mt-1 max-w-md truncate text-xs text-zinc-500' dir='ltr'>{source.url}</div></div>
              <div className='flex flex-wrap items-center gap-2'>
                <button aria-label={`ویرایش منبع ${source.name}`} onClick={() => editSource(source)} className='rounded-xl border p-2 text-zinc-600'><Edit3 size={16} /></button>
                <button onClick={() => toggleSource(source)} className={`flex items-center gap-2 rounded-xl px-3 py-2 text-xs font-bold ${source.active ? 'bg-emerald-50 text-emerald-800' : 'bg-zinc-100 text-zinc-600'}`}>{source.active ? <PauseCircle size={15} /> : <PlayCircle size={15} />}{source.active ? 'توقف' : 'فعال'}</button>
                <button disabled={busy || !source.rightsApproved} onClick={() => runSource(source)} className='flex items-center gap-2 rounded-xl bg-amber-50 px-3 py-2 text-xs font-bold text-amber-800 disabled:opacity-40'><RefreshCw size={15} /> بررسی اکنون</button>
                <button aria-label={`حذف منبع ${source.name}`} onClick={() => deleteSource(source)} className='flex items-center gap-1.5 rounded-xl border border-red-100 px-3 py-2 text-xs font-bold text-red-600'><Trash2 size={16} /> حذف</button>
              </div>
            </div>
            <div className='mt-4 flex flex-wrap gap-2 text-[11px]'>
              <span className='rounded-full bg-zinc-100 px-3 py-1'>{source.sourceType}</span><span className='rounded-full bg-zinc-100 px-3 py-1'>{source.language}</span><span className='rounded-full bg-zinc-100 px-3 py-1'>{source.topic}</span>
              <span className='rounded-full bg-zinc-100 px-3 py-1'>هر {source.intervalMinutes} دقیقه</span><span className={`rounded-full px-3 py-1 ${source.autoPublish ? 'bg-emerald-100 text-emerald-800' : 'bg-amber-100 text-amber-800'}`}>{source.autoPublish ? 'نشر خودکار' : 'تأیید دستی'}</span>
              {source.active && source.rightsApproved ? <span className='rounded-full bg-emerald-50 px-3 py-1 text-emerald-800'>پایش خودکار</span> : source.active ? <span className='rounded-full bg-red-50 px-3 py-1 text-red-700'>متوقف: مجوز ثبت نشده</span> : <span className='rounded-full bg-zinc-100 px-3 py-1 text-zinc-600'>پایش خاموش</span>}{source.ingestImage && <span className='rounded-full bg-sky-50 px-3 py-1 text-sky-800'>{source.language === 'English' || /bbc\.|بی[‌\s-]*بی[‌\s-]*سی/i.test(`${source.name} ${source.url}`) ? 'عکس مرتبط آزاد' : 'عکس منبع'}</span>}
              <span className={`rounded-full px-3 py-1 ${source.lastError ? 'bg-amber-50 text-amber-800' : 'bg-emerald-50 text-emerald-800'}`}>{source.lastError ? 'تلاش مجدد خودکار' : 'سالم'}</span>
            </div>
            <div className='mt-3 text-xs text-zinc-400'>آخرین بررسی: {dateText(source.lastCheckedAt)} · آخرین دریافت موفق: {dateText(source.lastSuccessAt)} · مطالب دریافت‌شده: {source.fetchedCount || 0}</div>
            {source.lastError && <div className='mt-2 rounded-xl bg-amber-50 p-3 text-xs text-amber-800'><strong>علت دقیق:</strong> {sourceErrorText(source.lastError)}<div className='mt-1 text-[11px] opacity-80'>سامانه در پایش بعدی دوباره تلاش می‌کند.</div></div>}
          </div>
        ))}</div> : <Empty text='هنوز منبعی اضافه نشده است.' />}
      </section>
    </div>
  );

  const Socials = () => (
    <div className='grid gap-6 xl:grid-cols-[.78fr_1.22fr]'>
      <section className='rounded-3xl border border-zinc-200 bg-white p-6 shadow-sm'>
        <h3 className='mb-2 text-xl font-black'>{socialForm.id ? 'ویرایش رسانه' : 'افزودن رسانه'}</h3>
        <p className='mb-5 text-sm leading-7 text-zinc-500'>صفحه را ثبت کن؛ پس از ثبت مجوز رسمی، مطالب نشرشده به‌صورت خودکار ارسال می‌شوند.</p>
        <div className='space-y-4'>
          <select aria-label='نام شبکه اجتماعی' value={socialForm.network} onChange={(event) => setSocialForm((current) => ({ ...current, network: event.target.value }))} className='w-full rounded-2xl border px-4 py-3'><option>Facebook</option><option>Instagram</option><option>Telegram</option><option>X</option></select>
          <input aria-label='لینک صفحه یا حساب' inputMode='url' value={socialForm.pageUrl} onChange={(event) => setSocialForm((current) => ({ ...current, pageUrl: event.target.value }))} className='w-full rounded-2xl border px-4 py-3' placeholder='https://facebook.com/sayehnews' dir='ltr' />
          <label className='flex items-center justify-between rounded-2xl bg-zinc-50 p-4 text-sm'><span>نشر خودکار همه مطالب</span><input type='checkbox' checked={socialForm.autoShare} onChange={(event) => setSocialForm((current) => ({ ...current, autoShare: event.target.checked }))} /></label>
          <label className='flex items-center justify-between rounded-2xl bg-zinc-50 p-4 text-sm'><span>فعال بودن رسانه</span><input type='checkbox' checked={socialForm.enabled} onChange={(event) => setSocialForm((current) => ({ ...current, enabled: event.target.checked }))} /></label>
          <button disabled={busy} onClick={saveSocial} className='flex w-full items-center justify-center gap-2 rounded-2xl bg-emerald-800 px-4 py-3 font-black text-white'><Link2 size={18} /> {socialForm.id ? 'ذخیره تغییرات' : 'ذخیره رسانه'}</button>
          {socialForm.id && <button onClick={() => setSocialForm(emptySocial)} className='w-full rounded-2xl border px-4 py-3 text-sm'>لغو ویرایش</button>}
        </div>
      </section>
      <section className='rounded-3xl border border-zinc-200 bg-white p-6 shadow-sm'>
        <h3 className='mb-5 text-xl font-black'>رسانه‌های ثبت‌شده</h3>
        {socials.length ? <div className='grid gap-4 sm:grid-cols-2'>{socials.map((social) => (
          <div key={social.id} className='rounded-3xl border border-zinc-100 p-5'>
            <div className='mb-4 flex items-center justify-between'><div className='rounded-2xl bg-emerald-50 p-3 text-emerald-800'><Share2 /></div><span className={`rounded-full px-3 py-1 text-[11px] ${social.configured ? 'bg-emerald-100 text-emerald-800' : 'bg-amber-100 text-amber-800'}`}>{social.status}</span></div>
            <div className='text-lg font-black'>{social.network}</div><div className='mt-2 truncate text-xs text-zinc-500' dir='ltr'>{social.pageUrl}</div>
            <div className='mt-4 flex items-center gap-2 text-xs text-zinc-600'>{social.enabled ? <Wifi size={15} className='text-emerald-700' /> : <WifiOff size={15} />} {social.enabled ? 'رسانه فعال' : 'رسانه متوقف'}</div>
            <div className='mt-2 flex items-center gap-2 text-xs text-zinc-600'>{social.autoShare ? <CheckCircle2 size={15} className='text-emerald-700' /> : <Clock3 size={15} />} {social.autoShare ? 'نشر خودکار فعال' : 'نشر دستی'}</div>
            {social.lastError && <div className='mt-3 rounded-xl bg-red-50 p-3 text-xs text-red-700'>{social.lastError}</div>}
            <div className='mt-4 flex gap-2'><button onClick={() => editSocial(social)} className='flex-1 rounded-xl border px-3 py-2 text-xs font-bold'>ویرایش</button><button onClick={() => toggleSocial(social)} className='flex-1 rounded-xl bg-zinc-100 px-3 py-2 text-xs font-bold'>{social.enabled ? 'توقف' : 'فعال'}</button><button aria-label={`حذف ${social.network}`} onClick={() => deleteSocial(social)} className='rounded-xl border border-red-100 p-2 text-red-600'><Trash2 size={16} /></button></div>
          </div>
        ))}</div> : <Empty text='هنوز رسانه‌ای ثبت نشده است.' />}
      </section>
    </div>
  );

  const VisitorsPanel = () => { const data = visitorAnalytics; if (!data) return <div className='rounded-3xl border bg-white p-8 text-center'><button onClick={() => void loadVisitorAnalytics()} className='rounded-2xl bg-emerald-800 px-5 py-3 font-black text-white'>دریافت آمار بازدیدکنندگان</button></div>; return <div className='space-y-6'><div className='grid gap-4 sm:grid-cols-2 xl:grid-cols-6'>{[['مخاطبان یکتا',data.totalRegistered],['کلیک روی ورود',data.totalEntryClicks],['ورود موفق',data.totalSiteOpens],['بازدید خبرها',data.totalArticleViews],['تلاش نصب',data.totalInstallAttempts],['نصب موفق',data.totalInstalls]].map(([label,value]) => <div key={String(label)} className='rounded-3xl border bg-white p-5 shadow-sm'><div className='text-3xl font-black'>{Number(value)}</div><div className='mt-1 text-sm text-zinc-500'>{String(label)}</div></div>)}</div><section className='rounded-3xl border bg-white p-5 shadow-sm'><div className='mb-5 flex items-center justify-between gap-3'><div><h3 className='text-xl font-black'>ورود و بازدید مخاطبان</h3><p className='mt-1 text-xs text-zinc-500'>برای مخاطبان عمومی ایمیل دریافت نمی‌شود؛ کلیک ورود و ورود موفق جداگانه ثبت می‌شود.</p></div><button onClick={() => void loadVisitorAnalytics()} className='rounded-xl border p-2 text-emerald-800' aria-label='تازه‌سازی آمار'><RefreshCw size={18} /></button></div><div className='overflow-x-auto'><table className='w-full min-w-[980px] text-right text-sm'><thead className='border-b text-xs text-zinc-500'><tr><th className='p-3'>مخاطب</th><th className='p-3'>کلیک‌ها</th><th className='p-3'>ورودها</th><th className='p-3'>خبرهای دیده‌شده</th><th className='p-3'>نصب</th><th className='p-3'>اولین فعالیت</th><th className='p-3'>آخرین فعالیت</th></tr></thead><tbody>{data.items.map((item) => <tr key={item.userId} className='border-b border-zinc-100'><td className='p-3'><div className='font-black'>{item.name||'مخاطب ناشناس'}</div>{item.email&&<div className='text-xs text-zinc-500' dir='ltr'>{item.email}</div>}</td><td className='p-3 font-black'>{item.entryClicks}</td><td className='p-3 font-black'>{item.siteOpens}</td><td className='p-3 font-black'>{item.articleViews}</td><td className='p-3'>{item.installs}</td><td className='p-3 text-xs'>{dateText(item.firstSeenAt)}</td><td className='p-3 text-xs'>{dateText(item.lastSeenAt)}</td></tr>)}</tbody></table></div>{!data.items.length && <Empty text='هنوز بازدیدکننده‌ای ثبت نشده است.' />}</section></div>; };

  const StaffPanel = () => (
    <div className='grid gap-6 xl:grid-cols-[.75fr_1.25fr]'>
      <section className='rounded-3xl border border-zinc-200 bg-white p-6 shadow-sm'>
        <div className='mb-2 flex items-center gap-2'><UserPlus className='text-emerald-800' /><h3 className='text-xl font-black'>{editingStaffId ? 'ویرایش صلاحیت' : 'افزودن عضو'}</h3></div>
        <p className='mb-5 text-sm leading-7 text-zinc-500'>عضو باید هنگام ورود، همین ایمیل را در حساب گوگل خود داشته باشد.</p>
        <div className='space-y-4'>
          <input aria-label='نام عضو' value={staffForm.name} onChange={(event) => setStaffForm((current) => ({ ...current, name: event.target.value }))} className='w-full rounded-2xl border px-4 py-3' placeholder='نام و نام خانوادگی' />
          <input aria-label='ایمیل عضو' inputMode='email' value={staffForm.email} onChange={(event) => setStaffForm((current) => ({ ...current, email: event.target.value }))} className='w-full rounded-2xl border px-4 py-3' placeholder='email@gmail.com' dir='ltr' />
          <select aria-label='نقش عضو' value={staffForm.role} onChange={(event) => setStaffForm((current) => ({ ...current, role: event.target.value as StaffRole }))} className='w-full rounded-2xl border px-4 py-3'>{Object.entries(roleLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select>
          <label className='flex items-center justify-between rounded-2xl bg-zinc-50 p-4 text-sm'><span>حساب فعال باشد</span><input type='checkbox' checked={staffForm.active} onChange={(event) => setStaffForm((current) => ({ ...current, active: event.target.checked }))} /></label>
          <button disabled={busy} onClick={saveStaff} className='flex w-full items-center justify-center gap-2 rounded-2xl bg-emerald-800 px-4 py-3 font-black text-white'><Save size={18} /> {editingStaffId ? 'ذخیره صلاحیت' : 'افزودن عضو'}</button>
          {editingStaffId && <button onClick={resetStaff} className='w-full rounded-2xl border px-4 py-3 text-sm'>لغو ویرایش</button>}
        </div>
      </section>
      <section className='rounded-3xl border border-zinc-200 bg-white p-6 shadow-sm'>
        <div className='mb-5 flex items-center justify-between'><h3 className='text-xl font-black'>اعضای پنل مدیریت</h3><span className='text-sm text-zinc-500'>{staff.length} نفر</span></div>
        {staff.length ? <div className='space-y-3'>{staff.map((member) => (
          <div key={member.id} className={`rounded-2xl border p-4 ${member.primary ? 'border-amber-200 bg-amber-50/50' : 'border-zinc-100'}`}>
            <div className='flex flex-col gap-3 sm:flex-row sm:items-center sm:justify-between'>
              <div className='min-w-0'><div className='flex items-center gap-2 font-black'><ShieldCheck size={17} className={member.active ? 'text-emerald-700' : 'text-zinc-400'} /> {member.name}</div><div className='mt-1 truncate text-xs text-zinc-500' dir='ltr'>{member.email}</div></div>
              <div className='flex items-center gap-2'><span className={`rounded-full px-3 py-1 text-xs ${member.active ? 'bg-emerald-100 text-emerald-800' : 'bg-zinc-100 text-zinc-600'}`}>{roleLabels[member.role]} · {member.active ? 'فعال' : 'غیرفعال'}</span>{member.primary ? <span className='rounded-full bg-amber-200 px-3 py-1 text-xs text-amber-900'>مالک اصلی</span> : <><button onClick={() => editStaff(member)} className='rounded-xl border p-2 text-zinc-600'><Edit3 size={16} /></button><button onClick={() => deleteStaff(member)} className='rounded-xl border border-red-100 p-2 text-red-600'><Trash2 size={16} /></button></>}</div>
            </div>
            <div className='mt-3 text-[11px] text-zinc-400'>آخرین ورود: {dateText(member.lastLoginAt)}</div>
          </div>
        ))}</div> : <Empty text='هنوز عضوی اضافه نشده است.' />}
      </section>
    </div>
  );

  const SettingsPanel = () => (
    <div className='space-y-6'>
      {adminSession?.role === 'owner' && <div className='rounded-3xl border border-amber-200 bg-amber-50 p-5'><div className='flex items-center gap-2 font-black text-amber-950'><ShieldCheck size={19} /> مرکز کنترل مالک</div><p className='mt-2 text-sm leading-7 text-amber-900'>تنظیمات مادر خبرگزاری از اینجا ذخیره می‌شود و بعد از رفرش نیز باقی می‌ماند.</p></div>}
      <div className='grid gap-6 lg:grid-cols-2'><section className='rounded-3xl border bg-white p-6 shadow-sm'><h3 className='mb-5 text-xl font-black'>هویت، لوگو و زبان</h3><div className='mb-5 flex items-center gap-4 rounded-2xl bg-zinc-950 p-5 text-white'><Logo large /><div><div className='font-black'>{settingsData.brandName}</div><div className='mt-2 text-xs text-zinc-400'>{settingsData.tagline}</div></div></div><div className='space-y-3'><input aria-label='نام فارسی رسانه' value={settingsData.siteTitle} onChange={(event) => setSettingsData((current) => ({ ...current, siteTitle: event.target.value }))} className='w-full rounded-2xl border px-4 py-3' /><select aria-label='زبان پیش‌فرض' value={settingsData.defaultLanguage} onChange={(event) => setSettingsData((current) => ({ ...current, defaultLanguage: event.target.value }))} className='w-full rounded-2xl border px-4 py-3'>{languages.map((language) => <option key={language}>{language}</option>)}</select>{adminSession?.role === 'owner' && <><input aria-label='نام لاتین رسانه' value={settingsData.brandName} onChange={(event) => setSettingsData((current) => ({ ...current, brandName: event.target.value }))} className='w-full rounded-2xl border px-4 py-3' /><input aria-label='شعار رسانه' value={settingsData.tagline} onChange={(event) => setSettingsData((current) => ({ ...current, tagline: event.target.value }))} className='w-full rounded-2xl border px-4 py-3' /><label className='flex cursor-pointer items-center justify-between rounded-2xl border border-dashed p-4 text-sm'><span className='flex items-center gap-2'><Upload size={17} /> {settingsLogoFile ? settingsLogoFile.name : 'تغییر لوگوی خبرگزاری'}</span><input type='file' accept='image/*' className='hidden' onChange={(event) => setSettingsLogoFile(event.target.files?.[0] || null)} /></label></>}</div></section><section className='rounded-3xl border bg-white p-6 shadow-sm'><h3 className='mb-5 text-xl font-black'>تنظیمات نشر</h3><div className='space-y-3 text-sm'><label className='flex items-center justify-between rounded-2xl bg-zinc-50 p-4'><span>نوار خبر فوری</span><input type='checkbox' checked={settingsData.breakingBar} onChange={(event) => setSettingsData((current) => ({ ...current, breakingBar: event.target.checked }))} /></label><label className='flex items-center justify-between rounded-2xl bg-zinc-50 p-4'><span>واترمارک خودکار</span><input type='checkbox' checked={settingsData.watermark} onChange={(event) => setSettingsData((current) => ({ ...current, watermark: event.target.checked }))} /></label><label className='flex items-center justify-between rounded-2xl bg-zinc-50 p-4'><span>نگهداری نسخه‌های ویرایش</span><input type='checkbox' checked={settingsData.versioning} onChange={(event) => setSettingsData((current) => ({ ...current, versioning: event.target.checked }))} /></label><label className='flex items-center justify-between rounded-2xl bg-zinc-50 p-4'><span>نشر خودکار شبکه‌ها</span><input type='checkbox' checked={settingsData.autoShare} onChange={(event) => setSettingsData((current) => ({ ...current, autoShare: event.target.checked }))} /></label></div></section></div>
      {adminSession?.role === 'owner' && <div className='grid gap-6 lg:grid-cols-2'><section className='rounded-3xl border bg-white p-6 shadow-sm'><h3 className='mb-5 text-xl font-black'>متن‌ها و منوهای عمومی</h3><div className='grid gap-3 sm:grid-cols-2'>{[['عنوان خانه','homeTitle'],['عنوان خبرها','newsTitle'],['عنوان تحلیل','analysisTitle'],['عنوان آرشیف','archiveTitle'],['عنوان بخش انگلیسی','englishTitle'],['نام تحریریه انگلیسی','englishDeskLabel'],['منوی خانه','navHome'],['منوی خبرها','navNews'],['منوی تحلیل','navAnalysis'],['منوی آرشیف','navArchive'],['منوی انگلیسی','navEnglish'],['برچسب خبر فوری','breakingLabel'],['عنوان تحریریه','newsroomLabel'],['متن فوتر','footerText'],['سال فوتر','footerYear'],['متن جست‌وجو','searchPlaceholder']].map(([label,key]) => <label key={key} className={key === 'footerText' || key === 'searchPlaceholder' ? 'sm:col-span-2' : ''}><span className='mb-1 block text-xs font-bold text-zinc-500'>{label}</span><input value={String(settingsData[key as keyof AppSettings] ?? '')} onChange={(event) => setSettingsData((current) => ({ ...current, [key]: event.target.value }))} className='w-full rounded-2xl border px-4 py-3' /></label>)}</div></section><section className='rounded-3xl border bg-white p-6 shadow-sm'><h3 className='mb-5 text-xl font-black'>قواعد خبرگزاری</h3><div className='space-y-3'><label className='block'><span className='mb-1 block text-xs font-bold text-zinc-500'>انتقال خبر به آرشیف پس از چند روز</span><input type='number' min='1' max='3650' value={settingsData.archiveDays} onChange={(event) => setSettingsData((current) => ({ ...current, archiveDays: Math.max(1, Number(event.target.value) || 1) }))} className='w-full rounded-2xl border px-4 py-3' /></label><label className='block'><span className='mb-1 block text-xs font-bold text-zinc-500'>نمایش شمار بازدید از عدد</span><input type='number' min='0' value={settingsData.viewCountThreshold} onChange={(event) => setSettingsData((current) => ({ ...current, viewCountThreshold: Math.max(0, Number(event.target.value) || 0) }))} className='w-full rounded-2xl border px-4 py-3' /></label><label className='block'><span className='mb-1 block text-xs font-bold text-zinc-500'>حداکثر واژه بازنشر خودکار</span><input type='number' min='100' max='5000' value={settingsData.autoWordLimit} onChange={(event) => setSettingsData((current) => ({ ...current, autoWordLimit: Math.min(5000, Math.max(100, Number(event.target.value) || 1000)) }))} className='w-full rounded-2xl border px-4 py-3' /></label><label className='flex items-center justify-between rounded-2xl bg-zinc-50 p-4 text-sm'><span>تصویر/ویدیو برای نشر اجباری باشد</span><input type='checkbox' checked={settingsData.visualRequired} onChange={(event) => setSettingsData((current) => ({ ...current, visualRequired: event.target.checked }))} /></label></div></section><section className='rounded-3xl border bg-white p-6 shadow-sm'><h3 className='mb-5 text-xl font-black'>کنترل پایش منابع</h3><div className='space-y-3'><label className='flex items-center justify-between rounded-2xl bg-zinc-50 p-4 text-sm'><span>پایش خودکار منابع فعال باشد</span><input type='checkbox' checked={settingsData.sourceMonitorEnabled} onChange={(event) => setSettingsData((current) => ({ ...current, sourceMonitorEnabled: event.target.checked }))} /></label><label className='flex items-center justify-between rounded-2xl bg-zinc-50 p-4 text-sm'><span>آریانا منبع اجباری اصلی باشد</span><input type='checkbox' checked={settingsData.forceArianaSource} onChange={(event) => setSettingsData((current) => ({ ...current, forceArianaSource: event.target.checked }))} /></label><div className='grid grid-cols-2 gap-3'><input aria-label='فاصله آریانا' type='number' min='5' value={settingsData.primaryIntervalMinutes} onChange={(event) => setSettingsData((current) => ({ ...current, primaryIntervalMinutes: Math.max(5, Number(event.target.value) || 5) }))} className='rounded-2xl border px-3 py-2' /><input aria-label='خبر آریانا در هر دور' type='number' min='1' max='10' value={settingsData.primaryItemsPerCycle} onChange={(event) => setSettingsData((current) => ({ ...current, primaryItemsPerCycle: Math.min(10, Math.max(1, Number(event.target.value) || 3)) }))} className='rounded-2xl border px-3 py-2' /><input aria-label='منابع ثانوی در هر دور' type='number' min='1' max='5' value={settingsData.secondarySourcesPerCycle} onChange={(event) => setSettingsData((current) => ({ ...current, secondarySourcesPerCycle: Math.min(5, Math.max(1, Number(event.target.value) || 1)) }))} className='rounded-2xl border px-3 py-2' /><input aria-label='خبر منبع اولویت بالا' type='number' min='1' max='10' value={settingsData.secondaryHighItemsPerCycle} onChange={(event) => setSettingsData((current) => ({ ...current, secondaryHighItemsPerCycle: Math.min(10, Math.max(1, Number(event.target.value) || 3)) }))} className='rounded-2xl border px-3 py-2' /></div></div></section><section className='rounded-3xl border bg-white p-6 shadow-sm'><h3 className='mb-5 text-xl font-black'>کنترل تصاویر منابع</h3><div className='space-y-3'><label className='flex items-center justify-between rounded-2xl bg-zinc-50 p-4 text-sm'><span>جست‌وجوی تصویر آزاد جایگزین</span><input type='checkbox' checked={settingsData.openverseFallback} onChange={(event) => setSettingsData((current) => ({ ...current, openverseFallback: event.target.checked }))} /></label><label className='flex items-center justify-between rounded-2xl bg-zinc-50 p-4 text-sm'><span>تصویر BBC با تصویر آزاد جایگزین شود</span><input type='checkbox' checked={settingsData.bbcReplaceSourceImage} onChange={(event) => setSettingsData((current) => ({ ...current, bbcReplaceSourceImage: event.target.checked }))} /></label><input aria-label='خبر منبع عادی در هر دور' type='number' min='1' max='10' value={settingsData.secondaryNormalItemsPerCycle} onChange={(event) => setSettingsData((current) => ({ ...current, secondaryNormalItemsPerCycle: Math.min(10, Math.max(1, Number(event.target.value) || 2)) }))} className='w-full rounded-2xl border px-4 py-3' /></div></section></div>}
      <button disabled={busy} onClick={saveSettings} className='flex w-full items-center justify-center gap-2 rounded-2xl bg-emerald-800 px-4 py-4 font-black text-white'><Save size={18} /> ذخیره تنظیمات {adminSession?.role === 'owner' ? 'مالک' : ''}</button>
    </div>
  );

  const AdminApp = () => (
    <div className='min-h-screen bg-zinc-100 md:flex' dir='rtl'>
      {AdminSidebar()}
      <main className='min-w-0 flex-1 p-4 md:p-8'>
        <div className='mb-7 flex items-center justify-between gap-4'>
          <div><p className='text-xs font-bold text-emerald-800'>پنل مدیریت SAYEH NEWS</p><h1 className='mt-1 text-3xl font-black text-zinc-950'>{adminTab === 'dashboard' ? 'داشبورد مرکزی' : adminTab === 'articles' ? 'مدیریت مطالب' : adminTab === 'sources' ? 'مدیریت منابع' : adminTab === 'socials' ? 'نشر شبکه‌های اجتماعی' : adminTab === 'visitors' ? 'آمار بازدیدکنندگان' : adminTab === 'staff' ? 'کاربران و صلاحیت‌ها' : 'تنظیمات'}</h1></div>
          <div className='hidden items-center gap-2 rounded-full bg-white px-4 py-2 text-xs text-zinc-500 shadow-sm sm:flex'><span className='h-2 w-2 rounded-full bg-emerald-500' /> سامانه فعال است</div>
        </div>
        {adminTab === 'dashboard' && Dashboard()}
        {adminTab === 'articles' && Articles()}
        {adminTab === 'sources' && Sources()}
        {adminTab === 'socials' && Socials()}
        {adminTab === 'visitors' && adminSession?.role === 'owner' && VisitorsPanel()}
        {adminTab === 'settings' && SettingsPanel()}
        {adminTab === 'staff' && adminSession?.role === 'owner' && StaffPanel()}
      </main>
    </div>
  );

  const CustomVisitorGate=()=>{const copy=VISITOR_AUTH_COPY[publicLanguage];return <div className='min-h-screen bg-zinc-950 px-4 py-10 text-white' dir={publicDir}><div className='mx-auto flex min-h-[80vh] max-w-lg items-center justify-center'><section className='w-full rounded-[2rem] border border-white/10 bg-zinc-900 p-7 shadow-2xl md:p-9'><div className='mb-5 flex justify-center'><Logo large/></div><div className='text-center text-sm font-black tracking-[0.18em] text-amber-300'>SAYEH NEWS</div><label className='mx-auto mt-4 flex max-w-xs items-center gap-2 rounded-xl border border-white/10 bg-white/5 px-3 py-2'><Languages size={16} className='text-amber-300'/><select aria-label='Language' value={publicLanguage} onChange={(event)=>{const value=event.target.value;if(isPublicLanguage(value))switchPublicLanguage(value);}} className='w-full bg-zinc-900 text-sm text-white outline-none'>{PUBLIC_LANGUAGES.map((language)=><option key={language} value={language}>{PUBLIC_LANGUAGE_META[language].label}</option>)}</select></label>{visitorAuthStep==='credentials'?<><h1 className='mt-7 text-center text-2xl font-black leading-10'>{copy.title}</h1><div className='mt-6 space-y-3'><input aria-label={copy.email} type='email' inputMode='email' autoComplete='email' value={visitorEmail} onChange={(event)=>setVisitorEmail(event.target.value)} placeholder={copy.email} dir='ltr' className='w-full rounded-2xl border border-white/10 bg-zinc-950 px-4 py-4 text-left text-white outline-none focus:border-emerald-600'/><input aria-label={copy.password} type='password' autoComplete='current-password' value={visitorPassword} onChange={(event)=>setVisitorPassword(event.target.value)} placeholder={copy.password} dir='ltr' className='w-full rounded-2xl border border-white/10 bg-zinc-950 px-4 py-4 text-left text-white outline-none focus:border-emerald-600'/></div>{visitorError&&<div role='alert' className='mt-4 rounded-2xl bg-red-950/50 p-4 text-sm text-red-200'>{visitorError}</div>}<button disabled={authBusy} onClick={startVisitorOtp} className='mt-5 flex w-full items-center justify-center gap-2 rounded-2xl bg-emerald-700 px-4 py-4 font-black text-white disabled:opacity-50'><LogIn size={19}/>{authBusy?t.loginChecking:copy.send}</button></>:<><h1 className='mt-7 text-center text-2xl font-black leading-10'>{copy.codeTitle}</h1><p className='mt-2 text-center text-sm text-zinc-400' dir='ltr'>{visitorEmail}</p><input aria-label={copy.code} inputMode='numeric' autoComplete='one-time-code' maxLength={6} value={visitorCode} onChange={(event)=>setVisitorCode(event.target.value.replace(/\D/g,'').slice(0,6))} placeholder={copy.code} dir='ltr' className='mt-6 w-full rounded-2xl border border-white/10 bg-zinc-950 px-4 py-4 text-center text-2xl tracking-[.45em] text-white outline-none focus:border-emerald-600'/>{visitorError&&<div role='alert' className='mt-4 rounded-2xl bg-red-950/50 p-4 text-sm text-red-200'>{visitorError}</div>}<button disabled={authBusy} onClick={verifyVisitorOtp} className='mt-5 flex w-full items-center justify-center gap-2 rounded-2xl bg-emerald-700 px-4 py-4 font-black text-white disabled:opacity-50'><ShieldCheck size={19}/>{authBusy?t.loginChecking:copy.verify}</button><div className='mt-3 grid grid-cols-2 gap-3'><button disabled={authBusy} onClick={startVisitorOtp} className='rounded-2xl border border-white/15 px-3 py-3 text-sm font-bold text-zinc-300'>{copy.resend}</button><button disabled={authBusy} onClick={()=>{setVisitorAuthStep('credentials');setVisitorChallenge('');setVisitorCode('');setVisitorError('');}} className='rounded-2xl border border-white/15 px-3 py-3 text-sm font-bold text-zinc-300'>{copy.change}</button></div></>}</section></div></div>;};
  const VisitorGate=()=>{const copy=ONE_CLICK_ENTRY_COPY[publicLanguage];return <div className='min-h-screen bg-zinc-950 px-4 py-10 text-white' dir={publicDir}><div className='mx-auto flex min-h-[80vh] max-w-lg items-center justify-center'><section className='w-full rounded-[2rem] border border-white/10 bg-zinc-900 p-7 text-center shadow-2xl md:p-9'><div className='mb-5 flex justify-center'><Logo large/></div><div className='text-sm font-black tracking-[0.18em] text-amber-300'>SAYEH NEWS</div><label className='mx-auto mt-4 flex max-w-xs items-center gap-2 rounded-xl border border-white/10 bg-white/5 px-3 py-2'><Languages size={16} className='text-amber-300'/><select aria-label='Language' value={publicLanguage} onChange={(event)=>{const value=event.target.value;if(isPublicLanguage(value))switchPublicLanguage(value);}} className='w-full bg-zinc-900 text-sm text-white outline-none'>{PUBLIC_LANGUAGES.map((language)=><option key={language} value={language}>{PUBLIC_LANGUAGE_META[language].label}</option>)}</select></label><h1 className='mt-7 text-2xl font-black leading-10'>{copy.title}</h1>{visitorError&&<div role='alert' className='mt-4 rounded-2xl bg-red-950/50 p-4 text-sm text-red-200'>{visitorError}</div>}<button disabled={authBusy} onClick={enterVisitor} className='mt-7 flex w-full items-center justify-center gap-2 rounded-2xl bg-emerald-700 px-4 py-4 text-lg font-black text-white disabled:opacity-50'><LogIn size={20}/>{authBusy?t.loginChecking:copy.button}</button>{qaBoundaryRun&&<div className='mt-4 rounded-2xl border border-white/10 bg-white/5 p-3'><button type='button' disabled={qaBoundaryBusy} onClick={runQaBoundaryCheck} className='w-full rounded-xl border border-amber-300/40 px-3 py-2 text-xs font-black text-amber-200 disabled:opacity-50'>Run backend regression</button>{qaBoundaryResult&&<div role='status' className='mt-2 text-xs text-zinc-300'>{qaBoundaryResult}</div>}</div>}</section></div></div>;};

  void CustomVisitorGate;
  if (!visitorAuthChecked) return <div dir={publicDir} className='min-h-screen bg-zinc-950' />;
  if (!visitorSession) return visitorAuthConfigured?<VisitorGate/>:<VisitorGate />;
  return (
    <div dir='rtl'>
      {view === 'admin' && adminSession ? AdminApp() : PublicApp()}
      {loginOpen && <div className='fixed inset-0 z-[90] grid place-items-center bg-black/80 p-4 backdrop-blur-sm' onClick={() => { if (!authBusy) setLoginOpen(false); }}><section role='dialog' aria-modal='true' aria-labelledby='admin-login-title' className='w-full max-w-md overflow-hidden rounded-[2rem] border border-white/10 bg-zinc-950 text-white shadow-2xl' onClick={(event) => event.stopPropagation()}><div className='bg-gradient-to-br from-emerald-950 to-zinc-950 p-7'><div className='mb-5 flex items-start justify-between gap-4'><div className='flex items-center gap-3'><div className='rounded-2xl bg-emerald-800 p-3 text-amber-200'><ShieldCheck size={25} /></div><div><p className='text-xs font-bold text-amber-300'>SAYEH NEWS</p><h2 id='admin-login-title' className='mt-1 text-2xl font-black'>ورود امن مدیریت</h2></div></div><button disabled={authBusy} onClick={() => setLoginOpen(false)} className='rounded-full bg-white/10 p-2 text-zinc-300 disabled:opacity-40' aria-label='بستن پنجره ورود'><X size={19} /></button></div><p className='text-sm leading-7 text-zinc-300'>فقط مالک اصلی و اعضایی که مالک در بخش «کاربران و صلاحیت‌ها» ثبت کرده باشد، می‌توانند وارد پنل شوند.</p></div><div className='space-y-4 p-7'><div className='rounded-2xl border border-emerald-700/30 bg-emerald-950/40 p-4 text-sm leading-7 text-emerald-100'>ایمیل ثبت‌شدهٔ فعلی برای صلاحیت مدیریت بررسی می‌شود؛ رمز حساب هیچ‌گاه در اپ ذخیره نمی‌گردد.</div>{loginError && <div role='alert' className='rounded-2xl border border-red-500/20 bg-red-950/40 p-4 text-sm leading-7 text-red-200'>{loginError}</div>}<button disabled={authBusy} onClick={completeAdminLogin} className='flex w-full items-center justify-center gap-2 rounded-2xl bg-emerald-700 px-4 py-3.5 font-black text-white transition hover:bg-emerald-600 disabled:opacity-50'><LogIn size={19} /> {authBusy ? 'در حال بررسی حساب...' : 'بررسی صلاحیت همین ایمیل'}</button><button disabled={authBusy} onClick={() => setLoginOpen(false)} className='w-full rounded-2xl border border-white/10 px-4 py-3 text-sm text-zinc-400 disabled:opacity-40'>بازگشت به خبرها</button></div></section></div>}
      {installHelp && <div className='fixed inset-0 z-[80] grid place-items-center bg-black/75 p-4 backdrop-blur-sm' onClick={() => setInstallHelp(false)}><section dir={publicDir} className='w-full max-w-xl rounded-[2rem] bg-white p-6 shadow-2xl md:p-8' onClick={(event) => event.stopPropagation()}><div className='mb-5 flex items-center justify-between'><div><p className='text-xs font-bold text-emerald-800'>SAYEH NEWS</p><h2 className='mt-1 text-2xl font-black text-zinc-950'>{t.installTitle}</h2></div><button onClick={() => setInstallHelp(false)} className='rounded-full bg-zinc-100 p-2' aria-label={t.close}><X /></button></div><div className='space-y-3 text-sm leading-7 text-zinc-700'><div className='rounded-2xl bg-emerald-50 p-4'>{t.androidHelp}</div><div className='rounded-2xl bg-zinc-50 p-4'>{t.iphoneHelp}</div><div className='rounded-2xl bg-amber-50 p-4'>{t.desktopHelp}</div></div>{installPrompt&&!isInstalled&&<button onClick={installApp} className='mt-5 flex w-full items-center justify-center gap-2 rounded-2xl bg-amber-300 px-4 py-3 font-black text-zinc-950'><Download size={18}/>{t.install}</button>}<button onClick={() => setInstallHelp(false)} className='mt-3 w-full rounded-2xl bg-emerald-800 px-4 py-3 font-black text-white'>{t.gotIt}</button></section></div>}
      {toast && <div className='fixed bottom-6 right-6 z-[70] rounded-2xl bg-zinc-950 px-5 py-3 text-sm font-bold text-white shadow-2xl'>{toast}</div>}
    </div>
  );
}

export default AppContent;