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

/* Real-time desktop mirroring over WebRTC (no screenshot polling). */
const screenStart=$('screenStart'),screenStop=$('screenStop'),screenFrame=$('screenFrame');
const screenPlaceholder=$('screenPlaceholder'),screenState=$('screenState');
const screenLiveIndicator=$('screenLiveIndicator'),screenMessage=$('screenMessage');
const screenViewer=$('screenViewer'),screenFullscreen=$('screenFullscreen'),screenTouchControls=$('screenTouchControls');
let screenPointerId=null,screenPointerMoved=false,screenPointerStart=null,lastRemotePosition=null,positionSending=false;
let screenActive=false,screenPeer=null,screenStream=null;
function setScreenUi(active,message){
 screenState.textContent=active?'LIVE':'OFF';
 screenState.classList.toggle('live',active);
 screenStart.hidden=active;screenStop.hidden=!active;screenLiveIndicator.hidden=!active;
 screenFullscreen.disabled=!active;
 if(message)screenMessage.textContent=message;
 if(!active){screenFrame.hidden=true;screenFrame.srcObject=null;screenPlaceholder.hidden=false;screenTouchControls.hidden=true;}
}
async function stopScreenStream(message='Screen stream stopped'){
 screenActive=false;
 if(screenPeer){try{screenPeer.ontrack=null;screenPeer.close()}catch{}screenPeer=null}
 if(screenStream){screenStream.getTracks().forEach(track=>track.stop());screenStream=null}
 setScreenUi(false,message);
 try{await fetch('/api/screen/stop',{method:'POST',keepalive:true})}catch{}
}
async function startScreenStream(){
 if(screenActive)return;
 screenActive=true;setScreenUi(true,'Negotiating low-latency WebRTC stream…');
 screenFrame.hidden=true;screenPlaceholder.hidden=false;
 screenPlaceholder.querySelector('strong').textContent='Connecting to your desktop…';
 screenPlaceholder.querySelector('small').textContent='Starting real-time video transport.';
 try{
  const pc=new RTCPeerConnection({iceServers:[]});
  screenPeer=pc;
  pc.addTransceiver('video',{direction:'recvonly'});
  pc.ontrack=event=>{
   if(!screenActive)return;
   screenStream=event.streams[0]||new MediaStream([event.track]);
   screenFrame.srcObject=screenStream;screenFrame.hidden=false;screenPlaceholder.hidden=true;
   screenFrame.playsInline=true;screenFrame.autoplay=true;
   screenFrame.play().catch(()=>{});
   screenMessage.textContent='WebRTC live · '+$('screenResolution').value+' · '+$('screenFps').value+' FPS target';
  };
  pc.onconnectionstatechange=()=>{
   if(!screenActive)return;
   if(pc.connectionState==='connected')screenMessage.textContent='WebRTC connected · real-time desktop video';
   if(['failed','disconnected','closed'].includes(pc.connectionState)&&pc.connectionState!=='closed')stopScreenStream('Connection lost — press Start viewing to reconnect');
  };
  const offer=await pc.createOffer();
  await pc.setLocalDescription(offer);
  await new Promise((resolve,reject)=>{
   if(pc.iceGatheringState==='complete')return resolve();
   const timeout=setTimeout(()=>resolve(),5000);
   pc.addEventListener('icegatheringstatechange',()=>{
    if(pc.iceGatheringState==='complete'){clearTimeout(timeout);resolve()}
   });
  });
  const response=await fetch('/api/screen/offer',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({sdp:pc.localDescription.sdp,type:pc.localDescription.type,resolution:$('screenResolution').value,fps:Number($('screenFps').value)})});
  const answer=await response.json().catch(()=>({}));
  if(!response.ok)throw Error(answer.error||'Could not establish WebRTC stream');
  if(!screenActive){pc.close();return}
  await pc.setRemoteDescription(answer);
 }catch(error){
  if(screenPeer){try{screenPeer.close()}catch{}screenPeer=null}
  screenActive=false;setScreenUi(false,error.message||'Could not start screen stream');
  screenPlaceholder.querySelector('strong').textContent='Could not connect to your desktop';
  screenPlaceholder.querySelector('small').textContent=error.message||'Check SciNET-Link on the PC and try again.';
  showMessage(error.message||'Could not start screen mirroring',true);
 }
}
screenStart.addEventListener('click',startScreenStream);
screenStop.addEventListener('click',()=>stopScreenStream());

