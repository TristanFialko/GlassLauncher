/* GLASS Recently Used: small glass panel top-left showing the last app that was really launched. Persisted in Glass.store('recent'). */
(function(){
'use strict';
var G=window.Glass, $=G.$, el=$('recent'), thumb=$('rthumb'), nameEl=$('rname'), empty=$('rempty'), body=$('rbody');
var rec=G.store.get('recent',null), img=null, shown=null;

function def(id){            /* the configured card for this package, if any (gives the theme-aware artwork) */
  var all=G.cfg.apps.concat(G.cfg.entertainment.apps||[]), i;
  for(i=0;i<all.length;i++) if(all[i].id===id) return all[i];
  return null;
}
function valid(){ return !!(rec&&rec.id&&(!G.apps.installed.length||G.apps.isInstalled(rec.id))); }
function paintIcon(){
  if(!valid()) return;
  var d=def(rec.id), dark=G.theme.isDark(), ov=G.icons.override(rec.id), src=null, sq=false;
  if(d&&(d.icon||d.dark)) src=(dark&&d.dark)?d.dark:(d.icon||d.dark);
  else if(ov){ src=ov; sq=true; }
  var key=rec.id+'|'+src; if(shown===key) return; shown=key;
  function put(u,square){
    if(!img){ img=new Image(); img.alt=''; img.onerror=function(){ img.style.display='none'; thumb.textContent=G.initials(rec.name); }; }
    img.onload=function(){ thumb.classList.toggle('sq',square||(img.naturalWidth/img.naturalHeight<1.3)); thumb.classList.add('icon'); };
    img.style.display=''; if(!img.parentNode){ thumb.textContent=''; thumb.appendChild(img); }
    img.src=u;
  }
  if(src) put(src,sq);
  else { thumb.classList.remove('icon'); thumb.textContent=G.initials(rec.name); G.icons.packageIcon(rec.id,function(u){ if(u&&rec&&shown===key) put(u,true); }); }
}
function refresh(){
  var ok=valid();
  el.classList.toggle('has',ok); body.style.display=ok?'':'none'; empty.style.display=ok?'none':'';
  if(ok){ var d=def(rec.id); nameEl.textContent=(d&&d.name)||rec.name; paintIcon(); } else shown=null;
}
G.recent={
  record:function(a){ if(!a||!a.id||a.id==='com.glasslauncher') return; rec={id:a.id,name:a.name||a.id}; G.store.set('recent',rec); refresh(); },
  focusable:function(){ return valid(); },
  setFocus:function(b){ el.classList.toggle('f',!!b); },
  activate:function(){ if(valid()) G.launch({id:rec.id,name:(def(rec.id)||rec).name||rec.name}); },
  refresh:refresh
};
G.theme.onChange(function(){ if(valid()) paintIcon(); });
refresh();
})();
