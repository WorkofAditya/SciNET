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


/* Process Manager — enumerate only while the list is explicitly visible. */
const processResults=$('processResults');
const processLoadStatus=$('processLoadStatus');
const processDisplayButton=$('displayProcesses');
let processSnapshot=[];
let processHasLoaded=false;
let processRefreshTimer=null;
let processFetchBusy=false;
let processRequestController=null;
function processEscape(value){return String(value??'').replace(/[&<>"']/g,ch=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[ch]));}
function processBytes(value){const n=Number(value)||0;if(n<1024)return n+' B';const units=['KB','MB','GB','TB'];let v=n/1024,i=0;while(v>=1024&&i<units.length-1){v/=1024;i++}return v.toFixed(v>=100?0:v>=10?1:2)+' '+units[i];}
function processRuntime(created){if(!created)return 'Unknown';const seconds=Math.max(0,Math.floor(Date.now()/1000-created));const days=Math.floor(seconds/86400),hours=Math.floor(seconds%86400/3600),minutes=Math.floor(seconds%3600/60);return days?days+'d '+hours+'h':hours?hours+'h '+minutes+'m':minutes+'m';}
function processFilteredSorted(){const q=$('processSearch').value.trim().toLowerCase(),sort=$('processSort').value;return processSnapshot.filter(p=>[p.name,p.pid,p.exe,p.username,p.status].some(v=>String(v??'').toLowerCase().includes(q))).sort((a,b)=>sort==='cpu'?(b.cpu_percent-a.cpu_percent||a.name.localeCompare(b.name)):sort==='memory'?(b.memory_bytes-a.memory_bytes||a.name.localeCompare(b.name)):sort==='runtime'?((b.created_at||0)-(a.created_at||0)||a.name.localeCompare(b.name)):a.name.localeCompare(b.name));}
function processActionButtons(p){const disabled=p.protected?' disabled title="Protected process"':'';const min='<svg viewBox="0 0 24 24" aria-hidden="true"><path d="M5 12h14" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"/></svg>';const max='<svg viewBox="0 0 24 24" aria-hidden="true"><rect x="5" y="5" width="14" height="14" rx="1.5" fill="none" stroke="currentColor" stroke-width="2"/></svg>';return '<div class="process-actions"><button class="button process-action process-icon-action" data-process-action="minimize" data-pid="'+p.pid+'"'+disabled+' aria-label="Minimize '+processEscape(p.name)+'" title="Minimize">'+min+'</button><button class="button process-action process-icon-action" data-process-action="maximize" data-pid="'+p.pid+'"'+disabled+' aria-label="Maximize '+processEscape(p.name)+'" title="Maximize">'+max+'</button><button class="button process-action" data-process-action="stop" data-pid="'+p.pid+'"'+disabled+'>End task</button><button class="button process-action process-danger" data-process-action="force-stop" data-pid="'+p.pid+'"'+disabled+'>Force stop</button></div>';}
function processCard(p){return '<article class="process-row"><div class="process-app-icon">'+processEscape((p.app_name||p.name).slice(0,1).toUpperCase())+'</div><div class="process-main"><div class="process-title-row"><strong>'+processEscape(p.app_name||p.name)+'</strong><span class="process-pid">PID '+p.pid+'</span>'+(p.protected?'<span class="process-protected">PROTECTED</span>':'')+'</div><div class="process-meta"><span>CPU '+Number(p.cpu_percent||0).toFixed(1)+'%</span><span>RAM '+processBytes(p.memory_bytes)+'</span><span>Running '+processRuntime(p.created_at)+'</span><span>'+processEscape(p.status)+'</span></div><details class="process-details" data-process-pid="'+p.pid+'"><summary>Details</summary><dl><dt>Window</dt><dd>'+processEscape(p.window_title||'No visible window detected')+'</dd><dt>Executable</dt><dd>'+processEscape(p.exe||'Path unavailable')+'</dd><dt>Account</dt><dd>'+processEscape(p.username)+'</dd><dt>PID</dt><dd>'+p.pid+'</dd></dl></details>'+processActionButtons(p)+'</div></article>';}
function processRenderCategory(category,targetId,countId){
 const target=$(targetId),items=processFilteredSorted().filter(p=>p.category===category);
 const openGroups=new Set([...target.querySelectorAll('details.process-group[open]')].map(node=>node.dataset.processGroup));
 const openDetails=new Set([...target.querySelectorAll('details.process-details[open]')].map(node=>node.dataset.processPid));
 $(countId).textContent=items.length+' process'+(items.length===1?'':'es');
 if(!items.length){target.innerHTML='<div class="process-empty">No matching processes in this section.</div>';return}
 if(!$('processGroupApps').checked){target.innerHTML=items.map(processCard).join('');target.querySelectorAll('details.process-details').forEach(node=>{if(openDetails.has(node.dataset.processPid))node.open=true;});return}
 const groups=new Map();
 items.forEach(p=>{const key=(p.exe||p.name).toLowerCase();if(!groups.has(key))groups.set(key,[]);groups.get(key).push(p)});
 target.innerHTML=[...groups.entries()].map(([key,group])=>{
  const windowed=group.filter(p=>p.window_title);
  const representative=windowed[0]||group[0];
  const label=representative.window_title||representative.app_name||representative.name;
  return '<details class="process-group" data-process-group="'+processEscape(key)+'"'+(openGroups.has(key)?' open':'')+'><summary><span>'+processEscape(label)+'</span><span class="process-group-count">'+group.length+' process'+(group.length===1?'':'es')+(windowed.length?' · window available':'')+'</span></summary><div>'+processCard(representative)+'</div></details>';
 }).join('');
 target.querySelectorAll('details.process-details').forEach(node=>{if(openDetails.has(node.dataset.processPid))node.open=true;});
}
function renderProcessManager(){const filtered=processFilteredSorted();$('processTotal').textContent=filtered.length.toLocaleString();processRenderCategory('user','userProcesses','userProcessCount');processRenderCategory('system','systemProcesses','systemProcessCount');processRenderCategory('background','backgroundProcesses','backgroundProcessCount');}
async function loadCurrentProcesses(){if(processFetchBusy||processResults.hidden)return;processFetchBusy=true;const controller=new AbortController();processRequestController=controller;if(processLoadStatus)processLoadStatus.textContent='Updating live process list…';try{const response=await fetch('/api/processes',{cache:'no-store',signal:controller.signal});const data=await response.json().catch(()=>({error:'The server returned an unreadable response'}));if(!response.ok)throw Error(data.error||'Could not read processes');processSnapshot=data.processes||[];processHasLoaded=true;$('processCpu').textContent=Number(data.cpu_percent||0).toFixed(1)+'%';$('processMemory').textContent=Number(data.memory_percent||0).toFixed(1)+'%';$('processMemoryDetail').textContent=processBytes(data.memory_used)+' of '+processBytes(data.memory_total);$('processTotal').textContent=processSnapshot.length.toLocaleString();if(processLoadStatus)processLoadStatus.textContent='Live · updated '+new Date().toLocaleTimeString();renderProcessManager();}catch(error){if(error.name!=='AbortError'&&!processResults.hidden){if(processLoadStatus)processLoadStatus.textContent=error.message;showMessage('Process list: '+error.message,true)}}finally{processFetchBusy=false;if(processRequestController===controller)processRequestController=null;}}
function stopProcessMonitoring(){if(processRefreshTimer!==null){clearInterval(processRefreshTimer);processRefreshTimer=null;}if(processRequestController){processRequestController.abort();processRequestController=null;}fetch('/api/processes/monitor',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({enabled:false}),keepalive:true}).catch(()=>{});}
async function startProcessMonitoring(){stopProcessMonitoring();if(processResults.hidden)return;try{const response=await fetch('/api/processes/monitor',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({enabled:true})});const data=await response.json().catch(()=>({}));if(!response.ok)throw Error(data.error||'Could not start process monitoring');if(processResults.hidden)return;await loadCurrentProcesses();if(!processResults.hidden)processRefreshTimer=setInterval(loadCurrentProcesses,1000);}catch(error){if(!processResults.hidden){if(processLoadStatus)processLoadStatus.textContent=error.message;showMessage('Process monitoring: '+error.message,true);}}}
processDisplayButton.addEventListener('click',async()=>{if(!processResults.hidden){processResults.hidden=true;processSnapshot=[];processHasLoaded=false;stopProcessMonitoring();processDisplayButton.textContent='▤ Display current processes';processDisplayButton.setAttribute('aria-expanded','false');if(processLoadStatus)processLoadStatus.textContent='';return;}processResults.hidden=false;processDisplayButton.textContent='Loading processes…';processDisplayButton.disabled=true;processDisplayButton.setAttribute('aria-expanded','true');try{await startProcessMonitoring()}finally{processDisplayButton.disabled=false;processDisplayButton.textContent=processResults.hidden?'▤ Display current processes':'▤ Hide current processes';}});
$('processSearch').addEventListener('input',()=>{if(processHasLoaded)renderProcessManager()});$('processSort').addEventListener('change',()=>{if(processHasLoaded)renderProcessManager()});$('processGroupApps').addEventListener('change',()=>{if(processHasLoaded)renderProcessManager()});
$('processManager').addEventListener('click',async event=>{const button=event.target.closest('[data-process-action]');if(!button||button.disabled)return;const pid=Number(button.dataset.pid),action=button.dataset.processAction;const proc=processSnapshot.find(p=>p.pid===pid);if(!proc)return;button.disabled=true;if(processLoadStatus)processLoadStatus.textContent=(action==='force-stop'?'Force stopping':action==='minimize'?'Minimizing':action==='maximize'?'Maximizing':'Ending')+' '+proc.name+'…';try{const response=await fetch('/api/processes/action',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({pid,action})});const data=await response.json().catch(()=>({error:'The server returned an unreadable response'}));if(!response.ok)throw Error(data.error||'Process action failed');showMessage(data.message||'Process action completed');await loadCurrentProcesses();}catch(error){showMessage(error.message,true);if(processLoadStatus)processLoadStatus.textContent=error.message}finally{button.disabled=false;}});
window.addEventListener('pagehide',stopProcessMonitoring);
