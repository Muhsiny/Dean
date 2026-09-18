import { ai, db, error, json, requireAdminEmailAllowlist, requireAuth, router, secrets, storage, withScopes } from '@appdeploy/sdk';
import type { AuthUser, RouterMiddleware } from '@appdeploy/sdk';
import { randomUUID } from 'crypto';
import { renderFreeBulletinVideo, synthesizeFreePersianAudio } from './free-video-engine';
import { addSubscription, notifySubscribers, removeSubscriptions } from './realtime-subscribers';
import { canonicalArticleUrl, discoverSourceCandidates, fetchArticleText, findOpenLicensedImage, isAfghanistanRelevantText, likelyDuplicateImportedStory } from './source-monitor';
import { fetchYouTubeTranscript } from './video-transcript';
import { anonymousVisitorIdentity, verifyVisitorSession, visitorAuthRoutes, type VisitorIdentity } from './visitor-auth';

type NewsStatus = 'draft' | 'review' | 'scheduled' | 'published';
type MediaType = 'text' | 'image' | 'video' | 'interview';
type StaffRole = 'owner' | 'manager' | 'deputy' | 'editor' | 'reporter';
type StaffRecord = {
  email: string;
  name: string;
  role: StaffRole;
  active: boolean;
  primary: boolean;
  userId?: string;
  createdAt: number;
  updatedAt: number;
  createdBy: string;
  lastLoginAt?: number;
};
type StaffMember = StaffRecord & { id: string };

const PRIMARY_OWNER_EMAIL = 'sjzjan420@gmail.com';
const ADMIN_EMAILS = [PRIMARY_OWNER_EMAIL];
const STAFF_ROLES: StaffRole[] = ['owner', 'manager', 'deputy', 'editor', 'reporter'];

type NewsRecord = {
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
  mediaPath?: string;
  mediaMime?: string;
  mediaName?: string;
  externalVideoUrl?: string;
  facebookVideoUrl?: string;
  videoBulletinId?: string;
  originUrl?: string;
  originName?: string;
  originPublishedAt?: number; originImageKey?: string;
  originTitle?: string;
  originExcerpt?: string;
  fingerprint?: string;
  sourcePipelineVersion?: number;
  viewCount: number;
  archived?: boolean;
  archivedAt?: number; translationIds?: Record<string, string>;
};

type SourceRecord = {
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
  lastFingerprint?: string;
  lastError?: string;
  fetchedCount: number;
};

type SocialRecord = {
  network: string;
  pageUrl: string;
  autoShare: boolean;
  enabled: boolean;
  status: string;
  lastPublishAt?: number;
  lastError?: string;
  createdAt: number;
  updatedAt: number;
};

type SettingsRecord = {
  siteTitle: string; brandName: string; tagline: string; newsroomLabel: string; homeTitle: string; newsTitle: string; analysisTitle: string; archiveTitle: string; englishTitle: string; englishDeskLabel: string; navHome: string; navNews: string; navAnalysis: string; navArchive: string; navEnglish: string; breakingLabel: string; footerText: string; footerYear: string; searchPlaceholder: string; defaultLanguage: string; breakingBar: boolean; watermark: boolean; versioning: boolean; autoShare: boolean; viewCountThreshold: number; archiveDays: number; visualRequired: boolean; sourceMonitorEnabled: boolean; forceArianaSource: boolean; primaryIntervalMinutes: number; primaryItemsPerCycle: number; secondarySourcesPerCycle: number; secondaryHighItemsPerCycle: number; secondaryNormalItemsPerCycle: number; autoWordLimit: number; openverseFallback: boolean; bbcReplaceSourceImage: boolean; logoPath?: string; logoMime?: string; logoName?: string; updatedAt: number;
};

type VisitorEventKind = 'entry_click' | 'site_open' | 'article_view' | 'install_attempt' | 'install_success';
type VisitorEventRecord = { userId: string; email: string; name: string; event: VisitorEventKind; articleId?: string; createdAt: number };
type VisitorAnalyticsItem = { userId: string; email: string; name: string; firstSeenAt: number; lastSeenAt: number; entryClicks: number; siteOpens: number; articleViews: number; installAttempts: number; installs: number };
type MediaInput = { data?: string; mimeType?: string; name?: string };

const defaultSettings: SettingsRecord = { siteTitle: 'خبرگزاری سایه', brandName: 'SAYEH NEWS', tagline: 'پنهان از نگاه‌ها، مسلط بر رویدادها', newsroomLabel: 'تحریریه', homeTitle: 'پنهان از نگاه‌ها', newsTitle: 'آخرین خبرها', analysisTitle: 'تحلیل رویدادها', archiveTitle: 'آرشیف خبرها', englishTitle: 'English News', englishDeskLabel: 'English Desk', navHome: 'خانه', navNews: 'خبرها', navAnalysis: 'تحلیل', navArchive: 'آرشیف', navEnglish: 'English', breakingLabel: 'خبر فوری', footerText: 'تمام حقوق این خبرگزاری برای سایه محفوظ می‌باشد.', footerYear: '۲۰۲۳', searchPlaceholder: 'جست‌وجوی خبر و تحلیل...', defaultLanguage: 'دری', breakingBar: true, watermark: true, versioning: true, autoShare: true, viewCountThreshold: 500, archiveDays: 20, visualRequired: true, sourceMonitorEnabled: true, forceArianaSource: true, primaryIntervalMinutes: 5, primaryItemsPerCycle: 3, secondarySourcesPerCycle: 1, secondaryHighItemsPerCycle: 3, secondaryNormalItemsPerCycle: 2, autoWordLimit: 1000, openverseFallback: true, bbcReplaceSourceImage: true, updatedAt: Date.now() }; 

