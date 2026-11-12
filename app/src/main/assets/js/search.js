/* GLASS Search: its own full-screen interface. Results rank via Glass.registry. Typing comes from the Glass keyboard (D-pad) or a hardware keyboard;
   Android's on-screen keyboard is not used, so nothing can pop up over Glass. Back closes Search and returns to the previous home position. */
(function(){
'use strict';
var G=window.Glass, $=G.$, st=G.state;
var box=$('search'), panel=$('spanel'), field=$('sfield'), txt=$('stext'), res=$('sres'), list=$('slist');
var query='', results=[], sel=0, focusIn='kbd', MAXR=24, snap={zone:'hdr',hdr:0}, lastHw=0, kb=null;
var SUGGEST=['Settings','Wi-Fi','Bluetooth','Volume','Play Store','Appearance'];
G.icons.fill($('ssico'),'search');

var renderT=null;
function setQuery(q){
  query=q; txt.textContent=q; field.classList.toggle('has',q.length>0); sel=0;   // instant: the field itself never waits
  clearTimeout(renderT); renderT=setTimeout(render,110);                        // debounced: the expensive filter + rebuild + icon lookups
}
function addChar(c){ if(query.length<60) setQuery(query+c); }
function backspace(){ if(query) setQuery(query.slice(0,-1)); }

function flushRender(){ if(renderT){ clearTimeout(renderT); renderT=null; render(); } }
function setFocus(where){
  if(where==='list') flushRender();
  if(where==='list'&&!results.length) where='kbd';
  focusIn=where; if(kb) kb.setActive(where==='kbd'); paintSel();
}
function paintSel(){
  var rs=list.querySelectorAll('.sr'), i;
  for(i=0;i<rs.length;i++) rs[i].classList.toggle('f',focusIn==='list'&&i===sel);
  if(focusIn==='list'&&rs[sel]){                     // keep the selected row inside the panel
    var top=rs[sel].offsetTop, h=rs[sel].offsetHeight, vh=res.clientHeight-16, cur=-(parseFloat(list.getAttribute('data-y'))||0), y=cur;
    if(top-36<cur) y=Math.max(0,top-36); else if(top+h>cur+vh) y=top+h-vh+8;
    if(y!==cur){ list.style.transform='translateY('+(-y)+'px)'; list.setAttribute('data-y',-y); }
  }
}
function iconFor(e,holder){
  if(e.kind==='app'){
    var ov=G.icons.override(e.pkg), im=new Image(); im.alt='';
    holder.textContent=G.initials(e.title);
    im.onload=function(){ holder.textContent=''; holder.appendChild(im); };
    if(ov){ im.src=ov; return; }
    G.icons.packageIcon(e.pkg,function(u){ if(u) im.src=u; });
  } else holder.innerHTML=G.icons.svg(e.icon||'settings');
}
function header(t){ var h=document.createElement('div'); h.className='shd'; h.textContent=t; list.appendChild(h); }
function render(){
  if(!query){
    results=SUGGEST.map(function(w){ return {kind:'suggest',title:w,icon:'search',run:function(){ setQuery(w); setFocus('kbd'); }}; });
  } else results=G.registry.query(query,MAXR);
  sel=Math.min(sel,Math.max(0,results.length-1));
  list.innerHTML=''; list.style.transform=''; list.setAttribute('data-y',0);
  if(!query) header('Suggestions');
  if(query&&!results.length){ var n=document.createElement('div'); n.id='snone'; n.textContent='No apps or settings match \u201c'+query+'\u201d'; list.appendChild(n); paintSel(); return; }
  results.forEach(function(e,i){
    var sug=e.kind==='suggest';
    var r=document.createElement('div'); r.className='sr'+(sug?' sug':'');
    var ri=document.createElement('div'); ri.className='ri';
    var rt=document.createElement('div'); rt.className='rt';
    var a=document.createElement('div'); a.className='rt1'; a.textContent=e.title; rt.appendChild(a);
    if(e.kind==='shortcut'&&e.subtitle){ var b=document.createElement('div'); b.className='rt2'; b.textContent=e.subtitle; rt.appendChild(b); }
    r.appendChild(ri); r.appendChild(rt);
    if(!sug){ var tg=document.createElement('div'); tg.className='tag'; tg.textContent=e.tag||''; r.appendChild(tg); }
    list.appendChild(r);
    iconFor(e,ri);
    r.addEventListener('mousemove',function(e){ if(!G.pointer.moved(e)||(focusIn==='list'&&sel===i)) return; sel=i; setFocus('list'); });
    r.addEventListener('click',function(){ sel=i; run(i); });
  });
  paintSel();
}
function run(i){ var e=results[i]; if(!e) return; G.sound.click(); e.run(); }
function submit(){ flushRender(); if(query&&results.length){ sel=0; setFocus('list'); G.sound.nav(); } else G.toast(query?'Nothing matches that':'Type to search'); }

kb=G.keyboard.create($('skb'),{
  onChar:function(c){ addChar(c); },          // keyboard.js's own press() already plays the matching key sound
  onBackspace:function(){ backspace(); },
  onClear:function(){ setQuery(''); },
  onSubmit:function(){ submit(); },
  onHover:function(){ if(focusIn!=='kbd') setFocus('kbd'); }
});

function open(o){
  snap=(o&&o.restore)||{zone:st.zone,hdr:G.home.hdrIndex?G.home.hdrIndex():0};
  st.zone='search'; box.classList.add('on'); document.body.classList.add('searchopen');
  G.registry.invalidate(); kb.reset(); setQuery(''); G.home.render(true); setFocus('kbd');
  G.perf.watch(700);
}
function close(toHome){
  clearTimeout(renderT); renderT=null;
  box.classList.remove('on'); document.body.classList.remove('searchopen');
  if(toHome===true) return;
  if(snap.zone==='rows') G.home.focusRows(); else G.home.focusHeader(snap.hdr);
}

/* Tab / Shift+Tab: keys first, then results, then round again */
function tab(dir){
  G.sound.nav();
  if(focusIn==='kbd'){
    if(kb.step(dir)==='edge'){
      if(results.length){ flushRender(); sel=dir>0?0:results.length-1; setFocus('list'); } else kb.jump(dir<0);
    }
    return;
  }
  var n=sel+dir;
  if(n>=0&&n<results.length){ sel=n; paintSel(); return; }
  setFocus('kbd'); kb.jump(dir<0);
}
/* Clicking the dimmed backdrop closes Search. Compared by identity with the backdrop itself, so a click that rebuilds the
   keyboard or the results under the pointer (the clicked element is then detached) can never be mistaken for an outside click. */
box.addEventListener('click',function(e){ if(e.target===box&&st.zone==='search') close(); });
res.addEventListener('wheel',function(e){
  if(!results.length||Math.abs(e.deltaY)<4) return; e.preventDefault();
  sel=Math.max(0,Math.min(results.length-1,sel+(e.deltaY>0?1:-1))); if(focusIn!=='list') setFocus('list'); else paintSel();
},{passive:false});

G.zones.search={ tab:tab, key:function(k,e){
  if(k===10009){ e.preventDefault(); close(); return; }
  /* hardware keyboard: letters, digits, space, backspace type straight into the field */
  if(!e.ctrlKey&&!e.altKey&&!e.metaKey&&e.key&&e.key.length===1&&k!==13){ e.preventDefault(); lastHw=Date.now(); G.sound.key(e.key===' '?'space':'alpha'); addChar(e.key); if(focusIn==='list') setFocus('kbd'); return; }
  if(k===8){ e.preventDefault(); lastHw=Date.now(); G.sound.key('alt'); backspace(); return; }
  if(k===13&&Date.now()-lastHw<2500){ e.preventDefault(); lastHw=0; G.sound.key('enter'); submit(); return; }   // Enter right after hardware typing = go to results

  if(focusIn==='kbd'){
    if(k>=37&&k<=40){
      e.preventDefault(); var r=kb.move(k);
      if(r==='moved') G.sound.nav();
      else if(k===37&&results.length){ setFocus('list'); G.sound.nav(); }
    } else if(k===13){ e.preventDefault(); kb.press(); }
    return;
  }
  /* results list */
  if(k===38){ e.preventDefault(); if(sel>0){ sel--; paintSel(); G.sound.nav(); } }
  else if(k===40){ e.preventDefault(); if(sel<results.length-1){ sel++; paintSel(); G.sound.nav(); } }
  else if(k===39){ e.preventDefault(); setFocus('kbd'); G.sound.nav(); }
  else if(k===13){ e.preventDefault(); run(sel); }
}};
G.search={open:open,close:close,isOpen:function(){return st.zone==='search';}};
})();
