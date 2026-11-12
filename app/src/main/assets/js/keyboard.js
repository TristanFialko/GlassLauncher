/* GLASS keyboard: an on-screen keyboard built for a TV remote. It types into Glass Search only; Android's own keyboard is never involved.
   Usage: var kb=Glass.keyboard.create(hostElement,{onChar,onBackspace,onClear,onSubmit});  then kb.move(dir), kb.press(), kb.setActive(bool).
   Layouts are plain tables below: edit them to change keys. Key width is a flex weight, so rows always line up. */
(function(){
'use strict';
var G=window.Glass;

function chars(str,extra){ return str.split('').map(function(c){ return {t:c,k:'ch'}; }).concat(extra||[]); }
var BOTTOM=function(modeLabel){ return [
  {t:modeLabel,k:'mode',w:2.1,cls:'fn'},
  {t:'space',k:'space',w:5.4,cls:'sp'},
  {t:'clear',k:'clear',w:1.9,cls:'fn'},
  {t:'search',k:'submit',w:2.2,cls:'fn'}
]; };
var LAYOUTS={
  abc:[
    chars('qwertyuiop'),
    chars('asdfghjkl',[{t:'',k:'shift',ico:'shift',cls:'fn'}]),
    chars('zxcvbnm.-',[{t:'',k:'back',ico:'backspace',cls:'fn'}]),
    BOTTOM('123')
  ],
  num:[
    chars('1234567890'),
    chars('@#&*()-_+='),
    chars(".,?!':;/\"",[{t:'',k:'back',ico:'backspace',cls:'fn'}]),
    BOTTOM('abc')
  ]
};

function create(host,opt){
  var mode='abc', shifted=false, rows=[], pos={r:1,i:0}, active=false, xmem=0, keyEls=[];
  opt=opt||{};

  function build(){
    host.innerHTML=''; rows=[]; keyEls=[];
    var kb=document.createElement('div'); kb.className='kb';
    LAYOUTS[mode].forEach(function(def,ri){
      var row=document.createElement('div'), els=[]; row.className='kr';
      def.forEach(function(k){
        var el=document.createElement('div'); el.className='k'+(k.cls?' '+k.cls:''); el.style.flexGrow=k.w||1;
        el.setAttribute('data-k',k.k);
        if(k.ico) el.innerHTML=G.icons.svg(k.ico); else el.textContent=label(k);
        el._def=k; el.addEventListener('click',function(){ pos={r:ri,i:els.indexOf(el)}; paint(); press(); });
        el.addEventListener('mousemove',function(e){ if(!G.pointer.moved(e)) return; var i=els.indexOf(el); if(active&&pos.r===ri&&pos.i===i) return; pos={r:ri,i:i}; xmem=center(el); if(opt.onHover) opt.onHover(); paint(); });
        row.appendChild(el); els.push(el);
      });
      rows.push(els); kb.appendChild(row);
    });
    host.appendChild(kb); host.style.position='relative';
    paint(); xmem=center(cur());
  }
  function label(k){ return (k.k==='ch'&&shifted)?k.t.toUpperCase():k.t; }
  function cur(){ return rows[pos.r][pos.i]; }
  function center(el){ return el.offsetLeft+el.offsetWidth/2; }
  function paint(){
    rows.forEach(function(r,ri){ r.forEach(function(el,i){
      el.classList.toggle('f',active&&ri===pos.r&&i===pos.i);
      if(el._def.k==='shift'){ el.classList.toggle('on-shift',shifted); el.innerHTML=G.icons.svg(shifted?'shiftOn':'shift'); }
    }); });
  }
  function relabel(){ rows.forEach(function(r){ r.forEach(function(el){ if(el._def.k==='ch') el.textContent=label(el._def); }); }); }
  function nearest(r,x){
    var best=0, bd=1e9; rows[r].forEach(function(el,i){ var d=Math.abs(center(el)-x); if(d<bd){ bd=d; best=i; } }); return best;
  }
  /* returns 'moved' | 'edge' (nothing further that way) */
  function move(dir){
    var r=pos.r, i=pos.i;
    if(dir===37){ if(i===0) return 'edge'; pos.i=i-1; xmem=center(cur()); }
    else if(dir===39){ if(i===rows[r].length-1) return 'edge'; pos.i=i+1; xmem=center(cur()); }
    else if(dir===38){ if(r===0) return 'edge'; pos.r=r-1; pos.i=nearest(pos.r,xmem); }
    else if(dir===40){ if(r===rows.length-1) return 'edge'; pos.r=r+1; pos.i=nearest(pos.r,xmem); }
    paint(); return 'moved';
  }
  /* Tab order: every key, left to right, top to bottom. 'edge' when there is nothing further that way. */
  function step(dir){
    var r=pos.r, i=pos.i+dir;
    if(i<0){ if(r===0) return 'edge'; r--; i=rows[r].length-1; }
    else if(i>=rows[r].length){ if(r===rows.length-1) return 'edge'; r++; i=0; }
    pos={r:r,i:i}; xmem=center(cur()); paint(); return 'moved';
  }
  function jump(last){ pos=last?{r:rows.length-1,i:rows[rows.length-1].length-1}:{r:0,i:0}; xmem=center(cur()); paint(); }
  function press(){
    var el=cur(), k=el._def;
    el.classList.add('p'); setTimeout(function(){ el.classList.remove('p'); },110);
    if(k.k==='ch'){ G.sound.key('alpha'); if(opt.onChar) opt.onChar(shifted?k.t.toUpperCase():k.t); if(shifted){ shifted=false; relabel(); paint(); } }
    else if(k.k==='space'){ G.sound.key('space'); if(opt.onChar) opt.onChar(' '); }
    else if(k.k==='back'){ G.sound.key('alt'); if(opt.onBackspace) opt.onBackspace(); }
    else if(k.k==='clear'){ G.sound.key('alt'); if(opt.onClear) opt.onClear(); }
    else if(k.k==='submit'){ G.sound.key('enter'); if(opt.onSubmit) opt.onSubmit(); }
    else if(k.k==='shift'){ G.sound.key('alt'); shifted=!shifted; relabel(); paint(); }
    else if(k.k==='mode'){ G.sound.key('alt'); mode=(mode==='abc')?'num':'abc'; shifted=false; var keep={r:pos.r,i:pos.i}; build(); pos=keep; paint(); xmem=center(cur()); }
  }
  build();
  return {
    move:move, press:press, step:step, jump:jump,
    setActive:function(a){ active=!!a; paint(); },
    reset:function(){ mode='abc'; shifted=false; build(); pos={r:1,i:0}; xmem=center(cur()); paint(); },
    isActive:function(){ return active; }
  };
}
G.keyboard={create:create};
})();
