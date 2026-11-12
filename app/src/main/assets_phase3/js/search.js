/* GLASS Search: dedicated full-screen interface. Ranks via Glass.registry; keyboard works with a hardware keyboard or Android's on-screen one. */
(function(){
'use strict';
var G=window.Glass, $=G.$, st=G.state;
var box=$('search'), input=$('sinput'), bar=$('sbar'), res=$('sres'), list=$('slist'), hint=$('shint'), clr=$('sclr');
var results=[], sel=0, focusIn='input', MAXR=24, iconToken=0;
G.icons.fill($('ssico'),'search');

function setFocus(where){
  focusIn=where; bar.classList.toggle('f',where==='input');
  if(where==='input'){ input.focus(); setTimeout(function(){ if(st.zone==='search'&&focusIn==='input') G.bridge.call('showKeyboard'); },120); }
  else { input.blur(); G.bridge.call('hideKeyboard'); }
  paintSel();
}
function paintSel(){
  var rs=list.children, i;
  for(i=0;i<rs.length;i++) rs[i].classList.toggle('f',focusIn==='list'&&i===sel);
  if(focusIn==='list'&&rs[sel]){                     // keep the selected row inside the panel
    var top=rs[sel].offsetTop, h=rs[sel].offsetHeight, vh=res.clientHeight-16, cur=-(parseFloat(list.getAttribute('data-y'))||0), y=cur;
    if(top<cur) y=top; else if(top+h>cur+vh) y=top+h-vh;
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
function render(){
  var q=input.value; clr.style.visibility=q?'visible':'hidden';
  results=G.registry.query(q,MAXR); sel=Math.min(sel,Math.max(0,results.length-1));
  list.innerHTML=''; list.style.transform=''; list.setAttribute('data-y',0);
  hint.style.display=q?'none':'block';
  res.classList.toggle('empty',!q);
  if(q&&!results.length){ var n=document.createElement('div'); n.id='snone'; n.textContent='No apps or settings match \u201c'+q+'\u201d'; list.appendChild(n); return; }
  results.forEach(function(e,i){
    var r=document.createElement('div'); r.className='sr';
    var ri=document.createElement('div'); ri.className='ri';
    var rt=document.createElement('div'); rt.className='rt';
    var a=document.createElement('div'); a.className='rt1'; a.textContent=e.title;
    var b=document.createElement('div'); b.className='rt2'; b.textContent=e.subtitle||'';
    var tg=document.createElement('div'); tg.className='tag'; tg.textContent=e.tag||'';
    rt.appendChild(a); rt.appendChild(b); r.appendChild(ri); r.appendChild(rt); r.appendChild(tg); list.appendChild(r);
    iconFor(e,ri);
    r.addEventListener('mouseenter',function(){ sel=i; focusIn='list'; bar.classList.remove('f'); paintSel(); });
    r.addEventListener('click',function(){ run(i); });
  });
  paintSel();
}
function run(i){ var e=results[i]; if(!e) return; G.sound.click(); G.bridge.call('hideKeyboard'); e.run(); }

function open(){
  st.zone='search'; box.classList.add('on'); document.body.classList.add('searchopen');
  input.value=''; sel=0; render(); G.home.render(true); setFocus('input');
}
function close(toHome){
  G.bridge.call('hideKeyboard'); input.blur();
  box.classList.remove('on'); document.body.classList.remove('searchopen');
  if(toHome!==true) G.home.focusHeader(0);
}
input.addEventListener('input',function(){ sel=0; render(); });
clr.addEventListener('click',function(){ input.value=''; render(); setFocus('input'); });

G.zones.search={ key:function(k,e){
  if(focusIn==='input'){
    if(k===10009){ e.preventDefault(); close(); return; }
    if(k===40){ e.preventDefault(); if(results.length){ setFocus('list'); G.sound.nav(); } return; }
    if(k===13){ e.preventDefault(); if(input.value&&results.length){ setFocus('list'); G.sound.nav(); } else G.bridge.call('showKeyboard'); return; }
    return;                                           // left/right/up/typing: the text field handles them
  }
  if(k===38){ e.preventDefault(); if(sel>0){ sel--; paintSel(); G.sound.nav(); } else setFocus('input'); }
  else if(k===40){ e.preventDefault(); if(sel<results.length-1){ sel++; paintSel(); G.sound.nav(); } }
  else if(k===13){ e.preventDefault(); run(sel); }
  else if(k===10009){ e.preventDefault(); close(); }
  else if(k>=32&&k!==37&&k!==39){ setFocus('input'); }   // typing on a hardware keyboard jumps back to the field
}};
G.search={open:open,close:close,isOpen:function(){return st.zone==='search';}};
})();
