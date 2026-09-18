(function(){
  if(new URLSearchParams(location.search).get('hero-preview')==='1')return;
  function run(){
    var inner=document.querySelector('.heritage-masthead-inner');
    if(!inner||inner.classList.contains('hero-v4293-ready'))return;
    inner.classList.add('hero-v4293-ready');
    var text=inner.querySelector('.heritage-copy');
    var signature=inner.querySelector('.heritage-signature');
    var visual=document.createElement('div');
    visual.className='hero-office-visual';
    var lang=new URLSearchParams(location.search).get('lang')||'fa';
    visual.innerHTML='<div class="hero-office-photo" aria-hidden="true"></div><div class="hero-monitor" aria-hidden="true"><div class="hero-monitor-bezel"><div class="hero-monitor-screen"><iframe title="" tabindex="-1" loading="lazy" src="/?lang='+encodeURIComponent(lang)+'&hero-preview=1"></iframe></div></div><div class="hero-monitor-neck"></div><div class="hero-monitor-base"></div></div>';
    if(text)inner.insertBefore(visual,text);else inner.prepend(visual);
    if(signature)visual.appendChild(signature);
    var b=inner.querySelector('.heritage-bismillah');
    if(b){b.setAttribute('aria-label','بسم الله الرحمن الرحیم');b.setAttribute('title','بسم الله الرحمن الرحیم')}
  }
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',run,{once:true});else run();
  new MutationObserver(function(){requestAnimationFrame(run)}).observe(document.getElementById('app')||document.documentElement,{childList:true,subtree:true});
})();