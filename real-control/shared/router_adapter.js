(function(global){
  'use strict';
  function txt(e){return ((e&&(e.innerText||e.textContent||e.value||''))+'').replace(/\s+/g,' ').trim();}
  function lower(e){return txt(e).toLowerCase();}
  function normMac(v){return ((v||'')+'').trim().replace(/-/g,':').toUpperCase();}
  function validMac(v){return /^(?:[0-9A-F]{2}:){5}[0-9A-F]{2}$/.test(normMac(v));}
  function rowText(e){var r=e&&e.closest?e.closest('tr'):null;return lower(r||e&&e.parentElement||e);}
  function forms(){return Array.prototype.slice.call(document.forms||[]);}
  function findSave(form){
    var es=(form||document).querySelectorAll('input,button'); var best=null,score=-999;
    for(var i=0;i<es.length;i++){
      var e=es[i], t=lower(e), n=((e.name||'')+' '+(e.id||'')+' '+(e.getAttribute('onclick')||'')).toLowerCase(), s=0;
      if(t==='save'||t.indexOf('save')>=0)s+=120;
      if(t.indexOf('apply')>=0||n.indexOf('apply')>=0)s+=100;
      if(n.indexOf('save')>=0)s+=80;
      if(t.indexOf('delete')>=0||t.indexOf('cancel')>=0||t.indexOf('reset')>=0||n.indexOf('delete')>=0)s-=300;
      if(s>score){score=s;best=e;}
    }
    return score>0?best:null;
  }
  function clickSave(form){
    var b=findSave(form);
    if(b){try{b.click();return {ok:true,method:'button'};}catch(e){}}
    try{if(form.requestSubmit){form.requestSubmit();return {ok:true,method:'requestSubmit'};}}catch(e){}
    try{form.submit();return {ok:true,method:'submit'};}catch(e){}
    return {ok:false,error:'SAVE_NOT_FOUND'};
  }
  function findActionSelect(form){
    var ss=form.querySelectorAll('select');
    for(var i=0;i<ss.length;i++){
      var opts=Array.prototype.map.call(ss[i].options||[],function(o){return lower(o);}).join(' | ');
      if(opts.indexOf('allow association')>=0&&opts.indexOf('deny association')>=0)return ss[i];
    }
    return null;
  }
  function selectByText(sel,want){
    want=(want||'').toLowerCase();
    for(var i=0;i<sel.options.length;i++){
      if(lower(sel.options[i]).indexOf(want)>=0){sel.selectedIndex=i;sel.value=sel.options[i].value;sel.dispatchEvent(new Event('change',{bubbles:true}));return true;}
    }
    return false;
  }
  function selectedText(sel){try{return lower(sel.options[sel.selectedIndex]);}catch(e){return '';}}
  function radioGroups(form){
    var rs=form.querySelectorAll('input[type=radio]'), g={};
    for(var i=0;i<rs.length;i++){var n=rs[i].name||('__'+i);(g[n]||(g[n]=[])).push(rs[i]);}
    return g;
  }
  function groupText(group){var all='';for(var i=0;i<group.length;i++)all+=' '+rowText(group[i]);return all.toLowerCase();}
  function filterActiveGroup(form){
    var g=radioGroups(form), best=null,score=-1;
    Object.keys(g).forEach(function(k){var t=groupText(g[k]),s=0;if(t.indexOf('activated')>=0)s+=100;if(t.indexOf('deactivated')>=0)s+=100;if(t.indexOf('active')>=0)s+=20;if(s>score){score=s;best=g[k];}});
    return score>=100?best:null;
  }
  function setGroupState(group,on){
    if(!group||!group.length)return false;
    var pick=null;
    for(var i=0;i<group.length;i++){
      var t=(rowText(group[i])+' '+(group[i].value||'')).toLowerCase();
      if(on && t.indexOf('deactivated')<0 && (t.indexOf('activated')>=0||t.indexOf('active')>=0||group[i].value==='1'))pick=group[i];
      if(!on && (t.indexOf('deactivated')>=0||t.indexOf('inactive')>=0||group[i].value==='0'))pick=group[i];
    }
    if(!pick)pick=on?group[0]:group[group.length-1];
    pick.checked=true; try{pick.click();}catch(e){} try{pick.dispatchEvent(new Event('change',{bubbles:true}));}catch(e){}
    return true;
  }
  function groupEnabled(group){
    if(!group)return false;
    for(var i=0;i<group.length;i++)if(group[i].checked){var t=(rowText(group[i])+' '+(group[i].value||'')).toLowerCase();if(t.indexOf('deactivated')>=0||t.indexOf('inactive')>=0||group[i].value==='0')return false;return true;}
    return false;
  }
  function macInputs(form){
    var es=form.querySelectorAll('input[type=text],input:not([type])'),out=[];
    for(var i=0;i<es.length;i++){
      var e=es[i], meta=((e.name||'')+' '+(e.id||'')+' '+rowText(e)).toLowerCase(),v=normMac(e.value);
      if(meta.indexOf('mac')>=0||validMac(v)||v==='00:00:00:00:00:00')out.push(e);
    }
    return out;
  }
  function findWirelessForm(){
    var fs=forms(),best=null,score=-1;
    for(var i=0;i<fs.length;i++){
      var f=fs[i],s=0,a=findActionSelect(f); if(a)s+=300;
      if(lower(f).indexOf('wireless mac address filter')>=0)s+=250;
      var mi=macInputs(f);s+=Math.min(mi.length,20)*8;
      if(s>score){score=s;best=f;}
    }
    return score>=250?best:null;
  }
  function wirelessState(){
    var f=findWirelessForm(); if(!f)return {ok:false,error:'WIRELESS_FILTER_FORM_NOT_FOUND',url:location.href};
    var a=findActionSelect(f),g=filterActiveGroup(f),ins=macInputs(f),ms=[];
    for(var i=0;i<ins.length;i++){var m=normMac(ins[i].value);if(validMac(m)&&m!=='00:00:00:00:00:00')ms.push(m);}
    return {ok:true,url:location.href,formAction:f.action||'',enabled:groupEnabled(g),mode:a?selectedText(a):'',capacity:ins.length,macs:ms};
  }
  function setMacValue(e,v){e.value=v;try{e.dispatchEvent(new Event('input',{bubbles:true}));}catch(x){}try{e.dispatchEvent(new Event('change',{bubbles:true}));}catch(x){}}
  function prepareBlock(mac){
    mac=normMac(mac);if(!validMac(mac))return {ok:false,error:'INVALID_MAC'};
    var f=findWirelessForm();if(!f)return {ok:false,error:'WIRELESS_FILTER_FORM_NOT_FOUND'};
    var a=findActionSelect(f),g=filterActiveGroup(f),ins=macInputs(f);if(!a||!g||!ins.length)return {ok:false,error:'WIRELESS_FILTER_CONTROLS_INCOMPLETE'};
    setGroupState(g,true);var mode=selectedText(a);
    if(mode.indexOf('allow association')>=0){
      var found=false;for(var i=0;i<ins.length;i++)if(normMac(ins[i].value)===mac){setMacValue(ins[i],'00:00:00:00:00:00');found=true;}
      return {ok:true,needsSave:found,mode:'allow',effect:'block',message:found?'removed-from-allow-list':'already-blocked'};
    }
    if(mode.indexOf('deny association')<0){if(!selectByText(a,'deny association'))return {ok:false,error:'DENY_MODE_NOT_AVAILABLE'};}
    for(var j=0;j<ins.length;j++)if(normMac(ins[j].value)===mac)return {ok:true,needsSave:false,mode:'deny',effect:'block',message:'already-blocked'};
    for(var k=0;k<ins.length;k++){var v=normMac(ins[k].value);if(!v||v==='00:00:00:00:00:00'){setMacValue(ins[k],mac);return {ok:true,needsSave:true,mode:'deny',effect:'block',message:'ready'};}}
    return {ok:false,error:'NO_EMPTY_MAC_SLOT'};
  }
  function prepareUnblock(mac){
    mac=normMac(mac);if(!validMac(mac))return {ok:false,error:'INVALID_MAC'};
    var f=findWirelessForm();if(!f)return {ok:false,error:'WIRELESS_FILTER_FORM_NOT_FOUND'};
    var a=findActionSelect(f),g=filterActiveGroup(f),ins=macInputs(f);if(!a||!g||!ins.length)return {ok:false,error:'WIRELESS_FILTER_CONTROLS_INCOMPLETE'};
    setGroupState(g,true);var mode=selectedText(a);
    if(mode.indexOf('allow association')>=0){
      for(var i=0;i<ins.length;i++)if(normMac(ins[i].value)===mac)return {ok:true,needsSave:false,mode:'allow',effect:'unblock',message:'already-allowed'};
      for(var j=0;j<ins.length;j++){var v=normMac(ins[j].value);if(!v||v==='00:00:00:00:00:00'){setMacValue(ins[j],mac);return {ok:true,needsSave:true,mode:'allow',effect:'unblock',message:'ready'};}}
      return {ok:false,error:'NO_EMPTY_MAC_SLOT'};
    }
    if(mode.indexOf('deny association')<0){if(!selectByText(a,'deny association'))return {ok:false,error:'DENY_MODE_NOT_AVAILABLE'};}
    var changed=false;for(var k=0;k<ins.length;k++)if(normMac(ins[k].value)===mac){setMacValue(ins[k],'00:00:00:00:00:00');changed=true;}
    return {ok:true,needsSave:changed,mode:'deny',effect:'unblock',message:changed?'ready':'already-allowed'};
  }
  function prepareAllowList(macs){
    var wanted=(macs||[]).map(normMac).filter(validMac);wanted=Array.from(new Set(wanted));
    var f=findWirelessForm();if(!f)return {ok:false,error:'WIRELESS_FILTER_FORM_NOT_FOUND'};
    var a=findActionSelect(f),g=filterActiveGroup(f),ins=macInputs(f);if(!a||!g||!ins.length)return {ok:false,error:'WIRELESS_FILTER_CONTROLS_INCOMPLETE'};
    if(wanted.length>ins.length)return {ok:false,error:'ALLOW_LIST_CAPACITY',capacity:ins.length,requested:wanted.length};
    setGroupState(g,true);if(!selectByText(a,'allow association'))return {ok:false,error:'ALLOW_MODE_NOT_AVAILABLE'};
    for(var i=0;i<ins.length;i++)setMacValue(ins[i],wanted[i]||'00:00:00:00:00:00');
    return {ok:true,needsSave:true,capacity:ins.length,count:wanted.length};
  }
  function prepareFilterOff(){var f=findWirelessForm();if(!f)return {ok:false,error:'WIRELESS_FILTER_FORM_NOT_FOUND'};var g=filterActiveGroup(f);if(!g)return {ok:false,error:'ACTIVE_CONTROL_NOT_FOUND'};setGroupState(g,false);return {ok:true,needsSave:true};}
  function saveWireless(){var f=findWirelessForm();if(!f)return {ok:false,error:'WIRELESS_FILTER_FORM_NOT_FOUND'};return clickSave(f);}
  function isBlocked(mac){
    mac=normMac(mac);var s=wirelessState();if(!s.ok||!s.enabled)return false;
    if(s.mode.indexOf('allow association')>=0)return s.macs.indexOf(mac)<0;
    if(s.mode.indexOf('deny association')>=0)return s.macs.indexOf(mac)>=0;
    return false;
  }
  function scanClients(routerMac){
    routerMac=normMac(routerMac||'');var re=/\b(?:[0-9a-f]{2}[:-]){5}[0-9a-f]{2}\b/ig,best=[],bestScore=-1;
    var tables=document.querySelectorAll('table');
    for(var i=0;i<tables.length;i++){
      var rows=tables[i].querySelectorAll('tr'),arr=[];
      for(var r=0;r<rows.length;r++){
        var line=txt(rows[r]),ms=line.match(re)||[];
        if(ms.length){var m=normMac(ms[0]);if(validMac(m)&&m!==routerMac)arr.push({mac:m,row:line});}
      }
      var near='';var p=tables[i];for(var d=0;d<4&&p;d++,p=p.parentElement)near+=' '+lower(p);
      var score=arr.length*20+(near.indexOf('current connected wireless clients')>=0?300:0);
      if(score>bestScore){bestScore=score;best=arr;}
    }
    var out=[],seen={};for(var j=0;j<best.length;j++){if(!seen[best[j].mac]){seen[best[j].mac]=1;out.push(best[j]);}}
    return {ok:true,url:location.href,clients:out};
  }
  function scanStats(){
    var t=txt(document.body), nums={};
    function capture(label,key){var re=new RegExp(label+'\\s*[:：]?\\s*([0-9,]+)','i'),m=t.match(re);if(m)nums[key]=parseInt(m[1].replace(/,/g,''),10);}
    capture('Receive\\s+(?:total\\s+)?Bytes','rxBytes');capture('Transmit\\s+(?:total\\s+)?Bytes','txBytes');capture('Rx\\s+Bytes','rxBytes');capture('Tx\\s+Bytes','txBytes');
    return {ok:true,url:location.href,rxBytes:nums.rxBytes==null?null:nums.rxBytes,txBytes:nums.txBytes==null?null:nums.txBytes,text:t.slice(0,12000)};
  }
  function guestForm(){var fs=forms(),best=null,score=-1;for(var i=0;i<fs.length;i++){var t=lower(fs[i]),s=0;if(t.indexOf('guest network')>=0)s+=300;if(t.indexOf('upstream')>=0||t.indexOf('downstream')>=0)s+=50;if(s>score){score=s;best=fs[i];}}return score>=300?best:null;}
  function scanGuest(){
    var f=guestForm();if(!f)return {ok:false,error:'GUEST_FORM_NOT_FOUND'};var fields=[];var es=f.querySelectorAll('input,select');
    for(var i=0;i<es.length;i++){fields.push({name:es[i].name||'',type:es[i].type||es[i].tagName,value:es[i].value||'',checked:!!es[i].checked,row:rowText(es[i])});}
    return {ok:true,url:location.href,formAction:f.action||'',fields:fields};
  }
  function setGuestEnabled(on){
    var f=guestForm();if(!f)return {ok:false,error:'GUEST_FORM_NOT_FOUND'};var g=radioGroups(f),pickGroup=null;
    Object.keys(g).some(function(k){var t=groupText(g[k]);if(t.indexOf('guest')>=0&&(t.indexOf('enable')>=0||t.indexOf('active')>=0)){pickGroup=g[k];return true;}return false;});
    if(!pickGroup)return {ok:false,error:'GUEST_ENABLE_CONTROL_NOT_FOUND'};setGroupState(pickGroup,on);return {ok:true,needsSave:true};
  }
  function setGuestBandwidth(up,down){
    var f=guestForm();if(!f)return {ok:false,error:'GUEST_FORM_NOT_FOUND'};var es=f.querySelectorAll('input[type=text],input[type=number]'),u=null,d=null;
    for(var i=0;i<es.length;i++){var m=((es[i].name||'')+' '+(es[i].id||'')+' '+rowText(es[i])).toLowerCase();if(!u&&m.indexOf('upstream')>=0)u=es[i];if(!d&&m.indexOf('downstream')>=0)d=es[i];}
    if(!u&&!d)return {ok:false,error:'GUEST_BANDWIDTH_FIELDS_NOT_FOUND'};if(u&&up!=null)u.value=String(up);if(d&&down!=null)d.value=String(down);return {ok:true,needsSave:true,upstream:!!u,downstream:!!d};
  }
  function saveGuest(){var f=guestForm();if(!f)return {ok:false,error:'GUEST_FORM_NOT_FOUND'};return clickSave(f);}
  global.RouterAdapter={version:'1.0.0',normMac:normMac,scanClients:scanClients,wirelessState:wirelessState,prepareBlock:prepareBlock,prepareUnblock:prepareUnblock,prepareAllowList:prepareAllowList,prepareFilterOff:prepareFilterOff,saveWireless:saveWireless,isBlocked:isBlocked,scanStats:scanStats,scanGuest:scanGuest,setGuestEnabled:setGuestEnabled,setGuestBandwidth:setGuestBandwidth,saveGuest:saveGuest};
})(window);
