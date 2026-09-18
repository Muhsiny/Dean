(function(){
  var copy={
    fa:['اعتبار دیجیتال','گواهی قابل راستی‌آزمایی','هر گواهی دارای شناسهٔ مستقل برای بررسی آنلاین است. نمونهٔ زیر صرفاً پیش‌نمایش طراحی است و ادعای صدور رسمی ندارد.','بررسی اعتبار گواهی','گواهی تکمیل دوره','نمونه / SAMPLE','شناسهٔ نمونه','ثبت دیجیتال · قابل بررسی آنلاین'],
    ps:['ډیجیټلي اعتبار','د تایید وړ سند','هر سند خپل ځانګړی پېژند لري چې آنلاین یې تاییدولی شئ. لاندې سند یوازې د ډیزاین نمونه ده.','سند تایید کړئ','د کورس بشپړولو سند','بېلګه / SAMPLE','د بېلګې پېژند','ډیجیټلي ثبت · آنلاین تایید'],
    en:['DIGITAL CREDENTIAL','A verifiable credential','Each credential carries an independent record identifier for online verification. The certificate shown here is a design sample only.','Verify a credential','Certificate of Completion','SAMPLE','Sample record','Digital record · Online verification'],
    fr:['JUSTIFICATIF NUMÉRIQUE','Un certificat vérifiable','Chaque certificat dispose d’un identifiant indépendant pour vérification en ligne. Le document affiché ici est uniquement un exemple de conception.','Vérifier un certificat','Certificat de réussite','EXEMPLE / SAMPLE','Identifiant exemple','Registre numérique · Vérification en ligne']
  };
  function run(){
    var main=document.querySelector('main.home-heritage');
    if(!main||main.querySelector('.certificate-showcase'))return;
    var q=new URLSearchParams(location.search).get('lang');
    var l=copy[q]?q:((document.documentElement.lang||'fa').split('-')[0]);
    var c=copy[l]||copy.fa;
    var s=document.createElement('section');
    s.className='certificate-showcase';
    s.setAttribute('aria-label',c[1]);
    s.innerHTML='<div class="certificate-copy"><span class="eyebrow">'+c[0]+'</span><h2>'+c[1]+'</h2><p>'+c[2]+'</p><a class="primary" href="/verify" data-go="verify">'+c[3]+'</a></div><div class="certificate-stage"><div class="certificate-sheet"><div class="certificate-head"><img src="/assets/beheshti-header-transparent-crop.png" alt="Beheshti University"><span>'+c[5]+'</span></div><div class="certificate-body"><small>'+c[4]+'</small><h3>BEHESHTI UNIVERSITY</h3><p>'+c[7]+'</p></div><div class="certificate-meta"><div class="certificate-record"><span>'+c[6]+'</span><b>BU-SAMPLE-2026-0001</b></div><div class="certificate-qr" aria-hidden="true"></div></div></div></div>';
    main.appendChild(s);
  }
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',run,{once:true});else run();
  new MutationObserver(function(){requestAnimationFrame(run)}).observe(document.getElementById('app')||document.documentElement,{childList:true,subtree:true});
})();