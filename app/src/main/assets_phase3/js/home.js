/* GLASS home: clock scroll, app rows (Pinned, Utilities, Library), header buttons, card focus. */
(function(){
'use strict';
var G=window.Glass, $=G.$, LAY=G.cfg.layout, st=G.state;
var head=$('head'), clockEl=$('clock'), stage=$('stage'), rowsWrap=$('rowsWrap');
var rows=[], vpos=0, topRow=0, hdrIdx=0, pinnedApps=G.cfg.apps, M={};
var pinnedRowCount=0;
document.documentElement.style.setProperty('--rowgap',LAY.rowGap+'rem');
if(LAY.showLabels) document.body.classList.add('labels');

/* ---------- Utilities: configurable slots (apps.js UTILITIES, or edited through G.utilities API) ---------- */
function loadSlots(){
  var saved=G.store.get('utilities',null);
  var s=Array.isArray(saved)?saved:(G.cfg.utilities.slots||[]);
  return s.map(function(x){ return x&&(x.id||x.action)?x:null; });
}
var utilSlots=loadSlots();
function utilCount(){ var C=LAY.columns; return Math.max(C,Math.ceil(utilSlots.length/C)*C); }   // always whole rows of 5
G.utilities={
  title:function(){ return G.cfg.utilities.title||'Utilities'; },
  get:function(){ return utilSlots.slice(); },
  set:function(list){ utilSlots=(list||[]).map(function(x){return x||null;}); G.store.set('utilities',utilSlots); G.home.rebuild(); },
  assign:function(i,app){ while(utilSlots.length<=i) utilSlots.push(null); utilSlots[i]=app||null; G.utilities.set(utilSlots); },
  remove:function(i){ if(i<utilSlots.length){ utilSlots[i]=null; G.utilities.set(utilSlots); } },
  move:function(from,to){ var n=Math.max(utilSlots.length,from+1,to+1); while(utilSlots.length<n) utilSlots.push(null); var it=utilSlots.splice(from,1)[0]; utilSlots.splice(to,0,it); G.utilities.set(utilSlots); },
  reset:function(){ G.store.remove('utilities'); utilSlots=(G.cfg.utilities.slots||[]).map(function(x){return x&&(x.id||x.action)?x:null;}); G.home.rebuild(); }
};

/* ---------- slots / rows ---------- */
function setTileIcon(o,a,dark){
  var t=o.tile, ov=G.icons.override(a.id);
  function show(src,square){
    var im=o.img; if(!im){ im=new Image(); o.img=im; }
    im.onload=function(){ t.classList.toggle('sq',square||(im.naturalWidth/im.naturalHeight<1.3)); };
    im.onerror=function(){ im.style.display='none'; t.textContent=a.text||G.initials(a.name); };
    im.style.display=''; if(!im.parentNode){ t.textContent=''; t.appendChild(im); }
    im.src=src;
  }
  if(a.icon||a.dark){ show((dark&&a.dark)?a.dark:(a.icon||a.dark)); }
  else if(ov){ show(ov,true); }
  else if(a.useAppIcon&&a.id){ t.textContent=a.text||G.initials(a.name); G.icons.packageIcon(a.id,function(u){ if(u) show(u,true); }); }
  else t.textContent=a.text||G.initials(a.name);
}
function buildSlot(a,dock){
  var s=document.createElement('div'); s.className='slot';
  var t=document.createElement('div'); t.className='tile';
  var o={el:s,tile:t,img:null,item:a};
  if(a.placeholder){
    t.className='tile ph'; t.innerHTML='<span class="plus">+</span><span>'+a.name+'</span>';
  } else {
    t.style.background=a.bg||'#222'; t.style.color=a.fg||'#fff'; t.style.fontSize=(a.size||1.6)+'rem';
    setTileIcon(o,a,G.theme.isDark());
  }
  s.appendChild(t); dock.appendChild(s);
  return o;
}
function makeRow(label,kind){
  var el=document.createElement('div'); el.className='row'+(label&&kind==='util'?' titled':'');
  var lb=document.createElement('div'); lb.className='rowLabel'; lb.textContent=label||''; if(!label) lb.style.display='none';
  var dock=document.createElement('div'); dock.className='dock glass';
  el.appendChild(lb); el.appendChild(dock); rowsWrap.appendChild(el);
  var r={el:el,dock:dock,items:[],col:0,label:lb,kind:kind}; rows.push(r); var ri=rows.length-1;
  dock.addEventListener('mouseleave',function(){ render(true); });
  return r;
}
function addToRow(r,a){
  var o=buildSlot(a,r.dock); r.items.push(o); var ri=rows.indexOf(r);
  o.el.addEventListener('mouseenter',function(){ vpos=ri; r.col=r.items.indexOf(o); G.setZone('rows'); render(); });
  o.el.addEventListener('click',function(){ activate(a); });
  return o;
}
function activate(a){
  if(a.placeholder){ G.sound.click(); G.toast('Empty slot. Assign an app to it in apps.js (UTILITIES), then rebuild.'); return; }
  if(a.action==='list'){ G.sound.click(); G.ids.show(); return; }
  if(a.action==='exit'){ G.sound.click(); G.exitApp(); return; }
  G.launch(a);
}
function rebuild(){
  rowsWrap.innerHTML=''; rows=[];
  var C=LAY.columns, i, r;
  /* 1. Pinned (entertainment) rows */
  for(i=0;i<pinnedApps.length;i+=C){ r=makeRow(i===0?'Pinned':'','pinned'); pinnedApps.slice(i,i+C).forEach(function(a){addToRow(r,a);}); }
  pinnedRowCount=rows.length;
  /* 2. Utilities rows: same 5-column cards; empty slots are placeholders */
  var n=utilCount(), items=[];
  for(i=0;i<n;i++){ var s=utilSlots[i]; items.push(s?{name:s.name,id:s.id,action:s.action,icon:s.icon,dark:s.dark,text:s.text,bg:s.bg,fg:s.fg,size:s.size,useAppIcon:true}:{placeholder:true,name:'Utility '+(i+1)}); }
  for(i=0;i<n;i+=C){ r=makeRow(i===0?G.utilities.title():'','util'); items.slice(i,i+C).forEach(function(a){addToRow(r,a);}); }
  /* 3. Library rows (everything else installed) */
  var pids=pinnedApps.map(function(a){return a.id;}).filter(Boolean).concat(utilSlots.filter(Boolean).map(function(a){return a.id;}).filter(Boolean));
  var list=G.apps.installed.filter(function(x){ return x.show!==false&&!G.apps.HIDDEN.test(x.id)&&pids.indexOf(x.id)===-1; })
    .sort(function(a,b){ return a.name.toLowerCase()<b.name.toLowerCase()?-1:1; });
  if(!list.length){ var e=document.createElement('div'); e.id='empty'; e.textContent='No other apps found.'; rowsWrap.appendChild(e); }
  else for(i=0;i<list.length;i+=C){ r=makeRow(i===0?'All Apps':'','lib'); list.slice(i,i+C).forEach(function(x){addToRow(r,{name:x.name,id:x.id,text:G.initials(x.name)});}); }
  if(vpos>=rows.length) vpos=Math.max(0,rows.length-1);
  layout(); render(true);
}

/* ---------- layout (measured, not hard-coded; unchanged logic from Phase 2) ---------- */
function remPx(){ return parseFloat(getComputedStyle(document.documentElement).fontSize)||20; }
function layout(){
  var r=remPx(), S=LAY.clockScale;
  M.headH=head.offsetHeight;
  M.expTop=M.headH+LAY.clockGap*r;
  var target=LAY.collapsedClockTop*r;
  M.dy=clockEl.offsetTop*S-target;
  M.collBottom=clockEl.offsetHeight*S+target;
  M.viewTop=M.collBottom+LAY.collapsedGap*r;
  M.viewBottom=window.innerHeight-LAY.bottomMargin*r;
  rowsWrap.style.top=M.expTop+'px';
  var fade=1.2*r, bf0=M.viewBottom-1.2*r, bf1=M.viewBottom+2.8*r;
  var g='linear-gradient(180deg,transparent 0,transparent '+Math.max(0,M.viewTop-fade).toFixed(0)+'px,#000 '+M.viewTop.toFixed(0)+'px,#000 '+bf0.toFixed(0)+'px,transparent '+bf1.toFixed(0)+'px)';
  stage.style.webkitMaskImage=g; stage.style.maskImage=g;
}
function rowTop(i){ return rows[i]?rows[i].el.offsetTop:0; }
function rowBottom(i){ return rows[i]?rows[i].el.offsetTop+rows[i].el.offsetHeight:0; }
function applyScroll(){
  var z=st.zone, collapsed=(z==='rows'||z==='cc'||z==='search')&&vpos>=1;
  if(!rows.length||!collapsed){ topRow=0; }
  else {
    var avail=M.viewBottom-M.viewTop;
    if(vpos<topRow) topRow=vpos;
    while(topRow<vpos&&rowBottom(vpos)-rowTop(topRow)>avail) topRow++;
  }
  document.body.classList.toggle('scrolled',collapsed);
  head.style.transform=collapsed?('translateY('+(-M.dy).toFixed(1)+'px) scale('+LAY.clockScale+')'):'';
  var ty=collapsed?(M.viewTop-(M.expTop+rowTop(topRow))):0;
  rowsWrap.style.transform=ty?('translateY('+ty.toFixed(1)+'px)'):'';
}

/* ---------- focus ---------- */
var lastKey=null, srchBtn=$('searchbtn'), ccBtn=$('ccbtn');
function render(silent){
  var inRows=(st.zone==='rows');
  rows.forEach(function(r,ri){
    r.items.forEach(function(o){ o.el.classList.remove('f'); });
    var act=inRows&&ri===vpos;
    r.el.classList.toggle('active',act);
    if(act&&r.items[r.col]) r.items[r.col].el.classList.add('f');
  });
  srchBtn.classList.toggle('f',st.zone==='hdr'&&hdrIdx===0);
  ccBtn.classList.toggle('f',st.zone==='hdr'&&hdrIdx===1);
  applyScroll();
  var key=st.zone==='rows'?('r'+vpos+':'+(rows[vpos]?rows[vpos].col:0)):(st.zone==='hdr'?('h'+hdrIdx):st.zone);
  if(lastKey!==null&&key!==lastKey&&!silent&&st.zone!=='cc'&&st.zone!=='search') G.sound.nav();
  lastKey=key;
}

/* ---------- keys: rows + header ---------- */
G.zones.rows={ key:function(k,e){
  var r=rows[vpos], used=true;
  if(k===37){ if(r) r.col--; }
  else if(k===39){ if(r) r.col++; }
  else if(k===38){ if(vpos<=0) G.setZone('hdr'); else { var c=r.col; vpos--; rows[vpos].col=c; } }
  else if(k===40){ if(vpos<rows.length-1){ var c2=r.col; vpos++; rows[vpos].col=c2; } }
  else if(k===13){ var o=r&&r.items[r.col]; if(o) activate(o.item); }
  else if(k===10009){ G.exitApp(); }
  else used=false;
  if(used){ e.preventDefault(); var rr=rows[vpos]; if(st.zone==='rows'&&rr) rr.col=Math.max(0,Math.min(rr.col,rr.items.length-1)); render(); }
}};
G.zones.hdr={ key:function(k,e){
  var used=true;
  if(k===37) hdrIdx=0; else if(k===39) hdrIdx=1; else if(k===40) G.setZone('rows');
  else if(k===13){ G.sound.click(); if(hdrIdx===0) G.search.open(); else G.cc.open({from:'hdr'}); }
  else if(k===10009) G.exitApp(); else used=false;
  if(used){ e.preventDefault(); if(st.zone==='hdr'||st.zone==='rows') render(); }
}};
srchBtn.addEventListener('mouseenter',function(){ G.setZone('hdr'); hdrIdx=0; render(); });
ccBtn.addEventListener('mouseenter',function(){ G.setZone('hdr'); hdrIdx=1; render(); });
srchBtn.addEventListener('click',function(){ G.sound.click(); G.search.open(); });
ccBtn.addEventListener('click',function(){ G.sound.click(); G.cc.open({from:'hdr'}); });

/* header icons: your PNG if present (ui/search.png, ui/controls.png), else built-in SVG */
G.icons.fill($('searchico'),'search'); G.icons.fill($('ccico'),'controls');

/* ---------- theme-dependent card art ---------- */
G.theme.onChange(function(d){
  rows.forEach(function(r){ if(r.kind==='pinned'||r.kind==='util') r.items.forEach(function(o){ if(o.img&&!o.item.placeholder&&o.item.dark&&o.item.icon) o.img.src=d?o.item.dark:o.item.icon; }); });
});

G.home={
  rebuild:rebuild, render:render, layout:layout,
  focusHeader:function(i){ if(i!==undefined) hdrIdx=i; G.setZone('hdr'); render(true); },
  focusRows:function(){ G.setZone('rows'); render(true); },
  rows:function(){ return rows; }
};
})();