const textValue = (value: unknown) => typeof value === 'string' ? value.trim() : '';
const SOURCE_PIPELINE_VERSION = 6;
const importedStrongBoundary = /(?:^|\s)(?:continue\s+reading|read\s+more|latest\s+news|related\s+(?:news|stories|topics?)|ariana\s+news\s+related|don['’]?t\s+miss|advert(?:isement)?|you\s+may\s+like|by\s+ariana\s+news|most\s+viewed|ادامه\s+مطلب|بیشتر\s+بخوانید|آخرین\s+خبرها|تازه‌ترین\s+خبرها|خبرهای\s+مرتبط|مطالب\s+مرتبط|پربازدید(?:ترین)?)(?=\s|$|:)/i;
const importedLeadMetadata = /(?:^|\s)(?:منتشر\s+شده|نشر\s+شده|published\b|توسط\s+ariana\s+news|\d+\s*(?:minutes?|hours?)\s+(?:ago|پیش)|\d+\s*ساعت\s+ago)(?=\s|$)/i;
const importedNoiseTest = /continue\s+reading|ariana\s+news\s+related|related\s+topics?|don['’]?t\s+miss|advert(?:isement)?|you\s+may\s+like|by\s+ariana\s+news|منتشر\s+شده\s+\d+\s*(?:minutes?|hours?)|آخرین\s+خبرها|خبرهای\s+مرتبط|مطالب\s+مرتبط|پربازدید/i;
const normalizeParagraphText = (value: string) => value.replace(/\r\n?/g, '\n').replace(/\u00a0/g, ' ').split(/\n{2,}/).flatMap((block) => { const cleaned=block.trim(); if(!cleaned)return []; const structured=cleaned.match(/^(#{1,6}\s+[^\n]+)\n+([\s\S]+)$/); if(!structured)return [cleaned.replace(/\s*\n\s*/g, ' ').replace(/[ \t]+/g, ' ').trim()]; return [structured[1].replace(/[ \t]+/g,' ').trim(),structured[2].replace(/\s*\n\s*/g,' ').replace(/[ \t]+/g,' ').trim()]; }).filter(Boolean).join('\n\n').trim();
const cleanSourceText = (value: string, repeatedTitle = '') => {
  let normalized = normalizeParagraphText(value);
  const exactTitle = normalizeParagraphText(repeatedTitle);
  while (exactTitle && normalized.startsWith(exactTitle)) normalized = normalized.slice(exactTitle.length).trim();
  for (let pass = 0; pass < 3; pass += 1) {
    const lead = normalized.slice(0, 500).match(importedLeadMetadata);
    if (!lead || lead.index == null || lead.index > 180) break;
    normalized = normalizeParagraphText(`${normalized.slice(0, lead.index)} ${normalized.slice(lead.index + lead[0].length)}`);
  }
  const strong = normalized.search(importedStrongBoundary);
  return normalizeParagraphText(strong >= 0 ? normalized.slice(0, strong) : normalized);
};
const normalizeGeneratedText = (value: string) => normalizeParagraphText(value);
const isArianaUrl = (value?: string) => { try { return Boolean(value && new URL(value).hostname.toLowerCase().replace(/^www\./, '') === 'ariananews.af'); } catch { return false; } };
const isArianaImportedRecord = (item: NewsRecord) => isArianaUrl(item.originUrl);
const newsPublishedTimestamp = (item: Pick<NewsRecord, 'publishedAt' | 'createdAt' | 'updatedAt'>) => item.publishedAt || item.createdAt || item.updatedAt || 0;
const archiveAgeMs = (days = 20) => Math.max(1, Math.min(3650, days)) * 24 * 60 * 60 * 1000;
const hasReachedNewsArchiveAge = (item: Pick<NewsRecord, 'publishedAt' | 'createdAt' | 'updatedAt'>, now = Date.now(), days = 20) => { const published = newsPublishedTimestamp(item); return Boolean(published && now - published >= archiveAgeMs(days)); };
const isNewsArchiveEligible = (item: Pick<NewsRecord, 'section' | 'status' | 'publishedAt' | 'createdAt' | 'updatedAt'>, now = Date.now(), days = 20) => item.status === 'published' && item.section !== 'analysis' && hasReachedNewsArchiveAge(item, now, days);
const sanitizeLegacyImportedRecord = (item: NewsRecord & { id: string }) => { item = item.archived && item.section === 'analysis' ? { ...item, archived: false, archivedAt: undefined } : item;
  if (!isArianaImportedRecord(item) || !importedNoiseTest.test(`${item.summary}\n${item.body}`)) return item;
  const title = normalizeGeneratedText(item.title) || item.title.trim();
  const summary = cleanSourceText(item.summary, title);
  const body = cleanSourceText(item.body, title);
  return { ...item, title, summary: summary || body.slice(0, 220) || title, body: body || title };
};
const limitWords = (value: string, maxWords: number) => { const words = value.trim().split(/\s+/); return words.length <= maxWords ? value.trim() : words.slice(0, maxWords).join(' '); };
const safeUrl = (value: unknown) => typeof value === 'string' && /^https?:\/\//i.test(value.trim());
const safeYoutubeEmbed = (value: unknown) => { const url = textValue(value); return /^https:\/\/www\.youtube-nocookie\.com\/embed\/[A-Za-z0-9_-]{6,20}$/.test(url) ? url : ''; }; const safeFacebookVideoUrl=(value:unknown)=>{const url=textValue(value);return /^https:\/\/(?:www\.)?facebook\.com\/[^\s]+$/i.test(url)?url:'';}; const hasRenderableVisual = (item: Pick<NewsRecord, 'mediaPath' | 'mediaMime' | 'externalVideoUrl' | 'facebookVideoUrl'>) => Boolean(item.facebookVideoUrl || item.externalVideoUrl || (item.mediaPath && !/^audio\//i.test(item.mediaMime || '')));
const normalizeSourceUrl = (value: unknown, language = '') => {
  let raw = textValue(value).replace(/^[<\s"'«»]+|[>\s"'«»،؛]+$/g, '');
  if (!raw) return '';
  if (!/^https?:\/\//i.test(raw)) raw = `https://${raw.replace(/^\/+/, '')}`;
  try {
    const parsed = new URL(raw);
    if (!/^https?:$/.test(parsed.protocol) || !parsed.hostname) return '';
    parsed.hash = '';
    const host = parsed.hostname.toLowerCase().replace(/^www\./, '');
    const path = parsed.pathname.replace(/\/+$/, '') || '/';
    if (host === 'rta.af' && ['/', '/fa', '/fa/home'].includes(path) && /دری|فارسی|farsi|dari/i.test(language || 'دری')) parsed.pathname = '/fa/home/';
    if (host === 'tolonews.com' && ['/', '/fa'].includes(path) && /دری|فارسی|farsi|dari/i.test(language || 'دری')) parsed.pathname = '/fa/';
    return parsed.toString();
  } catch {
    return '';
  }
};
const boolValue = (value: unknown, fallback = false) => typeof value === 'boolean' ? value : fallback;
const numberValue = (value: unknown, fallback = 0) => Number.isFinite(Number(value)) ? Number(value) : fallback;
const withoutId = (record: Record<string, unknown>) => {
  const next = { ...record };
  delete next.id;
  return next;
};
const normalizeEmail = (value: unknown) => textValue(value).toLowerCase();
const validEmail = (value: string) => /^[^\s@]+@[^\s@]+\.[^\s@]+$/.test(value);
const permissionsFor = (role: StaffRole) => ({
  manageArticles: true,
  deleteArticles: role !== 'reporter',
  manageSources: role === 'owner' || role === 'manager' || role === 'deputy',
  manageSocials: role === 'owner' || role === 'manager' || role === 'deputy',
  manageSettings: role === 'owner' || role === 'manager',
  manageStaff: role === 'owner'
});
const staffForClient = (member: StaffMember) => {
  const clean: Record<string, unknown> = { ...member };
  delete clean.userId;
  delete clean.createdBy;
  return clean;
};
const listStaffMembers = async (): Promise<StaffMember[]> => {
  const { items } = await db.list<StaffRecord>('staff_members', { limit: 500 });
  return items as StaffMember[];
};
const ensurePrimaryOwner = async (user: AuthUser): Promise<StaffMember | null> => {
  const email = normalizeEmail(user.email);
  if (email !== PRIMARY_OWNER_EMAIL) return null;
  const members = await listStaffMembers();
  const existing = members.find((item) => normalizeEmail(item.email) === PRIMARY_OWNER_EMAIL);
  const now = Date.now();
  const record: StaffRecord = {
    email: PRIMARY_OWNER_EMAIL,
    name: textValue(user.name) || existing?.name || 'مالک اصلی',
    role: 'owner', active: true, primary: true, userId: user.userId,
    createdAt: existing?.createdAt || now, updatedAt: now,
    createdBy: existing?.createdBy || PRIMARY_OWNER_EMAIL, lastLoginAt: now
  };
  if (existing) {
    const [ok] = await db.update('staff_members', [{ id: existing.id, record: record as unknown as Record<string, unknown> }]);
    return ok ? { id: existing.id, ...record } : existing;
  }
  const [id] = await db.add('staff_members', [record as unknown as Record<string, unknown>]);
  return id ? { id, ...record } : null;
};
const resolveStaffMember = async (user: AuthUser, touch = false): Promise<StaffMember | null> => {
  const email = normalizeEmail(user.email);
  if (!email) return null;
  if (email === PRIMARY_OWNER_EMAIL) return ensurePrimaryOwner(user);
  const members = await listStaffMembers();
  const member = members.find((item) => normalizeEmail(item.email) === email && item.active);
  if (!member) return null;
  if (!touch) return member;
  const record: StaffRecord = { ...member, email, name: textValue(user.name) || member.name, userId: user.userId, lastLoginAt: Date.now(), updatedAt: Date.now() };
  const [ok] = await db.update('staff_members', [{ id: member.id, record: withoutId(record as unknown as Record<string, unknown>) }]);
  return ok ? { id: member.id, ...record } : member;
};
const requireRoles = (...roles: StaffRole[]): RouterMiddleware => async (ctx) => {
  const member = await resolveStaffMember(ctx.user!, false);
  if (!member) return error('این حساب اجازهٔ ورود به پنل مدیریت را ندارد.', 403);
  if (!roles.includes(member.role)) return error('صلاحیت لازم برای این بخش را ندارید.', 403);
};

const VISITOR_EVENTS_TABLE = 'visitor_events_v3';
const recordVisitorEvent = async (user: AuthUser, event: VisitorEventKind, detail: { articleId?: unknown } = {}) => { const email = normalizeEmail(user.email),anonymous=user.userId.startsWith('anon-'); if (!anonymous&&!validEmail(email)) return false; const record: VisitorEventRecord = { userId: user.userId, email, name: textValue(user.name) || (email?email.split('@')[0]:'مخاطب ناشناس'), event, articleId: textValue(detail.articleId) || undefined, createdAt: Date.now() }; const [id] = await db.add(VISITOR_EVENTS_TABLE, [record as unknown as Record<string, unknown>]); return Boolean(id); };
const listAllVisitorEvents = async () => { const events: Array<VisitorEventRecord & { id: string }> = []; let nextToken: string | undefined; do { const page = await db.list<VisitorEventRecord>(VISITOR_EVENTS_TABLE, { limit: 1000, nextToken }); events.push(...page.items); nextToken = page.nextToken; } while (nextToken); return events; };
const getVisitorAnalytics = async () => { const events = await listAllVisitorEvents(); const byUser = new Map<string, VisitorAnalyticsItem>(); let totalEntryClicks = 0; let totalSiteOpens = 0; let totalArticleViews = 0; let totalInstallAttempts = 0; let totalInstalls = 0; for (const event of [...events].sort((a, b) => a.createdAt - b.createdAt)) { let item = byUser.get(event.userId); if (!item) { item = { userId: event.userId, email: event.email, name: event.name, firstSeenAt: event.createdAt, lastSeenAt: event.createdAt, entryClicks: 0, siteOpens: 0, articleViews: 0, installAttempts: 0, installs: 0 }; byUser.set(event.userId, item); } item.email = event.email; item.name = event.name; item.firstSeenAt = Math.min(item.firstSeenAt, event.createdAt); item.lastSeenAt = Math.max(item.lastSeenAt, event.createdAt); if (event.event === 'entry_click') { item.entryClicks += 1; totalEntryClicks += 1; } if (event.event === 'site_open') { item.siteOpens += 1; totalSiteOpens += 1; } if (event.event === 'article_view') { item.articleViews += 1; totalArticleViews += 1; } if (event.event === 'install_attempt') { item.installAttempts += 1; totalInstallAttempts += 1; } if (event.event === 'install_success') { item.installs += 1; totalInstalls += 1; } } const items = [...byUser.values()].sort((a, b) => b.lastSeenAt - a.lastSeenAt); const recent = [...events].sort((a, b) => b.createdAt - a.createdAt).slice(0, 100).map(({ id: _id, ...event }) => event); return { totalRegistered: items.length, totalEntryClicks, totalSiteOpens, totalArticleViews, totalInstallAttempts, totalInstalls, items, recent }; };

const hashText = (value: string) => {
  let hash = 2166136261;
  for (let index = 0; index < value.length; index += 1) {
    hash ^= value.charCodeAt(index);
    hash = Math.imul(hash, 16777619);
  }
  return String(hash >>> 0);
};

const normalizeStatus = (input: Record<string, unknown>): NewsStatus => {
  if (input.status === 'draft' || input.status === 'review') return input.status;
  if (input.status === 'scheduled' && numberValue(input.publishAt) > Date.now()) return 'scheduled';
  return 'published';
};

const extensionForMime = (mimeType: string) => {
  if (mimeType.includes('avif')) return 'avif'; if (mimeType.includes('png')) return 'png';
  if (mimeType.includes('webp')) return 'webp';
  if (mimeType.includes('gif')) return 'gif';
  if (mimeType.includes('jpeg') || mimeType.includes('jpg')) return 'jpg';
  if (mimeType.includes('mp4')) return 'mp4';
  if (mimeType.includes('webm')) return 'webm';
  if (mimeType.includes('mpeg')) return 'mp3';
  if (mimeType.includes('wav')) return 'wav';
  return 'bin';
};

const stripJpegNonVisualMetadata = (source: Buffer) => {
  if (source.length < 4 || source[0] !== 0xff || source[1] !== 0xd8) return source;
  const parts: Buffer[] = [source.subarray(0, 2)];
  let offset = 2;
  while (offset < source.length) {
    const markerStart = offset;
    if (source[offset] !== 0xff) return source;
    while (offset < source.length && source[offset] === 0xff) offset += 1;
    if (offset >= source.length) return source;
    const marker = source[offset++];
    if (marker === 0xda) { parts.push(source.subarray(markerStart)); offset = source.length; break; }
    if (marker === 0xd9) { parts.push(source.subarray(markerStart, offset)); break; }
    if ((marker >= 0xd0 && marker <= 0xd7) || marker === 0x01) { parts.push(source.subarray(markerStart, offset)); continue; }
    if (offset + 2 > source.length) return source;
    const segmentLength = source.readUInt16BE(offset);
    const segmentEnd = offset + segmentLength;
    if (segmentLength < 2 || segmentEnd > source.length) return source;
    const header = source.subarray(offset + 2, Math.min(segmentEnd, offset + 66)).toString('ascii');
    const removable = marker === 0xfe || marker === 0xed || (marker === 0xe1 && (header.startsWith('http://ns.adobe.com/xap/1.0/') || header.startsWith('http://ns.adobe.com/xmp/extension/')));
    if (!removable) parts.push(source.subarray(markerStart, segmentEnd));
    offset = segmentEnd;
  }
  const candidate = Buffer.concat(parts);
  return candidate.length < source.length ? candidate : source;
};
const stripPngNonVisualMetadata = (source: Buffer) => {
  if (source.length < 20 || source.subarray(0, 8).toString('hex') !== '89504e470d0a1a0a') return source;
  const removable = new Set(['tEXt', 'zTXt', 'iTXt', 'tIME']);
  const parts: Buffer[] = [source.subarray(0, 8)];
  let offset = 8;
  while (offset + 12 <= source.length) {
    const length = source.readUInt32BE(offset);
    const end = offset + 12 + length;
    if (end > source.length) return source;
    const type = source.subarray(offset + 4, offset + 8).toString('ascii');
    if (!removable.has(type)) parts.push(source.subarray(offset, end));
    offset = end;
    if (type === 'IEND') break;
  }
  if (offset !== source.length) return source;
  const candidate = Buffer.concat(parts);
  return candidate.length < source.length ? candidate : source;
};
const optimizeImageLosslessly = (data: string, mimeType: string) => {
  try {
    const source = Buffer.from(data, 'base64');
    const normalizedMime = mimeType.toLowerCase().split(';')[0].trim();
    const optimized = normalizedMime === 'image/jpeg' || normalizedMime === 'image/jpg' ? stripJpegNonVisualMetadata(source) : normalizedMime === 'image/png' ? stripPngNonVisualMetadata(source) : source;
    return { data: optimized.toString('base64'), mimeType };
  } catch {
    return { data, mimeType };
  }
};

const storeMedia = async (media: MediaInput | undefined, oldPath?: string) => {
  const data = textValue(media?.data);
  const mimeType = textValue(media?.mimeType);
  if (!data || !mimeType) return { mediaPath: oldPath };
  const cleanData = data.includes(',') ? data.split(',').pop() || '' : data;
  const optimized = /^image\//i.test(mimeType) ? optimizeImageLosslessly(cleanData, mimeType) : { data: cleanData, mimeType };
  const path = `news-media/${Date.now()}-${randomUUID()}.${extensionForMime(optimized.mimeType)}`;
  const [ok] = await storage.write([{ path, content: optimized.data, contentType: optimized.mimeType }]);
  if (!ok) throw new Error('media_write_failed');

  return { mediaPath: path, mediaMime: optimized.mimeType, mediaName: textValue(media?.name) || 'media' };
};

const mediaUrlFor = async (path?: string) => {
  if (!path) return undefined;
  const [item] = await storage.url([path]);
  return item?.url;
};

const hydrateNews = async (items: Array<NewsRecord & { id: string }>) => {
  const paths = Array.from(new Set(items.map((item) => item.mediaPath).filter((path): path is string => Boolean(path))));
  const urls = paths.length ? await storage.url(paths) : [];
  const urlMap = new Map(urls.map((item) => [item.path, item.url]));
  return items.map((item) => {
    const clean: Record<string, unknown> = { ...item, mediaUrl: item.mediaPath ? urlMap.get(item.mediaPath) : undefined };
    delete clean.originUrl;
    delete clean.originName;
    delete clean.originPublishedAt; delete clean.originImageKey;
    delete clean.originTitle; delete clean.originExcerpt;
    delete clean.fingerprint; delete clean.translationIds;
    return clean;
  });
};

const seedAnalysisBody = `### سرعت بدون شتاب‌زدگی

رسانه در لحظه‌های بحرانی با یک دوگانگی واقعی روبه‌رو است: جامعه خبر را فوری می‌خواهد، اما خبر نادقیق می‌تواند اعتماد عمومی را فرسوده سازد. سرعت زمانی ارزش دارد که مسیر بررسی نام‌ها، تاریخ‌ها، اعداد و نسبت‌دادن گفته‌ها کوتاه اما روشن باشد.

### لایهٔ نخست؛ تشخیص واقعیت

هر رویداد مجموعه‌ای از ادعاها، مشاهده‌ها و تفسیرهاست. تحریریه باید این سه را از هم جدا کند، منبع هر ادعا را بشناسد و در جایی که قطعیت وجود ندارد زبان متن را محتاط نگه دارد. چنین روشی خبر را کند نمی‌کند؛ از بازنشر خطا و اصلاح‌های پی‌درپی جلوگیری می‌کند.

### لایهٔ دوم؛ زمینه و پیوندها

یک خبر مستقل از گذشته و ساختارهای پیرامونش فهمیده نمی‌شود. توضیح کوتاه درباره پیشینه، نهادهای درگیر و منافع بازیگران به مخاطب کمک می‌کند تا میان یک رخداد گذرا و روندی ماندگار تفاوت بگذارد، بی‌آنکه گزارش به تبلیغ یا داوری حزبی تبدیل شود.

### مسئولیت در برابر مخاطب

اعتماد با شفافیت ساخته می‌شود. رسانه باید روشن کند کدام اطلاعات تأیید شده، کدام بخش هنوز در حال بررسی است و چرا برخی جزئیات منتشر نشده‌اند. احترام به کرامت انسان، پرهیز از تصویر آزاردهنده و محافظت از افراد آسیب‌پذیر بخشی از همان دقت حرفه‌ای است.

### فناوری و قضاوت انسانی

ابزارهای خودکار می‌توانند متن‌های طولانی را دسته‌بندی کنند، شباهت خبرها را بسنجند و به یافتن تناقض کمک کنند؛ اما مسئولیت نهایی نشر را نمی‌توان به ابزار سپرد. قضاوت تحریریه، شناخت زبان و حساسیت فرهنگی همچنان تعیین‌کننده است.

### نتیجه‌گیری

رسانهٔ پایدار میان سرعت و دقت یکی را قربانی دیگری نمی‌کند. روند روشن، مسئولیت مشخص و بازبینی متناسب با خطر هر خبر، امکان می‌دهد گزارش به‌موقع منتشر شود و در عین حال اعتبار تحریریه حفظ گردد.`;

let seedReady=false; const ensureSeed = async () => { if(seedReady)return;
  const { items } = await db.list<NewsRecord>('news', { limit: 3 });
  if (items.length) { const existingSeed=items.find((item)=>item.title==='تحلیل رویدادها؛ سرعت همراه با دقت'&&item.status==='review'&&!item.mediaPath); if(items.length<3&&existingSeed){await db.update('news',[{id:existingSeed.id,record:withoutId({...existingSeed,summary:'تحلیل نمونه درباره توازن میان سرعت نشر، سنجش واقعیت و مسئولیت تحریریه.',body:seedAnalysisBody,mediaType:'image',status:'published',mediaPath:'news-media/sayeh-seed-visual.svg',mediaMime:'image/svg+xml',mediaName:'sayeh-seed-visual.svg',publishedAt:Date.now(),updatedAt:Date.now()})}]);} seedReady=true; return; }
  const now = Date.now(); const seedMediaPath = 'news-media/sayeh-seed-visual.svg'; const seedSvg = `<svg xmlns='http://www.w3.org/2000/svg' width='1200' height='675' viewBox='0 0 1200 675'><rect width='1200' height='675' fill='#052e2b'/><circle cx='170' cy='130' r='220' fill='#064e3b'/><circle cx='1040' cy='600' r='300' fill='#022c22'/><text x='600' y='315' text-anchor='middle' fill='#fcd34d' font-size='88' font-family='sans-serif' font-weight='700'>SAYEH NEWS</text><text x='600' y='390' text-anchor='middle' fill='white' font-size='38' font-family='sans-serif'>خبرگزاری سایه</text></svg>`; const [seedMediaStored] = await storage.write([{ path: seedMediaPath, content: Buffer.from(seedSvg).toString('base64'), contentType: 'image/svg+xml' }]); if (!seedMediaStored) throw new Error('seed_media_write_failed');
  await db.add('news', [
    {
      title: 'سایه؛ آغاز یک روایت تازه از خبر',
      summary: 'نسخه نخست خبرگزاری سایه با تمرکز بر سرعت، دقت و پوشش منظم رویدادها آغاز به کار کرد.',
      body: 'خبرگزاری سایه برای پوشش منظم خبر، تحلیل، مصاحبه، تصویر و ویدیو طراحی شده است. این متن نمونه پس از افزودن منابع و مطالب اصلی قابل حذف است.',
      category: 'خبرگزاری سایه', language: 'دری', section: 'news', mediaType: 'image', status: 'published', isBreaking: true,
      createdAt: now, updatedAt: now, publishedAt: now, mediaPath: seedMediaPath, mediaMime: 'image/svg+xml', mediaName: 'sayeh-seed-visual.svg', viewCount: 0
    },
    {
      title: 'تحلیل رویدادها؛ سرعت همراه با دقت',
      summary: 'تحلیل نمونه درباره توازن میان سرعت نشر، سنجش واقعیت و مسئولیت تحریریه.',
      body: seedAnalysisBody,
      category: 'رسانه', language: 'دری', section: 'analysis', mediaType: 'image', status: 'published', isBreaking: false,
      createdAt: now - 3600000, updatedAt: now - 3600000, publishedAt: now - 3600000, mediaPath: seedMediaPath, mediaMime: 'image/svg+xml', mediaName: 'sayeh-seed-visual.svg', viewCount: 0
    }
  ]); seedReady=true;
};

let settingsCache:{expiresAt:number;value:SettingsRecord}|undefined; const getSettings = async (): Promise<SettingsRecord> => { if(settingsCache&&settingsCache.expiresAt>Date.now())return settingsCache.value; const { items } = await db.list<SettingsRecord>('settings', { limit: 1 }); const value=items[0] ? { ...defaultSettings, ...items[0] } : { ...defaultSettings }; settingsCache={expiresAt:Date.now()+60000,value}; return value; };
const getSettingsForClient = async () => { const settings = await getSettings(); const logoUrl = await mediaUrlFor(settings.logoPath); return { ...settings, logoUrl }; };

const networkConfigured = (network: string, names: string[]) => {
  const value = network.toLowerCase();
  if (value === 'facebook') return names.includes('META_PAGE_ACCESS_TOKEN') && names.includes('META_PAGE_ID');
  if (value === 'instagram') return names.includes('META_PAGE_ACCESS_TOKEN') && names.includes('INSTAGRAM_ACCOUNT_ID');
  if (value === 'telegram') return names.includes('TELEGRAM_BOT_TOKEN') && names.includes('TELEGRAM_CHAT_ID');
  return false;
};

const updateSocialResult = async (social: SocialRecord & { id: string }, status: string, lastError?: string) => {
  const record = withoutId({ ...social, status, lastError: lastError || '', lastPublishAt: status === 'نشر موفق' ? Date.now() : social.lastPublishAt, updatedAt: Date.now() });
  await db.update('socials', [{ id: social.id, record }]);
};

const publishToSocials = async (article: NewsRecord & { id: string }) => {
  const settings = await getSettings();
  if (!settings.autoShare) return;
  const { items } = await db.list<SocialRecord>('socials', { limit: 100 });
  const targets = items.filter((item) => item.enabled && item.autoShare);
  if (!targets.length) return;
  const names = await secrets.listSecretNames();
  const mediaUrl = await mediaUrlFor(article.mediaPath);
  const caption = `${article.title}\n\n${article.summary || article.body.slice(0, 280)}`;

  for (const social of targets) {
    if (!networkConfigured(social.network, names)) {
      await updateSocialResult(social, 'در انتظار مجوز رسمی', 'مجوز رسمی این شبکه ثبت نشده است.');
      continue;
    }
    try {
      const network = social.network.toLowerCase();
      if (network === 'facebook') {
        const token = await secrets.readSecret('META_PAGE_ACCESS_TOKEN');
        const pageId = await secrets.readSecret('META_PAGE_ID');
        const endpoint = article.mediaType === 'image' && mediaUrl ? `https://graph.facebook.com/${pageId}/photos` : `https://graph.facebook.com/${pageId}/feed`;
        const values: Record<string, string> = { access_token: token };
        if (article.mediaType === 'image' && mediaUrl) {
          values.url = mediaUrl;
          values.caption = caption;
        } else values.message = caption;
        const response = await fetch(endpoint, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams(values) });
        if (!response.ok) throw new Error(`Facebook ${response.status}`);
      } else if (network === 'instagram') {
        if (!mediaUrl || article.mediaType !== 'image') throw new Error('Instagram requires an image for this publishing method');
        const token = await secrets.readSecret('META_PAGE_ACCESS_TOKEN');
        const accountId = await secrets.readSecret('INSTAGRAM_ACCOUNT_ID');
        const createResponse = await fetch(`https://graph.facebook.com/${accountId}/media`, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ image_url: mediaUrl, caption, access_token: token }) });
        if (!createResponse.ok) throw new Error(`Instagram container ${createResponse.status}`);
        const container = await createResponse.json() as { id?: string };
        if (!container.id) throw new Error('Instagram container missing');
        const publishResponse = await fetch(`https://graph.facebook.com/${accountId}/media_publish`, { method: 'POST', headers: { 'Content-Type': 'application/x-www-form-urlencoded' }, body: new URLSearchParams({ creation_id: container.id, access_token: token }) });
        if (!publishResponse.ok) throw new Error(`Instagram publish ${publishResponse.status}`);
      } else if (network === 'telegram') {
        const token = await secrets.readSecret('TELEGRAM_BOT_TOKEN');
        const chatId = await secrets.readSecret('TELEGRAM_CHAT_ID');
        const method = article.mediaType === 'image' && mediaUrl ? 'sendPhoto' : article.mediaType === 'video' && mediaUrl ? 'sendVideo' : 'sendMessage';
        const payload: Record<string, string> = { chat_id: chatId };
        if (method === 'sendPhoto') { payload.photo = mediaUrl || ''; payload.caption = caption; }
        else if (method === 'sendVideo') { payload.video = mediaUrl || ''; payload.caption = caption; }
        else payload.text = caption;
        const response = await fetch(`https://api.telegram.org/bot${token}/${method}`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(payload) });
        if (!response.ok) throw new Error(`Telegram ${response.status}`);
      }
      await updateSocialResult(social, 'نشر موفق');
    } catch (err) {
      await updateSocialResult(social, 'خطای نشر', err instanceof Error ? err.message : 'خطای نامشخص');
    }
  }
};

const buildNewsRecord = async (input: Record<string, unknown>, current?: NewsRecord) => {
  const settings = await getSettings();
  const title = textValue(input.title ?? current?.title);
  const body = textValue(input.body ?? current?.body);
  if (!title || !body) throw new Error('title_body_required');
  const status = normalizeStatus({ ...current, ...input });
  const now = Date.now();
  const media = await storeMedia(input.media as MediaInput | undefined);
  const clearMedia = input.clearMedia === true;
  const publishAt = numberValue(input.publishAt ?? current?.publishAt, 0) || undefined;
  const mediaType = ['image', 'video', 'interview'].includes(String(input.mediaType ?? current?.mediaType)) ? String(input.mediaType ?? current?.mediaType) as MediaType : 'text';
  const section = input.section === 'analysis' || current?.section === 'analysis' && input.section == null ? 'analysis' as const : 'news' as const;
  const nextMediaPath = clearMedia ? undefined : media.mediaPath || current?.mediaPath; const nextMediaMime = clearMedia ? undefined : media.mediaMime || current?.mediaMime; const nextMediaName = clearMedia ? undefined : media.mediaName || current?.mediaName; const nextExternalVideoUrl = safeYoutubeEmbed(input.externalVideoUrl ?? current?.externalVideoUrl); const nextFacebookVideoUrl=safeFacebookVideoUrl(input.facebookVideoUrl ?? current?.facebookVideoUrl);
  if (settings.visualRequired && (status === 'published' || status === 'scheduled') && !hasRenderableVisual({ mediaPath: nextMediaPath, mediaMime: nextMediaMime, externalVideoUrl: nextExternalVideoUrl, facebookVideoUrl: nextFacebookVideoUrl })) { if (media.mediaPath && media.mediaPath !== current?.mediaPath) await storage.delete([media.mediaPath]); throw new Error('visual_required'); }
  if (clearMedia && current?.mediaPath) await storage.delete([current.mediaPath]); else if (media.mediaPath && current?.mediaPath && media.mediaPath !== current.mediaPath) await storage.delete([current.mediaPath]);
  const publishedAt = status === 'published' ? current?.publishedAt || now : undefined;
  const archiveEligible = section !== 'analysis' && Boolean(publishedAt && now - publishedAt >= archiveAgeMs(settings.archiveDays));
  return {
    title,
    summary: textValue(input.summary ?? current?.summary) || body.slice(0, 180),
    body,
    category: textValue(input.category ?? current?.category) || 'عمومی',
    language: textValue(input.language ?? current?.language) || 'دری',
    section,
    mediaType,
    status,
    isBreaking: boolValue(input.isBreaking, current?.isBreaking || false),
    createdAt: current?.createdAt || now,
    updatedAt: now,
    publishAt: status === 'scheduled' ? publishAt : undefined,
    publishedAt,
    mediaPath: nextMediaPath,
    mediaMime: nextMediaMime,
    mediaName: nextMediaName,
    externalVideoUrl: nextExternalVideoUrl,
    facebookVideoUrl: nextFacebookVideoUrl || undefined,
    videoBulletinId: current?.videoBulletinId,
    originUrl: current?.originUrl,
    originName: current?.originName,
    originPublishedAt: current?.originPublishedAt, originImageKey: current?.originImageKey,
    originTitle: current?.originTitle,
    originExcerpt: current?.originExcerpt,
    fingerprint: current?.fingerprint,
    sourcePipelineVersion: current?.sourcePipelineVersion,
    viewCount: current?.viewCount || 0,
    archived: archiveEligible ? current?.archived || false : false,
    archivedAt: archiveEligible ? current?.archivedAt : undefined, translationIds: current?.translationIds
  } satisfies NewsRecord;
};

const listAllNews = async () => { const items: Array<NewsRecord & { id: string }> = []; let nextToken: string | undefined; for (let pageIndex = 0; pageIndex < 20; pageIndex += 1) { const page = await db.list<NewsRecord>('news', { limit: 500, ...(nextToken ? { nextToken } : {}) }); items.push(...page.items as Array<NewsRecord & { id: string }>); if (!page.nextToken) break; nextToken = page.nextToken; } return items; };

const PUBLIC_FEED_LIMIT = 24;
const GENERAL_FEED_LIMIT = 15;
const PRIMARY_ARIANA_FEED_LIMIT = 9;
const ARCHIVE_PAGE_LIMIT = 24;
const ADMIN_NEWS_LIMIT = 200;
const NEWS_INDEX_TABLE = 'news_index_v1';
const NEWS_INDEX_VERSION = 7;
const MAX_INDEXED_PUBLIC_NEWS = 7500;
const NEWS_READ_CACHE_MS = 45 * 1000;

type NewsIndexRecord = {
  version: number;
  publicIds: string[];
  primaryIds: string[];
  archivedIds: string[];
  analysisIds: string[];
  activeIds: string[];
  editorIds: string[];
  updatedAt: number;
};

type HydratedNewsItems = Awaited<ReturnType<typeof hydrateNews>>;
type ArchivePage = { items: HydratedNewsItems; total: number; offset: number; limit: number; hasMore: boolean };
let publicNewsCache: { expiresAt: number; items: HydratedNewsItems } | undefined;
const archiveNewsCache = new Map<string, { expiresAt: number; value: ArchivePage }>();
const recentNewsCache = new Map<string, { expiresAt: number; value: ArchivePage }>();
const analysisNewsCache = new Map<string, { expiresAt: number; value: ArchivePage }>();
const englishNewsCache = new Map<string, { expiresAt: number; value: ArchivePage }>();
const clearNewsReadCaches = () => { publicNewsCache = undefined; archiveNewsCache.clear(); recentNewsCache.clear(); analysisNewsCache.clear(); englishNewsCache.clear(); };

const sortNewsForDisplay = (items: Array<NewsRecord & { id: string }>) => items.sort((a, b) => (b.publishedAt || b.updatedAt || b.createdAt) - (a.publishedAt || a.updatedAt || a.createdAt));
const composeActiveIds = (publicIds: string[], primaryIds: string[], archivedIds: string[]) => {
  const archived = new Set(archivedIds);
  const candidates = publicIds.filter((id) => !archived.has(id));
  const publicSet = new Set(candidates);
  const selected = new Set([...candidates.slice(0, GENERAL_FEED_LIMIT), ...primaryIds.filter((id) => publicSet.has(id)).slice(0, PRIMARY_ARIANA_FEED_LIMIT)]);
  for (const id of candidates) { if (selected.size >= PUBLIC_FEED_LIMIT) break; selected.add(id); }
  return candidates.filter((id) => selected.has(id)).slice(0, PUBLIC_FEED_LIMIT);
};

const getNewsByIds = async (ids: string[]) => {
  if (!ids.length) return [] as Array<NewsRecord & { id: string }>;
  const chunks: string[][] = [];
  for (let offset = 0; offset < ids.length; offset += 100) chunks.push(ids.slice(offset, offset + 100));
  const pages = await Promise.all(chunks.map((chunk) => db.get<NewsRecord>('news', chunk)));
  const byId = new Map<string, NewsRecord>();
  pages.forEach((records, pageIndex) => records.forEach((record, recordIndex) => {
    if (record) byId.set(chunks[pageIndex][recordIndex], record);
  }));
  const items: Array<NewsRecord & { id: string }> = [];
  ids.forEach((id) => {
    const record = byId.get(id);
    if (record) items.push(sanitizeLegacyImportedRecord({ ...record, id } as NewsRecord & { id: string }));
  });
  return items;
};

const buildNewsIndex = async () => {
  const settings = await getSettings();
  const ordered = sortNewsForDisplay((await listAllNews()).map(sanitizeLegacyImportedRecord));
  const published = collapseImportedDuplicates(ordered.filter((item) => item.status === 'published' && (!settings.visualRequired || hasRenderableVisual(item))));
  const publicIds = published.map((item) => item.id).slice(0, MAX_INDEXED_PUBLIC_NEWS);
  const analysisIds = published.filter((item) => item.section === 'analysis').map((item) => item.id).slice(0, MAX_INDEXED_PUBLIC_NEWS);
  const primaryIds = published.filter((item) => !item.archived && isArianaImportedRecord(item)).map((item) => item.id).slice(0, PRIMARY_ARIANA_FEED_LIMIT);
  const archivedIds = published.filter((item) => item.archived && item.section !== 'analysis').map((item) => item.id).slice(0, MAX_INDEXED_PUBLIC_NEWS);
  const record: NewsIndexRecord = {
    version: NEWS_INDEX_VERSION,
    publicIds,
    primaryIds,
    archivedIds,
    analysisIds,
    activeIds: composeActiveIds(publicIds, primaryIds, archivedIds),
    editorIds: ordered.slice(0, ADMIN_NEWS_LIMIT).map((item) => item.id),
    updatedAt: Date.now()
  };
  const [id] = await db.add(NEWS_INDEX_TABLE, [record]);
  if (!id) throw new Error('news_index_create_failed');
  return { id, ...record };
};

const getNewsIndex = async () => {
  const { items } = await db.list<NewsIndexRecord>(NEWS_INDEX_TABLE, { limit: 20 });
  const current = items
    .filter((item) => item.version === NEWS_INDEX_VERSION && Array.isArray(item.publicIds) && Array.isArray(item.primaryIds) && Array.isArray(item.archivedIds) && Array.isArray(item.analysisIds) && Array.isArray(item.activeIds) && Array.isArray(item.editorIds))
    .sort((a, b) => b.updatedAt - a.updatedAt)[0];
  return current || buildNewsIndex();
};

const updateNewsIndexForRecord = async (id: string, news: NewsRecord) => {
  const settings = await getSettings();
  const index = await getNewsIndex();
  let publicIds = index.publicIds.filter((itemId) => itemId !== id);
  let primaryIds = index.primaryIds.filter((itemId) => itemId !== id);
  let archivedIds = index.archivedIds.filter((itemId) => itemId !== id);
  let analysisIds = index.analysisIds.filter((itemId) => itemId !== id);
  let editorIds = index.editorIds.filter((itemId) => itemId !== id);
  editorIds.unshift(id);
  if (news.status === 'published' && (!settings.visualRequired || hasRenderableVisual(news))) {
    publicIds.unshift(id);
    if (news.section === 'analysis') analysisIds.unshift(id);
    else if (news.archived) archivedIds.unshift(id);
    else if (isArianaImportedRecord(news)) primaryIds.unshift(id);
  }
  publicIds = Array.from(new Set(publicIds)).slice(0, MAX_INDEXED_PUBLIC_NEWS);
  primaryIds = Array.from(new Set(primaryIds)).slice(0, PRIMARY_ARIANA_FEED_LIMIT);
  archivedIds = Array.from(new Set(archivedIds)).slice(0, MAX_INDEXED_PUBLIC_NEWS);
  analysisIds = Array.from(new Set(analysisIds)).slice(0, MAX_INDEXED_PUBLIC_NEWS);
  const record: NewsIndexRecord = {
    version: NEWS_INDEX_VERSION,
    publicIds,
    primaryIds,
    archivedIds,
    analysisIds,
    activeIds: composeActiveIds(publicIds, primaryIds, archivedIds),
    editorIds: Array.from(new Set(editorIds)).slice(0, ADMIN_NEWS_LIMIT),
    updatedAt: Date.now()
  };
  const [ok] = await db.update(NEWS_INDEX_TABLE, [{ id: index.id, record: record as unknown as Record<string, unknown> }]);
  if (!ok) throw new Error('news_index_update_failed');
  clearNewsReadCaches();
  return { id: index.id, ...record };
};

const removeNewsFromIndex = async (id: string) => { const index = await getNewsIndex(); const publicIds = index.publicIds.filter((itemId) => itemId !== id); const primaryIds = index.primaryIds.filter((itemId) => itemId !== id); const archivedIds = index.archivedIds.filter((itemId) => itemId !== id); const analysisIds = index.analysisIds.filter((itemId) => itemId !== id); const editorIds = index.editorIds.filter((itemId) => itemId !== id); const record: NewsIndexRecord = { version: NEWS_INDEX_VERSION, publicIds, primaryIds, archivedIds, analysisIds, activeIds: composeActiveIds(publicIds, primaryIds, archivedIds), editorIds, updatedAt: Date.now() }; const [ok] = await db.update(NEWS_INDEX_TABLE, [{ id: index.id, record: record as unknown as Record<string, unknown> }]); if (!ok) throw new Error('news_index_delete_update_failed'); clearNewsReadCaches(); return { id: index.id, ...record }; };

const createNews = async (input: Record<string, unknown>, origin?: { url?: string; name?: string; fingerprint?: string; publishedAt?: number; imageKey?: string; title?: string; excerpt?: string }, existingNews?: Array<NewsRecord & { id: string }>) => {
  const canonicalOrigin = origin?.url ? canonicalArticleUrl(origin.url) : undefined;
  let current: (NewsRecord & { id: string }) | undefined;
  if (canonicalOrigin) {
    const items = existingNews || await (async () => { const index = await getNewsIndex(); return getNewsByIds(Array.from(new Set([...index.publicIds.slice(0, 1500), ...index.editorIds]))); })();
    const incomingIdentity = { originUrl: canonicalOrigin, fingerprint: origin?.fingerprint, title: origin?.title || textValue(input.title), summary: textValue(input.summary), body: origin?.excerpt || textValue(input.body), originName: origin?.name, originPublishedAt: origin?.publishedAt, originImageKey: origin?.imageKey, originTitle: origin?.title, originExcerpt: origin?.excerpt };
    const exactCurrent = items.find((item) => Boolean(item.originUrl) && canonicalArticleUrl(item.originUrl || '') === canonicalOrigin);
    const semanticCurrent = exactCurrent || items.slice(0, 400).find((item) => Boolean(item.originUrl) && likelyDuplicateImportedStory(incomingIdentity, item));
    if (!exactCurrent && semanticCurrent) { const [hydrated] = await hydrateNews([semanticCurrent]); return hydrated || semanticCurrent; }
    current = exactCurrent;
  }
  const wasPublished = current?.status === 'published';
  const record = await buildNewsRecord(input, current);
  record.originUrl = canonicalOrigin;
  record.originName = origin?.name;
  record.originPublishedAt = origin?.publishedAt; record.originImageKey = origin?.imageKey || current?.originImageKey;
  record.originTitle = origin?.title || current?.originTitle;
  record.originExcerpt = origin?.excerpt ? origin.excerpt.slice(0, 6000) : current?.originExcerpt;
  record.fingerprint = origin?.fingerprint;
  if (canonicalOrigin) record.sourcePipelineVersion = SOURCE_PIPELINE_VERSION;
  let id = current?.id || '';
  if (current) { const [ok] = await db.update('news', [{ id: current.id, record: record as unknown as Record<string, unknown> }]); if (!ok) throw new Error('update_failed'); }
  else { const [createdId] = await db.add('news', [record]); if (!createdId) throw new Error('create_failed'); id = createdId; }
  const created = { id, ...record };
  await updateNewsIndexForRecord(id, record);
  if (record.status === 'published' && !wasPublished) await publishToSocials(created).catch(() => undefined);
  const [hydrated] = await hydrateNews([created]);
  const visible = hydrated || created;
  await notifySubscribers('news', 'feed', { action: 'upsert', item: visible }).catch(() => undefined);
  return visible;
};

const sourceIntervalDue = (source: SourceRecord) => !source.lastCheckedAt || Date.now() - source.lastCheckedAt >= Math.max(5, source.intervalMinutes || 5) * 60000 - 45000;
const isArianaSource = (source: Pick<SourceRecord, 'url'>) => isArianaUrl(source.url); const sourceHost = (source: Pick<SourceRecord, 'url'>) => { try { return new URL(source.url).hostname.toLowerCase().replace(/^www\./, ''); } catch { return ''; } }; const isBBCSource = (source: Pick<SourceRecord, 'name' | 'url'>) => { const host = sourceHost(source); return host === 'bbc.com' || host.endsWith('.bbc.com') || host === 'bbc.co.uk' || host.endsWith('.bbc.co.uk') || /\bBBC\b|بی[\s‌-]*بی[\s‌-]*سی/i.test(source.name); }; const isVoaSource = (source: Pick<SourceRecord, 'name' | 'url'>) => { const host = sourceHost(source); return host === 'voanews.com' || host.endsWith('.voanews.com') || /\bVOA\b|Voice\s+of\s+America|صدای\s+امریکا/i.test(source.name); }; const isGlobalVoicesSource = (source: Pick<SourceRecord, 'name' | 'url'>) => sourceHost(source).endsWith('globalvoices.org') || /Global Voices/i.test(source.name); const isGovUkSource = (source: Pick<SourceRecord, 'name' | 'url'>) => sourceHost(source) === 'gov.uk' || sourceHost(source).endsWith('.gov.uk') || /GOV\.UK/i.test(source.name); const isStateGovSource = (source: Pick<SourceRecord, 'name' | 'url'>) => { const host = sourceHost(source); return host === 'state.gov' || host.endsWith('.state.gov') || /U\.S\. State Department|United States Department of State/i.test(source.name); }; const isEnglishDeskSource = (source: Pick<SourceRecord, 'name' | 'url'>) => isVoaSource(source) || isGlobalVoicesSource(source) || isGovUkSource(source) || isStateGovSource(source); const thirdPartyWireCredit = /\b(?:Associated\s+Press|Reuters|Agence\s+France-Presse|AFP|Getty Images?)\b|(?:^|\s)AP(?:\s|$|[.,:;])|some\s+information\s+for\s+this\s+report\s+(?:came|comes)\s+from|with\s+(?:information|reporting)\s+from/i; const globalVoicesRepublishConflict = /this\s+(?:article|story)\s+(?:was\s+)?originally\s+(?:published|appeared)|republished\s+with\s+permission|originally\s+published\s+by/i;
const isAfghanistanRelevantRecord = (item: Pick<NewsRecord, 'title' | 'summary' | 'body'>) => isAfghanistanRelevantText(`${item.title} ${item.summary} ${item.body}`);
const collapseImportedDuplicates = (items: Array<NewsRecord & { id: string }>) => { const kept: Array<NewsRecord & { id: string }> = []; const recentImported: Array<NewsRecord & { id: string }> = []; const seenUrls = new Set<string>(); const seenFingerprints = new Set<string>(); let semanticScanned = 0; for (const item of items) { if (!item.originUrl) { kept.push(item); continue; } const canonicalUrl = canonicalArticleUrl(item.originUrl); const exactDuplicate = Boolean((canonicalUrl && seenUrls.has(canonicalUrl)) || (item.fingerprint && seenFingerprints.has(item.fingerprint))); const checkSemantic = semanticScanned < 600; if (exactDuplicate || (checkSemantic && recentImported.some((candidate) => likelyDuplicateImportedStory(item, candidate)))) continue; kept.push(item); if (canonicalUrl) seenUrls.add(canonicalUrl); if (item.fingerprint) seenFingerprints.add(item.fingerprint); if (checkSemantic) { recentImported.push(item); semanticScanned += 1; if (recentImported.length > 120) recentImported.shift(); } } return kept; };
// Imported records are never physically deleted; duplicate suppression is enforced before ingest and again at public read time.


const updateSource = async (id: string, source: SourceRecord, updates: Partial<SourceRecord>) => {
  const record = withoutId({ ...source, ...updates, updatedAt: Date.now() });
  await db.update('sources', [{ id, record }]);
  return { id, ...record };
};

const ingestSource = async (id: string, maxItemsOverride?: number, scheduledFast = false) => {
  const settings = await getSettings();
  const [source] = await db.get<SourceRecord>('sources', [id]);
  if (!source) throw new Error('source_not_found');
  if (!source.rightsApproved) throw new Error('rights_required');
  const automaticPublish = source.autoPublish || (settings.forceArianaSource && isArianaSource(source)); const bbcSource = isBBCSource(source); const voaSource = isVoaSource(source); const globalVoicesSource = isGlobalVoicesSource(source); const govUkSource = isGovUkSource(source); const stateGovSource = isStateGovSource(source); const englishDeskSource = isEnglishDeskSource(source);
  try {
    const index = await getNewsIndex();
    const existing = await getNewsByIds(Array.from(new Set([...index.publicIds.slice(0, 1500), ...index.editorIds])));
    const { items: deletedOrigins } = await db.list<{ originUrl?: string; fingerprint?: string; originName?: string; originPublishedAt?: number; originTitle?: string; originExcerpt?: string; deletedAt: number }>('news_delete_tombstones', { limit: 1000 });
    const existingWithDeleted = [...existing, ...deletedOrigins.map((item) => ({ originUrl: item.originUrl, fingerprint: item.fingerprint, originName: item.originName, originPublishedAt: item.originPublishedAt, originTitle: item.originTitle, originExcerpt: item.originExcerpt, createdAt: item.deletedAt }))];
    const maxItems = maxItemsOverride ?? (isArianaSource(source) ? 3 : source.priority === 'بالا' ? 10 : source.priority === 'متوسط' ? 8 : 6);
    const discoverySource = (bbcSource && source.ingestImage && settings.bbcReplaceSourceImage) || englishDeskSource ? { ...source, ingestImage: false } : source; const batch = await discoverSourceCandidates(discoverySource, existingWithDeleted, maxItems);
    const createdItems: unknown[] = []; const globalVoicesAuthors = new Map<string, string>();
    const mediaChoices = [source.ingestText ? 'text' : '', source.ingestImage ? 'image' : '', source.ingestVideo ? 'video' : '', source.ingestInterview ? 'interview' : ''].filter(Boolean);
    const allowedMedia = mediaChoices.length ? mediaChoices : ['text'];
    const prepared: typeof batch.items = [];
    const minimumContentLength = isArianaSource(source) ? 45 : 300;
    for (const candidate of batch.items) {
      let content = cleanSourceText(candidate.text).slice(0, 9000);
      if (!scheduledFast && content.length < 300) {
        try {
          const scraped = await ai.scrape({ url: candidate.url });
          const scrapedContent = scraped.status < 400 ? cleanSourceText(scraped.text).slice(0, 9000) : '';
          if (scrapedContent.length > content.length) content = scrapedContent;
        } catch { /* keep extracted page text */ }
      }
      if (globalVoicesSource) { try { const scraped = await ai.scrape({ url: candidate.url }); const raw = scraped.status < 400 ? scraped.text : ''; if (globalVoicesRepublishConflict.test(raw)) continue; const authorMatch = raw.match(/(?:Written by|Author)\s*[:\-]?\s*([^\n|]{2,100})/i); const author = normalizeGeneratedText(authorMatch?.[1] || '').replace(/\s{2,}/g, ' ').trim(); if (!author) continue; globalVoicesAuthors.set(candidate.url, author); const scrapedContent = cleanSourceText(raw).slice(0, 9000); if (scrapedContent.length > content.length) content = scrapedContent; } catch { continue; } } if (englishDeskSource && thirdPartyWireCredit.test(`${candidate.title}\n${content}`)) continue; if (content.length >= minimumContentLength) prepared.push({ ...candidate, text: content });
    }
    for (let offset = 0; offset < prepared.length; offset += 3) {
      const chunk = prepared.slice(offset, offset + 3);
      let rewrites: Record<string, unknown>[] = [];
      try {
        const result = await Promise.race([ai.extract({
          content: JSON.stringify({ source: { name: source.name, url: source.url, topic: source.topic, language: source.language }, items: chunk.map((candidate, index) => ({ index, title: candidate.title, url: candidate.url, basis: candidate.contentBasis || 'source_text', text: candidate.text.slice(0, 7600) })) }),
          prompt: `${bbcSource || englishDeskSource ? 'برای هر مطلب یک imageQuery کوتاه شامل ۲ تا ۶ کلیدواژه انگلیسی درباره موضوع اصلی خبر نیز بساز؛ این عبارت فقط برای جست‌وجوی یک تصویر مرتبط آرشیفی استفاده می‌شود و نباید ادعا کند تصویر مربوط به خود رویداد است. ' : ''}${englishDeskSource ? 'این منبع برای English Desk خبرگزاری سایه است. متن را به انگلیسی روشن، حرفه‌ای، بی‌طرف و مستقل بازنویسی کن؛ تیتر، لید و بدنه باید نگارش تازهٔ SAYEH NEWS باشند و کپی جمله‌به‌جمله نباشند. فقط بر اطلاعات قابل اثبات در متن منبع تکیه کن، هیچ ادعای گزارش میدانی یا مصاحبه اختصاصی برای سایه نساز و اگر بخشی اعتبار روشن ندارد آن را وارد خروجی نکن. ' : ''}شیء ورودی شامل مشخصات منبع و آرایهٔ items است؛ هر عضو items یک مطلب مستقل است. اگر basis برابر video_transcript باشد، text متن واقعی کپشن یا زیرنویس همان ویدیو است: آن را به‌صورت گزارش و جمع‌بندی مستقل تحریریه سایه بنویس؛ عنوان و خلاصه باید مستقیماً مهم‌ترین خبر، استدلال یا نتیجهٔ گفت‌وگو را بیان کنند و نام برنامه یا رسانهٔ منبع را در عنوان و لید نیاورند. بدنه باید نکات اصلی، دیدگاه‌ها، اختلاف‌نظرها و نتیجه‌های قابل استناد را منسجم و بی‌طرفانه خلاصه کند. از جمله‌هایی مانند «برنامهٔ فلان منتشر شده»، «این برنامه به موضوع...» یا معرفی شبکهٔ منبع به‌عنوان متن اصلی استفاده نکن. ادعا نکن خبرنگار سایه مصاحبه را انجام داده یا در صحنه بوده و هیچ گفته‌ای را که در کپشن پشتیبانی نمی‌شود نساز. نام منبع را در خروجی این عضو اضافه نکن، چون سامانه خط «منبع ویدیویی» را جداگانه می‌افزاید. همهٔ مطالب را به زبان ${englishDeskSource ? 'English' : source.language || 'دری'} دقیق، حرفه‌ای و مستقل بازنویسی کن و index هر عضو را عیناً حفظ کن. ${englishDeskSource ? 'For Afghanistan coverage, when the source reports concrete, verifiable positive actions, public-service improvements or outcomes by Afghanistan’s de facto authorities, state them clearly and proportionately when they are central to the story; do not downplay them. Treat criticism, allegations and adverse claims with explicit attribution and only to the extent supported by the source, while also not suppressing material verified problems. Keep the tone factual, professional and non-promotional; never add praise, advocacy or unsupported claims. ' : 'در پوشش افغانستان، هرگاه متن منبع اقدام، دستاورد، بهبود خدمات یا نتیجهٔ مثبتِ مشخص و قابل‌اثبات از نهادهای حاکم یا امارت اسلامی را گزارش می‌کند و برای اصل خبر مهم است، آن را روشن، متناسب و بدون کم‌رنگ‌سازی در عنوان، خلاصه یا متن بازتاب بده. نقدها، اتهام‌ها و ادعاهای منفی را با نسبت‌دهی روشن و فقط به اندازه‌ای که متن منبع پشتیبانی می‌کند بنویس؛ مشکلات مهم و مستند را نیز حذف نکن. لحن باید خبری، حرفه‌ای و غیرتبلیغاتی باشد و هیچ ستایش، شعار یا ادعای بدون پشتوانه اضافه نشود. '}پیش از نگارش، کشور یا قلمرو اصلی رویداد، کشور وابسته به هر مقام، حکومت، وزارت، سفارت، کنسولگری، نمایندگی، نیروی نظامی یا نهاد رسمی و نیز محل نمایندگی را از عنوان و متن همان مطلب تشخیص بده. در عنوان، خلاصه و متن نهایی از تعبیرهای مبهمی مانند «وزیر خارجه کشور»، «رئیس‌جمهور این کشور»، «حکومت مربوطه»، «سفارت مربوطه»، «نمایندگی این کشور» یا ضمیرهایی که مرجع روشن ندارند استفاده نکن؛ در نخستین اشاره نام کامل و روشن بنویس، مانند «وزیر امور خارجه افغانستان»، «سفارت جمهوری اسلامی ایران در کابل» یا «نمایندگی افغانستان در سازمان ملل»، فقط هنگامی که همین نسبت و محل در منبع قابل اثبات باشد. کشور ناشر خبر را با کشور موضوع خبر اشتباه نگیر و مشخصات منبع را فقط قرینهٔ کمکی بدان. اگر کشور، نهاد یا محل در متن واقعاً روشن نیست، چیزی اختراع نکن؛ نام شخص و سمت مستند او را بنویس و ادعای نامطمئن نساز. اگر مطلب مصاحبه، گفت‌وگو یا برنامهٔ تحلیلی است، دیدگاه‌ها و نکات اصلی مهمان را بی‌طرفانه تحلیل کن و section را analysis تعیین کن؛ هیچ نتیجه یا ادعایی بیرون از متن منبع نساز. اگر متن کامل گفت‌وگو در ورودی موجود نیست، فقط موضوع ویدیوی منتشرشده را معرفی کن و صریح بنویس که جزئیات گفت‌وگو در متن منبع درج نشده است؛ تحلیل یا نقل‌قول ساختگی نساز. طول فعلی خبر را کوتاه و متناسب نگه دار و بی‌دلیل آن را مفصل نکن؛ تنها هنگامی که جزئیات منبع لازم است، متن می‌تواند تا سقف ${settings.autoWordLimit} واژه ادامه یابد و هرگز از این سقف بیشتر نشود. نام‌ها، سمت‌ها، کشورها، مکان‌ها، تاریخ‌ها، اعداد، آمار و نقل‌قول‌ها دقیق حفظ شود و ادعای گزارش اختصاصی ساخته نشود. هیچ متن رابط یا ناوبری سایت، «Continue Reading»، «Read More»، «Ariana News Related»، «Related Topics»، «Don't Miss»، «Advertisement»، «You may like»، «By Ariana News»، «آخرین خبرها»، «خبرهای مرتبط»، «مطالب مرتبط»، «پربازدید»، زمان نسبی خبرهای دیگر یا عنوان مطالب پیشنهادی را وارد عنوان، خلاصه یا بدنه نکن.`, 
          schema: {
            type: 'object',
            properties: {
              items: {
                type: 'array',
                items: {
                  type: 'object',
                  properties: {
                    index: { type: 'number' }, title: { type: 'string' }, summary: { type: 'string' }, body: { type: 'string' }, category: { type: 'string' }, imageQuery: { type: 'string' },
                    section: { type: 'string', enum: ['news', 'analysis'] }, mediaType: { type: 'string', enum: allowedMedia }, isBreaking: { type: 'boolean' }
                  },
                  required: ['index', 'title', 'summary', 'body', 'category', 'section', 'mediaType']
                }
              }
            },
            required: ['items']
          },
          maxTokens: scheduledFast ? Math.max(1200, chunk.length * 900) : isArianaSource(source) ? 3600 : 7600,
          temperature: 0.2,
          thinkingMode: scheduledFast ? 'NONE' : 'FAST'
        }), new Promise<never>((_, reject) => setTimeout(() => reject(new Error('source_rewrite_timeout')), scheduledFast ? 12000 : 15000))]);
        const data = result.data as { items?: Record<string, unknown>[] };
        rewrites = Array.isArray(data.items) ? data.items : [];
      } catch (err) {
        console.warn('source_batch_rewrite_fallback', { sourceId: id, reason: err instanceof Error ? err.message : 'unknown' });
      }
      for (let localIndex = 0; localIndex < chunk.length; localIndex += 1) {
        const candidate = chunk[localIndex];
        const extracted = rewrites.find((item) => Number(item.index) === localIndex) || rewrites[localIndex] || {};
        const rewrittenTitle = normalizeGeneratedText(textValue(extracted.title)) || normalizeGeneratedText(candidate.title) || candidate.title;
        const generatedBody = normalizeGeneratedText(textValue(extracted.body));
        const videoGrounded = candidate.contentBasis === 'video_transcript';
        const gvAuthor = globalVoicesAuthors.get(candidate.url) || ''; const topAttribution = globalVoicesSource ? `Source: Global Voices. Original reporting by ${gvAuthor}. Adapted by SAYEH NEWS English Desk under CC BY 3.0; changes made. Original: ${candidate.url} License: https://creativecommons.org/licenses/by/3.0/` : ''; const sourceAttribution = voaSource ? 'Source: Voice of America (VOA).' : govUkSource ? 'Source: GOV.UK. Adapted by SAYEH NEWS English Desk under the Open Government Licence v3.0; changes made.' : stateGovSource ? 'Source: U.S. Department of State. Adapted by SAYEH NEWS English Desk.' : videoGrounded ? `منبع ویدیویی: ${source.name}.` : '';
        const bodyCore = limitWords(generatedBody || cleanSourceText(candidate.text, rewrittenTitle) || rewrittenTitle, videoGrounded ? Math.max(100, settings.autoWordLimit - 30) : settings.autoWordLimit);
        const body = topAttribution ? `${topAttribution}\n\n${bodyCore}` : sourceAttribution ? `${bodyCore}\n\n${sourceAttribution}` : bodyCore;
        const rewrittenSummary = normalizeGeneratedText(textValue(extracted.summary)) || bodyCore.slice(0, 220);
        const interviewSignal = Boolean(candidate.externalVideoUrl) || (isArianaSource(source) && /(مصاحبه|گفت[‌\s-]*وگو|گفتگو|تحول|څار|دیدگاه|interview|tahawol|dialogue)/i.test(`${candidate.title} ${candidate.url} ${candidate.text.slice(0, 800)}`));
        const desiredMedia = textValue(extracted.mediaType);
        const articleMedia = (bbcSource && source.ingestImage && settings.bbcReplaceSourceImage) || englishDeskSource ? (source.ingestImage && settings.openverseFallback ? await findOpenLicensedImage(textValue(extracted.imageQuery) || rewrittenTitle) : undefined) : candidate.media || (source.ingestImage && settings.openverseFallback ? await findOpenLicensedImage(rewrittenTitle) : undefined);
        const mediaType = candidate.externalVideoUrl ? 'interview' : articleMedia ? 'image' : interviewSignal && source.ingestInterview ? 'interview' : allowedMedia.includes(desiredMedia) && desiredMedia !== 'image' ? desiredMedia : 'text'; const publishableVisual = Boolean(candidate.externalVideoUrl || articleMedia);
        const articleInput: Record<string, unknown> = { title: rewrittenTitle, summary: rewrittenSummary, body, category: interviewSignal ? (englishDeskSource ? 'Interview & Analysis' : 'مصاحبه و تحلیل') : textValue(extracted.category) || (englishDeskSource ? 'Afghanistan & Region' : source.topic || 'عمومی'), section: videoGrounded || interviewSignal || extracted.section === 'analysis' ? 'analysis' : 'news', mediaType, externalVideoUrl: candidate.externalVideoUrl, isBreaking: extracted.isBreaking === true, language: englishDeskSource ? 'English' : source.language, status: automaticPublish && (publishableVisual || !settings.visualRequired) ? 'published' : 'review', media: articleMedia };
        const articleOrigin = { url: candidate.url, name: source.name, fingerprint: candidate.fingerprint, publishedAt: candidate.sourcePublishedAt, imageKey: candidate.imageKey, title: candidate.title, excerpt: candidate.text };
        let article: Awaited<ReturnType<typeof createNews>>;
        try { article = await createNews(articleInput, articleOrigin, existing); }
        catch (err) { if (articleMedia && err instanceof Error && err.message === 'media_write_failed') article = await createNews({ ...articleInput, status: 'review', mediaType: 'text', media: undefined }, articleOrigin, existing); else throw err; }
        createdItems.push(article);
      }
    }
    const updatedSource = await updateSource(id, source, {
      autoPublish: automaticPublish,
      lastCheckedAt: Date.now(), lastSuccessAt: createdItems.length ? Date.now() : source.lastSuccessAt, lastFingerprint: createdItems.length ? batch.fingerprint : source.lastFingerprint, lastError: '', fetchedCount: (source.fetchedCount || 0) + createdItems.length
    });
    return { created: createdItems.length > 0, item: createdItems[0], items: createdItems, source: updatedSource, discoveredCount: batch.discoveredCount };
  } catch (err) {
    await updateSource(id, source, { lastCheckedAt: Date.now(), lastError: err instanceof Error ? err.message : 'خطای دریافت' });
    throw err;
  }
};

async function publishScheduledItems() {
  const settings = await getSettings();
  const items = await getNewsByIds((await getNewsIndex()).editorIds); const due = items.filter((item) => item.status === 'scheduled' && item.publishAt && item.publishAt <= Date.now()); let publishedCount = 0;
  for (const item of due) { if (settings.visualRequired && !hasRenderableVisual(item)) { const record = withoutId({ ...item, status: 'review', publishAt: undefined, updatedAt: Date.now() }); const [ok] = await db.update('news', [{ id: item.id, record }]); if (ok) await updateNewsIndexForRecord(item.id, { ...item, ...record }); continue; } const record = withoutId({ ...item, status: 'published', publishedAt: Date.now(), updatedAt: Date.now() }); const [ok] = await db.update('news', [{ id: item.id, record }]); if (ok) { const published = { ...item, ...record, id: item.id } as NewsRecord & { id: string }; await updateNewsIndexForRecord(item.id, published); await publishToSocials(published).catch(() => undefined); const [hydrated] = await hydrateNews([published]); await notifySubscribers('news', 'feed', { action: 'upsert', item: hydrated || published }).catch(() => undefined); publishedCount += 1; } }
  return publishedCount;
}

const getNewsItems = async (includeAll = false, settingsOverride?: SettingsRecord) => {
  await ensureSeed();
  const settings = settingsOverride || await getSettings();
  if (!includeAll && publicNewsCache && publicNewsCache.expiresAt > Date.now()) return publicNewsCache.items;
  const index = await getNewsIndex();
  const ids = includeAll ? index.editorIds.slice(0, ADMIN_NEWS_LIMIT) : Array.from(new Set([...index.activeIds,...index.publicIds.slice(0,PUBLIC_FEED_LIMIT*3)])).slice(0,PUBLIC_FEED_LIMIT*3);
  const records = await getNewsByIds(ids);
  const eligibleRecords = includeAll ? records : collapseImportedDuplicates(records.filter((item) => (!settings.visualRequired || hasRenderableVisual(item)) && (item.section === 'analysis' || !isNewsArchiveEligible(item, Date.now(), settings.archiveDays))));
  const selectedRecords = includeAll ? eligibleRecords : (() => { const selectedIds = composeActiveIds(eligibleRecords.map((item) => item.id), eligibleRecords.filter(isArianaImportedRecord).map((item) => item.id), []); const byId = new Map(eligibleRecords.map((item) => [item.id, item])); return selectedIds.map((id) => byId.get(id)).filter((item): item is NewsRecord & { id: string } => Boolean(item)); })();
  const items = await hydrateNews(selectedRecords);
  if (!includeAll) publicNewsCache = { expiresAt: Date.now() + NEWS_READ_CACHE_MS, items };
  return items;
};
const normalizeSearchText=(value:unknown)=>String(value??'').normalize('NFKC').toLowerCase().replace(/[\u064B-\u065F\u0670\u06D6-\u06ED]/g,'').replace(/[يىئ]/g,'ی').replace(/ك/g,'ک').replace(/[ۀة]/g,'ه').replace(/ؤ/g,'و').replace(/[أإٱآ]/g,'ا').replace(/[\u200B-\u200F\u202A-\u202E\u2060\uFEFF]/g,' ').replace(/[۰-۹]/g,(digit)=>String('۰۱۲۳۴۵۶۷۸۹'.indexOf(digit))).replace(/[٠-٩]/g,(digit)=>String('٠١٢٣٤٥٦٧٨٩'.indexOf(digit))).replace(/<[^>]*>/g,' ').replace(/[^\p{L}\p{N}]+/gu,' ').replace(/\s+/g,' ').trim();
const searchPublishedNews=async(queryValue:unknown,limitValue=60)=>{await ensureSeed();const query=normalizeSearchText(queryValue);if(query.length<2)return{items:[],query,total:0};const terms=Array.from(new Set(query.split(' ').filter(Boolean))).slice(0,10);const settings=await getSettings();const rows=collapseImportedDuplicates(sortNewsForDisplay((await listAllNews()).map(sanitizeLegacyImportedRecord).filter((item)=>item.status==='published'&&(!settings.visualRequired||hasRenderableVisual(item)))));const matched=rows.map((item)=>{const title=normalizeSearchText(item.title),summary=normalizeSearchText(item.summary),body=normalizeSearchText(item.body),meta=normalizeSearchText(`${item.category} ${item.originName||''} ${item.originTitle||''} ${item.originExcerpt||''} ${item.language}`),haystack=`${title} ${summary} ${body} ${meta}`;if(!terms.every((term)=>haystack.includes(term)))return null;const score=terms.reduce((total,term)=>total+(title.includes(term)?8:0)+(summary.includes(term)?4:0)+(meta.includes(term)?2:0)+(body.includes(term)?1:0),0);return{item,score};}).filter((entry):entry is {item:NewsRecord&{id:string};score:number}=>Boolean(entry)).sort((a,b)=>b.score-a.score||newsPublishedTimestamp(b.item)-newsPublishedTimestamp(a.item));const total=matched.length;const limit=Math.min(60,Math.max(1,Math.floor(limitValue)||60));return{items:await hydrateNews(matched.slice(0,limit).map((entry)=>entry.item)),query,total};};
const SAYEH_PUBLIC_SITE='https://sayeh-news.un-beha.org'; const SAYEH_API_ORIGIN='https://api-v2.appdeploy.ai/app/605a5f030a6d2cec77'; const xmlEscape=(value:unknown)=>String(value??'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&apos;'); const htmlEscape=(value:unknown)=>String(value??'').replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;').replace(/'/g,'&#39;'); const socialPlainText=(value:unknown)=>String(value??'').replace(/^<!--sayeh-rich-v1-->/,'').replace(/<[^>]+>/g,' ').replace(/\s+/g,' ').trim(); const socialStoryUrl=(id:string)=>`${SAYEH_API_ORIGIN}/share/${encodeURIComponent(id)}`; const socialImageUrl=(id:string)=>`${SAYEH_API_ORIGIN}/rss-image/${encodeURIComponent(id)}`; const resolveSocialImage=async(id:string)=>{const settings=await getSettings();const [stored]=await db.get<NewsRecord>('news',[id]);if(!stored||stored.status!=='published'||!stored.mediaPath||stored.mediaType!=='image'||(settings.visualRequired&&!hasRenderableVisual(stored)))return '';const [signed]=await storage.url([stored.mediaPath]);return signed?.url||'';}; const buildPublicRss=async()=>{const feed=(await getNewsItems(false) as Array<NewsRecord&{id:string;mediaUrl?:string}>).slice(0,50);const items=feed.map((item)=>{const link=socialStoryUrl(item.id),published=item.publishedAt||item.updatedAt||item.createdAt,media=item.mediaPath&&item.mediaType==='image'?(()=>{const image=socialImageUrl(item.id);return `<enclosure url="${xmlEscape(image)}" length="0" type="${xmlEscape(item.mediaMime||'image/jpeg')}"/><media:content url="${xmlEscape(image)}" medium="image" type="${xmlEscape(item.mediaMime||'image/jpeg')}"/><media:thumbnail url="${xmlEscape(image)}"/>`;})():'';return `<item><title>${xmlEscape(item.title)}</title><link>${xmlEscape(link)}</link><guid isPermaLink="false">sayeh-${xmlEscape(item.id)}</guid><pubDate>${new Date(published).toUTCString()}</pubDate><category>${xmlEscape(item.category)}</category><description>${xmlEscape('\n\n— '+socialPlainText(item.summary))}</description>${item.mediaPath&&item.mediaType==='image'?`<sayehImageUrl>${xmlEscape(socialImageUrl(item.id))}</sayehImageUrl>`:''}${media}</item>`;}).join('');return `<?xml version="1.0" encoding="UTF-8"?><rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/"><channel><title>SAYEH NEWS | خبرگزاری سایه</title><link>${xmlEscape(SAYEH_PUBLIC_SITE)}</link><description>پنهان از نگاه‌ها، مسلط بر رویدادها</description><language>fa-AF</language><lastBuildDate>${new Date().toUTCString()}</lastBuildDate>${items}</channel></rss>`;}; const buildSocialSharePage=async(id:string)=>{const settings=await getSettings();const [stored]=await db.get<NewsRecord>('news',[id]);if(!stored||stored.status!=='published'||(settings.visualRequired&&!hasRenderableVisual(stored)))return null;const [hydrated]=await hydrateNews([{...stored,id}]);const item=hydrated as NewsRecord&{id:string;mediaUrl?:string};const title=htmlEscape(item.title),summary=htmlEscape(socialPlainText(item.summary)),image=htmlEscape(item.mediaPath&&item.mediaType==='image'?socialImageUrl(id):''),target=`${SAYEH_PUBLIC_SITE}/#story=${encodeURIComponent(id)}`,share=socialStoryUrl(id);return `<!doctype html><html lang="fa-AF" dir="rtl"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>${title} — SAYEH NEWS</title><meta name="description" content="${summary}"><link rel="canonical" href="${htmlEscape(share)}"><meta property="og:type" content="article"><meta property="og:site_name" content="SAYEH NEWS"><meta property="og:title" content="${title}"><meta property="og:description" content="${summary}"><meta property="og:url" content="${htmlEscape(share)}">${image?`<meta property="og:image" content="${image}"><meta name="twitter:card" content="summary_large_image"><meta name="twitter:image" content="${image}">`:''}<meta name="twitter:title" content="${title}"><meta name="twitter:description" content="${summary}"><meta http-equiv="refresh" content="0;url=${htmlEscape(target)}"></head><body><p><a href="${htmlEscape(target)}">SAYEH NEWS — ${title}</a></p></body></html>`;};

const getAnalysisItems = async (offsetValue = 0, limitValue = ARCHIVE_PAGE_LIMIT) => {
  await ensureSeed();
  const settings = await getSettings();
  const offset = Math.max(0, Math.floor(offsetValue));
  const limit = Math.min(60, Math.max(12, Math.floor(limitValue) || ARCHIVE_PAGE_LIMIT));
  const cacheKey = `${offset}:${limit}`;
  const cached = analysisNewsCache.get(cacheKey);
  if (cached && cached.expiresAt > Date.now()) return cached.value;
  const index = await getNewsIndex();
  const ids = index.analysisIds.slice(offset, offset + limit);
  const analysisRecords = collapseImportedDuplicates((await getNewsByIds(ids)).filter((item) => item.status === 'published' && (!settings.visualRequired || hasRenderableVisual(item)))); const value: ArchivePage = { items: await hydrateNews(analysisRecords), total: index.analysisIds.length, offset, limit, hasMore: offset + ids.length < index.analysisIds.length };
  analysisNewsCache.set(cacheKey, { expiresAt: Date.now() + NEWS_READ_CACHE_MS, value });
  return value;
};

const getEnglishItems = async (offsetValue = 0, limitValue = ARCHIVE_PAGE_LIMIT) => { await ensureSeed(); const settings = await getSettings(); const offset = Math.max(0, Math.floor(offsetValue)); const limit = Math.min(60, Math.max(12, Math.floor(limitValue) || ARCHIVE_PAGE_LIMIT)); const cacheKey = `${offset}:${limit}`; const cached = englishNewsCache.get(cacheKey); if (cached && cached.expiresAt > Date.now()) return cached.value; const index = await getNewsIndex(); const needed = offset + limit + 1; let matched: Array<NewsRecord & { id: string }> = []; for (let cursor = 0; cursor < index.publicIds.length && matched.length < needed + 24; cursor += 120) { const page = await getNewsByIds(index.publicIds.slice(cursor, cursor + 120)); matched = collapseImportedDuplicates([...matched, ...page.filter((item) => item.status === 'published' && item.language.toLowerCase() === 'english' && (!settings.visualRequired || hasRenderableVisual(item)) && !isNewsArchiveEligible(item, Date.now(), settings.archiveDays))]); } matched.sort((a, b) => Number(isAfghanistanRelevantRecord(b)) - Number(isAfghanistanRelevantRecord(a)) || (b.publishedAt || b.updatedAt || b.createdAt) - (a.publishedAt || a.updatedAt || a.createdAt)); const pageItems = matched.slice(offset, offset + limit); const hasMore = matched.length > offset + limit; const value: ArchivePage = { items: await hydrateNews(pageItems), total: hasMore ? offset + pageItems.length + 1 : matched.length, offset, limit, hasMore }; englishNewsCache.set(cacheKey, { expiresAt: Date.now() + NEWS_READ_CACHE_MS, value }); return value; };

const getRecentNewsItems = async (offsetValue = 0, limitValue = ARCHIVE_PAGE_LIMIT) => {
  await ensureSeed();
  const settings = await getSettings();
  const offset = Math.max(0, Math.floor(offsetValue)); const limit = Math.min(60, Math.max(12, Math.floor(limitValue) || ARCHIVE_PAGE_LIMIT)); const cacheKey = `${offset}:${limit}`; const cached = recentNewsCache.get(cacheKey); if (cached && cached.expiresAt > Date.now()) return cached.value;
  const index = await getNewsIndex(); const needed = offset + limit + 1; let matched: Array<NewsRecord & { id: string }> = [];
  for (let cursor = 0; cursor < index.publicIds.length && matched.length < needed; cursor += 120) { const page = await getNewsByIds(index.publicIds.slice(cursor, cursor + 120)); matched = collapseImportedDuplicates([...matched, ...page.filter((item) => item.status === 'published' && item.section === 'news' && (!settings.visualRequired || hasRenderableVisual(item)) && !isNewsArchiveEligible(item, Date.now(), settings.archiveDays))]); }
  const pageItems = matched.slice(offset, offset + limit); const hasMore = matched.length > offset + limit; const value: ArchivePage = { items: await hydrateNews(pageItems), total: hasMore ? offset + pageItems.length + 1 : matched.length, offset, limit, hasMore }; recentNewsCache.set(cacheKey, { expiresAt: Date.now() + NEWS_READ_CACHE_MS, value }); return value;
};

const findArchiveBoundary = async (ids: string[], now: number, days: number) => { let low = 0; let high = ids.length; while (low < high) { const mid = Math.floor((low + high) / 2); const [item] = await getNewsByIds([ids[mid]]); if (item && hasReachedNewsArchiveAge(item, now, days)) high = mid; else low = mid + 1; } return low; };

const getArchiveItems = async (offsetValue = 0, limitValue = ARCHIVE_PAGE_LIMIT) => {
  await ensureSeed();
  const settings = await getSettings();
  const offset = Math.max(0, Math.floor(offsetValue));
  const limit = Math.min(60, Math.max(12, Math.floor(limitValue) || ARCHIVE_PAGE_LIMIT));
  const cacheKey = `${offset}:${limit}`;
  const cached = archiveNewsCache.get(cacheKey);
  if (cached && cached.expiresAt > Date.now()) return cached.value;
  const index = await getNewsIndex();
  const now = Date.now(); const boundary = await findArchiveBoundary(index.publicIds, now, settings.archiveDays); const needed = offset + limit + 1; let matched: Array<NewsRecord & { id: string }> = [];
  for (let cursor = Math.max(0, boundary - 24); cursor < index.publicIds.length && matched.length < needed; cursor += 120) { const page = await getNewsByIds(index.publicIds.slice(cursor, cursor + 120)); matched = collapseImportedDuplicates([...matched, ...page.filter((item) => (!settings.visualRequired || hasRenderableVisual(item)) && isNewsArchiveEligible(item, now, settings.archiveDays))]); }
  matched = sortNewsForDisplay(matched); const pageItems = matched.slice(offset, offset + limit); const hasMore = matched.length > offset + limit;



  const value: ArchivePage = { items: await hydrateNews(pageItems), total: hasMore ? offset + pageItems.length + 1 : matched.length, offset, limit, hasMore };
  archiveNewsCache.set(cacheKey, { expiresAt: Date.now() + NEWS_READ_CACHE_MS, value });
  return value;
};

const isArianaOrigin = (item: NewsRecord) => isArianaImportedRecord(item);
const repairArianaImports = async (maxItems = 1) => {
  const settings = await getSettings();
  const [items, { items: sources }] = await Promise.all([listAllNews(), db.list<SourceRecord>('sources', { limit: 200 })]);
  const source = sources.find(isArianaSource);
  if (!source) return 0;
  const targets = items.filter((item) => isArianaOrigin(item) && item.sourcePipelineVersion !== SOURCE_PIPELINE_VERSION && (importedNoiseTest.test(`${item.summary}\n${item.body}`) || normalizeGeneratedText(item.body).startsWith(normalizeGeneratedText(item.title)) || normalizeGeneratedText(item.body).length < 220)).slice(0, maxItems);
  let repairedCount = 0;
  for (const item of targets) {
    try {
      const sourceText = cleanSourceText(await fetchArticleText(item.originUrl || ''), item.title).slice(0, 9000);
      if (sourceText.length < 45) continue;
      const result = await ai.extract({
        content: JSON.stringify({ source: { name: source.name, url: source.url, topic: source.topic, language: source.language }, title: item.title, url: item.originUrl, text: sourceText.slice(0, 5200) }),
        prompt: `این خبر را به زبان ${source.language || 'دری'} حرفه‌ای، مستقل، روان و دقیق بازنویسی کن. فقط اطلاعات موجود در متن منبع را استفاده کن. در پوشش افغانستان، اگر منبع اقدام، دستاورد، بهبود خدمات یا نتیجهٔ مثبتِ مشخص و قابل‌اثبات از نهادهای حاکم یا امارت اسلامی را گزارش می‌کند و برای اصل خبر مهم است، آن را روشن و متناسب بازتاب بده و کم‌رنگ نکن. نقد، اتهام یا ادعای منفی را با نسبت‌دهی روشن و فقط در حد پشتیبانی متن منبع بنویس و مشکلات مهم و مستند را نیز حذف نکن؛ لحن خبری و غیرتبلیغاتی بماند و هیچ ستایش یا ادعای تازه اضافه نشود. عنوان باید روشن و خبری باشد؛ خلاصه باید یک جمله یا پاراگراف کامل باشد و وسط جمله قطع نشود؛ بدنه باید منسجم و متناسب با حجم منبع باشد و از ۱۰۰۰ واژه بیشتر نشود. نام‌ها، سمت‌ها، کشورها، مکان‌ها، تاریخ‌ها، اعداد، آمار و نقل‌قول‌ها را دقیق حفظ کن. هیچ متن منو، تبلیغ، خبر مرتبط، زمان نسبی مطالب دیگر، Continue Reading، Related Topics، Advertisement، You may like، By Ariana News یا ادعای اختصاصی وارد نکن.`,
        schema: { type: 'object', properties: { title: { type: 'string' }, summary: { type: 'string' }, body: { type: 'string' } }, required: ['title', 'summary', 'body'] },
        maxTokens: 3600, temperature: 0.2, thinkingMode: 'FAST'
      });
      const extracted = result.data as Record<string, unknown>;
      const title = normalizeGeneratedText(textValue(extracted.title)) || normalizeGeneratedText(item.title);
      const body = limitWords(normalizeGeneratedText(textValue(extracted.body)) || sourceText, settings.autoWordLimit);
      const summary = normalizeGeneratedText(textValue(extracted.summary)) || body.slice(0, 220);
      if (!title || body.length < 60) continue;
      const record = withoutId({ ...item, title, summary, body, sourcePipelineVersion: SOURCE_PIPELINE_VERSION, updatedAt: Date.now() });
      const [ok] = await db.update('news', [{ id: item.id, record }]);
      if (!ok) continue;
      const updated = { ...item, ...record, id: item.id } as NewsRecord & { id: string };
      const [hydrated] = await hydrateNews([updated]);
      await notifySubscribers('news', 'feed', { action: 'upsert', item: hydrated || updated }).catch(() => undefined);
      repairedCount += 1;
    } catch (err) {
      console.warn('ariana_import_repair_failed', { id: item.id, reason: err instanceof Error ? err.message : 'unknown' });
    }
  }
  return repairedCount;
};

const videoRepairInFlight = new Map<string, Promise<NewsRecord & { id: string }>>();
const repairLegacyVideoAnalysis = async (item: NewsRecord & { id: string }) => {
  if (!item.externalVideoUrl || item.sourcePipelineVersion === SOURCE_PIPELINE_VERSION) return item;
  const settings = await getSettings();
  const existing = videoRepairInFlight.get(item.id); if (existing) return existing;
  const task = (async () => {
    const transcript = await fetchYouTubeTranscript(item.externalVideoUrl || '');
    if (transcript.length < 220) return item;
    const sourceName = normalizeGeneratedText(item.originName || (isArianaOrigin(item) ? 'آریانا نیوز' : ''));
    const result = await ai.extract({ content: JSON.stringify({ sourceName, originalTitle: item.originTitle || item.title, transcript: transcript.slice(0, 9000) }), prompt: `بر پایهٔ متن واقعی کپشن/زیرنویس این ویدیو، یک گزارش و جمع‌بندی مستقل برای تحریریه سایه به زبان دری بنویس. عنوان و خلاصه باید مستقیماً مهم‌ترین موضوع، ادعا یا نتیجهٔ گفت‌وگو را بیان کنند و نام برنامه یا رسانهٔ منبع را در عنوان و لید نیاورند. بدنه باید نکات اصلی و دیدگاه‌های مطرح‌شده را دقیق، فشرده و بی‌طرفانه بازگو کند؛ از معرفی برنامه، تبلیغ منبع یا عبارت‌هایی مانند «برنامه منتشر شده» به‌عنوان متن اصلی پرهیز کن. ادعا نکن خبرنگار سایه مصاحبه را انجام داده یا در صحنه بوده است. هیچ جزئیات، نقل‌قول یا نتیجه‌ای بیرون از متن کپشن نساز. نام منبع را در متن تولیدی اضافه نکن؛ سامانه آن را جداگانه درج می‌کند.`, schema: { type: 'object', properties: { title: { type: 'string' }, summary: { type: 'string' }, body: { type: 'string' } }, required: ['title', 'summary', 'body'] }, maxTokens: 3600, temperature: 0.15, thinkingMode: 'FAST' });
    const extracted = result.data as Record<string, unknown>;
    const title = normalizeGeneratedText(textValue(extracted.title)) || normalizeGeneratedText(item.title);
    const bodyCore = limitWords(normalizeGeneratedText(textValue(extracted.body)), Math.max(100, settings.autoWordLimit - 30));
    const summary = normalizeGeneratedText(textValue(extracted.summary)) || bodyCore.slice(0, 220);
    if (!title || bodyCore.length < 120 || !summary) return item;
    const body = sourceName ? `${bodyCore}\n\nمنبع ویدیویی: ${sourceName}.` : bodyCore;
    const record = withoutId({ ...item, title, summary, body, section: 'analysis', category: 'مصاحبه و تحلیل', sourcePipelineVersion: SOURCE_PIPELINE_VERSION, updatedAt: Date.now() });
    const [ok] = await db.update('news', [{ id: item.id, record }]); if (!ok) return item;
    const updated = { ...item, ...record, id: item.id } as NewsRecord & { id: string };
    await updateNewsIndexForRecord(item.id, updated); clearNewsReadCaches();
    const [hydrated] = await hydrateNews([updated]); await notifySubscribers('news', 'feed', { action: 'upsert', item: hydrated || updated }).catch(() => undefined);
    return updated;
  })();
  videoRepairInFlight.set(item.id, task);
  try { return await task; } finally { videoRepairInFlight.delete(item.id); }
};

const PRIMARY_ARIANA_URL = 'https://www.ariananews.af/fa/'; const VOA_ENGLISH_RSS_URL = 'https://www.voanews.com/api/z_-mqyl-vomx-tpevyvqv'; const ENGLISH_SOURCE_DEFINITIONS = [{ key: 'voa_english_south_central_asia_v1', name: 'VOA English – South & Central Asia', url: VOA_ENGLISH_RSS_URL, sourceType: 'RSS', topic: 'Afghanistan & Region', priority: 'بالا', intervalMinutes: 5 }, { key: 'global_voices_afghanistan_v2', name: 'Global Voices – Afghanistan', url: 'https://globalvoices.org/-/world/central-asia-caucasus/afghanistan/feed/', sourceType: 'RSS', topic: 'Afghanistan', priority: 'بالا', intervalMinutes: 30 }, { key: 'govuk_afghanistan_news_v2', name: 'GOV.UK – Afghanistan and the UK', url: 'https://www.gov.uk/search/news-and-communications.atom?world_locations%5B%5D=afghanistan', sourceType: 'ATOM', topic: 'Afghanistan', priority: 'بالا', intervalMinutes: 15 }] as const;
const ensurePrimaryArianaSource = async (settings: SettingsRecord) => { if (!settings.forceArianaSource) return undefined; const { items } = await db.list<SourceRecord>('sources', { limit: 500 }); const ariana = items.filter(isArianaSource).sort((a, b) => Number(b.active) - Number(a.active) || (b.fetchedCount || 0) - (a.fetchedCount || 0)); const now = Date.now(); let primary = ariana[0]; if (primary) { const { id, ...current } = primary; const record: SourceRecord = { ...current, name: 'آریانا نیوز', url: PRIMARY_ARIANA_URL, sourceType: current.sourceType || 'وب‌سایت', language: 'دری', topic: 'افغانستان', priority: 'بالا', intervalMinutes: Math.max(5, settings.primaryIntervalMinutes), active: true, autoPublish: true, rightsApproved: true, ingestText: true, ingestImage: true, ingestVideo: true, ingestInterview: true, createdAt: current.createdAt || now, updatedAt: now, fetchedCount: current.fetchedCount || 0 }; const [ok] = await db.update('sources', [{ id, record: record as unknown as Record<string, unknown> }]); if (ok) primary = { id, ...record }; } else { const record: SourceRecord = { name: 'آریانا نیوز', url: PRIMARY_ARIANA_URL, sourceType: 'وب‌سایت', language: 'دری', topic: 'افغانستان', priority: 'بالا', intervalMinutes: Math.max(5, settings.primaryIntervalMinutes), active: true, autoPublish: true, rightsApproved: true, ingestText: true, ingestImage: true, ingestVideo: true, ingestInterview: true, createdAt: now, updatedAt: now, fetchedCount: 0 }; const [id] = await db.add('sources', [record]); if (id) primary = { id, ...record }; } if (primary) { const extras = ariana.filter((item) => item.id !== primary!.id && (item.active || item.autoPublish)).map((item) => ({ id: item.id, record: withoutId({ ...item, active: false, autoPublish: false, updatedAt: now }) })); if (extras.length) await db.update('sources', extras); } return primary; };
const ensureEnglishDeskSourcesOnce = async () => { const { items: migrations } = await db.list<{ key: string }>('system_migrations', { limit: 200 }); const completed = new Set(migrations.map((item) => item.key)); const { items } = await db.list<SourceRecord>('sources', { limit: 500 }); const now = Date.now(); for (const definition of ENGLISH_SOURCE_DEFINITIONS) { if (completed.has(definition.key)) continue; const existing = items.find((item) => canonicalArticleUrl(item.url) === canonicalArticleUrl(definition.url) || item.name === definition.name || (definition.key.startsWith('voa_') && isVoaSource(item))); const desired: SourceRecord = { name: definition.name, url: definition.url, sourceType: definition.sourceType, language: 'English', topic: definition.topic, priority: definition.priority, intervalMinutes: definition.intervalMinutes, active: true, autoPublish: true, rightsApproved: true, ingestText: true, ingestImage: true, ingestVideo: false, ingestInterview: false, createdAt: existing?.createdAt || now, updatedAt: now, lastCheckedAt: existing?.lastCheckedAt, lastSuccessAt: existing?.lastSuccessAt, lastFingerprint: existing?.lastFingerprint, lastError: '', fetchedCount: existing?.fetchedCount || 0 }; let provisioned = false; if (existing) { const [ok] = await db.update('sources', [{ id: existing.id, record: withoutId({ ...existing, ...desired }) }]); provisioned = ok; } else { const [id] = await db.add('sources', [desired]); provisioned = Boolean(id); } if (provisioned) { await db.add('system_migrations', [{ key: definition.key, createdAt: now }]); completed.add(definition.key); } } }; const retireLegacyStateEnglishSourceOnce = async () => { const key = 'state_afghanistan_updates_retired_v2'; const { items: migrations } = await db.list<{ key: string }>('system_migrations', { limit: 250 }); if (migrations.some((item) => item.key === key)) return; const { items } = await db.list<SourceRecord>('sources', { limit: 500 }); const legacy = items.find((item) => item.name === 'U.S. State Department – Afghanistan' && canonicalArticleUrl(item.url) === canonicalArticleUrl('https://travel.state.gov/en/international-travel/travel-advisories/afghanistan.html')); if (legacy) await db.update('sources', [{ id: legacy.id, record: withoutId({ ...legacy, active: false, autoPublish: false, lastError: '', updatedAt: Date.now() }) }]); await db.add('system_migrations', [{ key, createdAt: Date.now() }]); };
// Source monitoring is split into bounded, independent jobs so one slow publisher can never disable the newsroom.
const scheduledSourceTimeout = (sourceId:string) => new Promise<never>((_,reject)=>setTimeout(()=>reject(new Error(`source_ingest_timeout:${sourceId}`)),23000));
const runBoundedSourceIngest = async (source:SourceRecord & {id:string},job:string) => { try { const result=await Promise.race([ingestSource(source.id,1,true),scheduledSourceTimeout(source.id)]); return Boolean(result.created); } catch(err) { const reason=err instanceof Error?err.message:'source_ingest_failed'; await updateSource(source.id,source,{lastCheckedAt:Date.now(),lastError:reason}).catch(()=>undefined); console.warn('bounded_source_ingest_failed',{job,sourceId:source.id,reason}); return false; } };
const dueSecondarySource = async (settings:SettingsRecord) => { await ensureEnglishDeskSourcesOnce(); await retireLegacyStateEnglishSourceOnce(); const {items}=await db.list<SourceRecord>('sources',{limit:200}); const rank:Record<string,number>={'بالا':3,'متوسط':2,'عادی':1}; return items.filter((item)=>item.active&&item.rightsApproved&&(!settings.forceArianaSource||!isArianaSource(item))&&sourceIntervalDue(item)).sort((a,b)=>(a.lastCheckedAt||0)-(b.lastCheckedAt||0)||(rank[b.priority]||0)-(rank[a.priority]||0))[0]; };
let fakoriLookupLogged=false; export const monitorPrimarySourceHandler = async () => { if(!fakoriLookupLogged){fakoriLookupLogged=true;try{const rows=await listAllNews();const matchText=(value:string)=>{const text=normalizeSearchText(value);return text.includes('فکوری')||text.includes('فکوري')||text.includes('عبدالحکیم شرعی')||text.includes('بزرگان اهل تشیع')||(text.includes('بهشتی')&&text.includes('تبعیض'));};const matches=rows.filter((item)=>matchText(`${item.title} ${item.summary} ${item.body} ${item.originTitle||''} ${item.originExcerpt||''}`)).slice(0,20).map((item)=>({id:item.id,title:item.title,status:item.status,section:item.section,archived:Boolean(item.archived),publishedAt:item.publishedAt||null,createdAt:item.createdAt||null,hasMedia:Boolean(item.mediaPath),renderableVisual:hasRenderableVisual(item),originTitle:item.originTitle||''}));const {items:tombstones}=await db.list<{originTitle?:string;originExcerpt?:string;deletedAt?:number;originUrl?:string}>('news_delete_tombstones',{limit:500});const deleted=tombstones.filter((item)=>matchText(`${item.originTitle||''} ${item.originExcerpt||''}`)).slice(0,20).map((item)=>({id:item.id,originTitle:item.originTitle||'',deletedAt:item.deletedAt||null,originUrl:item.originUrl||''}));console.error('FAKORI_LOOKUP',JSON.stringify({totalRows:rows.length,matches,deleted}));}catch(err){console.error('FAKORI_LOOKUP_FAILED',err instanceof Error?err.message:'unknown');}}const settings=await getSettings(); if(!settings.sourceMonitorEnabled)return {statusCode:200}; const primary=await ensurePrimaryArianaSource(settings); if(primary&&sourceIntervalDue(primary))await runBoundedSourceIngest(primary,'primary'); return {statusCode:200}; };
export const monitorSecondarySourceHandler = async () => { const rows=await listAllNews();const matchText=(value:string)=>{const text=normalizeSearchText(value);return text.includes('فکوری')||text.includes('فکوري')||text.includes('عبدالحکیم شرعی')||text.includes('بزرگان اهل تشیع')||(text.includes('بهشتی')&&text.includes('تبعیض'));};const matches=rows.filter((item)=>matchText(`${item.title} ${item.summary} ${item.body} ${item.originTitle||''} ${item.originExcerpt||''}`)).slice(0,10).map((item)=>({id:item.id,title:item.title,status:item.status,archived:Boolean(item.archived),hasMedia:Boolean(item.mediaPath),renderableVisual:hasRenderableVisual(item),publishedAt:item.publishedAt||null}));const {items:tombstones}=await db.list<{originTitle?:string;originExcerpt?:string;deletedAt?:number;originUrl?:string}>('news_delete_tombstones',{limit:500});const deleted=tombstones.filter((item)=>matchText(`${item.originTitle||''} ${item.originExcerpt||''}`)).slice(0,10).map((item)=>({originTitle:item.originTitle||'',deletedAt:item.deletedAt||null,originUrl:item.originUrl||''}));throw new Error(`FAKORI_LOOKUP ${JSON.stringify({totalRows:rows.length,matches,deleted})}`); };
export const monitorSourceRecoveryHandler = async () => { const settings=await getSettings(); if(!settings.sourceMonitorEnabled)return {statusCode:200}; const {items}=await db.list<SourceRecord>('sources',{limit:200}); const now=Date.now(); const source=items.filter((item)=>item.active&&item.rightsApproved&&sourceIntervalDue(item)&&(Boolean(item.lastError)||!item.lastSuccessAt||now-item.lastSuccessAt>Math.max(30*60*1000,item.intervalMinutes*3*60*1000))).sort((a,b)=>Number(Boolean(b.lastError))-Number(Boolean(a.lastError))||(a.lastSuccessAt||0)-(b.lastSuccessAt||0))[0]; if(source)await runBoundedSourceIngest(source,'recovery'); return {statusCode:200}; };
export const monitorSourcesHandler = async () => monitorPrimarySourceHandler();
type AnalysisDeskRunRecord = { articleId:string; title:string; basisIds:string[]; createdAt:number }; type AnalysisDraftRecord={title:string;summary:string;body:string;category:string;basisIds:string[];imagePrompt:string;imageQuery:string;status:'pending'|'published';createdAt:number;updatedAt:number;lastImageAttemptAt?:number;articleId?:string}; type AnalysisDeskStateRecord={lastAttemptAt:number;updatedAt:number}; const ANALYSIS_DESK_RUNS_TABLE='analysis_desk_runs_v1'; const ANALYSIS_DRAFTS_TABLE='analysis_drafts_v1'; const ANALYSIS_DESK_STATE_TABLE='analysis_desk_state_v1'; const ANALYSIS_DESK_MAX_24H=4; const ANALYSIS_DESK_WINDOW_MS=24*60*60*1000; const ANALYSIS_DESK_MIN_GAP_MS=6*60*60*1000; const listAnalysisDeskRuns=async()=>{ const {items}=await db.list<AnalysisDeskRunRecord>(ANALYSIS_DESK_RUNS_TABLE,{limit:200}); return items.sort((a,b)=>b.createdAt-a.createdAt); }; const analysisNewsContext=async()=>{ const index=await getNewsIndex(); const records=collapseImportedDuplicates((await getNewsByIds(index.publicIds.slice(0,180))).filter((item)=>item.status==='published'&&item.section==='news')); const now=Date.now(); const fresh=records.filter((item)=>newsPublishedTimestamp(item)>=now-ANALYSIS_DESK_WINDOW_MS); const pool=fresh.sort((a,b)=>Number(isAfghanistanRelevantRecord(b))-Number(isAfghanistanRelevantRecord(a))||newsPublishedTimestamp(b)-newsPublishedTimestamp(a)).slice(0,24); return pool; }; const generateSeniorAnalysis=async()=>{ const now=Date.now(); const runs=await listAnalysisDeskRuns(); if(runs.filter((item)=>item.createdAt>=now-ANALYSIS_DESK_WINDOW_MS).length>=ANALYSIS_DESK_MAX_24H) return {created:false,reason:'daily_cap'}; const basis=await analysisNewsContext(); if(basis.length<3) return {created:false,reason:'insufficient_recent_news'}; const recentTitles=runs.filter((item)=>item.createdAt>=now-7*24*60*60*1000).slice(0,20).map((item)=>item.title); const context={recentAnalysisTitles:recentTitles,stories:basis.map((item)=>({id:item.id,title:item.title,summary:item.summary,body:translationPlainBody(item.body).slice(0,1000),category:item.category,language:item.language,source:item.originName||'SAYEH NEWS',publishedAt:item.publishedAt||item.updatedAt||item.createdAt,afghanistanRelevant:isAfghanistanRelevantRecord(item)}))}; const result=await ai.extract({system:'You are the SAYEH Senior Analysis Desk, a multidisciplinary editorial analysis function, not a named human persona. Never claim personal field experience, interviews, eyewitness access, academic degrees, or credentials. Produce rigorous, independent analysis with professional restraint.',content:JSON.stringify(context),prompt:'Write one original, research-grade analytical article in Dari using the supplied newsroom reporting as the factual basis for current events. Choose the most consequential topic, prioritizing Afghanistan, then the region, then major global developments. Avoid topics substantially overlapping recentAnalysisTitles. Analyze the issue through several relevant lenses such as politics and institutions, sociology and social structure, political psychology and collective behavior, political economy, diplomacy, history, law, security, media or public policy when they genuinely add explanatory value. Use stable academic concepts for interpretation, but do not introduce current-event facts, quotations, statistics or claims that are not supported by the supplied stories. Clearly distinguish reported facts from inference and scenarios. On contested political questions, fairly present major plausible explanations and interests without partisan advocacy. When the supplied stories show concrete, verifiable positive governance or public-service outcomes by Afghanistan’s de facto authorities, acknowledge them explicitly and analyze their significance; when they show failures, harms, criticism or allegations, cover them with the same evidentiary discipline and clear attribution. Do not suppress either side. Avoid slogans, propaganda, generic filler, formulaic openings, repetitive transitions and list-like padding. The article should read like a mature professor-researcher: precise, nuanced, coherent and humane. Target roughly 1200–1600 Dari words, with a strong opening, 4–6 short meaningful subheadings inside the body, integrated argument, competing interpretations, implications and a restrained conclusion. Return a concise but substantial summary. Select the exact story ids actually used as basisIds. Also return imagePrompt and imageQuery in English: imagePrompt must describe a symbolic editorial illustration matching the title, not a documentary photograph; no text, no logos, no recognizable real persons, no fabricated scene of a real event, no graphic violence, widescreen 16:9. imageQuery should be a short English Openverse fallback query.',schema:{type:'object',properties:{title:{type:'string'},summary:{type:'string'},body:{type:'string'},category:{type:'string'},basisIds:{type:'array',items:{type:'string'}},imagePrompt:{type:'string'},imageQuery:{type:'string'}},required:['title','summary','body','category','basisIds','imagePrompt','imageQuery']},maxRetries:2,maxTokens:7800,temperature:0.35,thinkingMode:'DEEP'}); const extracted=result.data as Record<string,unknown>; const title=normalizeGeneratedText(textValue(extracted.title)); const summary=normalizeGeneratedText(textValue(extracted.summary)); const body=normalizeGeneratedText(textValue(extracted.body)); const categoryCore=normalizeGeneratedText(textValue(extracted.category)).slice(0,70)||'سیاست و جامعه'; const imagePrompt=normalizeGeneratedText(textValue(extracted.imagePrompt)); const imageQuery=normalizeGeneratedText(textValue(extracted.imageQuery))||title; const availableIds=new Set(basis.map((item)=>item.id)); const basisIds=(Array.isArray(extracted.basisIds)?extracted.basisIds:[]).map((value)=>textValue(value)).filter((id)=>availableIds.has(id)).slice(0,8); if(!title||summary.length<80||body.length<4500||!basisIds.length) return {created:false,reason:'quality_gate'}; if(runs.some((item)=>item.title.trim().toLowerCase()===title.trim().toLowerCase()&&item.createdAt>=now-7*24*60*60*1000)) return {created:false,reason:'duplicate_title'}; let media:MediaInput|undefined; try{ const generated=await ai.imageGen({prompt:`Editorial analysis illustration for SAYEH NEWS. Topic/title: ${title}. Summary: ${summary.slice(0,500)}. ${imagePrompt}. Make it clearly illustrative and symbolic, not a documentary photograph. No written words, captions or logos. No recognizable real people or fabricated depiction of a real scene. No graphic violence. Professional newsroom visual, widescreen 16:9.`,maxOutputBytes:850000}); if(generated.image?.data&&generated.image.mimeType) media={data:generated.image.data,mimeType:generated.image.mimeType,name:`sayeh-analysis-illustration-${now}.png`}; }catch(err){ console.warn('analysis_image_generation_failed',{reason:err instanceof Error?err.message:'unknown'}); } if(!media){ const fallback=await findOpenLicensedImage(imageQuery); if(fallback) media=fallback; } if(!media) return {created:false,reason:'visual_unavailable'}; const article=await createNews({title,summary,body,category:`تحلیل ارشد · ${categoryCore}`,language:'دری',section:'analysis',mediaType:'image',status:'published',isBreaking:false,media}); const [runId]=await db.add(ANALYSIS_DESK_RUNS_TABLE,[{articleId:article.id,title,basisIds,createdAt:Date.now()}]); if(!runId) console.warn('analysis_run_record_failed',{articleId:article.id}); return {created:true,articleId:article.id}; }; export const analysisDeskHandler=async()=>{ try{ await generateSeniorAnalysis(); }catch(err){ const rpc=err as {statusCode?:number;responseText?:string}; if(rpc?.statusCode===429){ console.warn('analysis_desk_rate_limited',{reason:rpc.responseText||'429'}); return {statusCode:200}; } console.warn('analysis_desk_failed',{reason:err instanceof Error?err.message:'unknown'}); } return {statusCode:200}; }; const SUPPORTED_PUBLIC_LANGUAGES = ['دری','پشتو','English','عربی','اردو','ترکی','فرانسوی'] as const; type PublicLanguage = typeof SUPPORTED_PUBLIC_LANGUAGES[number]; type NewsTranslationRecord = { articleId:string; targetLanguage:PublicLanguage; sourceUpdatedAt:number; title:string; summary:string; body:string; category:string; createdAt:number; updatedAt:number }; const NEWS_TRANSLATIONS_TABLE = 'news_translations_v1'; const isPublicLanguage = (value:string): value is PublicLanguage => (SUPPORTED_PUBLIC_LANGUAGES as readonly string[]).includes(value); const translationPlainBody = (value:string) => value.replace(/^<!--sayeh-rich-v1-->/,'').replace(/<br\s*\/?\s*>/gi,'\n').replace(/<\/(?:p|div)>/gi,'\n\n').replace(/<[^>]+>/g,' ').replace(/&nbsp;/gi,' ').replace(/&amp;/gi,'&').replace(/\s+\n/g,'\n').trim();
const localizeNewsItems = async (items:Array<NewsRecord & {id:string}>,targetLanguage:PublicLanguage,detail=false) => { const localized = new Map<string,NewsRecord & {id:string}>(); const needs = items.filter((item) => item.language !== targetLanguage); items.filter((item) => item.language === targetLanguage).forEach((item) => localized.set(item.id,item)); const translationIds = needs.map((item) => item.translationIds?.[targetLanguage]).filter((id):id is string => Boolean(id)); const cached = translationIds.length ? await db.get<NewsTranslationRecord>(NEWS_TRANSLATIONS_TABLE,translationIds) : []; const cachedById = new Map<string,NewsTranslationRecord>(); cached.forEach((record,index) => { if (record) cachedById.set(translationIds[index],record); }); const missing:Array<NewsRecord & {id:string}> = []; for (const item of needs) { const translationId = item.translationIds?.[targetLanguage]; const saved = translationId ? cachedById.get(translationId) : undefined; if (saved && saved.articleId === item.id && saved.targetLanguage === targetLanguage && saved.sourceUpdatedAt === item.updatedAt && (!detail || Boolean(saved.body))) localized.set(item.id,{...item,title:saved.title,summary:saved.summary,body:detail?saved.body:item.body,category:saved.category,language:targetLanguage}); else missing.push(item); } const chunkSize = detail ? 1 : 8; for (let offset=0; offset<missing.length; offset+=chunkSize) { const chunk=missing.slice(offset,offset+chunkSize); try { const result = await Promise.race([ai.extract({ content:JSON.stringify({targetLanguage,detail,items:chunk.map((item)=>({id:item.id,sourceLanguage:item.language,title:item.title,summary:item.summary,category:item.category,body:detail?translationPlainBody(item.body).slice(0,12000):''}))}), prompt:`Translate every supplied field faithfully into ${targetLanguage}. Translation only: do not summarize, expand, interpret, add facts, remove facts, or rewrite it into a different story. Preserve people, institutions, places, dates, numbers, quotation meaning, URLs, source names, licence names and attribution. Keep each id exactly unchanged. When body is empty, return an empty body. When body is supplied, translate the complete supplied body. Use natural professional newsroom language.`, schema:{type:'object',properties:{items:{type:'array',items:{type:'object',properties:{id:{type:'string'},title:{type:'string'},summary:{type:'string'},category:{type:'string'},body:{type:'string'}},required:['id','title','summary','category','body']}}},required:['items']}, maxRetries:1,maxTokens:detail?7000:3200,temperature:0,thinkingMode:'NONE' }),new Promise<never>((_,reject)=>setTimeout(()=>reject(new Error('news_translation_timeout')),22000))]); const translatedItems=((result.data as {items?:Array<Record<string,unknown>>}).items||[]); for (const item of chunk) { const translated=translatedItems.find((entry)=>textValue(entry.id)===item.id); if (!translated) continue; const title=normalizeGeneratedText(textValue(translated.title)); const summary=normalizeGeneratedText(textValue(translated.summary)); const category=normalizeGeneratedText(textValue(translated.category)); const body=detail?normalizeGeneratedText(textValue(translated.body)):''; if (!title || !summary || (detail && !body)) continue; const now=Date.now(); const record:NewsTranslationRecord={articleId:item.id,targetLanguage,sourceUpdatedAt:item.updatedAt,title,summary,body,category:category||item.category,createdAt:now,updatedAt:now}; let translationId=item.translationIds?.[targetLanguage]||''; if (translationId) { const [ok]=await db.update(NEWS_TRANSLATIONS_TABLE,[{id:translationId,record:record as unknown as Record<string,unknown>}]); if (!ok) translationId=''; } if (!translationId) { const [createdId]=await db.add(NEWS_TRANSLATIONS_TABLE,[record as unknown as Record<string,unknown>]); translationId=createdId||''; if (translationId) { const [stored]=await db.get<NewsRecord>('news',[item.id]); if (stored) await db.update('news',[{id:item.id,record:withoutId({...stored,translationIds:{...(stored.translationIds||{}),[targetLanguage]:translationId}})}]); } } localized.set(item.id,{...item,title,summary,body:detail?body:item.body,category:category||item.category,language:targetLanguage}); } } catch (err) { console.warn('news_translation_failed',{targetLanguage,detail,ids:chunk.map((item)=>item.id),reason:err instanceof Error?err.message:'unknown'}); } } return items.map((item)=>localized.get(item.id)||item); };

const prepareSeniorAnalysisIfDue=async()=>{ const now=Date.now(); const runs=await listAnalysisDeskRuns(); if(runs.filter((item)=>item.createdAt>=now-ANALYSIS_DESK_WINDOW_MS).length>=ANALYSIS_DESK_MAX_24H)return false; const {items:drafts}=await db.list<AnalysisDraftRecord>(ANALYSIS_DRAFTS_TABLE,{limit:100}); if(drafts.some((item)=>item.status==='pending'))return false; const basis=await analysisNewsContext(); if(basis.length<3)return false; const {items:states}=await db.list<AnalysisDeskStateRecord>(ANALYSIS_DESK_STATE_TABLE,{limit:20}); const state=[...states].sort((a,b)=>b.updatedAt-a.updatedAt)[0]; if(state&&now-state.lastAttemptAt<ANALYSIS_DESK_MIN_GAP_MS)return false; const stateRecord:AnalysisDeskStateRecord={lastAttemptAt:now,updatedAt:now}; let stateId=state?.id||''; if(state){const [ok]=await db.update(ANALYSIS_DESK_STATE_TABLE,[{id:state.id,record:stateRecord as unknown as Record<string,unknown>}]);if(!ok)return false;}else{const [createdId]=await db.add(ANALYSIS_DESK_STATE_TABLE,[stateRecord as unknown as Record<string,unknown>]);stateId=createdId||'';if(!stateId)return false;} const recentTitles=runs.filter((item)=>item.createdAt>=now-7*24*60*60*1000).slice(0,20).map((item)=>item.title); const context={recentAnalysisTitles:recentTitles,stories:basis.map((item)=>({id:item.id,title:item.title,summary:item.summary,body:translationPlainBody(item.body).slice(0,1100),category:item.category,language:item.language,source:item.originName||'SAYEH NEWS',publishedAt:item.publishedAt||item.updatedAt||item.createdAt,afghanistanRelevant:isAfghanistanRelevantRecord(item)}))}; try{const result=await ai.extract({system:'You are the SAYEH Senior Analysis Desk, a multidisciplinary editorial analysis function, not a named human persona. Never claim personal field experience, interviews, eyewitness access, academic degrees or credentials. Write with scholarly discipline, political neutrality and humane judgment.',content:JSON.stringify(context),prompt:'Produce one original, substantial analytical article in professional Dari using only the supplied newsroom stories for current-event facts. Select the most consequential issue, prioritizing Afghanistan, then the region, then major global developments, and avoid substantial overlap with recentAnalysisTitles. Explain the issue through several genuinely relevant lenses: politics and institutions, sociology and social structure, political psychology and collective behavior, political economy, diplomacy, history, law, security, media or public policy. Stable academic concepts may be used for interpretation, but never invent a current fact, quotation, statistic, source or event. Clearly distinguish reported facts from interpretation, uncertainty and scenarios. On contested political questions, represent major plausible explanations and interests fairly without partisan advocacy. When the supplied stories show concrete, verifiable positive governance or public-service outcomes by Afghanistan’s de facto authorities, acknowledge them explicitly and analyze their significance; when they show failures, harms, criticism or allegations, cover them with the same evidentiary discipline and clear attribution. Do not suppress either side. Avoid slogans, propaganda, generic filler, formulaic openings, repetitive transitions and list-like padding. Write like a mature professor-researcher: precise, nuanced, coherent and readable. Target about 1200–1600 Dari words, with a strong opening, 4–6 short meaningful subheadings inside the body, integrated argument, competing interpretations, implications and a restrained conclusion. Return a concise but substantial summary and the exact supplied story ids actually used as basisIds. Also return imagePrompt and imageQuery in English. imagePrompt must describe a symbolic editorial illustration matching the title, not a documentary photo: no text, logos, recognizable real people, fabricated scene of a real event or graphic violence, widescreen 16:9. imageQuery must be a short English query for an openly licensed fallback image.',schema:{type:'object',properties:{title:{type:'string'},summary:{type:'string'},body:{type:'string'},category:{type:'string'},basisIds:{type:'array',items:{type:'string'}},imagePrompt:{type:'string'},imageQuery:{type:'string'}},required:['title','summary','body','category','basisIds','imagePrompt','imageQuery']},maxRetries:1,maxTokens:6200,temperature:0.25,thinkingMode:'FAST'}); const data=result.data as Record<string,unknown>; const title=normalizeGeneratedText(textValue(data.title)); const summary=normalizeGeneratedText(textValue(data.summary)); const body=normalizeGeneratedText(textValue(data.body)); const category=normalizeGeneratedText(textValue(data.category)).slice(0,70)||'سیاست و جامعه'; const imagePrompt=normalizeGeneratedText(textValue(data.imagePrompt)); const imageQuery=normalizeGeneratedText(textValue(data.imageQuery))||title; const availableIds=new Set(basis.map((item)=>item.id)); const basisIds=(Array.isArray(data.basisIds)?data.basisIds:[]).map((value)=>textValue(value)).filter((id)=>availableIds.has(id)).slice(0,8); if(!title||summary.length<80||body.length<4200||!basisIds.length||runs.some((item)=>item.title.trim().toLowerCase()===title.trim().toLowerCase()&&item.createdAt>=now-7*24*60*60*1000)){if(stateId)await db.update(ANALYSIS_DESK_STATE_TABLE,[{id:stateId,record:{lastAttemptAt:Date.now()-(ANALYSIS_DESK_MIN_GAP_MS-60*60*1000),updatedAt:Date.now()}}]).catch(()=>undefined);return false;} const draft:AnalysisDraftRecord={title,summary,body,category,basisIds,imagePrompt,imageQuery,status:'pending',createdAt:Date.now(),updatedAt:Date.now()}; const [id]=await db.add(ANALYSIS_DRAFTS_TABLE,[draft as unknown as Record<string,unknown>]); return Boolean(id);}catch(err){if(stateId)await db.update(ANALYSIS_DESK_STATE_TABLE,[{id:stateId,record:{lastAttemptAt:Date.now()-(ANALYSIS_DESK_MIN_GAP_MS-60*60*1000),updatedAt:Date.now()}}]).catch(()=>undefined);console.warn('analysis_prepare_failed',{reason:err instanceof Error?err.message:'unknown'});return false;}}; const processPendingSeniorAnalysis=async()=>{const {items}=await db.list<AnalysisDraftRecord>(ANALYSIS_DRAFTS_TABLE,{limit:100});const draft=items.filter((item)=>item.status==='pending').sort((a,b)=>a.createdAt-b.createdAt)[0];if(!draft)return false;const now=Date.now();if(draft.lastImageAttemptAt&&now-draft.lastImageAttemptAt<60*60*1000)return true;const locked=withoutId({...draft,lastImageAttemptAt:now,updatedAt:now});const [lockedOk]=await db.update(ANALYSIS_DRAFTS_TABLE,[{id:draft.id,record:locked}]);if(!lockedOk)return true;let media:MediaInput|undefined;try{media=await findOpenLicensedImage(draft.imageQuery)||undefined;}catch(err){console.warn('analysis_open_image_failed',{reason:err instanceof Error?err.message:'unknown'});}if(!media){try{const generated=await ai.imageGen({prompt:`Editorial analysis illustration for SAYEH NEWS. Topic/title: ${draft.title}. Summary: ${draft.summary.slice(0,500)}. ${draft.imagePrompt}. Clearly symbolic and illustrative, not a documentary photograph. No written words, captions, logos, recognizable real people, fabricated depiction of a real event, or graphic violence. Professional newsroom visual, widescreen 16:9.`,maxOutputBytes:850000});if(generated.image?.data&&generated.image.mimeType)media={data:generated.image.data,mimeType:generated.image.mimeType,name:`sayeh-analysis-illustration-${now}.png`};}catch(err){console.warn('analysis_image_generation_failed',{reason:err instanceof Error?err.message:'unknown'});}}if(!media)return true;try{const article=await createNews({title:draft.title,summary:draft.summary,body:draft.body,category:`تحلیل ارشد · ${draft.category}`,language:'دری',section:'analysis',mediaType:'image',status:'published',isBreaking:false,media});await db.update(ANALYSIS_DRAFTS_TABLE,[{id:draft.id,record:withoutId({...draft,status:'published',articleId:article.id,lastImageAttemptAt:now,updatedAt:Date.now()})}]);const [runId]=await db.add(ANALYSIS_DESK_RUNS_TABLE,[{articleId:article.id,title:draft.title,basisIds:draft.basisIds,createdAt:Date.now()}]);if(!runId)console.warn('analysis_run_record_failed',{articleId:article.id});return true;}catch(err){console.warn('analysis_publish_failed',{reason:err instanceof Error?err.message:'unknown'});return true;}}; export const publishScheduledHandler = async () => {
  await publishScheduledItems();
  try { await videoDeskHandler(); } catch(err) { console.warn('video_desk_publish_scheduler_failed',{reason:err instanceof Error?err.message:'unknown'}); }
  try { const hadPending=await processPendingSeniorAnalysis(); if(!hadPending) await prepareSeniorAnalysisIfDue(); } catch(err) { console.warn('analysis_pipeline_failed',{reason:err instanceof Error?err.message:'unknown'}); }
  return { statusCode: 200 };
};

type VideoBulletinJobStatus='pending_render'|'pending_video'|'waiting_facebook'|'published'|'failed'; type VideoBulletinJob={slotKey:string;windowStart:number;windowEnd:number;title:string;summary:string;script:string;basisIds:string[];headlines:string[];status:VideoBulletinJobStatus;callbackToken:string;createdAt:number;updatedAt:number;renderedVideoUrl?:string;heygenVideoId?:string;facebookVideoId?:string;facebookVideoUrl?:string;articleId?:string;lastError?:string;audioStoragePath?:string;videoStoragePath?:string;renderAttempts?:number;lastRenderAttemptAt?:number;renderEngine?:string}; const VIDEO_BULLETINS_TABLE='video_bulletins_v1'; const VIDEO_DESK_WINDOW_MS=6*60*60*1000; const VIDEO_DESK_ORIGIN='https://api-v2.appdeploy.ai/app/605a5f030a6d2cec77'; const VIDEO_DESK_AVATAR_ID='b9b860b3766e48ba859dc283f4bd1059'; const VIDEO_DESK_VOICE_ID='44efc076bc8d4349931245c7748250c8'; const videoDeskWindow=(now=Date.now())=>{const windowEnd=Math.floor(now/VIDEO_DESK_WINDOW_MS)*VIDEO_DESK_WINDOW_MS;return{windowStart:windowEnd-VIDEO_DESK_WINDOW_MS,windowEnd,slotKey:new Date(windowEnd).toISOString()};}; const videoDeskEligibleRecord=(item:NewsRecord&{id:string})=>item.section==='news'&&item.category!=='بولتن ویدیویی'&&(item.status==='published'||(item.status==='review'&&Boolean(item.originUrl)&&Boolean(item.originName)));const videoDeskBasis=async(windowStart:number,windowEnd:number)=>{const index=await getNewsIndex();const ids=Array.from(new Set([...index.editorIds.slice(0,400),...index.publicIds.slice(0,240)]));const records=collapseImportedDuplicates((await getNewsByIds(ids)).filter((item)=>videoDeskEligibleRecord(item)&&newsPublishedTimestamp(item)>=windowStart&&newsPublishedTimestamp(item)<windowEnd));return records.sort((a,b)=>newsPublishedTimestamp(a)-newsPublishedTimestamp(b)).slice(0,120);}; const prepareVideoBulletinIfDue=async()=>{const slot=videoDeskWindow();const{items:existing}=await db.list<VideoBulletinJob>(VIDEO_BULLETINS_TABLE,{limit:100});if(existing.some((item)=>item.slotKey===slot.slotKey))return false;const basis=await videoDeskBasis(slot.windowStart,slot.windowEnd);if(!basis.length)return false;const context={windowStart:new Date(slot.windowStart).toISOString(),windowEnd:new Date(slot.windowEnd).toISOString(),stories:basis.map((item)=>({id:item.id,title:item.title,summary:item.summary,body:translationPlainBody(item.body).slice(0,650),category:item.category,language:item.language,source:item.originName||'SAYEH NEWS',publishedAt:newsPublishedTimestamp(item)}))};const result=await ai.extract({system:'You are the SAYEH NEWS Video Desk bulletin editor. Write broadcast copy only from supplied newsroom facts. Never invent facts, quotations, numbers, context, motives or conclusions. Use natural standard Iranian Persian suitable for a professional female television news presenter, but do not imitate or name any real broadcaster.',content:JSON.stringify(context),prompt:'Create one approximately four-minute Persian news bulletin covering every unique supplied story from this six-hour window. Merge only stories that clearly describe the same event; do not omit a distinct event. Use fluent standard Iranian Persian rather than Afghan Dari vocabulary, with short broadcast-friendly sentences, natural transitions and clear attribution for allegations or contested claims. Prioritize Afghanistan in ordering, then the region, then major international news, while still covering all unique supplied stories. Begin with a brief greeting identifying SAYEH NEWS and end with a brief sign-off. Target about 480–560 Persian words. Return a concise title, a 2–3 sentence summary, the complete spoken script and an array of short headline strings.',schema:{type:'object',properties:{title:{type:'string'},summary:{type:'string'},script:{type:'string'},headlines:{type:'array',items:{type:'string'}}},required:['title','summary','script','headlines']},maxRetries:2,maxTokens:2400,temperature:0.15,thinkingMode:'FAST'});const data=result.data as Record<string,unknown>;const title=normalizeGeneratedText(textValue(data.title))||'بولتن شش‌ساعته سایه';const summary=normalizeGeneratedText(textValue(data.summary));const script=normalizeGeneratedText(textValue(data.script));const wordCount=script.split(/\s+/).filter(Boolean).length;const headlines=(Array.isArray(data.headlines)?data.headlines:[]).map((value)=>normalizeGeneratedText(textValue(value))).filter(Boolean).slice(0,30);if(summary.length<50||wordCount<350||wordCount>700)return false;const record:VideoBulletinJob={slotKey:slot.slotKey,windowStart:slot.windowStart,windowEnd:slot.windowEnd,title,summary,script,basisIds:basis.map((item)=>item.id),headlines:headlines.length?headlines:basis.slice(0,20).map((item)=>item.title),status:'pending_render',callbackToken:randomUUID(),createdAt:Date.now(),updatedAt:Date.now()};const[id]=await db.add(VIDEO_BULLETINS_TABLE,[record as unknown as Record<string,unknown>]);return Boolean(id);}; const pendingVideoBulletin=async()=>{const{items}=await db.list<VideoBulletinJob>(VIDEO_BULLETINS_TABLE,{limit:100});const recent=items.filter((item)=>Date.now()-item.createdAt<18*60*60*1000);const job=recent.filter((item)=>item.status==='pending_render').sort((a,b)=>a.createdAt-b.createdAt)[0]||recent.filter((item)=>item.status==='failed'&&/resolution|higher plan|subscribe/i.test(item.lastError||'')).sort((a,b)=>b.updatedAt-a.updatedAt)[0];if(!job)return null;return{jobId:job.id,title:job.title,summary:job.summary,script:job.script,windowStart:job.windowStart,windowEnd:job.windowEnd,avatarId:VIDEO_DESK_AVATAR_ID,voiceId:VIDEO_DESK_VOICE_ID,video:{aspectRatio:'16:9',resolution:'720p',outputFormat:'mp4',language:'Persian',presenterStyle:'professional neutral Iranian broadcast Persian'},callbackUrl:`${VIDEO_DESK_ORIGIN}/video-desk/callback?job=${encodeURIComponent(job.id)}&token=${encodeURIComponent(job.callbackToken)}`};}; const deepString=(value:unknown,names:Set<string>):string=>{if(!value||typeof value!=='object')return'';const obj=value as Record<string,unknown>;for(const[key,entry]of Object.entries(obj)){const normalized=key.toLowerCase().replace(/[^a-z0-9]/g,'');if(names.has(normalized)&&typeof entry==='string'&&entry.trim())return entry.trim();}for(const entry of Object.values(obj)){const found=deepString(entry,names);if(found)return found;}return'';}; const allowedRenderedVideoUrl=(value:string)=>{try{const url=new URL(value);const host=url.hostname.toLowerCase();return url.protocol==='https:'&&(host==='heygen.ai'||host.endsWith('.heygen.ai')||host.endsWith('.amazonaws.com')||host.endsWith('.cloudfront.net'));}catch{return false;}}; const publishVideoToFacebook=async(job:VideoBulletinJob&{id:string},videoUrl:string)=>{const names=await secrets.listSecretNames();if(!names.includes('VIDEO_FACEBOOK_PAGE_ACCESS_TOKEN')||!names.includes('VIDEO_FACEBOOK_PAGE_ID'))return{waiting:true as const};const token=await secrets.readSecret('VIDEO_FACEBOOK_PAGE_ACCESS_TOKEN');const pageId=await secrets.readSecret('VIDEO_FACEBOOK_PAGE_ID');const response=await fetch(`https://graph.facebook.com/${encodeURIComponent(pageId)}/videos`,{method:'POST',headers:{'Content-Type':'application/x-www-form-urlencoded'},body:new URLSearchParams({access_token:token,file_url:videoUrl,title:job.title,description:`${job.title}\n\n${job.summary}`,published:'true'})});const responseText=await response.text();if(!response.ok)throw new Error(`Facebook video ${response.status}: ${responseText.slice(0,180)}`);let payload:Record<string,unknown>={};try{payload=JSON.parse(responseText) as Record<string,unknown>;}catch{}const videoId=textValue(payload.id);if(!videoId)throw new Error('Facebook video id missing');return{waiting:false as const,videoId,facebookVideoUrl:`https://www.facebook.com/${encodeURIComponent(pageId)}/videos/${encodeURIComponent(videoId)}`};}; const createVideoBulletinArticle=async(job:VideoBulletinJob&{id:string},facebookVideoUrl:string)=>{if(job.articleId){const[current]=await db.get<NewsRecord>('news',[job.articleId]);if(current){const updated={...current,facebookVideoUrl,updatedAt:Date.now()};const[ok]=await db.update('news',[{id:job.articleId,record:withoutId(updated)}]);if(ok){await updateNewsIndexForRecord(job.articleId,updated);clearNewsReadCaches();}}return job.articleId;}const now=Date.now();const body=`در این بولتن ویدیویی، میز خبر سایه مهم‌ترین رویدادهای شش ساعت گذشته را به‌صورت فشرده مرور می‌کند.\n\n${job.headlines.map((headline,index)=>`${index+1}. ${headline}`).join('\n')}\n\nویدیوی کامل این بولتن در صفحهٔ فیسبوک خبرگزاری سایه منتشر شده است.`;const record:NewsRecord={title:job.title,summary:job.summary,body,category:'بولتن ویدیویی',language:'دری',section:'news',mediaType:'video',status:'published',isBreaking:false,createdAt:now,updatedAt:now,publishedAt:now,facebookVideoUrl,videoBulletinId:job.id,viewCount:0,archived:false};const[articleId]=await db.add('news',[record as unknown as Record<string,unknown>]);if(!articleId)throw new Error('video bulletin article create failed');await updateNewsIndexForRecord(articleId,record);clearNewsReadCaches();const[hydrated]=await hydrateNews([{id:articleId,...record}]);await notifySubscribers('news','feed',{action:'upsert',item:hydrated||{id:articleId,...record}}).catch(()=>undefined);return articleId;}; const finalizeRenderedBulletin=async(job:VideoBulletinJob&{id:string},videoUrl:string,heygenVideoId='')=>{await db.update(VIDEO_BULLETINS_TABLE,[{id:job.id,record:withoutId({...job,status:'waiting_facebook',renderedVideoUrl:videoUrl,heygenVideoId:heygenVideoId||job.heygenVideoId,lastError:'Ready for Zapier Facebook video publishing.',updatedAt:Date.now()})}]);}; const FREE_VIDEO_CADENCE_MS=6*60*60*1000;const FREE_VIDEO_ANCHOR=Date.parse('2026-08-16T07:40:00Z');const FREE_VIDEO_REVISION='v9';const freeVideoSlot=(now=Date.now())=>{const index=Math.floor((now-FREE_VIDEO_ANCHOR)/FREE_VIDEO_CADENCE_MS);const windowEnd=FREE_VIDEO_ANCHOR+index*FREE_VIDEO_CADENCE_MS;return{windowEnd,windowStart:windowEnd-FREE_VIDEO_CADENCE_MS,slotKey:`free-${FREE_VIDEO_REVISION}-${new Date(windowEnd).toISOString()}`};};const freeWords=(value:string)=>value.split(/\s+/).filter(Boolean);const freeExcerpt=(value:string,maxWords:number)=>{const clean=normalizeGeneratedText(translationPlainBody(value)).replace(/\s+/g,' ').trim();const words=freeWords(clean);return words.length>maxWords?`${words.slice(0,maxWords).join(' ')}…`:clean;};const freeSlotLabel=(timestamp:number)=>new Intl.DateTimeFormat('fa-IR',{timeZone:'Asia/Kabul',year:'numeric',month:'long',day:'numeric',hour:'2-digit',minute:'2-digit',hour12:false}).format(new Date(timestamp));const prepareFreeVideoBulletinIfDue=async()=>{const slot=freeVideoSlot();const lag=Date.now()-slot.windowEnd;if(lag<0||lag>=FREE_VIDEO_CADENCE_MS)return false;const{items:existing}=await db.list<VideoBulletinJob>(VIDEO_BULLETINS_TABLE,{limit:100});if(existing.some((item)=>item.slotKey===slot.slotKey))return false;let basis=await videoDeskBasis(slot.windowStart,slot.windowEnd);if(!basis.length){try{const settings=await getSettings();const primary=await ensurePrimaryArianaSource(settings);if(primary)await runBoundedSourceIngest(primary,'video-desk-bootstrap');basis=await videoDeskBasis(slot.windowStart,slot.windowEnd);}catch(err){console.warn('free_video_source_bootstrap_failed',{reason:err instanceof Error?err.message:'unknown'});}}const exactWindow=basis.length>0;if(!basis.length){const all=collapseImportedDuplicates((await listAllNews()).map(sanitizeLegacyImportedRecord).filter(videoDeskEligibleRecord)).sort((a,b)=>newsPublishedTimestamp(b)-newsPublishedTimestamp(a));basis=all.filter((item)=>newsPublishedTimestamp(item)>=slot.windowStart-18*60*60*1000).slice(0,60);if(!basis.length)basis=all.slice(0,24);if(!basis.length){console.warn('free_video_basis_empty',{slotKey:slot.slotKey,newsRows:all.length});return false;}}const selected=[...basis].sort((a,b)=>Number(isAfghanistanRelevantRecord(b))-Number(isAfghanistanRelevantRecord(a))||newsPublishedTimestamp(b)-newsPublishedTimestamp(a)).slice(0,18);const perStory=Math.max(22,Math.min(58,Math.floor(500/Math.max(1,selected.length))));const transitions=['نخست،','در ادامه،','همچنین،','از دیگر خبرها،','در همین حال،','و در خبری دیگر،'];const spoken=selected.map((item,index)=>{const combined=`${item.title}. ${item.summary||translationPlainBody(item.body)}`;return `${transitions[Math.min(index,transitions.length-1)]} ${freeExcerpt(combined,perStory)}`;});let script=`سلام. با بُولْتُن خبری سایه همراه هستید. ${exactWindow?'مهم‌ترین خبرهای شش ساعت گذشته را مرور می‌کنیم.':'تازه‌ترین خبرهای تأییدشده موجود در میز خبر سایه را مرور می‌کنیم.'}\n\n${spoken.join('\n\n')}`;if(freeWords(script).length<300){for(const item of selected.slice(0,4)){const detail=freeExcerpt(translationPlainBody(item.body),75);if(detail&&detail.length>80)script+=`\n\nجزئیات بیشتر: ${detail}`;if(freeWords(script).length>=380)break;}}script+=`\n\nاین بود بُولْتُن خبری سایه. برای تازه‌ترین خبرها با سایه همراه باشید.`;const title=`بولتن خبری سایه | ${freeSlotLabel(slot.windowEnd)}`;const summary=exactWindow?`مرور فشرده ${selected.length} رویداد ثبت‌شده در خبرگزاری سایه در بازه شش‌ساعته منتهی به ${freeSlotLabel(slot.windowEnd)}.`:`مرور فشرده ${selected.length} مورد از تازه‌ترین خبرهای تأییدشده موجود در میز خبر سایه، تهیه‌شده برای نوبت ${freeSlotLabel(slot.windowEnd)}.`;const record:VideoBulletinJob={slotKey:slot.slotKey,windowStart:slot.windowStart,windowEnd:slot.windowEnd,title,summary,script,basisIds:selected.map((item)=>item.id),headlines:selected.map((item)=>item.title),status:'pending_render',callbackToken:randomUUID(),createdAt:Date.now(),updatedAt:Date.now(),renderEngine:'free-edge-ffmpeg',renderAttempts:0};const[id]=await db.add(VIDEO_BULLETINS_TABLE,[record as unknown as Record<string,unknown>]);if(id)console.warn('free_video_job_created',{jobId:id,slotKey:slot.slotKey,stories:selected.length,exactWindow});return Boolean(id);};const processPendingFreeVideoBulletin=async()=>{const{items}=await db.list<VideoBulletinJob>(VIDEO_BULLETINS_TABLE,{limit:100});const job=items.filter((item)=>(item.status==='pending_render'||item.status==='pending_video')&&item.renderEngine==='free-edge-ffmpeg'&&item.slotKey.startsWith(`free-${FREE_VIDEO_REVISION}-`)&&((item.windowEnd-FREE_VIDEO_ANCHOR)%FREE_VIDEO_CADENCE_MS===0)).sort((a,b)=>a.createdAt-b.createdAt)[0];if(!job)return false;if(job.lastRenderAttemptAt&&Date.now()-job.lastRenderAttemptAt<4*60*1000&&job.status!=='pending_video')return true;const attempt=(job.renderAttempts||0)+1;const locked={...job,lastRenderAttemptAt:Date.now(),renderAttempts:attempt,updatedAt:Date.now()};await db.update(VIDEO_BULLETINS_TABLE,[{id:job.id,record:withoutId(locked)}]);try{if(job.status==='pending_render'){const audioStoragePath=await synthesizeFreePersianAudio(job.id,job.script);await db.update(VIDEO_BULLETINS_TABLE,[{id:job.id,record:withoutId({...locked,status:'pending_video',audioStoragePath,lastError:'Persian speech ready; video assembly queued.',updatedAt:Date.now()})}]);console.warn('free_video_audio_ready',{jobId:job.id,audioStoragePath});return true;}if(!job.audioStoragePath){await db.update(VIDEO_BULLETINS_TABLE,[{id:job.id,record:withoutId({...locked,status:'pending_render',lastError:'Audio stage missing; retrying.',updatedAt:Date.now()})}]);return true;}const videoStoragePath=await renderFreeBulletinVideo(job.id,job.audioStoragePath);await db.update(VIDEO_BULLETINS_TABLE,[{id:job.id,record:withoutId({...locked,status:'waiting_facebook',videoStoragePath,renderedVideoUrl:'',renderEngine:'free-edge-ffmpeg',lastError:'Ready for Zapier Facebook video publishing.',updatedAt:Date.now()})}]);console.warn('free_video_mp4_ready',{jobId:job.id,videoStoragePath});return true;}catch(err){await db.update(VIDEO_BULLETINS_TABLE,[{id:job.id,record:withoutId({...locked,status:job.status,lastError:`Free renderer: ${err instanceof Error?err.message:'unknown error'}`,updatedAt:Date.now()})}]).catch(()=>undefined);console.warn('free_video_render_failed',{jobId:job.id,stage:job.status,attempt,reason:err instanceof Error?err.message:'unknown'});return true;}};const retryWaitingVideoBulletins=async()=>false; export const videoDeskHandler=async()=>{try{await retryWaitingVideoBulletins();await prepareFreeVideoBulletinIfDue();await processPendingFreeVideoBulletin();await processPendingFreeVideoBulletin();}catch(err){console.warn('video_desk_cycle_failed',{reason:err instanceof Error?err.message:'unknown'});}try{const{items}=await db.list<VideoBulletinJob>(VIDEO_BULLETINS_TABLE,{limit:100});const slot=freeVideoSlot();const current=items.find((item)=>item.slotKey===slot.slotKey);console.error('video_desk_prod_probe',JSON.stringify(current?{slotKey:current.slotKey,status:current.status,basisCount:current.basisIds?.length||0,hasAudio:Boolean(current.audioStoragePath),hasVideo:Boolean(current.videoStoragePath||current.renderedVideoUrl),lastError:current.lastError||'',renderAttempts:current.renderAttempts||0}:{slotKey:slot.slotKey,status:'missing',basisCount:0,hasAudio:false,hasVideo:false,lastError:'',renderAttempts:0}));}catch(err){console.error('video_desk_prod_probe_failed',err instanceof Error?err.message:'unknown');}return{statusCode:200};}; const handleVideoDeskCallback=async(query:Record<string,string>,body:unknown)=>{let jobId=textValue(query.job);const token=textValue(query.token);if(!token)return error('callback credentials required',403);let stored:VideoBulletinJob|null=null;if(jobId){const[direct]=await db.get<VideoBulletinJob>(VIDEO_BULLETINS_TABLE,[jobId]);stored=direct;}else{const{items}=await db.list<VideoBulletinJob>(VIDEO_BULLETINS_TABLE,{limit:100});const match=items.find((item)=>item.callbackToken===token);if(match){jobId=match.id;stored=match;}}if(!jobId||!stored||stored.callbackToken!==token)return error('callback rejected',403);const job={...stored,id:jobId};if(job.status==='published')return json({ok:true,alreadyPublished:true});const status=deepString(body,new Set(['status','videostatus'])).toLowerCase();if(status.includes('fail')||status.includes('error')){await db.update(VIDEO_BULLETINS_TABLE,[{id:jobId,record:withoutId({...job,status:'failed',lastError:deepString(body,new Set(['error','errormessage','message']))||'Video render failed',updatedAt:Date.now()})}]);return json({ok:true,failed:true});}const videoUrl=deepString(body,new Set(['videourl','outputvideourl']));const videoId=deepString(body,new Set(['videoid']));if(!videoUrl)return json({ok:true,waiting:true});if(!allowedRenderedVideoUrl(videoUrl))return error('rendered video url rejected',400);try{await finalizeRenderedBulletin(job,videoUrl,videoId);return json({ok:true});}catch(err){await db.update(VIDEO_BULLETINS_TABLE,[{id:jobId,record:withoutId({...job,status:'waiting_facebook',renderedVideoUrl:videoUrl,heygenVideoId:videoId,lastError:err instanceof Error?err.message:'Facebook publish failed',updatedAt:Date.now()})}]).catch(()=>undefined);return json({ok:true,facebookPending:true});}};

const URGENT_VIDEO_SLOT='urgent-2026-08-15-2200-v1';const URGENT_VIDEO_TOKEN='0be94223-74f1-4c8e-a05c-c87e48b9210d';const URGENT_VIDEO_SCRIPT='سلام، وقت شما بخیر. با بولتن خبری سایه همراه هستید؛ مروری کوتاه بر مهم‌ترین خبرهای ساعات گذشته.\n\nدر افغانستان، پنجمین سالگرد بازگشت امارت اسلامی به قدرت با برنامه‌ها و مراسم رسمی در کابل و شماری از ولایت‌ها برگزار شد. مقام‌های امارت اسلامی این روز را نشانه پایان حضور نظامی خارجی و تثبیت حاکمیت کنونی دانستند. سراج‌الدین حقانی، وزیر داخله، در یک پیام ویدیویی از «روحیه، شجاعت و یاری الهی» سخن گفت و در عین حال پذیرفت که هنوز مشکلات و چالش‌هایی وجود دارد. هم‌زمان، خبرگزاری‌های بین‌المللی گزارش داده‌اند که دولت کنونی در پنج سال گذشته توانسته کنترل امنیتی و اداری خود را در سراسر کشور حفظ کند، اما همچنان با فشارهای جدی اقتصادی، بشردوستانه و سیاسی روبه‌رو است.\n\nدر بخش دیپلماسی، تعامل خارجی با کابل در حال افزایش است. روسیه همچنان تنها کشوری است که امارت اسلامی را به‌طور رسمی به رسمیت شناخته، اما چندین کشور منطقه و فرامنطقه روابط کاری و تماس‌های سیاسی خود را با مقام‌های افغان گسترش داده‌اند. مقام‌های وزارت خارجه افغانستان گفته‌اند برنامه‌های مرتبط با سالگرد در شماری از پایتخت‌های منطقه نیز برگزار می‌شود. در عین حال، اختلاف میان کابل و اسلام‌آباد بر سر مسائل امنیتی همچنان ادامه دارد و پاکستان بار دیگر نسبت به فعالیت گروه‌های مسلح در خاک افغانستان ابراز نگرانی کرده است؛ موضوعی که مقام‌های افغان پیش‌تر رد کرده‌اند.\n\nدر کنار این تحولات، نهادهای امدادی و سازمان ملل درباره وضعیت بشردوستانه افغانستان هشدار داده‌اند. کمیته بین‌المللی نجات می‌گوید شمار افرادی که به کمک نیاز دارند نسبت به سال‌های نخست پس از تغییر حکومت افزایش یافته و خشکسالی، زمین‌لرزه‌ها، کاهش کمک‌های خارجی و بازگشت مهاجران از ایران و پاکستان فشار بر خانواده‌ها را بیشتر کرده است. سازمان ملل همچنین بر محدودیت‌های گسترده علیه زنان و دختران تأکید کرده و خواستار ادامه حمایت مالی از برنامه‌های مربوط به زنان شده است. مقام‌های امارت اسلامی می‌گویند حقوق زنان در چارچوب شریعت اسلامی رعایت می‌شود.\n\nدر خاورمیانه، قطر ادعای ایران درباره بازداشت خلبانان ایرانی را رد کرده است. وزارت خارجه قطر اعلام کرده در پی یک حادثه هوایی، بقایای پیکر یک خلبان پیدا شده و دوحه برای هماهنگی بازگرداندن آن با تهران تماس گرفته است. این موضوع در شرایطی مطرح می‌شود که تنش‌های منطقه‌ای همچنان بالا است و گزارش‌هایی از حملات و درگیری‌های تازه در لبنان و یمن نیز منتشر شده است.\n\nو در جنگ اوکراین، وزارت دفاع روسیه اعلام کرده نیروهای این کشور در عملیات تابستانی خود کنترل چندین منطقه مسکونی در جنوب‌شرق اوکراین را به دست گرفته‌اند. رویترز می‌گوید این ادعاها به‌طور مستقل تأیید نشده است. در مقابل، ولودیمیر زلنسکی، رئیس‌جمهور اوکراین، پیش‌تر گفته بود نیروهای اوکراینی در سال جاری بخشی از مناطق تحت کنترل روسیه را پس گرفته‌اند. هم‌زمان، نگرانی‌ها درباره امنیت کشتیرانی در دریای سیاه و تأثیر آن بر صادرات غلات و قیمت مواد غذایی ادامه دارد. اوکراین پیشنهاد توقف متقابل حملات به اهداف غیرنظامی دریایی را مطرح کرده، اما مسکو گفته پیشنهاد رسمی قابل بررسی دریافت نکرده است.\n\nاین بود مهم‌ترین خبرهای این ساعت از سایه. برای خبرها و گزارش‌های بعدی با ما همراه باشید.';const ensureUrgentVideoBulletin=async()=>{const{items}=await db.list<VideoBulletinJob>(VIDEO_BULLETINS_TABLE,{limit:100});const existing=items.find((item)=>item.slotKey===URGENT_VIDEO_SLOT);const urgentRenderedVideoUrl='https://files2.heygen.ai/aws_pacific/avatar_tmp/da62d916a8f94906b5877d4bc708ee3a/4a6c6e29fe61480d83e7072e81e014bf.mp4?Expires=1787465118&Signature=MUHJ4Hyvb8dscInA3BBMQ-6EK5YoEa6t6p9DvLMhdFetg2eZ4lOfnVCsyfy1z35oamzV8tKF4NmKYqwt2lN0t9lt5V85UVC~BsVLrU7VeWkJkpTSNFbrijgCKXjAbvJcdbAeoXMDXOKZ3HxhQw8~LeiY51-H14QUA8Hee8xBKYf0cdky3eJEFXNGUlBTmjhBk92dYC4HQMxf7CHlnfB-~57JzIIinpta7U7D6~YAdRIvGP2yumD-xvE1x98v5hv9gXF6yMUToslePpm03naq7JCmb06mHu-95BN3Y9czarwXvLRa7ukumudJmPBDye4NYAP8N32ycIA1lGicDPlBGw__&Key-Pair-Id=K38HBHX5LX3X2H';if(existing){if(existing.renderedVideoUrl!==urgentRenderedVideoUrl||existing.status!=='waiting_facebook'){await db.update(VIDEO_BULLETINS_TABLE,[{id:existing.id,record:withoutId({...existing,status:'waiting_facebook',renderedVideoUrl:urgentRenderedVideoUrl,heygenVideoId:'4a6c6e29fe61480d83e7072e81e014bf',lastError:'Ready for Zapier Facebook video publishing.',updatedAt:Date.now()})}]);}return;}const now=Date.now();const record:VideoBulletinJob={slotKey:URGENT_VIDEO_SLOT,windowStart:Date.parse('2026-08-15T12:00:00Z'),windowEnd:Date.parse('2026-08-15T18:00:00Z'),title:'بولتن خبری شبانگاهی سایه | ۱۵ آگست ۲۰۲۶',summary:'مرور فشرده مهم‌ترین تحولات افغانستان، منطقه و جهان در ساعات منتهی به شب ۱۵ آگست ۲۰۲۶.',script:URGENT_VIDEO_SCRIPT,basisIds:[],headlines:['پنجمین سالگرد بازگشت امارت اسلامی به قدرت','افزایش تعامل خارجی با کابل و تداوم اختلاف با پاکستان','هشدار نهادهای امدادی درباره وضعیت بشردوستانه افغانستان','رد ادعای بازداشت خلبانان ایرانی از سوی قطر','تحولات تازه جنگ اوکراین و دریای سیاه'],status:'waiting_facebook',callbackToken:URGENT_VIDEO_TOKEN,createdAt:now,updatedAt:now,renderedVideoUrl:urgentRenderedVideoUrl,heygenVideoId:'4a6c6e29fe61480d83e7072e81e014bf',lastError:'Ready for Zapier Facebook video publishing.'};await db.add(VIDEO_BULLETINS_TABLE,[record as unknown as Record<string,unknown>]);};

const videoDeskDiagnostics=async()=>{const{items}=await db.list<VideoBulletinJob>(VIDEO_BULLETINS_TABLE,{limit:100});const latest=[...items].sort((a,b)=>(b.updatedAt||b.createdAt)-(a.updatedAt||a.createdAt))[0];const slot=freeVideoSlot();const current=items.find((item)=>item.slotKey===slot.slotKey);const safe=(item:(VideoBulletinJob&{id:string})|undefined)=>item?{id:item.id,slotKey:item.slotKey,status:item.status,renderEngine:item.renderEngine||'',basisCount:item.basisIds?.length||0,hasAudio:Boolean(item.audioStoragePath),hasVideo:Boolean(item.videoStoragePath||item.renderedVideoUrl),lastError:item.lastError||'',renderAttempts:item.renderAttempts||0,updatedAt:item.updatedAt}:null;return{revision:FREE_VIDEO_REVISION,currentSlot:slot.slotKey,totalJobs:items.length,current:safe(current),latest:safe(latest)};};const buildVideoZapierRss=async()=>{const{items}=await db.list<VideoBulletinJob>(VIDEO_BULLETINS_TABLE,{limit:100});const ready=items.filter((item)=>Boolean(item.renderedVideoUrl||item.videoStoragePath)&&(item.status==='waiting_facebook'||item.status==='published')).sort((a,b)=>b.updatedAt-a.updatedAt).slice(0,20);const rssItems=ready.map((item)=>{const videoUrl=item.videoStoragePath?`${VIDEO_DESK_ORIGIN}/video-desk/file/${encodeURIComponent(item.id)}`:(item.renderedVideoUrl||'');const pubDate=new Date(item.updatedAt||item.createdAt).toUTCString();return `<item><title>${xmlEscape(item.title)}</title><link>${xmlEscape(SAYEH_PUBLIC_SITE)}</link><guid isPermaLink="false">sayeh-video-${xmlEscape(item.id)}</guid><pubDate>${pubDate}</pubDate><description>${xmlEscape(item.summary)}</description><enclosure url="${xmlEscape(videoUrl)}" length="0" type="video/mp4"/><media:content url="${xmlEscape(videoUrl)}" medium="video" type="video/mp4"/><sayehVideoUrl>${xmlEscape(videoUrl)}</sayehVideoUrl><sayehJobId>${xmlEscape(item.id)}</sayehJobId><sayehCallbackToken>${xmlEscape(item.callbackToken)}</sayehCallbackToken><sayehConfirmUrl>${xmlEscape(`${VIDEO_DESK_ORIGIN}/video-desk/facebook-confirm`)}</sayehConfirmUrl></item>`;}).join('');return `<?xml version="1.0" encoding="UTF-8"?><rss version="2.0" xmlns:media="http://search.yahoo.com/mrss/"><channel><title>SAYEH NEWS | Video Desk for Zapier</title><link>${xmlEscape(SAYEH_PUBLIC_SITE)}</link><description>Rendered six-hour SAYEH video bulletins ready for Facebook video publishing through Zapier.</description><language>fa-IR</language><lastBuildDate>${new Date().toUTCString()}</lastBuildDate>${rssItems}</channel></rss>`;}; const handleVideoFileRedirect=async(id:string)=>{const[job]=await db.get<VideoBulletinJob>(VIDEO_BULLETINS_TABLE,[id]);if(!job)return error('Video bulletin not found.',404);if(job.videoStoragePath){const[signed]=await storage.url([job.videoStoragePath]);if(signed?.url)return{statusCode:302,headers:{Location:signed.url,'Cache-Control':'no-store, max-age=0','X-Content-Type-Options':'nosniff'},body:''};}if(job.renderedVideoUrl)return{statusCode:302,headers:{Location:job.renderedVideoUrl,'Cache-Control':'no-store, max-age=0','X-Content-Type-Options':'nosniff'},body:''};return error('Video bulletin file is not ready.',404);}; const handleVideoFacebookConfirm=async(body:unknown)=>{const input=(body||{}) as Record<string,unknown>;const jobId=textValue(input.jobId);const token=textValue(input.callbackToken);const facebookUrl=safeFacebookVideoUrl(input.facebookUrl);if(!jobId||!token||!facebookUrl)return error('jobId, callbackToken and a valid Facebook URL are required.',400);const[stored]=await db.get<VideoBulletinJob>(VIDEO_BULLETINS_TABLE,[jobId]);if(!stored||stored.callbackToken!==token)return error('Video Desk confirmation rejected.',403);const job={...stored,id:jobId};const articleId=await createVideoBulletinArticle(job,facebookUrl);await db.update(VIDEO_BULLETINS_TABLE,[{id:jobId,record:withoutId({...job,status:'published',facebookVideoUrl:facebookUrl,articleId,lastError:'',updatedAt:Date.now()})}]);return json({ok:true,articleId});}; const resolveCustomVisitor=async(body:unknown)=>{const input=(body||{}) as Record<string,unknown>;const visitor=await verifyVisitorSession(textValue(input.sessionToken));return{input,visitor};};
const customVisitorUser=(visitor:VisitorIdentity):AuthUser=>({userId:visitor.userId,email:visitor.email,name:visitor.name,scope:'email'});

export const handler = router({
  'GET /rss.xml':[async()=>({statusCode:200,headers:{'Content-Type':'application/rss+xml; charset=utf-8','Cache-Control':'public, max-age=120, stale-while-revalidate=300','X-Content-Type-Options':'nosniff'},body:await buildPublicRss()})],
  'GET /facebook-rss.xml':[async()=>({statusCode:200,headers:{'Content-Type':'application/rss+xml; charset=utf-8','Cache-Control':'no-store, max-age=0','Pragma':'no-cache','X-Content-Type-Options':'nosniff'},body:await buildPublicRss()})], 'GET /facebook-video-rss.xml':[async()=>({statusCode:200,headers:{'Content-Type':'application/rss+xml; charset=utf-8','Cache-Control':'no-store, max-age=0','Pragma':'no-cache','X-Content-Type-Options':'nosniff'},body:await buildVideoZapierRss()})], 'GET /facebook-video-rss-v2.xml':[async()=>({statusCode:200,headers:{'Content-Type':'application/rss+xml; charset=utf-8','Cache-Control':'no-store, max-age=0','Pragma':'no-cache','X-Content-Type-Options':'nosniff','X-Sayeh-Feed':'video-v2'},body:await buildVideoZapierRss()})], 'GET /video-desk/diagnostics':[async({query})=>{if(query.run==='1')await videoDeskHandler();return json(await videoDeskDiagnostics());}], 'GET /video-desk/file/:id':[async({params})=>handleVideoFileRedirect(params.id)],
  'GET /rss-image/:id':[async({params})=>{const target=await resolveSocialImage(params.id);return target?{statusCode:302,headers:{Location:target,'Cache-Control':'no-store, max-age=0','X-Content-Type-Options':'nosniff'},body:''}:error('تصویر یافت نشد.',404);}],
  'GET /share/:id':[async({params})=>{const page=await buildSocialSharePage(params.id);return page?{statusCode:200,headers:{'Content-Type':'text/html; charset=utf-8','Cache-Control':'public, max-age=120, stale-while-revalidate=300','X-Content-Type-Options':'nosniff'},body:page}:error('مطلب یافت نشد.',404);}],  'GET /diagnostics/fakori-beheshti-lookup':[async()=>{const rows=await listAllNews();const matchText=(value:string)=>{const text=normalizeSearchText(value);return text.includes('فکوری')||text.includes('فکوري')||text.includes('بزرگان اهل تشیع')||text.includes('عبدالحکیم شرعی')||(text.includes('بهشتی')&&text.includes('تبعیض'));};const matches=rows.filter((item)=>matchText(`${item.title} ${item.summary} ${item.body} ${item.originTitle||''} ${item.originExcerpt||''}`)).slice(0,20).map((item)=>({id:item.id,title:item.title,status:item.status,section:item.section,archived:Boolean(item.archived),publishedAt:item.publishedAt||null,createdAt:item.createdAt||null,hasMedia:Boolean(item.mediaPath),renderableVisual:hasRenderableVisual(item),originTitle:item.originTitle||''}));const {items:tombstones}=await db.list<{originTitle?:string;originExcerpt?:string;deletedAt?:number;originUrl?:string}>('news_delete_tombstones',{limit:500});const deleted=tombstones.filter((item)=>matchText(`${item.originTitle||''} ${item.originExcerpt||''}`)).slice(0,20).map((item)=>({originTitle:item.originTitle||'',deletedAt:item.deletedAt||null,originUrl:item.originUrl||''}));throw new Error(`FAKORI_LOOKUP ${JSON.stringify({totalRows:rows.length,matches,deleted})}`);}],
  'GET /video-desk/pending':[async()=>{const job=await pendingVideoBulletin();console.error('video_desk_pending_snapshot',JSON.stringify(job));return json({job});}],
  'POST /video-desk/callback':[async({query,body})=>handleVideoDeskCallback(query,body)], 'POST /video-desk/facebook-confirm':[async({body})=>handleVideoFacebookConfirm(body)],
  ...visitorAuthRoutes,
  'POST /api/public/bootstrap':[async({body})=>{const {input,visitor}=await resolveCustomVisitor(body);if(!visitor)return error('نشست ورود معتبر نیست.',401);await ensureSeed();const settings=await getSettings();const countEntry=input.countEntry===true;const [items,logoUrl,entryRecorded]=await Promise.all([getNewsItems(false,settings),mediaUrlFor(settings.logoPath),countEntry?recordVisitorEvent(customVisitorUser(visitor),'site_open'):Promise.resolve(true)]);return json({visitor,items,settings:{...settings,logoUrl},entryRecorded});}],
  'POST /api/public/news':[async({body})=>{const {visitor}=await resolveCustomVisitor(body);return visitor?json({items:await getNewsItems(false)}):error('نشست ورود معتبر نیست.',401);}],
  'POST /api/public/search':[async({body})=>{const {input,visitor}=await resolveCustomVisitor(body);return visitor?json(await searchPublishedNews(input.q,numberValue(input.limit,60))):error('نشست ورود معتبر نیست.',401);}],
  'POST /api/public/news-page':[async({body})=>{const {input,visitor}=await resolveCustomVisitor(body);return visitor?json(await getRecentNewsItems(numberValue(input.offset,0),numberValue(input.limit,ARCHIVE_PAGE_LIMIT))):error('نشست ورود معتبر نیست.',401);}],
  'POST /api/public/english':[async({body})=>{const {input,visitor}=await resolveCustomVisitor(body);return visitor?json(await getEnglishItems(numberValue(input.offset,0),numberValue(input.limit,ARCHIVE_PAGE_LIMIT))):error('نشست ورود معتبر نیست.',401);}],
  'POST /api/public/analysis':[async({body})=>{const {input,visitor}=await resolveCustomVisitor(body);return visitor?json(await getAnalysisItems(numberValue(input.offset,0),numberValue(input.limit,ARCHIVE_PAGE_LIMIT))):error('نشست ورود معتبر نیست.',401);}],
  'POST /api/public/archive':[async({body})=>{const {input,visitor}=await resolveCustomVisitor(body);return visitor?json(await getArchiveItems(numberValue(input.offset,0),numberValue(input.limit,ARCHIVE_PAGE_LIMIT))):error('نشست ورود معتبر نیست.',401);}],
  'POST /api/public/news/localize':[async({body})=>{const {input,visitor}=await resolveCustomVisitor(body);if(!visitor)return error('نشست ورود معتبر نیست.',401);const language=textValue(input.language);if(!isPublicLanguage(language))return error('زبان انتخاب‌شده پشتیبانی نمی‌شود.',400);const ids=Array.isArray(input.ids)?Array.from(new Set(input.ids.map((value)=>textValue(value)).filter(Boolean))).slice(0,input.detail===true?1:8):[];if(!ids.length)return json({items:[]});const settings=await getSettings();const records=(await getNewsByIds(ids)).filter((item)=>item.status==='published'&&(!settings.visualRequired||hasRenderableVisual(item)));const localized=await localizeNewsItems(records,language,input.detail===true);return json({items:await hydrateNews(localized)});}],
  'POST /api/public/news/:id':[async({body,params})=>{const {visitor}=await resolveCustomVisitor(body);if(!visitor)return error('نشست ورود معتبر نیست.',401);const settings=await getSettings();const [current]=await db.get<NewsRecord>('news',[params.id]);if(!current||current.status!=='published'||(settings.visualRequired&&!hasRenderableVisual(current)))return error('مطلب یافت نشد.',404);const base={...current,id:params.id} as NewsRecord&{id:string};const visible=current.externalVideoUrl&&current.sourcePipelineVersion!==SOURCE_PIPELINE_VERSION?await repairLegacyVideoAnalysis(base).catch(()=>base):base;const [hydrated]=await hydrateNews([visible]);return json(hydrated);}],
  'POST /api/public/news/:id/view':[async({body,params})=>{const {visitor}=await resolveCustomVisitor(body);if(!visitor)return error('نشست ورود معتبر نیست.',401);const settings=await getSettings();const [current]=await db.get<NewsRecord>('news',[params.id]);if(!current||current.status!=='published'||(settings.visualRequired&&!hasRenderableVisual(current)))return error('مطلب یافت نشد.',404);const base={...current,id:params.id} as NewsRecord&{id:string};const refreshed=current.externalVideoUrl&&current.sourcePipelineVersion!==SOURCE_PIPELINE_VERSION?await repairLegacyVideoAnalysis(base).catch(()=>base):base;const logged=await recordVisitorEvent(customVisitorUser(visitor),'article_view',{articleId:params.id});if(!logged)return error('ثبت بازدید کاربر انجام نشد.',500);const record=withoutId({...refreshed,viewCount:(refreshed.viewCount||0)+1});const [ok]=await db.update('news',[{id:params.id,record}]);if(!ok)return error('ثبت بازدید انجام نشد.',500);const [hydrated]=await hydrateNews([{...refreshed,...record,id:params.id} as NewsRecord&{id:string}]);return json(hydrated);}],
  'POST /api/public/settings':[async({body})=>{const {visitor}=await resolveCustomVisitor(body);return visitor?json(await getSettingsForClient()):error('نشست ورود معتبر نیست.',401);}],
  'POST /api/public/visitor/click':[async({body})=>{const input=(body||{}) as Record<string,unknown>,visitor=anonymousVisitorIdentity(input.visitorId);if(!visitor)return error('شناسهٔ مرورگر معتبر نیست.',400);const logged=await recordVisitorEvent(customVisitorUser(visitor),'entry_click');return logged?json({ok:true}):error('ثبت کلیک ورود انجام نشد.',500);}],
  'POST /api/public/visitor/enter':[async({body})=>{const {visitor}=await resolveCustomVisitor(body);if(!visitor)return error('نشست ورود معتبر نیست.',401);const logged=await recordVisitorEvent(customVisitorUser(visitor),'site_open');return logged?json(visitor):error('ثبت ورود انجام نشد.',500);}],
  'POST /api/public/visitor/event':[async({body})=>{const {input,visitor}=await resolveCustomVisitor(body);if(!visitor)return error('نشست ورود معتبر نیست.',401);const event=textValue(input.event) as VisitorEventKind;if(event!=='install_attempt'&&event!=='install_success')return error('رویداد معتبر نیست.',400);const logged=await recordVisitorEvent(customVisitorUser(visitor),event);return logged?json({ok:true}):error('ثبت رویداد انجام نشد.',500);}],
  'POST /api/public/news-subscription':[async({body})=>{const {input,visitor}=await resolveCustomVisitor(body);if(!visitor)return error('نشست ورود معتبر نیست.',401);const connectionId=textValue(input.connection_id);if(!connectionId)return error('connection_id is required',400);if(input.action==='remove')await removeSubscriptions('news','feed',connectionId);else await addSubscription('news','feed',connectionId);return json({ok:true});}],
  'GET /api/news': [requireAuth(), withScopes('email'), async () => json({ items: await getNewsItems(false) })],
  'GET /api/news-page': [requireAuth(), withScopes('email'), async ({ query }) => json(await getRecentNewsItems(numberValue(query.offset, 0), numberValue(query.limit, ARCHIVE_PAGE_LIMIT)))],
  'GET /api/english': [requireAuth(), withScopes('email'), async ({ query }) => json(await getEnglishItems(numberValue(query.offset, 0), numberValue(query.limit, ARCHIVE_PAGE_LIMIT)))],
  'GET /api/analysis': [requireAuth(), withScopes('email'), async ({ query }) => json(await getAnalysisItems(numberValue(query.offset, 0), numberValue(query.limit, ARCHIVE_PAGE_LIMIT)))],
  'GET /api/archive': [requireAuth(), withScopes('email'), async ({ query }) => json(await getArchiveItems(numberValue(query.offset, 0), numberValue(query.limit, ARCHIVE_PAGE_LIMIT)))],
  'POST /api/news/localize': [requireAuth(), withScopes('email'), async ({ body }) => { const input=(body||{}) as Record<string,unknown>; const language=textValue(input.language); if (!isPublicLanguage(language)) return error('زبان انتخاب‌شده پشتیبانی نمی‌شود.',400); const ids=Array.isArray(input.ids)?Array.from(new Set(input.ids.map((value)=>textValue(value)).filter(Boolean))).slice(0,input.detail===true?1:8):[]; if (!ids.length) return json({items:[]}); const settings=await getSettings(); const records=(await getNewsByIds(ids)).filter((item)=>item.status==='published'&&(!settings.visualRequired||hasRenderableVisual(item))); const localized=await localizeNewsItems(records,language,input.detail===true); return json({items:await hydrateNews(localized)}); }],
  'GET /api/news/:id': [requireAuth(), withScopes('email'), async ({ params }) => {
    const settings = await getSettings(); const [current] = await db.get<NewsRecord>('news', [params.id]);
    if (!current || current.status !== 'published' || (settings.visualRequired && !hasRenderableVisual(current))) return error('مطلب یافت نشد.', 404);
    const base = { ...current, id: params.id } as NewsRecord & { id: string };
    const visible = current.externalVideoUrl && current.sourcePipelineVersion !== SOURCE_PIPELINE_VERSION ? await repairLegacyVideoAnalysis(base).catch((err) => { console.warn('video_analysis_lazy_repair_failed', { id: params.id, reason: err instanceof Error ? err.message : 'unknown' }); return base; }) : base;
    const [hydrated] = await hydrateNews([visible]);
    return json(hydrated);
  }],

  'POST /api/visitor/enter': [requireAuth(), withScopes('email'), async (ctx) => { const email = normalizeEmail(ctx.user!.email); if (!validEmail(email)) return error('ورود معتبر نیست.', 403); const logged = await recordVisitorEvent(ctx.user!, 'site_open'); return logged ? json({ userId: ctx.user!.userId, email, name: textValue(ctx.user!.name) || email.split('@')[0] }) : error('ثبت ورود انجام نشد.', 500); }],
  'POST /api/visitor/event': [requireAuth(), withScopes('email'), async (ctx) => { const input = (ctx.body || {}) as Record<string, unknown>; const event = textValue(input.event) as VisitorEventKind; if (event !== 'install_attempt' && event !== 'install_success') return error('رویداد معتبر نیست.', 400); const logged = await recordVisitorEvent(ctx.user!, event); return logged ? json({ ok: true }) : error('ثبت رویداد انجام نشد.', 500); }],
  'GET /api/owner/visitors': [requireAuth(), withScopes('email'), requireRoles('owner'), async () => json(await getVisitorAnalytics())],

  'POST /api/admin/bootstrap': [requireAuth(), withScopes('email'), requireAdminEmailAllowlist(ADMIN_EMAILS), async (ctx) => {
    const member = await ensurePrimaryOwner(ctx.user!);
    return member ? json({ ok: true }) : error('ایجاد مالک اصلی انجام نشد.', 500);
  }],

  'GET /api/admin/me': [requireAuth(), withScopes('email'), async (ctx) => {
    const member = await resolveStaffMember(ctx.user!, true);
    if (!member) return error('این حساب اجازهٔ ورود به پنل مدیریت را ندارد.', 403);
    return json({ email: member.email, name: member.name, role: member.role, primary: member.primary, permissions: permissionsFor(member.role) });
  }],

  'GET /api/admin/news': [requireAuth(), withScopes('email'), requireRoles(...STAFF_ROLES), async () => json({ items: await getNewsItems(true) })],

  'GET /api/staff': [requireAuth(), withScopes('email'), requireRoles('owner'), async () => {
    const members = await listStaffMembers();
    const roleRank: Record<StaffRole, number> = { owner: 5, manager: 4, deputy: 3, editor: 2, reporter: 1 };
    members.sort((a, b) => Number(b.primary) - Number(a.primary) || roleRank[b.role] - roleRank[a.role] || a.name.localeCompare(b.name));
    return json({ items: members.map(staffForClient) });
  }],

  'POST /api/staff': [requireAuth(), withScopes('email'), requireRoles('owner'), async (ctx) => {
    const input = (ctx.body || {}) as Record<string, unknown>;
    const email = normalizeEmail(input.email);
    const role = textValue(input.role) as StaffRole;
    if (!validEmail(email)) return error('ایمیل معتبر الزامی است.', 400);
    if (!STAFF_ROLES.includes(role)) return error('نقش انتخاب‌شده معتبر نیست.', 400);
    const members = await listStaffMembers();
    if (members.some((item) => normalizeEmail(item.email) === email)) return error('این ایمیل قبلاً اضافه شده است.', 409);
    const now = Date.now();
    const record: StaffRecord = {
      email, name: textValue(input.name) || email.split('@')[0], role,
      active: boolValue(input.active, true), primary: false,
      createdAt: now, updatedAt: now, createdBy: normalizeEmail(ctx.user!.email)
    };
    const [id] = await db.add('staff_members', [record as unknown as Record<string, unknown>]);
    return id ? json(staffForClient({ id, ...record }), 201) : error('افزودن عضو انجام نشد.', 500);
  }],

  'PUT /api/staff/:id': [requireAuth(), withScopes('email'), requireRoles('owner'), async (ctx) => {
    const [current] = await db.get<StaffRecord>('staff_members', [ctx.params.id]);
    if (!current) return error('عضو یافت نشد.', 404);
    if (current.primary) return error('مالک اصلی قابل تغییر نیست.', 403);
    const input = (ctx.body || {}) as Record<string, unknown>;
    const email = normalizeEmail(input.email ?? current.email);
    const role = textValue(input.role ?? current.role) as StaffRole;
    if (!validEmail(email)) return error('ایمیل معتبر الزامی است.', 400);
    if (!STAFF_ROLES.includes(role)) return error('نقش انتخاب‌شده معتبر نیست.', 400);
    const members = await listStaffMembers();
    if (members.some((item) => item.id !== ctx.params.id && normalizeEmail(item.email) === email)) return error('این ایمیل قبلاً اضافه شده است.', 409);
    const record: StaffRecord = {
      ...current, email, name: textValue(input.name ?? current.name) || email.split('@')[0], role,
      active: boolValue(input.active, current.active), primary: false,
      userId: email === normalizeEmail(current.email) ? current.userId : undefined, updatedAt: Date.now()
    };
    const [ok] = await db.update('staff_members', [{ id: ctx.params.id, record: withoutId(record as unknown as Record<string, unknown>) }]);
    return ok ? json(staffForClient({ id: ctx.params.id, ...record })) : error('ویرایش عضو انجام نشد.', 500);
  }],

  'DELETE /api/staff/:id': [requireAuth(), withScopes('email'), requireRoles('owner'), async (ctx) => {
    const [current] = await db.get<StaffRecord>('staff_members', [ctx.params.id]);
    if (!current) return error('عضو یافت نشد.', 404);
    if (current.primary) return error('مالک اصلی قابل حذف نیست.', 403);
    const [ok] = await db.delete('staff_members', [ctx.params.id]);
    return ok ? json({ ok: true }) : error('حذف عضو انجام نشد.', 500);
  }],

  'POST /api/news-subscription': [requireAuth(), withScopes('email'), async ({ body }) => {
    const input = (body || {}) as Record<string, unknown>;
    const connectionId = textValue(input.connection_id);
    if (!connectionId) return error('connection_id is required', 400);
    if (input.action === 'remove') await removeSubscriptions('news', 'feed', connectionId);
    else await addSubscription('news', 'feed', connectionId);
    return json({ ok: true });
  }],

  'POST /api/news': [requireAuth(), withScopes('email'), requireRoles(...STAFF_ROLES), async ({ body }) => {
    try {
      return json(await createNews((body || {}) as Record<string, unknown>), 201);
    } catch (err) {
      const message = err instanceof Error ? err.message : '';
      return error(message === 'title_body_required' ? 'عنوان و متن الزامی است.' : message === 'visual_required' ? 'برای نشر، تصویر یا ویدیوی قابل‌نمایش الزامی است.' : message === 'media_write_failed' ? 'آپلود رسانه انجام نشد.' : 'ثبت مطلب انجام نشد.', 400);
    }
  }],

  'POST /api/news/:id/view': [requireAuth(), withScopes('email'), async (ctx) => { const { params } = ctx;
    const settings = await getSettings(); const [current] = await db.get<NewsRecord>('news', [params.id]);
    if (!current || current.status !== 'published' || (settings.visualRequired && !hasRenderableVisual(current))) return error('مطلب یافت نشد.', 404);
    const base = { ...current, id: params.id } as NewsRecord & { id: string };
    const refreshed = current.externalVideoUrl && current.sourcePipelineVersion !== SOURCE_PIPELINE_VERSION ? await repairLegacyVideoAnalysis(base).catch((err) => { console.warn('video_analysis_view_repair_failed', { id: params.id, reason: err instanceof Error ? err.message : 'unknown' }); return base; }) : base;
    const visitorLogged = await recordVisitorEvent(ctx.user!, 'article_view', { articleId: params.id }); if (!visitorLogged) return error('ثبت بازدید کاربر انجام نشد.', 500); const record = withoutId({ ...refreshed, viewCount: (refreshed.viewCount || 0) + 1 });
    const [ok] = await db.update('news', [{ id: params.id, record }]);
    if (!ok) return error('ثبت بازدید انجام نشد.', 500);
    const [hydrated] = await hydrateNews([{ ...refreshed, ...record, id: params.id } as NewsRecord & { id: string }]);
    return json(hydrated);
  }],

  'PUT /api/news/:id': [requireAuth(), withScopes('email'), requireRoles(...STAFF_ROLES), async ({ body, params }) => {
    const [current] = await db.get<NewsRecord>('news', [params.id]);
    if (!current) return error('مطلب یافت نشد.', 404);
    const input = (body || {}) as Record<string, unknown>;
    try {
      const record = await buildNewsRecord(input, current);
      const [ok] = await db.update('news', [{ id: params.id, record: withoutId(record as unknown as Record<string, unknown>) }]);
      if (!ok) return error('ویرایش انجام نشد.', 500);
      const updated = { id: params.id, ...record };
      await updateNewsIndexForRecord(params.id, record);
      if (record.status === 'published' && current.status !== 'published') await publishToSocials(updated).catch(() => undefined);
      const [hydrated] = await hydrateNews([updated]);
      await notifySubscribers('news', 'feed', { action: 'upsert', item: hydrated || updated }).catch(() => undefined);
      return json(hydrated);
    } catch (err) {
      const message = err instanceof Error ? err.message : ''; return error(message === 'title_body_required' ? 'عنوان و متن الزامی است.' : message === 'visual_required' ? 'برای نشر، تصویر یا ویدیوی قابل‌نمایش الزامی است.' : 'ویرایش انجام نشد.', 400);
    }
  }],

  'DELETE /api/news/:id': [requireAuth(), withScopes('email'), requireRoles('owner', 'manager', 'deputy', 'editor'), async ({ params }) => {
    const settings = await getSettings(); const [current] = await db.get<NewsRecord>('news', [params.id]);
    if (!current) return error('مطلب یافت نشد.', 404);
    if (current.section === 'analysis') return error('مطالب بخش تحلیل دائمی‌اند و به آرشیف منتقل نمی‌شوند.', 400);
    if (!isNewsArchiveEligible(current, Date.now(), settings.archiveDays)) return error(`خبر پیش از تکمیل ${settings.archiveDays} روز به آرشیف منتقل نمی‌شود.`, 400);
    const record = withoutId({ ...current, archived: true, archivedAt: Date.now(), updatedAt: Date.now() });
    const [ok] = await db.update('news', [{ id: params.id, record }]);
    if (!ok) return error('انتقال به آرشیف انجام نشد.', 500);
    const archived = { ...current, ...record, id: params.id } as NewsRecord & { id: string };
    await updateNewsIndexForRecord(params.id, archived);
    const [hydrated] = await hydrateNews([archived]);
    await notifySubscribers('news', 'feed', { action: 'archive', id: params.id, item: hydrated || archived }).catch(() => undefined);
    return json({ ok: true, item: hydrated || archived });
  }],

  'DELETE /api/news/:id/permanent': [requireAuth(), withScopes('email'), requireRoles('owner', 'manager', 'deputy', 'editor'), async ({ params }) => { const [current] = await db.get<NewsRecord>('news', [params.id]); if (!current) return error('مطلب یافت نشد.', 404); let tombstoneId: string | null | undefined; if (current.originUrl) { const [createdTombstone] = await db.add('news_delete_tombstones', [{ originUrl: canonicalArticleUrl(current.originUrl), fingerprint: current.fingerprint, originName: current.originName, originPublishedAt: current.originPublishedAt, originTitle: current.originTitle || current.title, originExcerpt: current.originExcerpt || current.summary, deletedAt: Date.now() }]); tombstoneId = createdTombstone; if (!tombstoneId) return error('ثبت حذف پایدار انجام نشد.', 500); } const [deleted] = await db.delete('news', [params.id]); if (!deleted) { if (tombstoneId) await db.delete('news_delete_tombstones', [tombstoneId]).catch(() => undefined); return error('حذف مطلب انجام نشد.', 500); } try { await removeNewsFromIndex(params.id); } catch { await buildNewsIndex(); clearNewsReadCaches(); } if (current.mediaPath) { const [mediaDeleted] = await storage.delete([current.mediaPath]); if (!mediaDeleted) console.warn('deleted_news_media_cleanup_failed', { id: params.id, path: current.mediaPath }); } await notifySubscribers('news', 'feed', { action: 'delete', id: params.id }).catch(() => undefined); return json({ ok: true, id: params.id }); }],

  'POST /api/news/:id/restore': [requireAuth(), withScopes('email'), requireRoles('owner', 'manager', 'deputy', 'editor'), async ({ params }) => {
    const [current] = await db.get<NewsRecord>('news', [params.id]);
    if (!current) return error('مطلب یافت نشد.', 404);
    const record = withoutId({ ...current, archived: false, archivedAt: 0, updatedAt: Date.now() });
    const [ok] = await db.update('news', [{ id: params.id, record }]);
    if (!ok) return error('بازگرداندن مطلب انجام نشد.', 500);
    const restored = { ...current, ...record, id: params.id } as NewsRecord & { id: string };
    await updateNewsIndexForRecord(params.id, restored);
    const [hydrated] = await hydrateNews([restored]);
    await notifySubscribers('news', 'feed', { action: 'restore', id: params.id, item: hydrated || restored }).catch(() => undefined);
    return json({ ok: true, item: hydrated || restored });
  }],

  'GET /api/sources': [requireAuth(), withScopes('email'), requireRoles('owner', 'manager', 'deputy'), async () => {
    await ensureEnglishDeskSourcesOnce(); await retireLegacyStateEnglishSourceOnce();
    const { items } = await db.list<SourceRecord>('sources', { limit: 500 });
    items.sort((a, b) => b.createdAt - a.createdAt);
    return json({ items });
  }],

  'POST /api/sources': [requireAuth(), withScopes('email'), requireRoles('owner', 'manager', 'deputy'), async ({ body }) => {
    const input = (body || {}) as Record<string, unknown>;
    const url = normalizeSourceUrl(input.url, textValue(input.language) || 'دری');
    if (!safeUrl(url)) return error('نشانی معتبر منبع الزامی است.', 400);
    const now = Date.now();
    const record: SourceRecord = {
      name: textValue(input.name) || new URL(url).hostname.replace(/^www\./, ''), url,
      sourceType: textValue(input.sourceType) || 'وب‌سایت', language: textValue(input.language) || 'دری', topic: textValue(input.topic) || 'عمومی',
      priority: textValue(input.priority) || 'عادی', intervalMinutes: Math.max(5, numberValue(input.intervalMinutes, 5)),
      active: boolValue(input.active, true) && boolValue(input.rightsApproved), autoPublish: boolValue(input.autoPublish) && boolValue(input.rightsApproved), rightsApproved: boolValue(input.rightsApproved),
      ingestText: boolValue(input.ingestText, true), ingestImage: boolValue(input.ingestImage, true), ingestVideo: boolValue(input.ingestVideo, true), ingestInterview: boolValue(input.ingestInterview, true),
      createdAt: now, updatedAt: now, fetchedCount: 0
    };
    const [id] = await db.add('sources', [record]);
    return id ? json({ id, ...record }, 201) : error('افزودن منبع انجام نشد.', 500);
  }],

  'PUT /api/sources/:id': [requireAuth(), withScopes('email'), requireRoles('owner', 'manager', 'deputy'), async ({ body, params }) => {
    const [current] = await db.get<SourceRecord>('sources', [params.id]);
    if (!current) return error('منبع یافت نشد.', 404);
    const input = (body || {}) as Record<string, unknown>;
    const url = normalizeSourceUrl(input.url ?? current.url, textValue(input.language ?? current.language) || 'دری');
    if (!safeUrl(url)) return error('نشانی معتبر منبع الزامی است.', 400);
    const rightsApproved = boolValue(input.rightsApproved ?? current.rightsApproved, current.rightsApproved); const record = withoutId({ ...current, ...input, url, rightsApproved, active: boolValue(input.active ?? current.active, current.active) && rightsApproved, autoPublish: boolValue(input.autoPublish ?? current.autoPublish, current.autoPublish) && rightsApproved, intervalMinutes: Math.max(5, numberValue(input.intervalMinutes ?? current.intervalMinutes, 5)), updatedAt: Date.now() });
    const [ok] = await db.update('sources', [{ id: params.id, record }]);
    return ok ? json({ id: params.id, ...record }) : error('ویرایش منبع انجام نشد.', 500);
  }],

  'DELETE /api/sources/:id': [requireAuth(), withScopes('email'), requireRoles('owner', 'manager', 'deputy'), async ({ params }) => {
    const [ok] = await db.delete('sources', [params.id]);
    return ok ? json({ ok: true }) : error('حذف منبع انجام نشد.', 500);
  }],

  'POST /api/sources/:id/ingest': [requireAuth(), withScopes('email'), requireRoles('owner', 'manager', 'deputy'), async ({ params }) => {
    try {
      return json(await ingestSource(params.id,1,true));
    } catch (err) {
      const message = err instanceof Error ? err.message : '';
      if (message === 'rights_required') return error('مجوز استفاده برای این منبع ثبت نشده است.', 403);
      if (message === 'source_not_found') return error('منبع یافت نشد.', 404);
      const [source] = await db.get<SourceRecord>('sources', [params.id]);
      console.warn('source_ingest_failed', { sourceId: params.id, sourceUrl: source?.url, reason: message || 'unknown' });
      return json({
        created: false,
        source: source ? { id: params.id, ...source } : undefined,
        warning: 'منبع ذخیره شده است؛ سایت در این نوبت پاسخ قابل دریافت نداد و در پایش بعدی دوباره بررسی می‌شود.'
      });
    }
  }],

  'GET /api/socials': [requireAuth(), withScopes('email'), requireRoles('owner', 'manager', 'deputy'), async () => {
    const names = await secrets.listSecretNames();
    const { items } = await db.list<SocialRecord>('socials', { limit: 100 });
    return json({ items: items.map((item) => ({ ...item, configured: networkConfigured(item.network, names) })) });
  }],

  'POST /api/socials': [requireAuth(), withScopes('email'), requireRoles('owner', 'manager', 'deputy'), async ({ body }) => {
    const input = (body || {}) as Record<string, unknown>;
    let pageUrl = textValue(input.pageUrl);
    if (pageUrl && !safeUrl(pageUrl)) pageUrl = `https://${pageUrl.replace(/^\/+/, '')}`;
    if (!safeUrl(pageUrl)) return error('لینک معتبر صفحه الزامی است.', 400);
    const network = textValue(input.network) || 'Facebook';
    const names = await secrets.listSecretNames();
    const configured = networkConfigured(network, names);
    const id = textValue(input.id);
    if (id) {
      const [current] = await db.get<SocialRecord>('socials', [id]);
      if (!current) return error('رسانه یافت نشد.', 404);
      const record = withoutId({ ...current, ...input, network, pageUrl, status: configured ? 'اتصال آماده' : 'در انتظار مجوز رسمی', updatedAt: Date.now() });
      const [ok] = await db.update('socials', [{ id, record }]);
      return ok ? json({ id, ...record, configured }) : error('ویرایش رسانه انجام نشد.', 500);
    }
    const now = Date.now();
    const record: SocialRecord = {
      network, pageUrl, autoShare: boolValue(input.autoShare, true), enabled: boolValue(input.enabled, true),
      status: configured ? 'اتصال آماده' : 'در انتظار مجوز رسمی', createdAt: now, updatedAt: now
    };
    const [newId] = await db.add('socials', [record]);
    return newId ? json({ id: newId, ...record, configured }, 201) : error('ثبت رسانه انجام نشد.', 500);
  }],

  'DELETE /api/socials/:id': [requireAuth(), withScopes('email'), requireRoles('owner', 'manager', 'deputy'), async ({ params }) => {
    const [ok] = await db.delete('socials', [params.id]);
    return ok ? json({ ok: true }) : error('حذف رسانه انجام نشد.', 500);
  }],

  'GET /api/settings': [requireAuth(), withScopes('email'), async () => json(await getSettingsForClient())],

  'POST /api/settings': [requireAuth(), withScopes('email'), requireRoles('owner', 'manager'), async (ctx) => {
    const input = (ctx.body || {}) as Record<string, unknown>; const current = await getSettings(); const member = await resolveStaffMember(ctx.user!); const isOwner = member?.role === 'owner'; let logoPath = current.logoPath; let logoMime = current.logoMime; let logoName = current.logoName;
    if (isOwner && input.logo) { const logo = await storeMedia(input.logo as MediaInput); if (logo.mediaPath) { if (current.logoPath && current.logoPath !== logo.mediaPath) await storage.delete([current.logoPath]).catch(() => undefined); logoPath = logo.mediaPath; logoMime = logo.mediaMime; logoName = logo.mediaName; } }
    const ownerText = (key: keyof SettingsRecord, fallback: string) => isOwner ? textValue(input[key] ?? current[key]) || fallback : String(current[key] ?? fallback); const ownerBool = (key: keyof SettingsRecord) => isOwner ? boolValue(input[key], Boolean(current[key])) : Boolean(current[key]); const ownerNumber = (key: keyof SettingsRecord, min: number, max: number, fallback: number) => isOwner ? Math.min(max, Math.max(min, numberValue(input[key] ?? current[key], fallback))) : Number(current[key] ?? fallback);
    const record: SettingsRecord = { ...current, siteTitle: textValue(input.siteTitle ?? current.siteTitle) || 'خبرگزاری سایه', defaultLanguage: textValue(input.defaultLanguage ?? current.defaultLanguage) || 'دری', breakingBar: boolValue(input.breakingBar, current.breakingBar), watermark: boolValue(input.watermark, current.watermark), versioning: boolValue(input.versioning, current.versioning), autoShare: boolValue(input.autoShare, current.autoShare), brandName: ownerText('brandName','SAYEH NEWS'), tagline: ownerText('tagline','پنهان از نگاه‌ها، مسلط بر رویدادها'), newsroomLabel: ownerText('newsroomLabel','تحریریه'), homeTitle: ownerText('homeTitle','پنهان از نگاه‌ها'), newsTitle: ownerText('newsTitle','آخرین خبرها'), analysisTitle: ownerText('analysisTitle','تحلیل رویدادها'), archiveTitle: ownerText('archiveTitle','آرشیف خبرها'), englishTitle: ownerText('englishTitle','English News'), englishDeskLabel: ownerText('englishDeskLabel','English Desk'), navHome: ownerText('navHome','خانه'), navNews: ownerText('navNews','خبرها'), navAnalysis: ownerText('navAnalysis','تحلیل'), navArchive: ownerText('navArchive','آرشیف'), navEnglish: ownerText('navEnglish','English'), breakingLabel: ownerText('breakingLabel','خبر فوری'), footerText: ownerText('footerText','تمام حقوق این خبرگزاری برای سایه محفوظ می‌باشد.'), footerYear: ownerText('footerYear','۲۰۲۳'), searchPlaceholder: ownerText('searchPlaceholder','جست‌وجوی خبر و تحلیل...'), viewCountThreshold: ownerNumber('viewCountThreshold',0,100000000,500), archiveDays: ownerNumber('archiveDays',1,3650,20), visualRequired: ownerBool('visualRequired'), sourceMonitorEnabled: ownerBool('sourceMonitorEnabled'), forceArianaSource: ownerBool('forceArianaSource'), primaryIntervalMinutes: ownerNumber('primaryIntervalMinutes',5,1440,5), primaryItemsPerCycle: ownerNumber('primaryItemsPerCycle',1,10,3), secondarySourcesPerCycle: ownerNumber('secondarySourcesPerCycle',1,5,1), secondaryHighItemsPerCycle: ownerNumber('secondaryHighItemsPerCycle',1,10,3), secondaryNormalItemsPerCycle: ownerNumber('secondaryNormalItemsPerCycle',1,10,2), autoWordLimit: ownerNumber('autoWordLimit',100,5000,1000), openverseFallback: ownerBool('openverseFallback'), bbcReplaceSourceImage: ownerBool('bbcReplaceSourceImage'), logoPath, logoMime, logoName, updatedAt: Date.now() };
    const { items } = await db.list<SettingsRecord>('settings', { limit: 1 }); if (items[0]) { const [ok] = await db.update('settings', [{ id: items[0].id, record }]); if (!ok) return error('ذخیره تنظیمات انجام نشد.', 500); settingsCache=undefined; clearNewsReadCaches(); if (isOwner) await buildNewsIndex(); return json(await getSettingsForClient()); } const [id] = await db.add('settings', [record]); if (!id) return error('ذخیره تنظیمات انجام نشد.', 500); settingsCache=undefined; clearNewsReadCaches(); if (isOwner) await buildNewsIndex(); return json(await getSettingsForClient(), 201);
  }]
});