async function enterScreenFullscreen(){
 if(!screenActive)return;
 try{
  if(!document.fullscreenElement)await screenViewer.requestFullscreen();
  if(screenViewer.requestFullscreen&&screenViewer===document.fullscreenElement&&screenTouchControls)screenTouchControls.hidden=false;
  try{await screenViewer.focus({preventScroll:true})}catch{}
  if(screenFrame.requestVideoFrameCallback)screenFrame.requestVideoFrameCallback(()=>{});
  if(screen.orientation&&screen.orientation.lock)await screen.orientation.lock('landscape').catch(()=>{});
 }catch(error){
  showMessage('Fullscreen is unavailable in this browser. Try rotating your phone sideways.',true);
 }
}
async function leaveScreenFullscreen(){
 if(document.fullscreenElement===screenViewer)await document.exitFullscreen().catch(()=>{});
 if(screenTouchControls)screenTouchControls.hidden=true;
 if(screen.orientation&&screen.orientation.unlock){try{screen.orientation.unlock()}catch{}}
}
screenFullscreen.addEventListener('click',enterScreenFullscreen);
$('screenExitFullscreen').addEventListener('click',leaveScreenFullscreen);
document.addEventListener('fullscreenchange',()=>{
 const full=document.fullscreenElement===screenViewer;
 screenTouchControls.hidden=!full||!screenActive;
 screenFullscreen.textContent=full?'⛶ Fullscreen':'⛶ Fullscreen';
 if(!full&&screen.orientation&&screen.orientation.unlock){try{screen.orientation.unlock()}catch{}}
});
document.querySelectorAll('[data-screen-click]').forEach(button=>button.addEventListener('click',async event=>{
 event.stopPropagation();
 const result=await requestControl({action:'mouse-click',button:button.dataset.screenClick},true);
 if(!result)showMessage('Mouse click failed',true);
}));
$('screenType').addEventListener('click',event=>{
 event.stopPropagation();
 leaveScreenFullscreen().finally(()=>{textInput.focus({preventScroll:true})});
});

// Touch directly on the full-screen desktop: tap positions and clicks; drag moves
// the Windows pointer without generating an accidental click on release.
function screenCoordinates(clientX,clientY){
 const rect=screenViewer.getBoundingClientRect();
 const vw=screenFrame.videoWidth||rect.width,vh=screenFrame.videoHeight||rect.height;
 if(!vw||!vh||!rect.width||!rect.height)return null;
 const scale=Math.min(rect.width/vw,rect.height/vh);
 const renderedW=vw*scale,renderedH=vh*scale;
 const left=rect.left+(rect.width-renderedW)/2,top=rect.top+(rect.height-renderedH)/2;
 if(clientX<left||clientX>left+renderedW||clientY<top||clientY>top+renderedH)return null;
 return {x:Math.max(0,Math.min(1,(clientX-left)/renderedW)),y:Math.max(0,Math.min(1,(clientY-top)/renderedH))};
}
function queueRemotePosition(position){
 lastRemotePosition=position;
 if(positionSending)return;
 positionSending=true;
 (async()=>{
  while(lastRemotePosition){
   const next=lastRemotePosition;lastRemotePosition=null;
   await requestControl({action:'mouse-position',x:next.x,y:next.y},true);
  }
  positionSending=false;
 })();
}
screenViewer.addEventListener('pointerdown',event=>{
 if(document.fullscreenElement!==screenViewer||event.target.closest('.screen-touch-controls'))return;
 if(event.pointerType==='mouse'&&event.button!==0)return;
 const position=screenCoordinates(event.clientX,event.clientY);if(!position)return;
 event.preventDefault();screenPointerId=event.pointerId;screenPointerMoved=false;
 screenPointerStart={x:event.clientX,y:event.clientY};queueRemotePosition(position);
 try{screenViewer.setPointerCapture(event.pointerId)}catch{}
});
screenViewer.addEventListener('pointermove',event=>{
 if(document.fullscreenElement!==screenViewer||screenPointerId!==event.pointerId)return;
 if(Math.hypot(event.clientX-screenPointerStart.x,event.clientY-screenPointerStart.y)>6)screenPointerMoved=true;
 const position=screenCoordinates(event.clientX,event.clientY);if(position)queueRemotePosition(position);
});
screenViewer.addEventListener('pointerup',async event=>{
 if(screenPointerId!==event.pointerId)return;
 const click=!screenPointerMoved;screenPointerId=null;screenPointerStart=null;
 if(click)await requestControl({action:'mouse-click',button:'left'},true);
});
screenViewer.addEventListener('pointercancel',()=>{screenPointerId=null;screenPointerStart=null;});

let screenSettingsChangeQueued=false;
async function applyLiveScreenSettings(){
 if(!screenActive||screenSettingsChangeQueued)return;
 screenSettingsChangeQueued=true;
 screenMessage.textContent='Applying stream settings…';
 await stopScreenStream('Reconfiguring stream…');
 screenSettingsChangeQueued=false;
 await startScreenStream();
}
$('screenResolution').addEventListener('change',applyLiveScreenSettings);
$('screenFps').addEventListener('change',applyLiveScreenSettings);
window.addEventListener('pagehide',()=>{screenActive=false;if(screenPeer)screenPeer.close();if(screenStream)screenStream.getTracks().forEach(track=>track.stop());});
