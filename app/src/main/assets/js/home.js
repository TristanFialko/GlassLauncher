/* GLASS home: clock scroll, two curated rows (top apps + Entertainment), header controls, card focus. */
(function(){
'use strict';
var G=window.Glass, $=G.$, LAY=G.cfg.layout, st=G.state;
var head=$('head'), clockEl=$('clock'), stage=$('stage'), rowsWrap=$('rowsWrap');
var rows=[], vpos=0, topRow=0, hdrIdx=0, pinnedApps=G.cfg.apps, M={};   // hdrIdx: 0 Search, 1 Control Centre, 2 Recently Used
var overlayOpen=function(){ return st.zone==='cc'||st.zone==='search'; };
document.documentElement.style.setProperty('--rowgap',LAY.rowGap+'rem');
if(LAY.showLabels) document.body.classList.add('labels');

/* ---------- slots / rows ---------- */
function setTileIcon(o,a,dark){
  var t=o.tile, ov=G.icons.override(a.id);
  function show(src,square){
    var im=o.img; if(!im){ im=new Image(); o.img=im; }
    im.onload=function(){ t.classList.toggle('sq',square||(im.naturalWidth/im.naturalHeight<1.3)); t.classList.add('icon'); };
    im.onerror=function(){ im.style.display='none'; t.classList.remove('icon'); t.style.background=a.bg||'#222'; t.textContent=a.text||G.initials(a.name); };
    im.style.display=''; if(!im.parentNode){ t.textContent=''; t.appendChild(im); }
    im.src=src;
  }
  if(a.icon||a.dark){ show((dark&&a.dark)?a.dark:(a.icon||a.dark)); if(a.icon&&a.dark&&!a._pre){ a._pre=1; new Image().src=dark?a.icon:a.dark; } }   // warm the other theme's art so switching never flashes
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
  var el=document.createElement('div'); el.className='row'+(label&&kind==='ent'?' titled':'');
  var lb=document.createElement('div'); lb.className='rowLabel'; lb.textContent=label||''; if(!label) lb.style.display='none';
  var dock=document.createElement('div'); dock.className='dock glass';
  el.appendChild(lb); el.appendChild(dock); rowsWrap.appendChild(el);
  var r={el:el,dock:dock,items:[],col:0,label:lb,kind:kind}; rows.push(r); var ri=rows.length-1;
  return r;
}
function addToRow(r,a){
  var o=buildSlot(a,r.dock); r.items.push(o);
  o.el.addEventListener('mousemove',function(e){            // hover = focus, but only for a real pointer move and never under an overlay
    if(overlayOpen()||!G.pointer.moved(e)) return;
    var ri=rows.indexOf(r), ci=r.items.indexOf(o);
    if(st.zone==='rows'&&vpos===ri&&r.col===ci) return;
    vpos=ri; r.col=ci; G.setZone('rows'); render();
  });
  o.el.addEventListener('click',function(){ activate(a); });
  return o;
}
function activate(a){
  if(a.action==='list'){ G.sound.click(); G.ids.show(); return; }
  if(a.action==='exit'){ G.sound.click(); G.exitApp(); return; }
  G.launch(a);
}
function rebuild(){
  rowsWrap.innerHTML=''; rows=[];
  var C=LAY.columns, i, r;
  /* 1. top row: the five pinned apps, exactly as configured */
  for(i=0;i<pinnedApps.length;i+=C){ r=makeRow('','pinned'); pinnedApps.slice(i,i+C).forEach(function(a){addToRow(r,a);}); }
  /* 2. Entertainment: one row of four. Same card size and gap; the group is centred inside a panel as wide as the top row's. */
  var ent=G.cfg.entertainment.apps||[];
  if(ent.length){ r=makeRow('','ent'); r.dock.classList.add('center'); ent.forEach(function(a){addToRow(r,a);}); }
  /* installed apps that are not on the home screen are not listed here; Search finds and launches every one of them */
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
  G.recent.setFocus(st.zone==='hdr'&&hdrIdx===2);
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
/* header stops, left to right: Recently Used (when it has an app), Search, Control Centre */
function hdrOrder(){ return G.recent.focusable()?[2,0,1]:[0,1]; }
G.zones.hdr={ key:function(k,e){
  var used=true, o=hdrOrder(), p=o.indexOf(hdrIdx);
  if(p<0){ p=0; hdrIdx=o[0]; }
  if(k===37) hdrIdx=o[Math.max(0,p-1)]; else if(k===39) hdrIdx=o[Math.min(o.length-1,p+1)]; else if(k===40) G.setZone('rows');
  else if(k===13){ G.sound.click(); if(hdrIdx===0) G.search.open(); else if(hdrIdx===1) G.cc.open({from:'hdr'}); else G.recent.activate(); }
  else if(k===10009) G.exitApp(); else used=false;
  if(used){ e.preventDefault(); if(st.zone==='hdr'||st.zone==='rows') render(); }
}};
/* Tab / Shift+Tab: one linear path through every stop: header, then each card left to right, top to bottom */
function tab(dir){
  var s=[], cur=-1, i;
  hdrOrder().forEach(function(h){ s.push({h:h}); });
  rows.forEach(function(r,ri){ r.items.forEach(function(o,ci){ s.push({r:ri,c:ci}); }); });
  for(i=0;i<s.length;i++){ var x=s[i]; if(st.zone==='hdr'?x.h===hdrIdx:(x.r===vpos&&x.c===(rows[vpos]||{}).col)){ cur=i; break; } }
  var n=s[(Math.max(cur,0)+dir+s.length)%s.length];
  if(n.h!==undefined){ hdrIdx=n.h; G.setZone('hdr'); } else { vpos=n.r; rows[vpos].col=n.c; G.setZone('rows'); }
  render();
}
G.zones.rows.tab=tab; G.zones.hdr.tab=tab;
function hoverHdr(btn,idx){ btn.addEventListener('mousemove',function(e){ if(overlayOpen()||!G.pointer.moved(e)) return; if(st.zone==='hdr'&&hdrIdx===idx) return; G.setZone('hdr'); hdrIdx=idx; render(); }); }
hoverHdr(srchBtn,0); hoverHdr(ccBtn,1); hoverHdr($('recent'),2);
srchBtn.addEventListener('click',function(){ if(overlayOpen()) return; G.sound.click(); G.search.open(); });
ccBtn.addEventListener('click',function(){ if(overlayOpen()) return; G.sound.click(); G.cc.open({from:'hdr'}); });
$('recent').addEventListener('click',function(){ if(overlayOpen()) return; G.recent.activate(); });
/* mouse wheel moves between the two rows (throttled so one notch = one step) */
var wheelT=0;
window.addEventListener('wheel',function(e){
  if(overlayOpen()||Math.abs(e.deltaY)<4||Date.now()-wheelT<350) return; wheelT=Date.now();
  if(e.deltaY>0){ if(st.zone==='hdr') G.setZone('rows'); else if(vpos<rows.length-1) vpos++; else return; }
  else { if(st.zone==='hdr'||vpos<=0) return; vpos--; G.setZone('rows'); }
  if(rows[vpos]) rows[vpos].col=Math.max(0,Math.min(rows[vpos].col,rows[vpos].items.length-1));
  render();
},{passive:true});

/* header icons: your PNG if present (ui/search.png, ui/controls.png), else built-in SVG */
G.icons.fill($('searchico'),'search'); G.icons.fill($('ccico'),'controls');

/* ---------- theme-dependent card art ---------- */
G.theme.onChange(function(d){
  rows.forEach(function(r){ r.items.forEach(function(o){ if(o.img&&!o.item.placeholder&&o.item.dark&&o.item.icon) o.img.src=d?o.item.dark:o.item.icon; }); });
});

G.home={
  rebuild:rebuild, render:render, layout:layout,
  focusHeader:function(i){ if(i!==undefined) hdrIdx=i; G.setZone('hdr'); render(true); },
  focusRows:function(){ G.setZone('rows'); render(true); },
  hdrIndex:function(){ return hdrIdx; },
  hdrOrder:hdrOrder,
  rows:function(){ return rows; }
};
})();
