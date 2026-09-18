import { Buffer } from 'buffer';
import { fetchYouTubeTranscript } from './video-transcript';

export type SourceMonitorInput = {
  name: string;
  url: string;
  sourceType: string;
  priority: string;
  ingestImage: boolean; lastSuccessAt?: number;
};

export type ExistingNewsIdentity = { originUrl?: string; fingerprint?: string; title?: string; summary?: string; body?: string; originName?: string; originPublishedAt?: number; originImageKey?: string; originTitle?: string; originExcerpt?: string; mediaName?: string; createdAt?: number; updatedAt?: number; status?: string };
export type RemoteMediaInput = { data: string; mimeType: string; name: string };
export type SourceCandidate = { url: string; title: string; text: string; fingerprint: string; sourcePublishedAt?: number; imageKey?: string; media?: RemoteMediaInput; externalVideoUrl?: string; contentBasis?: 'video_transcript' | 'source_text' };

const requestHeaders = {
  'User-Agent': 'Mozilla/5.0 (Linux; Android 11; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Mobile Safari/537.36',
  Accept: 'text/html,application/xhtml+xml,application/rss+xml,application/atom+xml,application/xml;q=0.9,*/*;q=0.8',
  'Accept-Language': 'fa-AF,fa;q=0.9,ps;q=0.8,en;q=0.6',
  'Cache-Control': 'no-cache',
  Pragma: 'no-cache'
};

const hashText = (value: string) => {
  let hash = 2166136261;
  for (let index = 0; index < value.length; index += 1) {
    hash ^= value.charCodeAt(index);
    hash = Math.imul(hash, 16777619);
  }
  return String(hash >>> 0);
};

