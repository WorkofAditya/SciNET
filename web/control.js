const $=id=>document.getElementById(id);
let installPrompt=null;
const toast=$('controlToast'),status=$('controlStatus');
const heldModifiers=new Set();
let busyActions=false;
function showMessage(message,error=false){toast.textContent=message;toast.classList.toggle('error',error);toast.classList.add('visible');status.textContent=error?'ERROR':'READY';clearTimeout(showMessage.timer);showMessage.timer=setTimeout(()=>toast.classList.remove('visible'),3500)}
async function requestControl(payload,quiet=false){
 try{
  const r=await fetch('/api/control',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(payload)});
  const data=await r.json().catch(()=>({}));
  if(!r.ok)throw Error(data.error||'Control command failed');
  if(!quiet&&data.action)showMessage('Sent: '+data.action.replaceAll('-',' '));
  return data;
 }catch(e){if(!quiet)showMessage(e.message||'Could not reach the host',true);return null}
}
async function sendAction(action){
 if(busyActions)return;busyActions=true;
 const buttons=[...document.querySelectorAll('[data-action]')];buttons.forEach(b=>b.disabled=true);status.textContent='SENDING';
 try{const result=await requestControl({action},true);if(result)showMessage(action==='shutdown'?'Shutdown command sent':action==='sleep'?'Sleep requested':action==='lock'?'Lock command sent':`Sent: ${action.replaceAll('-',' ')}`,false)}
 finally{busyActions=false;buttons.forEach(b=>b.disabled=false)}
}
document.querySelectorAll('[data-action]').forEach(b=>b.addEventListener('click',()=>sendAction(b.dataset.action)));
const modifierButtons=[...document.querySelectorAll('[data-modifier]')];
async function setModifier(name,enabled){
 const result=await requestControl({action:'modifier',key:name,down:enabled},true);
 if(!result)return;
 if(enabled)heldModifiers.add(name);else heldModifiers.delete(name);
 const button=document.querySelector('[data-modifier="'+name+'"]');if(button)button.classList.toggle('active',enabled);
}
modifierButtons.forEach(button=>button.addEventListener('click',()=>setModifier(button.dataset.modifier,!heldModifiers.has(button.dataset.modifier))));
async function releaseModifiers(){
 for(const name of [...heldModifiers])await setModifier(name,false);
}
window.addEventListener('pagehide',()=>{for(const name of heldModifiers)navigator.sendBeacon('/api/control',new Blob([JSON.stringify({action:'modifier',key:name,down:false})],{type:'application/json'}))});
document.addEventListener('visibilitychange',()=>{if(document.hidden)releaseModifiers()});
const textInput=$('remoteTextInput'),openKeyboard=$('openKeyboard');
if(textInput&&openKeyboard)openKeyboard.addEventListener('click',()=>textInput.focus({preventScroll:true}));
let textQueue=Promise.resolve();
textInput.addEventListener('input',()=>{
 const value=textInput.value;if(!value)return;
 textInput.value='';
 textQueue=textQueue.then(()=>requestControl({action:'type',text:value},true)).then(result=>{if(result)showMessage('Text sent to PC')});
});
textInput.addEventListener('keydown',event=>{
 if(event.key==='Enter'&&!event.shiftKey){event.preventDefault();requestControl({action:'key',key:'enter'},true)}
 if(event.key==='Escape'){event.preventDefault();textInput.blur()}
});
document.querySelectorAll('[data-key]').forEach(button=>button.addEventListener('click',async()=>{
 const result=await requestControl({action:'key',key:button.dataset.key,modifiers:[...heldModifiers]},true);
 if(!result)showMessage('Could not send key',true);else showMessage('Key sent: '+button.textContent.trim());
}));
const trackpad=$('trackpad');let pointer=null,moved=false,lastX=0,lastY=0,startX=0,startY=0,lastMoveAt=0;
trackpad.addEventListener('pointerdown',event=>{
 if(event.pointerType==='mouse'&&event.button!==0)return;
 trackpad.setPointerCapture(event.pointerId);pointer=event.pointerId;moved=false;lastX=startX=event.clientX;lastY=startY=event.clientY;lastMoveAt=performance.now();
});
trackpad.addEventListener('pointermove',event=>{
 if(pointer!==event.pointerId)return;
 const dx=event.clientX-lastX,dy=event.clientY-lastY;lastX=event.clientX;lastY=event.clientY;
 if(Math.hypot(event.clientX-startX,event.clientY-startY)>5)moved=true;
 if(dx||dy){const scale=window.innerWidth<600?1.65:1.2;requestControl({action:'mouse-move',dx:Math.round(dx*scale),dy:Math.round(dy*scale)},true)}
});
async function finishPad(event){
 if(pointer!==event.pointerId)return;
 const wasMoved=moved;pointer=null;
 if(!wasMoved)await requestControl({action:'mouse-click',button:'left'},true);
}
trackpad.addEventListener('pointerup',finishPad);trackpad.addEventListener('pointercancel',()=>{pointer=null});
document.querySelectorAll('[data-mouse-click]').forEach(button=>button.addEventListener('click',async()=>{
 const result=await requestControl({action:'mouse-click',button:button.dataset.mouseClick},true);
 if(result)showMessage(button.dataset.mouseClick==='right'?'Right click sent':'Left click sent');else showMessage('Mouse click failed',true);
}));
window.addEventListener('beforeinstallprompt',e=>{e.preventDefault();installPrompt=e;$('installApp').hidden=false});
$('installApp').addEventListener('click',async()=>{if(!installPrompt){showMessage('Use your browser menu to install SciNET when available.',true);return}installPrompt.prompt();await installPrompt.userChoice;installPrompt=null;$('installApp').hidden=true});
if('serviceWorker'in navigator)window.addEventListener('load',()=>navigator.serviceWorker.register('/sw.js').catch(()=>{}));
