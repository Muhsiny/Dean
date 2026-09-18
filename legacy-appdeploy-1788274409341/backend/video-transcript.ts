const youtubeHeaders = {
  'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36',
  'Accept-Language': 'fa-AF,fa;q=0.9,ps;q=0.8,en;q=0.7'
};

const decodeEntities = (value: string) => value.replace(/&nbsp;/gi, ' ').replace(/&amp;/gi, '&').replace(/&quot;/gi, '"').replace(/&#39;|&apos;/gi, "'").replace(/&lt;/gi, '<').replace(/&gt;/gi, '>').replace(/&#(\d+);/g, (_, code: string) => String.fromCharCode(Number(code)));
const cleanTranscript = (value: string) => decodeEntities(value).replace(/\u200b/g, '').replace(/\s+/g, ' ').trim();
const videoIdFromUrl = (value: string) => { const match = String(value || '').match(/(?:youtube(?:-nocookie)?\.com\/embed\/|youtube\.com\/watch\?v=|youtu\.be\/)([A-Za-z0-9_-]{6,20})/i); return match?.[1] || ''; };
const jsonArrayAfterKey = (text: string, key: string) => { const keyIndex = text.indexOf(key); if (keyIndex < 0) return ''; const start = text.indexOf('[', keyIndex + key.length); if (start < 0) return ''; let depth = 0; let quoted = false; let escaped = false; for (let index = start; index < text.length; index += 1) { const char = text[index]; if (quoted) { if (escaped) escaped = false; else if (char === '\\') escaped = true; else if (char === '"') quoted = false; continue; } if (char === '"') { quoted = true; continue; } if (char === '[') depth += 1; else if (char === ']') { depth -= 1; if (depth === 0) return text.slice(start, index + 1); } } return ''; };

type CaptionTrack = { baseUrl?: string; languageCode?: string; kind?: string; name?: { simpleText?: string; runs?: Array<{ text?: string }> } };
const trackLabel = (track: CaptionTrack) => track.name?.simpleText || track.name?.runs?.map((run) => run.text || '').join(' ') || '';
const trackScore = (track: CaptionTrack) => { const language = String(track.languageCode || '').toLowerCase(); const label = trackLabel(track).toLowerCase(); const languageScore = language.startsWith('fa') ? 50 : language.startsWith('ps') ? 45 : language.startsWith('en') ? 35 : 10; const manualScore = track.kind === 'asr' ? 0 : 8; const labelScore = /persian|farsi|فارسی/.test(label) ? 5 : /pashto|پښتو/.test(label) ? 4 : /english/.test(label) ? 3 : 0; return languageScore + manualScore + labelScore; };
const transcriptFromJson3 = (payload: unknown) => { const data = payload as { events?: Array<{ segs?: Array<{ utf8?: string }> }> }; const parts: string[] = []; for (const event of Array.isArray(data.events) ? data.events : []) for (const segment of Array.isArray(event.segs) ? event.segs : []) { const text = cleanTranscript(segment.utf8 || ''); if (text && text !== '[Music]' && text !== '[Applause]') parts.push(text); } return cleanTranscript(parts.join(' ')); };
const transcriptFromXml = (xml: string) => cleanTranscript((xml.match(/<text\b[^>]*>[\s\S]*?<\/text>/gi) || []).map((block) => cleanTranscript(block.replace(/^<text\b[^>]*>|<\/text>$/gi, ''))).join(' '));

export const fetchYouTubeTranscript = async (videoUrl: string) => {
  const videoId = videoIdFromUrl(videoUrl);
  if (!videoId) return '';
  const pages = [`https://www.youtube.com/watch?v=${videoId}`, `https://www.youtube.com/embed/${videoId}`];
  let tracks: CaptionTrack[] = [];
  for (const page of pages) {
    try {
      const response = await fetch(page, { headers: youtubeHeaders, redirect: 'follow', signal: AbortSignal.timeout(5000) });
      if (!response.ok) continue;
      const html = await response.text();
      const arrayText = jsonArrayAfterKey(html, '"captionTracks"');
      if (!arrayText) continue;
      const parsed = JSON.parse(arrayText) as CaptionTrack[];
      if (Array.isArray(parsed) && parsed.length) { tracks = parsed; break; }
    } catch { /* try next player page */ }
  }
  if (!tracks.length) return '';
  tracks.sort((a, b) => trackScore(b) - trackScore(a));
  for (const track of tracks.slice(0, 4)) {
    if (!track.baseUrl) continue;
    try {
      const captionUrl = new URL(track.baseUrl);
      captionUrl.searchParams.set('fmt', 'json3');
      const response = await fetch(captionUrl.toString(), { headers: youtubeHeaders, redirect: 'follow', signal: AbortSignal.timeout(5000) });
      if (response.ok) {
        const contentType = response.headers.get('content-type') || '';
        if (/json/i.test(contentType)) {
          const transcript = transcriptFromJson3(await response.json());
          if (transcript.length >= 220) return transcript.slice(0, 16000);
        } else {
          const transcript = transcriptFromXml(await response.text());
          if (transcript.length >= 220) return transcript.slice(0, 16000);
        }
      }
    } catch { /* try next track */ }
    try {
      const response = await fetch(track.baseUrl, { headers: youtubeHeaders, redirect: 'follow', signal: AbortSignal.timeout(5000) });
      if (!response.ok) continue;
      const transcript = transcriptFromXml(await response.text());
      if (transcript.length >= 220) return transcript.slice(0, 16000);
    } catch { /* try next track */ }
  }
  return '';
};