const decodeEntities = (value: string) => value
  .replace(/<!\[CDATA\[([\s\S]*?)\]\]>/gi, '$1')
  .replace(/&nbsp;/gi, ' ')
  .replace(/&amp;/gi, '&')
  .replace(/&quot;/gi, '"')
  .replace(/&#39;|&apos;/gi, "'")
  .replace(/&lt;/gi, '<')
  .replace(/&gt;/gi, '>')
  .replace(/&#(\d+);/g, (_, code: string) => String.fromCharCode(Number(code)));

const stripHtml = (value: string) => decodeEntities(value)
  .replace(/<script\b[\s\S]*?<\/script>/gi, ' ')
  .replace(/<style\b[\s\S]*?<\/style>/gi, ' ')
  .replace(/<noscript\b[\s\S]*?<\/noscript>/gi, ' ')
  .replace(/<[^>]+>/g, ' ')
  .replace(/\s+/g, ' ')
  .trim();

const articleTailBoundary = /(?:^|\s)(?:continue\s+reading|read\s+more|latest\s+news|related\s+(?:news|stories|topics?)|ariana\s+news\s+related|don['’]?t\s+miss|advert(?:isement)?|you\s+may\s+like|by\s+ariana\s+news|most\s+viewed|ادامه\s+مطلب|بیشتر\s+بخوانید|آخرین\s+خبرها|تازه‌ترین\s+خبرها|خبرهای\s+مرتبط|مطالب\s+مرتبط|پربازدید(?:ترین)?)(?=\s|$|:)/i;
const articleMetadataNoise = /(?:^|\s)(?:منتشر\s+شده|نشر\s+شده|published\b|توسط\s+ariana\s+news|\d+\s*(?:minutes?|hours?)\s+(?:ago|پیش)|\d+\s*ساعت\s+ago)(?=\s|$)/i;
const cleanArticleText = (value: string) => {
  let normalized = value.replace(/\u00a0/g, ' ').replace(/\s+/g, ' ').trim();
  for (let pass = 0; pass < 3; pass += 1) {
    const lead = normalized.slice(0, 500).match(articleMetadataNoise);
    if (!lead || lead.index == null || lead.index > 180) break;
    normalized = `${normalized.slice(0, lead.index)} ${normalized.slice(lead.index + lead[0].length)}`.replace(/\s+/g, ' ').trim();
  }
  const strong = normalized.search(articleTailBoundary);
  return (strong >= 0 ? normalized.slice(0, strong) : normalized).replace(/\s+/g, ' ').trim();
};
const articleParagraphsFromMarkup = (markup: string) => {
  const safeMarkup = markup.replace(/<script\b[\s\S]*?<\/script>/gi, ' ').replace(/<style\b[\s\S]*?<\/style>/gi, ' ').replace(/<noscript\b[\s\S]*?<\/noscript>/gi, ' ').replace(/<(?:nav|aside|footer)\b[\s\S]*?<\/(?:nav|aside|footer)>/gi, ' ');
  const paragraphs: string[] = [];
  const pattern = /<(p|h2|h3|blockquote)\b[^>]*>([\s\S]*?)<\/\1>/gi;
  let match: RegExpExecArray | null;
  while ((match = pattern.exec(safeMarkup))) {
    const text = stripHtml(match[2]);
    if (text.length < 25) continue;
    if (articleTailBoundary.test(text)) break;
    if (text.length < 450 && articleMetadataNoise.test(text)) continue;
    if (!paragraphs.includes(text)) paragraphs.push(text);
  }
  return paragraphs.join('\n\n').trim();
};

const attribute = (tag: string, name: string) => {
  const match = tag.match(new RegExp(`${name}\\s*=\\s*(?:"([^"]*)"|'([^']*)'|([^\\s>]+))`, 'i'));
  return decodeEntities(match?.[1] || match?.[2] || match?.[3] || '').trim();
};

const absoluteUrl = (value: string, base: string) => {
  if (!value || /^(data:|javascript:|mailto:|tel:)/i.test(value)) return '';
  try {
    const resolved = new URL(value, base);
    return /^https?:$/i.test(resolved.protocol) ? resolved.toString() : '';
  } catch {
    return '';
  }
};
const bestSrcsetUrl=(value:string,base:string)=>{let best='',bestScore=0;for(const entry of value.split(',')){const parts=entry.trim().split(/\s+/);const url=absoluteUrl(parts[0]||'',base);if(!url)continue;const descriptor=parts[1]||'1x';const match=descriptor.match(/^(\d+(?:\.\d+)?)(w|x)$/i);const score=match?(match[2].toLowerCase()==='w'?Number(match[1]):Number(match[1])*1000):1;if(score>bestScore){bestScore=score;best=url;}}return best;};

export const canonicalArticleUrl = (value: string) => { const raw = String(value || '').trim(); if (!raw) return ''; try { const parsed = new URL(raw); if (!/^https?:$/.test(parsed.protocol) || !parsed.hostname) return ''; parsed.protocol = 'https:'; parsed.hostname = parsed.hostname.toLowerCase().replace(/^www\./, ''); parsed.hash = ''; parsed.search = ''; parsed.pathname = parsed.pathname.replace(/\/+/g, '/').replace(/\/amp\/?$/i, '').replace(/\/+$/, '') || '/'; return parsed.toString().replace(/\/$/, ''); } catch { return raw.replace(/[?#].*$/, '').replace(/\/+$/, ''); } };
const FRESH_NEWS_WINDOW_MS = 12 * 60 * 60 * 1000;
const parsePublishedDate = (value: string) => { const raw = decodeEntities(String(value || '')).trim(); if (!raw) return undefined; const numeric = Number(raw); const parsed = Number.isFinite(numeric) && numeric > 0 ? (numeric > 1000000000000 ? numeric : numeric > 1000000000 ? numeric * 1000 : NaN) : Date.parse(raw); return Number.isFinite(parsed) && parsed > 0 ? parsed : undefined; };
const publishedDateFromMarkup = (html: string) => { const parseRelative = (value: string) => { const normalized = stripHtml(value).replace(/\s+/g, ' '); const match = normalized.match(/(\d{1,3})\s*(دقیقه|ساعت|روز|هفته|minutes?|hours?|days?|weeks?)\s*(?:ago|پیش|قبل)?/i); if (!match) return undefined; const amount = Number(match[1]); const unit = match[2].toLowerCase(); const multiplier = /دقیقه|minute/.test(unit) ? 60000 : /ساعت|hour/.test(unit) ? 3600000 : /هفته|week/.test(unit) ? 7 * 86400000 : 86400000; return Date.now() - amount * multiplier; }; const metaTags = html.match(/<meta\b[^>]*>/gi) || []; for (const tag of metaTags) { const key = (attribute(tag, 'property') || attribute(tag, 'name') || attribute(tag, 'itemprop')).toLowerCase(); if (!['article:published_time', 'datepublished', 'publishdate', 'pubdate', 'date', 'dc.date', 'dcterms.date', 'parsely-pub-date', 'sailthru.date', 'originalpublicationdate'].includes(key)) continue; const parsed = parsePublishedDate(attribute(tag, 'content') || attribute(tag, 'datetime')); if (parsed) return parsed; } const jsonCandidates = [html.match(/"datePublished"\s*:\s*"([^"]+)"/i)?.[1], html.match(/\\"datePublished\\"\s*:\s*\\"([^\\"]+)\\"/i)?.[1], html.match(/'datePublished'\s*:\s*'([^']+)'/i)?.[1]]; for (const value of jsonCandidates) { const parsed = parsePublishedDate(value || ''); if (parsed) return parsed; } const timeBlocks = html.match(/<time\b[^>]*>[\s\S]*?<\/time>/gi) || []; for (const block of timeBlocks) { const opening = (block.match(/<time\b[^>]*>/i) || [])[0] || ''; const raw = attribute(opening, 'datetime') || attribute(opening, 'data-time') || attribute(opening, 'data-timestamp'); const parsed = parsePublishedDate(raw) || parsePublishedDate(stripHtml(block)) || parseRelative(block); if (parsed) return parsed; } const dataDate = html.match(/\b(?:data-published(?:-at)?|data-pubdate|data-date|data-timestamp)\s*=\s*(?:"([^"]+)"|'([^']+)'|([^\s>]+))/i); const dataRaw = dataDate?.[1] || dataDate?.[2] || dataDate?.[3] || ''; return parsePublishedDate(dataRaw) || parseRelative(dataRaw); };
const isFreshPublishedDate = (value?: number) => Boolean(value && value <= Date.now() + 10 * 60 * 1000 && value >= Date.now() - FRESH_NEWS_WINDOW_MS);
const afghanistanSignal = /(افغانستان|افغان|کابل|امارت\s+اسلامی|طالبان|قندهار|کندهار|هرات|بلخ|مزار(?:\s+شریف)?|ننگرهار|جلال(?:\s*آباد)?|بامیان|بدخشان|کندز|قندوز|هلمند|غزنی|پکتیا|پکتیکا|خوست|لغمان|نورستان|دایکندی|ارزگان|تخار|سرپل|جوزجان|فاریاب|پروان|کاپیسا|لوگر|میدان\s+وردک|سمنگان|بادغیس|فراه|نیمروز|پنجشیر|کنر|وزارت\s+(?:خارجه|داخله|دفاع|مالیه|صحت|معارف)\s+افغانستان|بانک\s+مرکزی\s+افغانستان|Afghanistan|Afghan|Kabul|Taliban|Kandahar|Herat|Balkh|Mazar(?:-i-Sharif|\s+Sharif)?|Nangarhar|Jalalabad|Bamyan|Badakhshan|Kunduz|Helmand|Ghazni|Paktia|Paktika|Khost|Laghman|Nuristan|Daikundi|Uruzgan|Takhar|Sar-e-Pul|Jowzjan|Faryab|Parwan|Kapisa|Logar|Wardak|Samangan|Badghis|Farah|Nimroz|Panjshir|Kunar)/i;
export const isAfghanistanRelevantText = (value: string) => afghanistanSignal.test(stripHtml(value).replace(/[يى]/g, 'ی').replace(/ك/g, 'ک'));
const identityTitle = (value: string) => stripHtml(value).toLowerCase().replace(/[يى]/g, 'ی').replace(/ك/g, 'ک').replace(/[ۀة]/g, 'ه').replace(/صدراعظم|صدر\s+اعظم/g, 'نخست وزیر').replace(/ایالات\s+متحده(?:\s+امریکا)?|آمریکا/g, 'امریکا').replace(/[^\p{L}\p{N}]+/gu, ' ').trim(); const identityStopWords = new Set(['و','در','از','به','با','بر','برای','که','این','آن','یک','را','تا','است','شد','شده','کرد','می','های','ها','the','a','an','of','to','in','on','for','and','is','was']); const normalizeIdentityToken = (token: string) => { if (token === 'کشوری') return 'کشور'; if (token.length > 5 && token.endsWith('های')) return token.slice(0, -3); if (token.length > 5 && token.endsWith('ها')) return token.slice(0, -2); return token; }; const identityTokens = (value: string) => identityTitle(value).split(' ').map(normalizeIdentityToken).filter((token) => token.length > 1 && !identityStopWords.has(token)); const tokenContainment = (left: string, right: string) => { const a = new Set(identityTokens(left)); const b = new Set(identityTokens(right)); if (!a.size || !b.size) return 0; let common = 0; a.forEach((token) => { if (b.has(token)) common += 1; }); return common / Math.min(a.size, b.size); }; const mediaIdentity = (value: string) => String(value || '').trim().split(/[/?#]/).filter(Boolean).pop()?.toLowerCase().replace(/[^\p{L}\p{N}._-]+/gu, '') || ''; const identityTime = (item: ExistingNewsIdentity) => item.originPublishedAt || item.createdAt || item.updatedAt || 0; const sameOrigin = (left: ExistingNewsIdentity, right: ExistingNewsIdentity) => Boolean((left.originName && right.originName && identityTitle(left.originName) === identityTitle(right.originName)) || (left.originUrl && right.originUrl && samePublisher(left.originUrl, right.originUrl))); export const likelyDuplicateImportedStory = (left: ExistingNewsIdentity, right: ExistingNewsIdentity) => { const leftUrl = left.originUrl ? canonicalArticleUrl(left.originUrl) : ''; const rightUrl = right.originUrl ? canonicalArticleUrl(right.originUrl) : ''; if (leftUrl && rightUrl && leftUrl === rightUrl) return true; if (left.fingerprint && right.fingerprint && left.fingerprint === right.fingerprint) return true; const leftTime = identityTime(left); const rightTime = identityTime(right); const closeInTime = !leftTime || !rightTime || Math.abs(leftTime - rightTime) <= 36 * 60 * 60 * 1000; if (!closeInTime) return false; const originTitleScore = tokenContainment(left.originTitle || '', right.originTitle || ''); const displayTitleScore = tokenContainment(left.title || left.originTitle || '', right.title || right.originTitle || ''); const titleScore = Math.max(originTitleScore, displayTitleScore); const originBodyScore = tokenContainment((left.originExcerpt || '').slice(0, 6000), (right.originExcerpt || '').slice(0, 6000)); const displayBodyScore = tokenContainment(`${left.summary || ''} ${left.body || ''}`.slice(0, 6000), `${right.summary || ''} ${right.body || ''}`.slice(0, 6000)); const bodyScore = Math.max(originBodyScore, displayBodyScore); const validImageKey = (value: string) => { const key = mediaIdentity(value); return key && !['media','source-image','archive-image'].includes(key) ? key : ''; }; const leftImages = new Set([validImageKey(left.originImageKey || ''), validImageKey(left.mediaName || '')].filter(Boolean)); const rightImages = new Set([validImageKey(right.originImageKey || ''), validImageKey(right.mediaName || '')].filter(Boolean)); const sameImage = [...leftImages].some((key) => rightImages.has(key)); const sameSource = sameOrigin(left, right); if (displayTitleScore >= 0.9 || originTitleScore >= 0.9 || bodyScore >= 0.94) return true; if (sameImage && (displayTitleScore >= 0.32 || displayBodyScore >= 0.3 || (titleScore >= 0.42 && bodyScore >= 0.28))) return true; if (sameSource && titleScore >= 0.7 && bodyScore >= 0.35) return true; if (sameSource && titleScore >= 0.82) return true; if (sameSource && bodyScore >= 0.88) return true; return !sameSource && titleScore >= 0.8 && bodyScore >= 0.58; };

const publisherRoot = (hostname: string) => hostname.toLowerCase().replace(/^www\./, '').split('.').slice(-2).join('.');
const samePublisher = (left: string, right: string) => {
  try {
    return publisherRoot(new URL(left).hostname) === publisherRoot(new URL(right).hostname);
  } catch {
    return false;
  }
};

const fetchDocument = async (url: string) => {
  const response = await fetch(url, { headers: requestHeaders, redirect: 'follow', signal: AbortSignal.timeout(8000) });
  if (!response.ok) throw new Error(`fetch_${response.status}`);
  const text = (await response.text()).slice(0, 1500000);
  return { text, url: response.url || url, contentType: response.headers.get('content-type') || '' };
};

const sourceUrlCandidates = (source: SourceMonitorInput) => {
  const candidates: string[] = [];
  const add = (value: string) => { if (value && !candidates.includes(value)) candidates.push(value); };
  try {
    const parsed = new URL(source.url);
    const root = `${parsed.protocol}//${parsed.host}`;
    const host = parsed.hostname.toLowerCase().replace(/^www\./, '');
    if (host === 'rta.af') {
      add(`${root}/fa/feed/`);
      add(`${root}/feed/`);
      add(`${root}/fa/home/`);
      add(source.url);
    } else if (host === 'tolonews.com') {
      add(`${root}/fa/`);
      add(source.url);
      add(`${root}/fa/rss.xml`);
      add(`${root}/rss.xml`);
    } else if (host === 'ariananews.af') {
      add(`${root}/fa/feed`);
      add(`${root}/fa/feed/`);
      add(`${root}/?feed=rss2&lang=fa`);
      add(`${root}/fa/`);
      add(source.url);
      add(`${root}/feed/`);
    } else if (host === 'bbc.com' || host.endsWith('.bbc.com') || host === 'bbc.co.uk' || host.endsWith('.bbc.co.uk')) {
      add('https://feeds.bbci.co.uk/persian/rss.xml');
      add(source.url);
    } else if (host === 'globalvoices.org' || host.endsWith('.globalvoices.org') || host === 'gov.uk' || host.endsWith('.gov.uk') || host === 'travel.state.gov') {
      add(source.url);
    } else {
      add(source.url);
      add(new URL('/feed/', root).toString());
      add(new URL('/rss.xml', root).toString());
      add(new URL('/feed.xml', root).toString());
    }
  } catch {
    add(source.url);
  }
  return candidates;
};

const fetchSourceDocument = async (source: SourceMonitorInput) => {
  let lastError: unknown;
  let fallback: Awaited<ReturnType<typeof fetchDocument>> | undefined;
  let host = '';
  try { host = new URL(source.url).hostname.toLowerCase().replace(/^www\./, ''); } catch { /* keep generic selection */ }
  for (const url of sourceUrlCandidates(source)) {
    try {
      const document = await fetchDocument(url);
      fallback ||= document;
      const usefulRtaDocument = host !== 'rta.af' || /<item\b|<entry\b|href\s*=\s*["'][^"']*\/(?:fa\/)?article\d+/i.test(document.text);
      const usefulArianaDocument = host !== 'ariananews.af' || /<item\b|<entry\b/i.test(document.text) || /href\s*=\s*["'](?:https?:\/\/(?:www\.)?ariananews\.af)?\/fa\/(?:%[0-9a-f]{2}|[\u0600-\u06ff]|[a-z0-9])/i.test(document.text);
      if (usefulRtaDocument && usefulArianaDocument) return document;
    } catch (err) {
      lastError = err;
    }
  }
  if (fallback) return fallback;
  throw lastError instanceof Error ? lastError : new Error('source_unreachable');
};

const tagContent = (block: string, names: string[]) => {
  for (const name of names) {
    const match = block.match(new RegExp(`<${name}\\b[^>]*>([\\s\\S]*?)<\\/${name}>`, 'i'));
    if (match?.[1]) return decodeEntities(match[1]).trim();
  }
  return '';
};

const imageFromMarkup = (html: string, base: string) => {
  const metaTags = html.match(/<meta\b[^>]*>/gi) || [];
  for (const tag of metaTags) {
    const key = (attribute(tag, 'property') || attribute(tag, 'name') || attribute(tag, 'itemprop')).toLowerCase();
    if (['og:image', 'og:image:url', 'twitter:image', 'twitter:image:src', 'image'].includes(key)) {
      const resolved = absoluteUrl(attribute(tag, 'content'), base);
      if (resolved) return resolved;
    }
  }
  const imageLink = (html.match(/<link\b[^>]*(?:rel=["'][^"']*image_src[^"']*["'])[^>]*>/i) || [])[0] || '';
  const linked = absoluteUrl(attribute(imageLink, 'href'), base);
  if (linked) return linked;
  const jsonImage = html.match(/"image"\s*:\s*(?:"([^"]+)"|\[\s*"([^"]+)")/i);
  const structured = absoluteUrl(jsonImage?.[1] || jsonImage?.[2] || '', base);
  if (structured) return structured;
  const imageTags = html.match(/<img\b[^>]*>/gi) || [];
  for (const tag of imageTags) {
    const raw = attribute(tag, 'src') || attribute(tag, 'data-src') || attribute(tag, 'data-lazy-src');
    const srcset = bestSrcsetUrl(attribute(tag, 'srcset') || attribute(tag, 'data-srcset'), base);
    if ((!raw && !srcset) || /(logo|icon|avatar|sprite|emoji|blank|tracking)/i.test(`${raw} ${srcset}`)) continue;
    const resolved = srcset || absoluteUrl(raw, base);
    if (resolved) return resolved;
  }
  return '';
};

const youtubeEmbedFromMarkup = (html: string) => { const match = html.match(/(?:youtube(?:-nocookie)?\.com\/embed\/|youtu\.be\/)([A-Za-z0-9_-]{6,20})/i); return match?.[1] ? `https://www.youtube-nocookie.com/embed/${match[1]}` : ''; };

const descriptionFromMarkup = (html: string) => {
  const metaTags = html.match(/<meta\b[^>]*>/gi) || [];
  for (const tag of metaTags) {
    const key = (attribute(tag, 'property') || attribute(tag, 'name')).toLowerCase();
    if (!['description', 'og:description', 'twitter:description'].includes(key)) continue;
    const value = cleanArticleText(attribute(tag, 'content'));
    if (value) return value;
  }
  const jsonDescription = html.match(/"description"\s*:\s*"((?:\\.|[^"\\])*)"/i);
  if (!jsonDescription?.[1]) return '';
  try { return cleanArticleText(JSON.parse(`"${jsonDescription[1]}"`)); } catch { return cleanArticleText(jsonDescription[1].replace(/\\"/g, '"')); }
};

const articleTextFromMarkup = (html: string) => {
  const jsonBody = html.match(/"articleBody"\s*:\s*"((?:\\.|[^"\\])*)"/i);
  if (jsonBody?.[1]) {
    try { return cleanArticleText(stripHtml(JSON.parse(`"${jsonBody[1]}"`))); } catch { return cleanArticleText(stripHtml(jsonBody[1].replace(/\\"/g, '"'))); }
  }
  const article = html.match(/<article\b[^>]*>([\s\S]*?)<\/article>/i);
  const main = html.match(/<main\b[^>]*>([\s\S]*?)<\/main>/i);
  const selected = article?.[1] || main?.[1] || html;
  const paragraphs = articleParagraphsFromMarkup(selected);
  const description = descriptionFromMarkup(html);
  const fallback = cleanArticleText(stripHtml(selected));
  return [paragraphs, description, fallback].filter(Boolean).sort((left, right) => right.length - left.length)[0] || '';
};

export const fetchArticleText = async (url: string) => {
  const document = await fetchDocument(url);
  return articleTextFromMarkup(document.text);
};


const downloadedImageKey = (media?: RemoteMediaInput) => media?.data ? `img-${hashText(`${media.data.length}:${media.data.slice(0,120000)}:${media.data.slice(-120000)}`)}` : ''; const downloadImage = async (url: string): Promise<RemoteMediaInput | undefined> => {
  if (!url) return undefined;
  try {
    const response = await fetch(url, { headers: { ...requestHeaders, 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/126.0.0.0 Safari/537.36', Accept: 'image/avif,image/webp,image/apng,image/jpeg,image/png,image/gif,*/*;q=0.5', Referer: 'https://www.ariananews.af/' }, redirect: 'follow', signal: AbortSignal.timeout(6000) });
    if (!response.ok) return undefined;
    const mimeType = (response.headers.get('content-type') || '').split(';')[0].trim().toLowerCase();
    if (!/^image\/(jpeg|jpg|png|webp|gif|avif)$/.test(mimeType)) return undefined;
    const bytes = new Uint8Array(await response.arrayBuffer());
    if (!bytes.byteLength) return undefined;
    let name = 'source-image';
    try { name = decodeURIComponent(new URL(response.url || url).pathname.split('/').pop() || name); } catch { /* keep fallback */ }
    return { data: Buffer.from(bytes).toString('base64'), mimeType, name };
  } catch {
    return undefined;
  }
};
const downloadBestImage = async (urls: string[]): Promise<RemoteMediaInput | undefined> => { const unique = [...new Set(urls.map((value) => value.trim()).filter(Boolean))]; if (!unique.length) return undefined; const downloaded = await Promise.all(unique.map((url) => downloadImage(url))); return downloaded.filter((item): item is RemoteMediaInput => Boolean(item?.data)).sort((a, b) => b.data.length - a.data.length)[0]; };

const downloadOpenverseImage = async (url: string): Promise<RemoteMediaInput | undefined> => { if (!url) return undefined; try { const response = await fetch(url, { headers: { 'User-Agent': requestHeaders['User-Agent'], Accept: 'image/avif,image/webp,image/jpeg,image/png,*/*;q=0.5' }, redirect: 'follow', signal: AbortSignal.timeout(3000) }); if (!response.ok) return undefined; const mimeType = (response.headers.get('content-type') || '').split(';')[0].trim().toLowerCase(); if (!/^image\/(jpeg|jpg|png|webp|avif)$/.test(mimeType)) return undefined; const bytes = new Uint8Array(await response.arrayBuffer()); if (!bytes.byteLength) return undefined; let name = 'archive-image'; try { name = decodeURIComponent(new URL(response.url || url).pathname.split('/').pop() || name); } catch {} return { data: Buffer.from(bytes).toString('base64'), mimeType, name: `openverse-public-domain-${name}` }; } catch { return undefined; } }; export const findOpenLicensedImage = async (query: string): Promise<RemoteMediaInput | undefined> => { const normalized = stripHtml(query).replace(/\s+/g, ' ').trim().slice(0, 160); const queryTokens = new Set(identityTokens(normalized).filter((token) => token.length > 2)); if (normalized.length < 2 || !queryTokens.size) return undefined; try { const params = new URLSearchParams({ q: normalized, license: 'cc0,pdm', page_size: '8', mature: 'false' }); const response = await fetch(`https://api.openverse.org/v1/images/?${params.toString()}`, { headers: { 'User-Agent': requestHeaders['User-Agent'], Accept: 'application/json' }, redirect: 'follow', signal: AbortSignal.timeout(2500) }); if (!response.ok) return undefined; type OpenverseResult = { thumbnail?: string; url?: string; license?: string; title?: string; tags?: Array<{ name?: string; accuracy?: number }>; width?: number; height?: number; watermarked?: boolean }; const payload = await response.json() as { results?: OpenverseResult[] }; const ranked = (Array.isArray(payload.results) ? payload.results : []).filter((item) => ['cc0', 'pdm'].includes(String(item.license || '').toLowerCase()) && !item.watermarked && Boolean(item.url || item.thumbnail)).map((item) => { const metadata = `${item.title || ''} ${(Array.isArray(item.tags) ? item.tags : []).map((tag) => tag.name || '').join(' ')}`; const metadataTokens = new Set(identityTokens(metadata)); let common = 0; queryTokens.forEach((token) => { if (metadataTokens.has(token)) common += 1; }); const titleScore = tokenContainment(normalized, item.title || ''); return { item, common, score: common * 2 + titleScore * 4 }; }).filter((entry) => entry.common >= 1 || entry.score >= 1.8).sort((a, b) => b.score - a.score || b.common - a.common); for (const { item } of ranked.slice(0, 2)) { const direct = item.url ? await downloadOpenverseImage(item.url) : undefined; if (direct) return direct; if (item.thumbnail && item.thumbnail !== item.url) { const thumbnail = await downloadOpenverseImage(item.thumbnail); if (thumbnail) return thumbnail; } } return undefined; } catch { return undefined; } }; type DiscoveredLink = { url: string; title: string; imageUrl?: string; videoUrl?: string; excerpt?: string; publishedAt?: number; score?: number; position?: number };

const parseFeed = (xml: string, base: string): DiscoveredLink[] => {
  const blocks = xml.match(/<item\b[\s\S]*?<\/item>/gi) || xml.match(/<entry\b[\s\S]*?<\/entry>/gi) || [];
  const results: DiscoveredLink[] = [];
  for (const [position, block] of blocks.slice(0, 50).entries()) {
    const title = stripHtml(tagContent(block, ['title']));
    const linkTag = (block.match(/<link\b[^>]*>/i) || [])[0] || '';
    const url = absoluteUrl(attribute(linkTag, 'href') || stripHtml(tagContent(block, ['link', 'guid'])), base);
    if (!url || !title) continue;
    let imageUrl = '';
    const mediaTags = block.match(/<(?:media:content|media:thumbnail|enclosure)\b[^>]*>/gi) || [];
    let bestMediaScore = -1;
    for (const tag of mediaTags) {
      const candidate = absoluteUrl(attribute(tag, 'url') || attribute(tag, 'href'), base);
      const type = attribute(tag, 'type');
      if (!candidate || (!/^image\//i.test(type) && !/\.(?:jpe?g|png|webp|gif)(?:\?|$)/i.test(candidate))) continue;
      const width = Number(attribute(tag, 'width')) || 0; const height = Number(attribute(tag, 'height')) || 0; const pixels = width && height ? width * height : Math.max(width, height) ** 2; const roleBonus = /^<media:content\b/i.test(tag) ? 1000000 : /^<enclosure\b/i.test(tag) ? 500000 : 0; const score = pixels + roleBonus;
      if (score > bestMediaScore) { bestMediaScore = score; imageUrl = candidate; }
    }
    const excerptMarkup = tagContent(block, ['content:encoded', 'content', 'description', 'summary']);
    if (!imageUrl) imageUrl = imageFromMarkup(decodeEntities(excerptMarkup), base);
    const publishedAt = parsePublishedDate(stripHtml(tagContent(block, ['pubDate', 'published', 'updated', 'dc:date', 'date'])));
    const excerpt = cleanArticleText(stripHtml(excerptMarkup));
    const score = samePublisher(base, 'https://www.ariananews.af/fa/') && isAfghanistanRelevantText(`${title} ${excerpt}`) ? 2000 : 1000;
    results.push({ url, title, imageUrl, excerpt, publishedAt, score, position });
  }
  return results;
};

const feedUrlFromHtml = (html: string, base: string) => {
  const tags = html.match(/<link\b[^>]*>/gi) || [];
  for (const tag of tags) {
    const rel = attribute(tag, 'rel').toLowerCase();
    const type = attribute(tag, 'type').toLowerCase();
    if ((rel.includes('alternate') || rel.includes('feed')) && /(rss|atom|xml)/.test(type)) {
      const url = absoluteUrl(attribute(tag, 'href'), base);
      if (url) return url;
    }
  }
  return '';
};

const arianaSectionSlugs = new Set(['recent-events', 'latest-news', 'videos', 'world', 'regional', 'programs', 'business', 'sport', 'sports', 'your-views', 'live', 'live-stream', 'health', 'climate-change', 'science-technology', 'most-viewed', 'page', 'رویداد-های-اخیر', 'ویدیوها', 'جهان', 'اخبار-ساحوی', 'برنامه-ها', 'تجارت', 'ورزش', 'دیدگاه-شما', 'نشرات-زنده']);
const isArianaArticlePath = (path: string) => {
  let decodedPath = path;
  try { decodedPath = decodeURIComponent(path); } catch { /* keep encoded path */ }
  const match = decodedPath.replace(/\/+$/, '').match(/^\/fa\/([^/]+)$/i);
  return Boolean(match?.[1] && !arianaSectionSlugs.has(match[1].toLowerCase()));
};
const relativePublishedAt = (value: string) => { const normalized = stripHtml(value).replace(/\s+/g, ' '); const match = normalized.match(/(\d{1,3})\s*(دقیقه|ساعت|روز|هفته|minutes?|hours?|days?|weeks?)\s*(?:ago|پیش|قبل)?/i); if (!match) return undefined; const amount = Number(match[1]); const unit = match[2].toLowerCase(); const multiplier = /دقیقه|minute/.test(unit) ? 60000 : /ساعت|hour/.test(unit) ? 3600000 : /هفته|week/.test(unit) ? 7 * 86400000 : 86400000; return Date.now() - amount * multiplier; };

const arianaRestLinks = async (): Promise<DiscoveredLink[]> => {
  const endpoints = ['https://www.ariananews.af/wp-json/wp/v2/posts?per_page=20&orderby=date&order=desc&lang=fa&_embed=wp:featuredmedia&_fields=date,date_gmt,link,title,excerpt,content,_embedded','https://www.ariananews.af/wp-json/wp/v2/posts?per_page=30&orderby=date&order=desc&_embed=wp:featuredmedia&_fields=date,date_gmt,link,title,excerpt,content,_embedded'];
  for (const endpoint of endpoints) {
    try {
      const document = await fetchDocument(endpoint);
      const posts = JSON.parse(document.text) as Array<{ date?: string; date_gmt?: string; link?: string; title?: { rendered?: string }; excerpt?: { rendered?: string }; content?: { rendered?: string }; _embedded?: { 'wp:featuredmedia'?: Array<{ source_url?: string }> } }>; 
      if (!Array.isArray(posts)) continue;
      const results: DiscoveredLink[] = [];
      for (const [position, post] of posts.entries()) {
        const url = canonicalArticleUrl(post.link || '');
        if (!url) continue;
        try { if (!isArianaArticlePath(new URL(url).pathname)) continue; } catch { continue; }
        const title = stripHtml(post.title?.rendered || '');
        if (title.length < 14) continue;
        const rawDate = post.date_gmt ? `${post.date_gmt}${/[zZ]|[+-]\d\d:?\d\d$/.test(post.date_gmt) ? '' : 'Z'}` : post.date || '';
        const publishedAt = parsePublishedDate(rawDate);
        if (!isFreshPublishedDate(publishedAt)) continue;
        const markup = post.content?.rendered || post.excerpt?.rendered || '';
        const videoUrl = youtubeEmbedFromMarkup(markup);
        const extractedText = cleanArticleText(stripHtml(markup));
        const excerpt = extractedText;
        if (excerpt.length < 45 && !videoUrl) continue;
        const featuredImage = post._embedded?.['wp:featuredmedia']?.[0]?.source_url || ''; const imageUrl = absoluteUrl(featuredImage, url) || imageFromMarkup(markup, url);
        const score = isAfghanistanRelevantText(`${title} ${excerpt}`) ? 2000 : 1000;
        results.push({ url, title, imageUrl, videoUrl, excerpt, publishedAt, score, position });
      }
      if (results.length) return results.sort((a, b) => (b.publishedAt || 0) - (a.publishedAt || 0) || (b.score || 0) - (a.score || 0) || (a.position || 0) - (b.position || 0));
    } catch { /* use RSS/HTML fallback */ }
  }
  return [];
};

const linksFromHtml = (html: string, base: string): DiscoveredLink[] => {
  const results: DiscoveredLink[] = [];
  const seen = new Set<string>();
  const pattern = /<a\b([^>]*?)href\s*=\s*(?:"([^"]+)"|'([^']+)'|([^\s>]+))([^>]*)>([\s\S]*?)<\/a>/gi;
  let match: RegExpExecArray | null;
  let position = 0;
  let host = '';
  try { host = new URL(base).hostname.toLowerCase().replace(/^www\./, ''); } catch { /* keep generic rules */ }
  while ((match = pattern.exec(html)) && results.length < 500) {
    const currentPosition = position++;
    const url = absoluteUrl(match[2] || match[3] || match[4] || '', base);
    const title = stripHtml(match[6] || '');
    if (!url || !samePublisher(url, base) || title.length < 14 || seen.has(url)) continue;
    const parsed = new URL(url);
    const path = parsed.pathname.replace(/\/+$/, '');
    if (!path || path === '/' || /\.(?:jpg|jpeg|png|gif|webp|svg|css|js|pdf|zip|mp4|mp3)$/i.test(path)) continue;
    if (/(login|signup|search|tag|category|author|archive|contact|about|privacy|terms|advert)/i.test(path)) continue;
    const lowerTitle = title.toLowerCase();
    if (/^(خانه|صفحه اصلی|بیشتر|ادامه|ورود|عضویت|home|more|read more)$/.test(lowerTitle)) continue;
    const likelyTolo = /^\/fa\/(?:afghanistan|world|business|sport|arts-culture|health|science-technology|opinion|video)(?:\/|-)/i.test(path);
    const likelyRta = /^\/fa\/article\d+/i.test(path) || /^\/article\d+/i.test(path);
    const likelyAriana = isArianaArticlePath(path);
    if (host === 'tolonews.com' && !likelyTolo) continue;
    if (host === 'rta.af' && !likelyRta) continue;
    if (host === 'ariananews.af' && !likelyAriana) continue;
    const segments = path.split('/').filter(Boolean).length;
    const afghanistanFocus = host === 'ariananews.af' && /(افغانستان|افغان|کابل|امارت اسلامی|طالبان|قندهار|کندهار|هرات|بلخ|ننگرهار|بامیان|بدخشان|کندز|هلمند|غزنی|پکتیا|پکتیکا|خوست|لغمان|نورستان|دایکندی|ارزگان|تخار|سرپل|جوزجان|فاریاب|پروان|کاپیسا|لوگر|میدان وردک|سمنگان|بادغیس)/i.test(title);
    const score = afghanistanFocus ? 2000 : likelyTolo || likelyRta || likelyAriana ? 1000 : segments + (/\/20\d{2}[\/-]/.test(path) ? 5 : 0) + (title.length > 35 ? 2 : 0);
    const context = html.slice(Math.max(0, match.index - 240), Math.min(html.length, pattern.lastIndex + 240));
    const publishedAt = relativePublishedAt(context);
    seen.add(url);
    results.push({ url, title, score, publishedAt, position: currentPosition });
  }
  return results.sort((a, b) => (b.score || 0) - (a.score || 0) || (a.position || 0) - (b.position || 0)).slice(0, 100);
};

export const discoverSourceCandidates = async (source: SourceMonitorInput, existing: ExistingNewsIdentity[], maxItems = 3) => {
  let sourceHost = '';
  try { sourceHost = new URL(source.url).hostname.toLowerCase().replace(/^www\./, ''); } catch { /* keep generic filtering */ }
  const isAriana = sourceHost === 'ariananews.af'; const isVoa = sourceHost === 'voanews.com'; const arianaFreshCutoff = Date.now() - FRESH_NEWS_WINDOW_MS;
  let links: DiscoveredLink[] = isAriana ? await arianaRestLinks() : [];
  const arianaStructured = isAriana && links.length > 0;
  let sourceDocument: Awaited<ReturnType<typeof fetchSourceDocument>> | undefined;
  let looksLikeFeed = false;
  if (!links.length) {
    sourceDocument = await fetchSourceDocument(source);
    looksLikeFeed = /(rss|atom|xml)/i.test(sourceDocument.contentType) || /^\s*<\?xml|<rss\b|<feed\b/i.test(sourceDocument.text);
    links = looksLikeFeed ? parseFeed(sourceDocument.text, sourceDocument.url) : isAriana ? linksFromHtml(sourceDocument.text, sourceDocument.url) : [];
    if (!links.length && !looksLikeFeed && !isAriana) {
      const feedUrl = feedUrlFromHtml(sourceDocument.text, sourceDocument.url);
      if (feedUrl) {
        try { const feed = await fetchDocument(feedUrl); links = parseFeed(feed.text, feed.url); } catch { /* fall back to page links */ }
      }
    }
    if (!links.length) links = linksFromHtml(sourceDocument.text, sourceDocument.url);
  }
  if (isAriana) links = links.filter((item) => { try { return isArianaArticlePath(new URL(item.url).pathname); } catch { return false; } });
  if (!links.length && sourceDocument && sourceHost !== 'ariananews.af') links = [{ url: sourceDocument.url, title: stripHtml((sourceDocument.text.match(/<title\b[^>]*>([\s\S]*?)<\/title>/i) || [])[1] || source.name) }];

  const identityUrl = (value: string) => canonicalArticleUrl(value);
  const unique = new Map<string, DiscoveredLink>();
  links.forEach((item) => { const key = identityUrl(item.url); if (key && !unique.has(key)) unique.set(key, { ...item, url: key }); });
  const chronological = [...unique.values()].sort((a, b) => (b.publishedAt || 0) - (a.publishedAt || 0) || (b.score || 0) - (a.score || 0) || (a.position || 0) - (b.position || 0));
  const discovered = isAriana ? (() => { const articles = chronological.filter((item) => !item.videoUrl); const videos = chronological.filter((item) => Boolean(item.videoUrl)); const reservedArticleSlots = Math.max(1, maxItems - 1); return [...articles.slice(0, reservedArticleSlots), ...videos.slice(0, 1), ...articles.slice(reservedArticleSlots), ...videos.slice(1)]; })() : isVoa ? [...chronological].sort((a, b) => Number(isAfghanistanRelevantText(`${b.title} ${b.excerpt || ''}`)) - Number(isAfghanistanRelevantText(`${a.title} ${a.excerpt || ''}`)) || (b.publishedAt || 0) - (a.publishedAt || 0) || (a.position || 0) - (b.position || 0)) : chronological;
  const discoveryFingerprint = hashText(discovered.slice(0, 20).map((item) => `${item.url}|${item.title}`).join('\n'));
  const existingImported = existing.filter((item) => Boolean(item.originUrl));
  const semanticExisting = existingImported.filter((item) => { const time = identityTime(item); return !time || time >= Date.now() - 36 * 60 * 60 * 1000; }).slice(0, 400);
  const existingUrls = new Set(existingImported.map((item) => item.originUrl ? identityUrl(item.originUrl) : '').filter(Boolean));
  const existingFingerprints = new Set(existingImported.map((item) => item.fingerprint).filter((value): value is string => Boolean(value)));
  const existingTitles = new Set(existingImported.map((item) => identityTitle(item.originTitle || item.title || '')).filter(Boolean));
  const existingImageKeys = new Set(existingImported.flatMap((item) => [mediaIdentity(item.originImageKey || ''), mediaIdentity(item.mediaName || '')]).filter(Boolean));
  const preparedIdentities: ExistingNewsIdentity[] = []; const prepared: SourceCandidate[] = []; let remoteAttempts = 0; const maxRemoteAttempts = Math.max(2, maxItems * 2);

  for (const link of discovered) {
    if (prepared.length >= maxItems) break; if (isAriana && (!link.publishedAt || link.publishedAt < arianaFreshCutoff || link.publishedAt > Date.now() + 10 * 60 * 1000)) continue;
    const linkUrl = identityUrl(link.url);
    const titleKey = identityTitle(link.title);
    if (!linkUrl || existingUrls.has(linkUrl) || (titleKey && existingTitles.has(titleKey))) continue;
    const feedExcerpt = cleanArticleText(stripHtml(link.excerpt || '')).slice(0, 12000);
    if (isAriana && (arianaStructured || looksLikeFeed) && isFreshPublishedDate(link.publishedAt) && (feedExcerpt.length >= 45 || Boolean(link.videoUrl))) {
      const videoTranscript = link.videoUrl ? await fetchYouTubeTranscript(link.videoUrl) : '';
      if (link.videoUrl && videoTranscript.length < 220) continue;
      const candidateText = link.videoUrl ? videoTranscript.slice(0, 12000) : feedExcerpt;
      const contentBasis = link.videoUrl ? 'video_transcript' as const : 'source_text' as const;
      const fingerprint = hashText(identityTitle(candidateText).slice(0, 6000));
      if (existingFingerprints.has(fingerprint)) continue;
      let candidateImageUrl = link.imageUrl || ''; if (source.ingestImage && !candidateImageUrl) { try { const imageDocument = await fetchDocument(linkUrl); candidateImageUrl = imageFromMarkup(imageDocument.text, linkUrl); } catch {} }
      let media = source.ingestImage ? await downloadImage(candidateImageUrl) : undefined;
      if (source.ingestImage && !media) { try { const imageDocument = await fetchDocument(linkUrl); const fallbackImageUrl = imageFromMarkup(imageDocument.text, imageDocument.url); if (fallbackImageUrl && fallbackImageUrl !== candidateImageUrl) media = await downloadImage(fallbackImageUrl); } catch {} }
      const imageKey = downloadedImageKey(media) || mediaIdentity(candidateImageUrl);
      const candidateIdentity: ExistingNewsIdentity = { originUrl: linkUrl, fingerprint, title: link.title, body: candidateText, originName: source.name, originPublishedAt: link.publishedAt, originImageKey: imageKey, originTitle: link.title, originExcerpt: candidateText, mediaName: media?.name || imageKey };
      if (semanticExisting.some((item) => likelyDuplicateImportedStory(candidateIdentity, item)) || preparedIdentities.some((item) => likelyDuplicateImportedStory(candidateIdentity, item))) continue;
      prepared.push({ url: linkUrl, title: link.title, text: candidateText, fingerprint, sourcePublishedAt: link.publishedAt, imageKey, media: imageKey && existingImageKeys.has(mediaIdentity(imageKey)) ? undefined : media, externalVideoUrl: link.videoUrl, contentBasis });
      preparedIdentities.push(candidateIdentity);
      existingFingerprints.add(fingerprint);
      existingUrls.add(linkUrl);
      if (titleKey) existingTitles.add(titleKey); if (imageKey) existingImageKeys.add(mediaIdentity(imageKey));
      continue;
    }
    if (remoteAttempts >= maxRemoteAttempts) break;
    remoteAttempts += 1;
    try {
      const document = sourceDocument && linkUrl === identityUrl(sourceDocument.url) ? sourceDocument : await fetchDocument(linkUrl);
      const sourcePublishedAt = link.publishedAt || publishedDateFromMarkup(document.text);
      if (!isFreshPublishedDate(sourcePublishedAt)) continue;
      const pageText = articleTextFromMarkup(document.text);
      const excerptText = cleanArticleText(stripHtml(link.excerpt || ''));
      let text = (pageText.length >= excerptText.length ? pageText : excerptText).slice(0, 12000);
      let contentBasis: SourceCandidate['contentBasis'] = 'source_text';
      if (link.videoUrl) { const transcript = await fetchYouTubeTranscript(link.videoUrl); if (transcript.length < 220) continue; text = transcript.slice(0, 12000); contentBasis = 'video_transcript'; }
      if (text.length < (isAriana ? 45 : 220)) continue;
      const fingerprint = hashText(identityTitle(text).slice(0, 6000));
      if (existingFingerprints.has(fingerprint)) continue;
      const pageImageUrl = imageFromMarkup(document.text, document.url); const imageUrl = pageImageUrl || link.imageUrl || ''; const media = source.ingestImage ? await downloadBestImage([pageImageUrl, link.imageUrl || '']) : undefined; const imageKey = downloadedImageKey(media) || mediaIdentity(imageUrl); const candidateIdentity: ExistingNewsIdentity = { originUrl: linkUrl, fingerprint, title: link.title, body: text, originName: source.name, originPublishedAt: sourcePublishedAt, originImageKey: imageKey, originTitle: link.title, originExcerpt: text, mediaName: media?.name || imageKey }; if (semanticExisting.some((item) => likelyDuplicateImportedStory(candidateIdentity, item)) || preparedIdentities.some((item) => likelyDuplicateImportedStory(candidateIdentity, item))) continue;
      prepared.push({ url: linkUrl, title: link.title, text, fingerprint, sourcePublishedAt, imageKey, media: imageKey && existingImageKeys.has(mediaIdentity(imageKey)) ? undefined : media, externalVideoUrl: link.videoUrl, contentBasis }); preparedIdentities.push(candidateIdentity);
      existingFingerprints.add(fingerprint);
      existingUrls.add(linkUrl);
      if (titleKey) existingTitles.add(titleKey); if (imageKey) existingImageKeys.add(mediaIdentity(imageKey));
    } catch {
      const sourcePublishedAt = link.publishedAt;
      if (!isFreshPublishedDate(sourcePublishedAt)) continue;
      let text = cleanArticleText(stripHtml(link.excerpt || '')).slice(0, 12000);
      let contentBasis: SourceCandidate['contentBasis'] = 'source_text';
      if (link.videoUrl) { const transcript = await fetchYouTubeTranscript(link.videoUrl); if (transcript.length < 220) continue; text = transcript.slice(0, 12000); contentBasis = 'video_transcript'; }
      if (text.length < (isAriana ? 45 : 220)) continue;
      const fingerprint = hashText(identityTitle(text).slice(0, 6000));
      if (existingFingerprints.has(fingerprint)) continue;
      const media = source.ingestImage ? await downloadImage(link.imageUrl || '') : undefined; const imageKey = downloadedImageKey(media) || mediaIdentity(link.imageUrl || ''); const candidateIdentity: ExistingNewsIdentity = { originUrl: linkUrl, fingerprint, title: link.title, body: text, originName: source.name, originPublishedAt: sourcePublishedAt, originImageKey: imageKey, originTitle: link.title, originExcerpt: text, mediaName: media?.name || imageKey }; if (semanticExisting.some((item) => likelyDuplicateImportedStory(candidateIdentity, item)) || preparedIdentities.some((item) => likelyDuplicateImportedStory(candidateIdentity, item))) continue;
      prepared.push({ url: linkUrl, title: link.title, text, fingerprint, sourcePublishedAt, imageKey, media: imageKey && existingImageKeys.has(mediaIdentity(imageKey)) ? undefined : media, externalVideoUrl: link.videoUrl, contentBasis }); preparedIdentities.push(candidateIdentity);
      existingFingerprints.add(fingerprint);
      existingUrls.add(linkUrl);
      if (titleKey) existingTitles.add(titleKey); if (imageKey) existingImageKeys.add(mediaIdentity(imageKey));
    }
  }

  return { items: prepared, fingerprint: discoveryFingerprint, discoveredCount: discovered.length };
};
