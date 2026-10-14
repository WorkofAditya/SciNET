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
 try{const result=await requestControl({action},true);if(result)showMessage(action==='shutdown'?'Shutdown command sent':action==='sleep'?'Sleep requested':action==='restart'?'Restart scheduled in 10 seconds':action==='lock'?'Lock command sent':`Sent: ${action.replaceAll('-',' ')}`,false)}
 finally{busyActions=false;buttons.forEach(b=>b.disabled=false)}
}
document.querySelectorAll('[data-action]').forEach(b=>b.addEventListener('click',()=>sendAction(b.dataset.action)));
const modifierButtons=[...document.querySelectorAll('[data-modifier]')];
function clearModifiers(){
 heldModifiers.clear();
 modifierButtons.forEach(button=>button.classList.remove('active'));
}
function consumeModifiers(){const modifiers=[...heldModifiers];clearModifiers();return modifiers;}
modifierButtons.forEach(button=>button.addEventListener('pointerdown',event=>event.preventDefault()));
modifierButtons.forEach(button=>button.addEventListener('click',()=>{
 const name=button.dataset.modifier;
 if(heldModifiers.has(name)){heldModifiers.delete(name);button.classList.remove('active')}
 else{heldModifiers.add(name);button.classList.add('active')}
}));
window.addEventListener('pagehide',clearModifiers);
document.addEventListener('visibilitychange',()=>{if(document.hidden)clearModifiers()});
const textInput=$('remoteTextInput'),openKeyboard=$('openKeyboard');
if(textInput&&openKeyboard)openKeyboard.addEventListener('click',()=>textInput.focus({preventScroll:true}));
let textQueue=Promise.resolve();
function queueText(value){if(!value)return;textQueue=textQueue.then(()=>requestControl({action:'type',text:value},true)).then(result=>{if(result)showMessage('Text sent to PC')})}
let composingText=false;
function sendCommittedText(){
 if(!textInput||composingText||heldModifiers.size)return;
 const value=textInput.value;
 const boundary=Math.max(value.lastIndexOf(' '),value.lastIndexOf(String.fromCharCode(10)),value.lastIndexOf(String.fromCharCode(9)));
 if(boundary<0)return;
 const committed=value.slice(0,boundary+1);
 textInput.value=value.slice(boundary+1);
 queueText(committed);
}
function keyNameFromEvent(key){
 const k=String(key||'').toLowerCase();
 if(k==='escape')return 'esc';
 if(k===' ')return 'space';
 if(k==='arrowup')return 'up';if(k==='arrowdown')return 'down';if(k==='arrowleft')return 'left';if(k==='arrowright')return 'right';
 if(k==='backspace')return 'backspace';if(k==='delete')return 'delete';if(k==='enter')return 'enter';if(k==='tab')return 'tab';
 if(k==='home'||k==='end')return k;
 if(/^f([1-9]|1[0-2])$/.test(k))return k;
 if(/^[a-z0-9]$/.test(k))return k;
 return null;
}
async function sendKey(key,modifiers=[]){
 const result=await requestControl({action:'key',key,modifiers},true);
 if(!result)showMessage('Could not send key',true);
 else showMessage('Key sent: '+key.toUpperCase());
}
if(textInput){
 document.querySelectorAll('.keyboard-section button:not(#openKeyboard),.keyboard-section summary').forEach(control=>control.addEventListener('pointerdown',event=>{
  // Keep modifier/special-key taps from reopening the phone's native keyboard
  // when the textarea still owns focus after the keyboard was dismissed.
  event.preventDefault();
  if(document.activeElement===textInput)textInput.blur();
 }));
 textInput.addEventListener('compositionstart',()=>{composingText=true});
 textInput.addEventListener('compositionend',()=>{composingText=false;sendCommittedText()});
 textInput.addEventListener('keydown',event=>{
  const key=keyNameFromEvent(event.key);
  if(heldModifiers.size&&key){
   event.preventDefault();const modifiers=consumeModifiers();sendKey(key,modifiers);return;
  }
  if(event.key==='Backspace'||event.key==='Delete'){
   if(!textInput.value){event.preventDefault();sendKey(event.key==='Backspace'?'backspace':'delete',consumeModifiers())}return;
  }
  if(event.key==='Enter'&&!event.shiftKey){
   event.preventDefault();if(textInput.value){queueText(textInput.value);textInput.value=''}sendKey('enter',consumeModifiers());return;
  }
  if(event.key==='Escape'){event.preventDefault();textInput.blur()}
 });
 textInput.addEventListener('beforeinput',event=>{
  if((event.inputType==='deleteContentBackward'||event.inputType==='deleteContentForward')&&!textInput.value){event.preventDefault();sendKey(event.inputType==='deleteContentBackward'?'backspace':'delete',consumeModifiers())}
 });
 textInput.addEventListener('input',()=>{
  if(composingText)return;
  if(heldModifiers.size){
   const value=textInput.value;if(!value)return;textInput.value='';
   const modifiers=consumeModifiers();
   const chars=Array.from(value);
   textQueue=textQueue.then(async()=>{for(const char of chars){const key=keyNameFromEvent(char);if(key)await requestControl({action:'key',key,modifiers},true);else await requestControl({action:'type',text:char},true)}}).then(()=>showMessage('Shortcut sent to PC'));
   return;
  }
  sendCommittedText();
 });
}
document.querySelectorAll('[data-key]').forEach(button=>button.addEventListener('click',()=>{
 const key=button.dataset.key;sendKey(key,consumeModifiers());
}));
const trackpad=$('trackpad');let pointer=null,moved=false,lastX=0,lastY=0,startX=0,startY=0;
trackpad.addEventListener('pointerdown',event=>{
 if(event.pointerType==='mouse'&&event.button!==0)return;
 trackpad.setPointerCapture(event.pointerId);pointer=event.pointerId;moved=false;lastX=startX=event.clientX;lastY=startY=event.clientY;
});
trackpad.addEventListener('pointermove',event=>{
 if(pointer!==event.pointerId)return;
 const dx=event.clientX-lastX,dy=event.clientY-lastY;lastX=event.clientX;lastY=event.clientY;
 if(Math.hypot(event.clientX-startX,event.clientY-startY)>5)moved=true;
 if(dx||dy){const scale=window.innerWidth<600?3.0:2.0;requestControl({action:'mouse-move',dx:Math.round(dx*scale),dy:Math.round(dy*scale)},true)}
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

/* Desktop screen mirroring is explicitly opt-in and captures only while active. */
const screenStart=$('screenStart'),screenStop=$('screenStop'),screenFrame=$('screenFrame');
const screenPlaceholder=$('screenPlaceholder'),screenState=$('screenState');
const screenLiveIndicator=$('screenLiveIndicator'),screenMessage=$('screenMessage');
let screenActive=false,screenAbort=null,screenObjectUrl=null;
const screenDelay=ms=>new Promise(resolve=>setTimeout(resolve,ms));
function setScreenUi(active,message){
 screenState.textContent=active?'LIVE':'OFF';
 screenState.classList.toggle('live',active);
 screenStart.hidden=active;screenStop.hidden=!active;
 screenLiveIndicator.hidden=!active;
 if(message)screenMessage.textContent=message;
 if(!active){
  screenFrame.hidden=true;screenPlaceholder.hidden=false;
  if(screenObjectUrl){URL.revokeObjectURL(screenObjectUrl);screenObjectUrl=null}
  screenFrame.removeAttribute('src');
 }
}
async function runScreenLoop(){
 while(screenActive){
  screenAbort=new AbortController();
  try{
   const response=await fetch('/api/screen/frame?t='+Date.now(),{cache:'no-store',signal:screenAbort.signal});
   if(!response.ok){
    let details={};try{details=await response.json()}catch{}
    throw Error(details.error||'Screen capture failed');
   }
   const blob=await response.blob();
   if(!screenActive)break;
   const nextUrl=URL.createObjectURL(blob);
   const previousUrl=screenObjectUrl;screenObjectUrl=nextUrl;
   screenFrame.src=nextUrl;screenFrame.hidden=false;screenPlaceholder.hidden=true;
   if(previousUrl)URL.revokeObjectURL(previousUrl);
   screenMessage.textContent='Live desktop · refreshing automatically';
  }catch(error){
   if(!screenActive||error.name==='AbortError')break;
   screenActive=false;setScreenUi(false,error.message||'Screen view stopped');
   showMessage(error.message||'Could not capture the desktop',true);
   break;
  }
  await screenDelay(250);
 }
 screenAbort=null;
}
screenStart.addEventListener('click',()=>{
 if(screenActive)return;
 screenActive=true;setScreenUi(true,'Connecting to desktop…');
 screenFrame.hidden=true;screenPlaceholder.hidden=false;
 screenPlaceholder.querySelector('strong').textContent='Connecting to your desktop…';
 screenPlaceholder.querySelector('small').textContent='The screen view is active only while this session runs.';
 runScreenLoop();
});
screenStop.addEventListener('click',()=>{
 screenActive=false;if(screenAbort)screenAbort.abort();
 setScreenUi(false,'Screen capture is off');
 screenPlaceholder.querySelector('strong').textContent='Your desktop is not being shared';
 screenPlaceholder.querySelector('small').textContent='Screen capture starts only when you press Start viewing.';
});
window.addEventListener('pagehide',()=>{
 screenActive=false;if(screenAbort)screenAbort.abort();
 if(screenObjectUrl)URL.revokeObjectURL(screenObjectUrl);
});
