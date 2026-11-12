/* GLASS Control Centre: right-side sliding panel. Every control is real (AudioManager, system state, system settings). */
(function(){
'use strict';
var G=window.Glass, $=G.$, st=G.state;
var cc=$('cc'), inner=$('ccinner'), body=$('ccbody');
var built=false, els={}, order=[], idx=0, timer=null, from='hdr', vol=null;

/* sections and items, in D-pad order (top to bottom) */
var SPEC=[
  {sec:'Connectivity',items:[
    {id:'wifi',icon:'wifi',t:'Wi-Fi',chev:true},
    {id:'bt',icon:'bluetooth',t:'Bluetooth',chev:true}]},
  {sec:'Audio',items:[
    {id:'vol',icon:'volume',t:'Volume',bar:true},
    {id:'mute',icon:'volumeMuted',t:'Mute'}]},
  {sec:'Appearance',items:[
    {id:'theme',icon:'themeLight',t:'Appearance',sw:true}]},
  {sec:'Device',items:[
    {id:'dev',icon:'device',t:'This box',chev:true},
    {id:'set',icon:'settings',t:'Android Settings',chev:true}]}
];
function build(){
  if(built) return; built=true;
  SPEC.forEach(function(s){
    var h=document.createElement('div'); h.className='ccsec'; h.textContent=s.sec; inner.appendChild(h);
    s.items.forEach(function(it){
      var d=document.createElement('div'); d.className='cci'; d.id='cc_'+it.id;
      var ico=document.createElement('div'); ico.className='ico';
      var tx=document.createElement('div'); tx.className='txt';
      tx.innerHTML='<div class="t1"><span class="n"></span><span class="v"></span></div>'+(it.bar?'<div class="bar"><i></i></div>':'')+'<div class="t2"></div>';
      tx.querySelector('.n').textContent=it.t;
      d.appendChild(ico); d.appendChild(tx);
      var sw=null; if(it.sw){ sw=document.createElement('div'); sw.className='sw'; d.appendChild(sw); }
      if(it.chev){ var c=document.createElement('div'); c.className='chev'; c.textContent='\u203a'; d.appendChild(c); }
      inner.appendChild(d);
      els[it.id]={el:d,ico:ico,v:tx.querySelector('.v'),t2:tx.querySelector('.t2'),bar:tx.querySelector('.bar i'),sw:sw,key:it.icon};
      G.icons.fill(ico,it.icon);
      order.push(it.id);
      d.addEventListener('mouseenter',function(){ idx=order.indexOf(it.id); focus(); });
      d.addEventListener('click',function(){ idx=order.indexOf(it.id); focus(); activate(it.id); });
    });
  });
}
function icon(id,key){ G.icons.fill(els[id].ico,key); }

/* ---------- live state ---------- */
function refreshWifi(){
  var w=G.bridge.json('getWifiInfo'), e=els.wifi; if(!e) return;
  if(!w){ e.v.textContent=''; e.t2.textContent='Status unavailable'; icon('wifi','wifiOff'); return; }
  if(w.ethernet){ e.v.textContent='Ethernet'; e.t2.textContent='Wired connection'+(w.wifiConnected&&w.ssid?'  \u00b7  Wi-Fi '+w.ssid:''); icon('wifi','ethernet'); }
  else if(w.wifiConnected){ e.v.textContent='Connected'; e.t2.textContent=w.ssid?w.ssid:(w.locationGranted?'Network name unavailable':'Network name hidden (location permission off)'); icon('wifi','wifi'); }
  else if(w.wifiEnabled){ e.v.textContent='Not connected'; e.t2.textContent='OK for Wi-Fi settings'; icon('wifi','wifiOff'); }
  else { e.v.textContent='Off'; e.t2.textContent='OK for Wi-Fi settings'; icon('wifi','wifiOff'); }
}
function refreshBT(){
  var b=G.bridge.json('getBluetoothInfo'), e=els.bt; if(!e) return;
  if(!b||!b.supported){ e.v.textContent=''; e.t2.textContent='Not available on this box'; icon('bt','bluetoothOff'); return; }
  if(!b.enabled){ e.v.textContent='Off'; e.t2.textContent='OK for Bluetooth settings'; icon('bt','bluetoothOff'); return; }
  e.v.textContent='On'; icon('bt','bluetooth');
  var c=(b.connected||[]), p=(b.paired||[]);
  if(b.connectedKnown&&c.length) e.t2.textContent='Connected: '+c.slice(0,2).join(', ')+(c.length>2?' +'+(c.length-2):'');
  else if(b.connectedKnown) e.t2.textContent='No device connected  \u00b7  '+p.length+' paired';
  else e.t2.textContent=p.length?(p.length+' paired: '+p.slice(0,2).join(', ')+(p.length>2?'...':'')):'No paired devices';
}
function refreshVol(){
  var v=G.bridge.json('getVolumeInfo'), e=els.vol, m=els.mute; if(!e) return; vol=v;
  if(!v){ e.t2.textContent='Unavailable'; e.bar.style.width='0'; e.v.textContent=''; m.t2.textContent='Unavailable'; return; }
  var pct=v.max?Math.round(v.cur*100/v.max):0;
  e.el.classList.toggle('off',!!v.muted); e.el.classList.toggle('dim',!!v.fixed); m.el.classList.toggle('dim',!!v.fixed);
  e.bar.style.width=(v.muted?0:pct)+'%';
  e.v.textContent=v.fixed?'':(v.muted?'Muted':pct+'%');
  e.t2.textContent=v.fixed?'Fixed volume: your TV or soundbar controls it':'Left / Right to adjust';
  m.v.textContent=v.muted?'On':'Off'; m.t2.textContent=v.fixed?'Not available with fixed volume':(v.muted?'OK to unmute':'OK to mute');
  icon('vol',v.muted?'volumeMuted':'volume'); icon('mute',v.muted?'volumeMuted':'volume');
}
function refreshTheme(){
  var e=els.theme; if(!e) return; var d=G.theme.isDark();
  e.v.textContent=''; e.t2.textContent=d?'Dark  \u00b7  OK for light':'Light  \u00b7  OK for dark';
  e.sw.classList.toggle('on',d); icon('theme',d?'themeDark':'themeLight');
}
function refreshDev(){
  var d=G.bridge.json('getDeviceInfo'), e=els.dev; if(!e) return;
  if(!d){ e.t2.textContent='Unavailable'; return; }
  e.t2.textContent=(d.model||'')+'  \u00b7  Android '+(d.android||'?')+'  \u00b7  '+(d.ip||'no IP')+'  \u00b7  '+(d.freeGb||'?')+' GB free';
}
function refreshSet(){ els.set.t2.textContent='Open the system settings'; }
function refreshAll(){ if(!built) return; refreshWifi(); refreshBT(); refreshVol(); refreshTheme(); refreshDev(); refreshSet(); }

/* ---------- focus + scroll (panel can scroll if a screen is short) ---------- */
function focus(){
  order.forEach(function(k){ els[k].el.classList.remove('f'); });
  var e=els[order[idx]]; e.el.classList.add('f');
  var top=e.el.offsetTop, h=e.el.offsetHeight, vh=body.clientHeight, cur=-(parseFloat(inner.getAttribute('data-y'))||0), y=cur;
  if(top-24<cur) y=Math.max(0,top-24); else if(top+h+24>cur+vh) y=top+h+24-vh;
  if(y!==cur){ inner.style.transform='translateY('+(-y)+'px)'; inner.setAttribute('data-y',-y); }
}

/* ---------- actions ---------- */
function adjustVol(dir){
  var v=vol; if(!v||v.fixed) return;
  var step=Math.max(1,Math.round(v.max/20)), n=Math.max(0,Math.min(v.max,v.cur+dir*step)); if(n===v.cur) return;
  var r=G.bridge.json('setVolume',n); if(r) vol=r; refreshVol(); G.sound.nav();
}
function activate(id){
  G.sound.click();
  if(id==='wifi') G.openSettings('wifi');
  else if(id==='bt') G.openSettings('bluetooth');
  else if(id==='theme') G.theme.toggle();
  else if(id==='vol'||id==='mute'){ if(vol&&!vol.fixed){ G.bridge.call('toggleMute'); refreshVol(); } else G.toast('Volume is fixed: use your TV or soundbar volume.'); }
  else if(id==='dev') G.openSettings('device');
  else if(id==='set') G.openSettings('main');
}
G.zones.cc={ key:function(k,e){
  if(k===37||k===38||k===39||k===40||k===13||k===10009) e.preventDefault(); else return;
  var id=order[idx], moved=false;
  if(k===38){ if(idx>0){ idx--; moved=true; } }
  else if(k===40){ if(idx<order.length-1){ idx++; moved=true; } }
  else if(k===37){ if(id==='vol') adjustVol(-1); }
  else if(k===39){ if(id==='vol') adjustVol(1); }
  else if(k===13) activate(id);
  else if(k===10009){ close(); return; }
  if(moved) G.sound.nav();
  focus();
}};

function open(o){
  o=o||{}; build(); from=o.from||'hdr';
  st.zone='cc'; idx=Math.max(0,order.indexOf(o.focus||order[0]));
  cc.classList.add('on'); document.body.classList.add('ccopen');
  refreshAll(); focus(); G.home.render(true);
  clearInterval(timer); timer=setInterval(function(){ refreshVol(); refreshWifi(); refreshBT(); },2000);
  if(!G.store.get('locasked',false)){ G.store.set('locasked',true); G.bridge.call('requestLocationPermission'); }
}
function close(){
  cc.classList.remove('on'); document.body.classList.remove('ccopen'); clearInterval(timer);
  if(from==='rows'||from==='search'){ G.home.focusRows(); } else G.home.focusHeader(1);
}
G.theme.onChange(function(){ if(built) refreshTheme(); });
/* called from Java */
window.glassVolumeSync=function(){ if(st.zone==='cc') refreshVol(); };
window.glassResume=function(){ if(st.zone==='cc') refreshAll(); };

G.cc={open:open,close:close,isOpen:function(){return st.zone==='cc';}};
})();
