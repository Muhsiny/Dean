(function(g){
'use strict';
if(!g.RouterAdapter) return;
var R=g.RouterAdapter;
function A(x){return Array.prototype.slice.call(x||[])}
function T(e){return ((e&&(e.innerText||e.textContent||e.value||''))+'').replace(/\s+/g,' ').trim()}
function L(e){return T(e).toLowerCase()}
function M(v){return ((v||'')+'').trim().replace(/-/g,':').toUpperCase()}
function VM(v){return /^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$/.test(M(v))}
function row(e){return e&&e.closest?e.closest('tr'):null}
function meta(e){return (((e&&e.name)||'')+' '+((e&&e.id)||'')+' '+L(row(e)||e&&e.parentElement||e)).toLowerCase()}
function sels(scope){return A((scope||document).querySelectorAll('select'))}
function radios(scope){var out={},rs=(scope||document).querySelectorAll('input[type=radio]');for(var i=0;i<rs.length;i++){var n=rs[i].name||('__'+i);(out[n]||(out[n]=[])).push(rs[i])}return out}
function opts(s){return A(s&&s.options).map(L).join(' | ')}
function st(s){try{return L(s.options[s.selectedIndex])}catch(e){return ''}}
function pickSel(scope,need){var ss=sels(scope);for(var i=0;i<ss.length;i++){var o=opts(ss[i]),ok=true;for(var j=0;j<need.length;j++)if(o.indexOf(need[j])<0)ok=false;if(ok)return ss[i]}return null}
function pickRadio(scope,need){var gs=radios(scope),ks=Object.keys(gs);for(var i=0;i<ks.length;i++){var t=gs[ks[i]].map(function(e){return L(row(e)||e.parentElement||e)+' '+(e.value||'')}).join(' ');var ok=true;for(var j=0;j<need.length;j++)if(t.indexOf(need[j])<0)ok=false;if(ok)return gs[ks[i]]}return null}
function setSel(s,wants){if(!s)return false;if(typeof wants==='string')wants=[wants];for(var w=0;w<wants.length;w++)for(var i=0;i<s.options.length;i++)if(L(s.options[i]).indexOf(String(wants[w]).toLowerCase())>=0){s.selectedIndex=i;s.value=s.options[i].value;try{s.dispatchEvent(new Event('change',{bubbles:true}))}catch(e){}return true}return false}
function setRadio(gr,wants){if(!gr)return false;if(typeof wants==='string')wants=[wants];for(var w=0;w<wants.length;w++)for(var i=0;i<gr.length;i++){var t=(L(row(gr[i])||gr[i].parentElement||gr[i])+' '+(gr[i].value||'')).toLowerCase();if(t.indexOf(String(wants[w]).toLowerCase())>=0){gr[i].checked=true;try{gr[i].dispatchEvent(new Event('change',{bubbles:true}))}catch(e){}return true}}return false}
function save(form){if(!form)return {ok:false,error:'FORM_NOT_FOUND'};var es=form.querySelectorAll('input,button'),best=null;for(var i=0;i<es.length;i++){var t=L(es[i]),n=((es[i].name||'')+' '+(es[i].id||'')+' '+(es[i].getAttribute('onclick')||'')).toLowerCase();if((t.indexOf('save')>=0||t.indexOf('apply')>=0||n.indexOf('save')>=0)&&!/(delete|reset|reboot|upgrade|cancel)/.test(t+' '+n)){best=es[i];break}}if(!best)return {ok:false,error:'SAFE_SAVE_NOT_FOUND'};try{best.click();return {ok:true,method:'button',action:form.action||''}}catch(e){return {ok:false,error:'SAVE_CLICK_FAILED'}}}
function setV(e,v){if(!e)return false;e.value=v;try{e.dispatchEvent(new Event('input',{bubbles:true}))}catch(x){}try{e.dispatchEvent(new Event('change',{bubbles:true}))}catch(x){}return true}
function samePath(u){try{var x=new URL(u,location.href);return x.origin===location.origin?x.pathname+x.search:null}catch(e){return null}}

/* Legacy TD-W8961N/TrendChip wireless form: /Forms/home_wlan_1, 8 ACL slots. */
function wc(){var fs=A(document.forms),best=null,score=-1;for(var i=0;i<fs.length;i++){var f=fs[i],all=L(f),act=(f.action||'').toLowerCase(),s=0;if(act.indexOf('home_wlan_1')>=0)s+=600;if(all.indexOf('wireless mac address filter')>=0)s+=450;var action=pickSel(f,['allow association','deny association']);if(action)s+=400;var active=pickRadio(f,['activated','deactivated']);if(active)s+=250;var es=f.querySelectorAll('input[type=text],input:not([type])'),mis=[];for(var j=0;j<es.length;j++){var mm=meta(es[j]),v=M(es[j].value);if((/mac\s*address|macaddr|acl/.test(mm)||VM(v)||v==='00:00:00:00:00:00')&&!/(wds|bssid|spoof)/.test(mm))mis.push(es[j])}s+=Math.min(mis.length,8)*25;if(s>score){score=s;best={form:f,action:action,active:active,macs:mis}}}return score>=500?best:null}
function wstate(){var c=wc();if(!c)return R._v2_wirelessState?R._v2_wirelessState():{ok:false,error:'WIRELESS_FILTER_NOT_FOUND'};var ms=[];for(var i=0;i<c.macs.length;i++){var m=M(c.macs[i].value);if(VM(m)&&m!=='00:00:00:00:00:00')ms.push(m)}var at='';if(c.active)for(var j=0;j<c.active.length;j++)if(c.active[j].checked)at=(L(row(c.active[j])||c.active[j].parentElement||c.active[j])+' '+(c.active[j].value||'')).toLowerCase();return {ok:!!c.action&&!!c.active&&c.macs.length>0,url:location.href,formAction:c.form.action||'',enabled:at.indexOf('deactivated')<0&&at.indexOf('inactive')<0&&at.indexOf('no')<0&&at!=='0',mode:st(c.action),capacity:c.macs.length,macs:ms,wps:R.wpsState?R.wpsState():{supported:false},engine:'legacy-v3'}}
function prepWifi(mac,block){
  mac=M(mac);
  if(!VM(mac)) return {ok:false,error:'INVALID_MAC'};
  var c=wc();
  if(!c||!c.action||!c.active||!c.macs.length) return {ok:false,error:'WIRELESS_FILTER_CONTROLS_INCOMPLETE'};
  setRadio(c.active,['activated','yes','on']);
  var mode=st(c.action);
  if(mode.indexOf('allow association')>=0){
    if(block){
      var found=false;
      for(var i=0;i<c.macs.length;i++) if(M(c.macs[i].value)===mac){setV(c.macs[i],'00:00:00:00:00:00');found=true;}
      return {ok:true,needsSave:found,mode:'allow'};
    }
    for(var a=0;a<c.macs.length;a++) if(M(c.macs[a].value)===mac) return {ok:true,needsSave:false,mode:'allow'};
    for(var b=0;b<c.macs.length;b++){
      var x=M(c.macs[b].value);
      if(!x||x==='00:00:00:00:00:00'){setV(c.macs[b],mac);return {ok:true,needsSave:true,mode:'allow'};}
    }
    return {ok:false,error:'NO_EMPTY_MAC_SLOT'};
  }
  if(mode.indexOf('deny association')<0 && !setSel(c.action,['deny association'])) return {ok:false,error:'DENY_MODE_NOT_AVAILABLE'};
  if(block){
    for(var d=0;d<c.macs.length;d++) if(M(c.macs[d].value)===mac) return {ok:true,needsSave:false,mode:'deny'};
    for(var e=0;e<c.macs.length;e++){
      var y=M(c.macs[e].value);
      if(!y||y==='00:00:00:00:00:00'){setV(c.macs[e],mac);return {ok:true,needsSave:true,mode:'deny'};}
    }
    return {ok:false,error:'NO_EMPTY_MAC_SLOT'};
  }
  var changed=false;
  for(var f=0;f<c.macs.length;f++) if(M(c.macs[f].value)===mac){setV(c.macs[f],'00:00:00:00:00:00');changed=true;}
  return {ok:true,needsSave:changed,mode:'deny'};
}
function allowList(macs){var c=wc(),wanted=(macs||[]).map(M).filter(VM);wanted=Array.from(new Set(wanted));if(!c||!c.action||!c.active||!c.macs.length)return {ok:false,error:'WIRELESS_FILTER_CONTROLS_INCOMPLETE'};if(wanted.length>c.macs.length)return {ok:false,error:'ALLOW_LIST_CAPACITY',capacity:c.macs.length};setRadio(c.active,['activated','yes','on']);if(!setSel(c.action,['allow association']))return {ok:false,error:'ALLOW_MODE_NOT_AVAILABLE'};for(var i=0;i<c.macs.length;i++)setV(c.macs[i],wanted[i]||'00:00:00:00:00:00');return {ok:true,needsSave:true,capacity:c.macs.length,count:wanted.length}}

/* Access Management IP/MAC filter. */
function ac(){var fs=A(document.forms),best=null,score=-1;for(var i=0;i<fs.length;i++){var f=fs[i],all=L(f),act=(f.action||'').toLowerCase(),s=0;if(act.indexOf('filter')>=0)s+=450;if(all.indexOf('ip/mac filter')>=0)s+=350;if(all.indexOf('rule unmatched')>=0)s+=150;var rt=pickSel(f,['mac','ip']);if(rt)s+=250;var es=f.querySelectorAll('input[type=text],input:not([type])'),mi=null;for(var j=0;j<es.length;j++){var mm=meta(es[j]);if(mm.indexOf('mac address')>=0&&!/(source|destination)/.test(mm)){mi=es[j];break}}if(mi)s+=250;if(s>score){score=s;best={form:f,ruleType:rt,mac:mi}}}if(score<500)return null;var f=best.form;best.filterType=pickSel(f,['ip/mac filter','url filter']);best.setIndex=null;best.ruleIndex=null;var ss=sels(f);for(var k=0;k<ss.length;k++){var mm=meta(ss[k]);if(mm.indexOf('set index')>=0)best.setIndex=ss[k];if(mm.indexOf('rule index')>=0)best.ruleIndex=ss[k]}best.direction=pickSel(f,['outgoing','incoming']);best.active=pickRadio(f,['yes','no'])||pickSel(f,['yes','no']);best.unmatched=pickSel(f,['next','forward']);best.iface=null;for(var q=0;q<ss.length;q++)if(meta(ss[q]).indexOf('interface')>=0)best.iface=ss[q];return best}
function setChoice(c,wants){if(!c)return false;return c.tagName&&c.tagName.toLowerCase()==='select'?setSel(c,wants):Array.isArray(c)?setRadio(c,wants):false}
function accessState(){var c=ac();if(!c)return {ok:false,error:'ACCESS_MAC_FILTER_NOT_FOUND',url:location.href};var rows=[],trs=document.querySelectorAll('tr'),re=/\b(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}\b/i;for(var i=0;i<trs.length;i++){var t=T(trs[i]),m=t.match(re);if(m)rows.push({mac:M(m[0]),text:t})}return {ok:!!c.ruleType&&!!c.mac&&!!c.active,url:location.href,formAction:c.form.action||'',rules:rows,engine:'legacy-v3'}}
function accessPrep(mac,block){mac=M(mac);if(!VM(mac))return {ok:false,error:'INVALID_MAC'};var c=ac();if(!c||!c.ruleType||!c.mac||!c.active)return {ok:false,error:'ACCESS_MAC_FILTER_INCOMPLETE'};if(c.filterType)setSel(c.filterType,['ip/mac filter']);setSel(c.ruleType,['mac']);if(c.iface)setSel(c.iface,['pvc0','internet','wan']);if(c.direction)setSel(c.direction,['outgoing','both']);if(c.unmatched)setSel(c.unmatched,['next']);if(block){if(!setChoice(c.active,['yes','activated','on']))return {ok:false,error:'ACTIVE_YES_NOT_AVAILABLE'};setV(c.mac,mac);return {ok:true,needsSave:true,mac:mac}}if(!setChoice(c.active,['no','deactivated','off']))return {ok:false,error:'ACTIVE_NO_NOT_AVAILABLE'};setV(c.mac,mac);return {ok:true,needsSave:true,mac:mac}}
function isNetBlocked(mac){mac=M(mac);var trs=document.querySelectorAll('tr'),re=/\b(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}\b/i;for(var i=0;i<trs.length;i++){var t=(' '+T(trs[i])+' '),m=t.match(re);if(m&&M(m[0])===mac&&!/(\bNo\b|Deactivated|Inactive|Disabled)/i.test(t))return true}var c=ac();if(c&&c.mac&&M(c.mac.value)===mac){var a='';if(Array.isArray(c.active)){for(var j=0;j<c.active.length;j++)if(c.active[j].checked)a=(L(row(c.active[j])||c.active[j].parentElement||c.active[j])+' '+c.active[j].value)}else a=st(c.active);return !/(no|deactivated|inactive|off|disable)/i.test(a)}return false}

/* Navigation fallbacks for this TrendChip family. */
var oldDiscover=R.discoverNavigation;
function discover(){var out=oldDiscover?oldDiscover():{ok:false,routes:{}};out=out||{routes:{}};out.routes=out.routes||{};var p=(location.pathname||'').toLowerCase(),links=document.querySelectorAll('a');for(var i=0;i<links.length;i++){var label=L(links[i]);var h=links[i].getAttribute('href')||'',oc=links[i].getAttribute('onclick')||'',m=oc.match(/["']([^"']+\.htm[l]?)["']/i),path=samePath(m?m[1]:h);if(label&&path)out.routes[label]=path}if(p.indexOf('navigation-basic')>=0&&!Object.keys(out.routes).some(function(k){return k.indexOf('wireless')>=0}))out.routes['wireless']='/basic/home_wlan.htm';if(p.indexOf('navigation-advanced')>=0&&!Object.keys(out.routes).some(function(k){return k.indexOf('qos')>=0}))out.routes['qos']='/advanced/adv_qos.htm';out.ok=Object.keys(out.routes).length>0;out.engine='v3';return out}

R._v2_wirelessState=R.wirelessState;
R.discoverNavigation=discover;
R.wirelessState=wstate;
R.prepareBlock=function(mac){return prepWifi(mac,true)};
R.prepareUnblock=function(mac){return prepWifi(mac,false)};
R.prepareAllowList=allowList;
R.prepareFilterOff=function(){var c=wc();if(!c||!c.active)return {ok:false,error:'WIRELESS_FILTER_CONTROLS_INCOMPLETE'};return setRadio(c.active,['deactivated','no','off'])?{ok:true,needsSave:true}:{ok:false,error:'DEACTIVATE_NOT_AVAILABLE'}};
R.saveWireless=function(){var c=wc();return c?save(c.form):{ok:false,error:'WIRELESS_FILTER_NOT_FOUND'}};
R.isBlocked=function(mac){var s=wstate();mac=M(mac);if(!s.ok||!s.enabled)return false;if(s.mode.indexOf('allow association')>=0)return s.macs.indexOf(mac)<0;if(s.mode.indexOf('deny association')>=0)return s.macs.indexOf(mac)>=0;return false};
R.accessState=accessState;
R.prepareInternetBlock=function(mac){return accessPrep(mac,true)};
R.prepareInternetUnblock=function(mac){return accessPrep(mac,false)};
R.saveAccess=function(){var c=ac();return c?save(c.form):{ok:false,error:'ACCESS_MAC_FILTER_NOT_FOUND'}};
R.isInternetBlocked=isNetBlocked;
R.version='3.0.0';
})(window);
