/* GLASS Control Centre: right-side glass overlay. Every tile shows real device state (AudioManager, ConnectivityManager, BluetoothAdapter, Build).
   Layout: Appearance (large), Wi-Fi + Bluetooth (wide), Volume + This box (full width), four shortcuts (compact). D-pad moves spatially between tiles. */
(function(){
'use strict';
var G=window.Glass, $=G.$, st=G.state;
var cc=$('cc'), inner=$('ccinner'), body=$('ccbody');
var built=false, els={}, order=[], idx=0, timer=null, from='hdr', vol=null, out='', clockT=null;

/* tiles in grid order. area = row-start/col-start/row-end/col-end */
var SPEC=[
  {id:'theme',cls:'big', area:'1/1/3/3',icon:'themeLight',t:'Appearance'},
  {id:'wifi', cls:'wide',area:'1/3/2/5',icon:'wifi',t:'Wi-Fi'},
  {id:'bt',   cls:'wide',area:'2/3/3/5',icon:'bluetooth',t:'Bluetooth'},
  {id:'vol',  cls:'full',area:'3/1/4/5',icon:'volume',t:'Volume',bar:true},
  {id:'dev',  cls:'full',area:'4/1/5/5',icon:'device',t:'This box'},
  {id:'set',  cls:'mini',area:'5/1/6/2',icon:'settings',t:'Settings'},
  {id:'snd',  cls:'mini',area:'5/2/6/3',icon:'volume',t:'Sound'},
  {id:'dsp',  cls:'mini',area:'5/3/6/4',icon:'display',t:'Display'},
  {id:'srch', cls:'mini',area:'5/4/6/5',icon:'search',t:'Search'}
];
function build(){
  if(built) return; built=true;
  SPEC.forEach(function(it,n){
    var d=document.createElement('div'); d.className='gt cci '+it.cls; d.id='cc_'+it.id; d.style.gridArea=it.area; d.style.setProperty('--i',n);
    var chip=document.createElement('div'); chip.className='chip';
    var ico=document.createElement('div'); ico.className='ico'; chip.appendChild(ico);
    var tx=document.createElement('div'); tx.className='txt';
    tx.innerHTML='<div class="t1"><span class="n"></span><span class="v"></span></div>'+(it.bar?'<div class="bar"><i></i></div>':'')+(it.cls==='mini'?'':'<div class="t2"></div>');
    tx.querySelector('.n').textContent=it.t;
    d.appendChild(chip); d.appendChild(tx); inner.appendChild(d);
    els[it.id]={el:d,ico:ico,v:tx.querySelector('.v'),t2:tx.querySelector('.t2')||{},bar:tx.querySelector('.bar i'),key:it.icon};
    G.icons.fill(ico,it.icon);
    order.push(it.id);
    d.addEventListener('mousemove',function(e){ if(!G.pointer.moved(e)||order[idx]===it.id) return; idx=order.indexOf(it.id); focus(); });
    d.addEventListener('click',function(){ idx=order.indexOf(it.id); focus(); activate(it.id); });
  });
}
function icon(id,key){ G.icons.fill(els[id].ico,key); }
function setOn(id,on){ els[id].el.classList.toggle('on',!!on); }

/* ---------- live state ---------- */
function refreshClock(){
  var d=new Date(), h=d.getHours()%12||12, m=('0'+d.getMinutes()).slice(-2);
  $('cctime').textContent=h+':'+m; $('ccdate').textContent=d.toLocaleDateString('en-GB',{weekday:'long',day:'numeric',month:'long'});
}
function refreshWifi(){
  var w=G.bridge.json('getWifiInfo'), e=els.wifi; if(!e) return;
  if(!w){ e.v.textContent='Unavailable'; e.t2.textContent='Status unavailable'; icon('wifi','wifiOff'); setOn('wifi',false); return; }
  if(w.ethernet){ e.v.textContent='Ethernet'; e.t2.textContent='Wired connection'+(w.wifiConnected&&w.ssid?'  \u00b7  Wi-Fi '+w.ssid:''); icon('wifi','ethernet'); setOn('wifi',true); }
  else if(w.wifiConnected){ e.v.textContent='Connected'; e.t2.textContent=w.ssid?w.ssid:(w.locationGranted?'Network name unavailable':'Network name hidden'); icon('wifi','wifi'); setOn('wifi',true); }
  else if(w.wifiEnabled){ e.v.textContent='Not connected'; e.t2.textContent='OK opens Wi-Fi settings'; icon('wifi','wifiOff'); setOn('wifi',false); }
  else { e.v.textContent='Off'; e.t2.textContent='OK opens Wi-Fi settings'; icon('wifi','wifiOff'); setOn('wifi',false); }
}
function refreshBT(){
  var b=G.bridge.json('getBluetoothInfo'), e=els.bt; if(!e) return;
  if(!b||!b.supported){ e.v.textContent='Unavailable'; e.t2.textContent='Not available on this box'; icon('bt','bluetoothOff'); setOn('bt',false); return; }
  if(!b.enabled){ e.v.textContent='Off'; e.t2.textContent='OK opens Bluetooth settings'; icon('bt','bluetoothOff'); setOn('bt',false); return; }
  e.v.textContent='On'; icon('bt','bluetooth'); setOn('bt',true);
  var c=(b.connected||[]), p=(b.paired||[]);
  if(b.connectedKnown&&c.length) e.t2.textContent=c.slice(0,2).join(', ')+(c.length>2?' +'+(c.length-2):'');
  else if(b.connectedKnown) e.t2.textContent='No device connected \u00b7 '+p.length+' paired';
  else e.t2.textContent=p.length?(p.length+' paired: '+p.slice(0,2).join(', ')+(p.length>2?'...':'')):'No paired devices';
}
function refreshOut(){
  var o=G.bridge.json('getAudioOutput'); out=(o&&o.label)?o.label:'';
}
function refreshVol(){
  var v=G.bridge.json('getVolumeInfo'), e=els.vol; if(!e) return; vol=v;
  if(!v){ e.t2.textContent='Unavailable'; e.bar.style.width='0'; e.v.textContent=''; return; }
  var pct=v.max?Math.round(v.cur*100/v.max):0;
  e.el.classList.toggle('off',!!v.muted); e.el.classList.toggle('dim',!!v.fixed);
  e.bar.style.width=(v.muted?0:pct)+'%';
  e.v.textContent=v.fixed?'':(v.muted?'Muted':pct+'%');
  e.t2.textContent=(out?'Output: '+out+'  \u00b7  ':'')+(v.fixed?'Fixed volume: your TV or soundbar controls it':(v.muted?'OK to unmute':'Left / Right to adjust, OK to mute'));
  icon('vol',v.muted?'volumeMuted':'volume'); setOn('vol',!v.muted&&!v.fixed);
}
function refreshTheme(){
  var e=els.theme; if(!e) return; var d=G.theme.isDark(), holder=e.ico;
  e.t2.textContent=d?'Dark':'Light'; setOn('theme',d);
  /* A clean built-in crescent/sun replaces the oversized legacy PNG artwork. */
  holder.innerHTML=d
    ? '<svg class="gsvg" viewBox="0 0 24 24" aria-hidden="true"><path d="M19.1 15.1A8.2 8.2 0 0 1 8.9 4.9 8.5 8.5 0 1 0 19.1 15.1Z" fill="currentColor" stroke="none"/></svg>'
    : '<svg class="gsvg" viewBox="0 0 24 24" aria-hidden="true"><circle cx="12" cy="12" r="4"/><path d="M12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.3 5.3l1.4 1.4M17.3 17.3l1.4 1.4M18.7 5.3l-1.4 1.4M6.7 17.3l-1.4 1.4"/></svg>';
}
function refreshDev(){
  var d=G.bridge.json('getDeviceInfo'), e=els.dev; if(!e) return;
  if(!d){ e.t2.textContent='Unavailable'; return; }
  e.t2.textContent=(d.model||'')+'  \u00b7  Android '+(d.android||'?')+'  \u00b7  '+(d.ip||'no IP')+'  \u00b7  '+(d.freeGb||'?')+' GB free';
}
function refreshMinis(){
  els.snd.el.classList.toggle('dim',!G.registry.hasSettings('sound'));
  els.dsp.el.classList.toggle('dim',!G.registry.hasSettings('display'));
}
function refreshAll(){ if(!built) return; refreshClock(); refreshWifi(); refreshBT(); refreshOut(); refreshVol(); refreshTheme(); refreshDev(); refreshMinis(); }

/* ---------- focus + scroll (the grid scrolls only if a screen is too short) ---------- */
function focus(){
  order.forEach(function(k){ els[k].el.classList.remove('f'); });
  var e=els[order[idx]]; e.el.classList.add('f');
  /* Scroll the viewport naturally instead of translating the entire grid. */
  var top=e.el.offsetTop, bottom=top+e.el.offsetHeight, viewTop=body.scrollTop, viewBottom=viewTop+body.clientHeight;
  if(top<viewTop+12) body.scrollTo({top:Math.max(0,top-18),behavior:'smooth'});
  else if(bottom>viewBottom-12) body.scrollTo({top:bottom-body.clientHeight+18,behavior:'smooth'});
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
  else if(id==='vol'){ if(vol&&!vol.fixed){ G.bridge.call('toggleMute'); refreshVol(); } else G.toast('Volume is fixed: use your TV or soundbar volume.'); }
  else if(id==='dev') G.openSettings('device');
  else if(id==='set') G.openSettings('main');
  else if(id==='snd') G.openSettings('sound');
  else if(id==='dsp') G.openSettings('display');
  else if(id==='srch'){ close(); G.home.focusHeader(0); G.search.open(); }
}
G.zones.cc={ tab:function(dir){ idx=(idx+dir+order.length)%order.length; G.sound.nav(); focus(); }, key:function(k,e){
  if(k===37||k===38||k===39||k===40||k===13||k===10009) e.preventDefault(); else return;
  var id=order[idx], moved=false;
  if(k===10009){ close(); return; }
  if(k===13){ activate(id); focus(); return; }
  if(id==='vol'&&(k===37||k===39)){ adjustVol(k===37?-1:1); focus(); return; }   // volume slider keeps Left/Right; Up/Down leave it
  var n=G.spatial.move(order.map(function(o){ return els[o].el; }),idx,k);
  if(n>=0){ idx=n; moved=true; }
  if(moved) G.sound.nav();
  focus();
}};

function open(o){
  o=o||{}; build(); from=o.from||'hdr';
  st.zone='cc'; idx=Math.max(0,order.indexOf(o.focus||order[0]));
  cc.classList.add('on'); document.body.classList.add('ccopen');
  refreshAll(); focus(); G.home.render(true);
  clearInterval(timer); timer=setInterval(function(){ refreshVol(); refreshWifi(); refreshBT(); },2000);
  clearInterval(clockT); clockT=setInterval(refreshClock,10000);
  G.perf.watch(800);
  if(!G.store.get('locasked',false)){ G.store.set('locasked',true); G.bridge.call('requestLocationPermission'); }
}
function close(){
  cc.classList.remove('on'); document.body.classList.remove('ccopen'); clearInterval(timer); clearInterval(clockT);
  if(from==='rows'||from==='search'){ G.home.focusRows(); } else G.home.focusHeader(1);
}
/* clicking anywhere outside the panel (the dimmed scrim) closes it; clicks inside the panel never reach the scrim */
$('ccScrim').addEventListener('click',function(){ if(st.zone==='cc') close(); });
G.theme.onChange(function(){ if(built) refreshTheme(); });
/* called from Java */
window.glassVolumeSync=function(){ if(st.zone==='cc') refreshVol(); };
window.glassResume=function(){ if(st.zone==='cc') refreshAll(); };

G.cc={open:open,close:close,isOpen:function(){return st.zone==='cc';}};
})();
