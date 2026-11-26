(() => {
  if (!globalThis.AllembraNative) return;
  const bridge = globalThis.AllembraNative;
  const encoder = new TextEncoder(), decoder = new TextDecoder('utf-8', {fatal:true});
  const rounds = 600000, maxBackup = 32 * 1024 * 1024;
  const dayPattern = /^\d{4}-\d{2}-\d{2}$/;
  const formats = /^(image\/(jpeg|png|webp)|(?:audio|video)\/(?:webm|mp4|ogg|aac|3gpp))(?:;[^\r\n]{0,64})?$/i;
  let key, salt, current, hooks, lockedEnvelope, busy = false, pending = Promise.resolve();
  function encode(bytes) {
    const parts=[];
    for(let i=0;i<bytes.length;i+=8192)parts.push(String.fromCharCode(...bytes.subarray(i,i+8192)));
    return btoa(parts.join(''));
  }
  function decode(text) {
    return Uint8Array.from(atob(text),c=>c.charCodeAt(0));
  }
  async function derive(phrase, sourceSalt) {
    const phraseKey=await crypto.subtle.importKey('raw',encoder.encode(phrase),'PBKDF2',false,['deriveKey']);
    return crypto.subtle.deriveKey({name:'PBKDF2',hash:'SHA-256',salt:sourceSalt,iterations:rounds},phraseKey,
      {name:'AES-GCM',length:256},false,['encrypt','decrypt']);
  }
  function descriptor(iv, purpose="Allembra backup v1") {
    return {name:'AES-GCM',iv,additionalData:encoder.encode(purpose),tagLength:128};
  }
  function parseEnvelope(raw) {
    if(typeof raw!=='string'||raw.length>maxBackup)throw new Error('O arquivo excede o limite de restauração.');
    const record=JSON.parse(raw);
    if(record.version!==1||record.rounds!==rounds||typeof record.payload!=='string'
      ||typeof record.salt!=='string'||typeof record.iv!=='string')throw new Error('Arquivo de backup incompatível.');
    const sourceSalt=decode(record.salt),iv=decode(record.iv);
    if(sourceSalt.length!==16||iv.length!==12)throw new Error('Arquivo de backup inválido.');
    return {sourceSalt,iv,cipher:decode(record.payload)};
  }
  const requireValid=(test)=>{if(!test)throw new Error('Conteúdo do backup inválido.');};
  function restoreItem(item, sealed) {
    requireValid(item&&Number.isInteger(item.kind)&&item.kind>=0&&item.kind<=3&&dayPattern.test(item.day)
      &&typeof item.closed==='boolean'&&Number.isInteger(item.term)&&item.term>=-1&&item.term<=6);
    if(sealed)requireValid(item.closed&&Number.isSafeInteger(item.openAt)&&item.openAt>0);
    let content=item.content;
    if(item.kind===0){requireValid(typeof content==='string'&&content.length<=100000);}
    else if(content!==true){
      requireValid(content&&typeof content.type==='string'&&formats.test(content.type)&&typeof content.bytes==='string');
      content=new Blob([decode(content.bytes)],{type:content.type});
    }
    return {kind:item.kind,day:item.day,closed:item.closed,term:item.term,content,...(sealed?{openAt:item.openAt}:{})};
  }
  function restored(data) {
    requireValid(data&&Array.isArray(data.drafts)&&Array.isArray(data.capsules)&&data.drafts.length<=5000&&data.capsules.length<=5000);
    const drafts=data.drafts.map(item=>restoreItem(item,false));
    const capsules=data.capsules.map(capsule=>{
      requireValid(capsule&&/^[a-f0-9-]{36}$/.test(capsule.id)&&Array.isArray(capsule.items)&&capsule.items.length<=5000);
      return {id:capsule.id,items:capsule.items.map(item=>restoreItem(item,true))};
    });
    const entries=new Map();
    for(const entry of [...drafts,...capsules.flatMap(c=>c.items)]){
      const day=entries.get(entry.day)||[];
      day.push(entry.kind);entries.set(entry.day,day);
    }
    const options=data.options||{};
    return {drafts,capsules,entries,options:{
      hours:Number.isInteger(options.hours)&&options.hours>=1&&options.hours<=168?options.hours:24,
      notifications:options.notifications===true,
      mediaLabels:['text','both','icons'].includes(options.mediaLabels)?options.mediaLabels:'text',
      boldLabels:options.boldLabels!==false,reviewGuideSeen:options.reviewGuideSeen===true,
      backup:['off','daily','weekly'].includes(options.backup)?options.backup:'off'
    }};
  }
  async function itemFile(item) {
    if(!(item.content instanceof Blob)) return {...item};
    if(item.content.size>15*1024*1024) throw new Error('O registro excede 15 MB.');
    return {...item,content:{type:item.content.type,bytes:encode(new Uint8Array(await item.content.arrayBuffer()))}};
  }
  async function write(state) {
    if(!key)throw new Error('Desbloqueie o Allembra antes de salvar.');
    const data={
      drafts:await Promise.all(state.drafts.map(itemFile)),
      capsules:await Promise.all(state.capsules.map(async capsule=>({id:capsule.id,items:await Promise.all(capsule.items.map(itemFile))}))),
      options:{hours:state.hours,notifications:state.notifications,mediaLabels:state.mediaLabels,boldLabels:state.boldLabels,reviewGuideSeen:state.reviewGuideSeen,backup:state.backup},
    };
    const iv=crypto.getRandomValues(new Uint8Array(12));
    const payload=await crypto.subtle.encrypt(descriptor(iv),key,encoder.encode(JSON.stringify(data)));
    const record=JSON.stringify({version:1,rounds,salt:encode(salt),iv:encode(iv),payload:encode(new Uint8Array(payload))});
    if(record.length>maxBackup)throw new Error('O cofre desta versão atingiu 32 MB.');
    if(!bridge.writeVault(record))throw new Error('Não foi possível gravar o cofre local.');
  }
  function save(state){
    const next=pending.then(()=>write(state));
    pending=next.catch(()=>{});
    return next;
  }
  function gate(mode, raw) {
    let dialog=document.querySelector('.ae-vault');
    if(dialog){if(dialog.open)dialog.close();dialog.remove();}
    dialog=document.createElement('dialog');
    dialog.className='ae-vault';
    dialog.setAttribute('aria-label','Dados criptografados');
    const settingUp=mode==='setup';
    const hasBio=mode==='unlock'&&bridge.biometricEnabled?.();
    const keyFile=mode==='key';
    dialog.innerHTML=`<form>
      <h2>${settingUp?'Sua chave de memória':mode==='restore'?'Recuperar backup':keyFile?'Recuperar sua frase':'Desbloquear Allembra'}</h2>
      <p>${settingUp?'Crie uma frase de pelo menos 16 caracteres. Guarde-a no gerenciador de senhas ou em lugar seguro. Ela é necessária para recuperar o seu backup.':mode==='restore'?'Use a frase que protegeu este arquivo. Recuperar substitui o Relicário atual.':keyFile?'Use a senha de emergência com que você cifrou este arquivo.':'Use a frase escolhida no primeiro acesso.'}</p>
      <input type="text" name="user" value="Allembra" autocomplete="username" readonly hidden>
      <label>Frase de recuperação<input type="password" name="vault-password" autocomplete="${settingUp?'new-password':'current-password'}" required maxlength="512"></label><button type="button" class="ae-eye" data-eye>Mostrar frase</button>
      ${settingUp?'<label>Repita a frase<input type="password" name="confirmation" autocomplete="new-password" required maxlength="512"></label><label class="ae-first-bio"><span><strong>Ativar biometria</strong><small>Recomendado para desbloquear mais rápido neste aparelho.</small></span><input type="checkbox" name="first-bio"></label>':''}
      ${mode==='restore'?'<label><input type="checkbox" name="replace" required> Confirmo a substituição do Relicário</label>':''}
      <p role="alert"></p>
      <button class="ae-primary" type="submit">${settingUp?'Criar meu Relicário':mode==='restore'||keyFile?'Recuperar':'Desbloquear'}</button>
      ${hasBio?'<button type="button" data-gate-bio>Usar biometria</button>':''}
      ${mode==='restore'||keyFile?'<button type="button" data-gate-cancel>Cancelar</button>':'<button type="button" data-gate-restore>Já tenho um backup</button>'}
    </form>`;
    document.querySelector('.ae-phone').append(dialog);
    installEye(dialog);
    dialog.querySelector('[data-gate-bio]')?.addEventListener('click',()=>bridge.biometricOpen());
    dialog.addEventListener('cancel',event=>{event.preventDefault();if(!busy&&(mode==='restore'||keyFile))exit();});
    dialog.querySelector('[data-gate-restore]')?.addEventListener('click',()=>bridge.chooseRestore());
    dialog.querySelector('[data-gate-cancel]')?.addEventListener('click',()=>{if(!busy)exit();});
    function exit(){
      dialog.close();dialog.remove();
      if(!key&&!settingUp)gate(lockedEnvelope?'unlock':'setup',lockedEnvelope);
    }
    dialog.querySelector('form').addEventListener('submit',async event=>{
      event.preventDefault();
      if(busy)return;
      const form=event.target;
      const alert=form.querySelector('[role=alert]');
      const phrase=form.elements['vault-password'].value;
      if(settingUp&&(phrase.trim().length<16||phrase!==form.elements.confirmation.value)){
        alert.textContent='Use pelo menos 16 caracteres e confirme a mesma frase.';return;
      }
      busy=true;
      const submit=form.querySelector('[type=submit]');submit.disabled=true;
      const formerKey=key,formerSalt=salt;
      try {
        if(settingUp){
          salt=crypto.getRandomValues(new Uint8Array(16));key=await derive(phrase,salt);
          await save(current);
        } else if(keyFile) {
          const {sourceSalt,iv,cipher}=parseEnvelope(raw);
          const rescue=await derive(phrase,sourceSalt);
          const recovered=decoder.decode(await crypto.subtle.decrypt(descriptor(iv,'Allembra recovery key v1'),rescue,cipher));
          if(recovered.length<16||recovered.length>512)throw new Error();
          exit();
          if(!document.querySelector('.ae-vault'))gate(lockedEnvelope?'unlock':'setup',lockedEnvelope);
          const destination=document.querySelector('.ae-vault form');
          destination.elements['vault-password'].value=recovered;
          if(destination.elements.confirmation)destination.elements.confirmation.value=recovered;
          destination.querySelector('[role=alert]').textContent='Frase recuperada. Confira ou continue.';
          return;
        } else {
          const {sourceSalt,iv,cipher}=parseEnvelope(raw);
          const candidate=await derive(phrase,sourceSalt);
          const clear=await crypto.subtle.decrypt(descriptor(iv),candidate,cipher);
          const data=restored(JSON.parse(decoder.decode(clear)));
          if(mode==='restore'&&!bridge.writeVault(raw))throw new Error('Não foi possível gravar o Relicário restaurado.');
          key=candidate;salt=sourceSalt;
          Object.assign(current,{drafts:data.drafts,capsules:data.capsules,entries:data.entries},data.options);
          if(mode==='restore'){lockedEnvelope=raw;bridge.biometricClear?.();bridge.configure(current.backup,current.notifications,current.hours);}
        }
        const enroll=settingUp&&form.elements['first-bio']?.checked;
        form.reset();dialog.close();dialog.remove();hooks.render();
        if(enroll)bridge.biometricSave?.(phrase);
        if(mode==='restore')hooks.present('Backup recuperado.');
      } catch {
        key=formerKey;salt=formerSalt;
        alert.textContent=settingUp?'Não foi possível criar o Relicário. Tente novamente.':'Frase incorreta, arquivo incompatível ou falha ao abrir.';
      } finally {busy=false;submit.disabled=false;}
    });
    dialog.showModal();
  }
  globalThis.allembraRestoreReady=()=>{
    const raw=bridge.takeRestore();
    if(raw){
      try { if(JSON.parse(raw).purpose==='recovery-key'){gate('key',raw);return;} } catch {}
      gate('restore',raw);
    }
    else hooks.present('Não foi possível ler o arquivo.');
  };
  function installEye(dialog){
    const toggle=dialog.querySelector('[data-eye]');
    toggle?.addEventListener('click',()=>{
      const inputs=[...dialog.querySelectorAll('input[data-showable],input[name="vault-password"],input[name=confirmation]')];
      const reveal=inputs[0]?.type==='password';
      inputs.forEach(input=>input.type=reveal?'text':'password');
      toggle.textContent=reveal?'Ocultar frase':'Mostrar frase';
      toggle.setAttribute('aria-pressed',String(!!reveal));
    });
  }
  globalThis.allembraBiometricPhrase=phrase=>{
    const form=document.querySelector('.ae-vault form');
    if(!form||busy)return;
    form.elements['vault-password'].value=phrase;
    form.requestSubmit();
  };
  globalThis.allembraBiometricSaved=()=>{
    document.querySelector('.ae-security')?.close();
    hooks?.present('Biometria ativada para este aparelho.');
  };
  globalThis.allembraSecurityMessage=message=>{
    const target=document.querySelector('.ae-security[open] [role=alert],.ae-vault[open] [role=alert]');
    if(target)target.textContent=message;
    else hooks?.present(message);
  };
  function securitySettings(mode){
    const dialog=document.createElement('dialog');
    dialog.className='ae-vault ae-security';
    const email=mode==='email';
    dialog.innerHTML=`<form><h2>${email?'Chave por e-mail':'Biometria'}</h2>
      <p>${email?'A frase será cifrada com uma senha de emergência diferente. Guarde essa senha em outro lugar. O e-mail abrirá para você revisar antes de enviar.':'Confirme sua frase e use a impressão digital deste aparelho para ativar o desbloqueio.'}</p>
      <label>Frase atual<input name="phrase" type="password" data-showable required maxlength="512"></label>
      ${email?'<label>E-mail de destino<input name="email" type="email" required maxlength="250"></label><label>Senha de emergência<input name="emergency" type="password" data-showable required maxlength="512"></label><label>Repita a senha de emergência<input name="emergency-confirm" type="password" data-showable required maxlength="512"></label>':''}
      <button type="button" class="ae-eye" data-eye>Mostrar frase</button><p role="alert"></p>
      <button class="ae-primary" type="submit">${email?'Preparar e-mail':'Ativar biometria'}</button>
      ${!email&&bridge.biometricEnabled?.()?'<button type="button" data-bio-remove>Remover biometria</button>':''}
      <button type="button" data-security-cancel>Cancelar</button></form>`;
    document.querySelector('.ae-phone').append(dialog);
    dialog.addEventListener('close',()=>dialog.remove());
    dialog.querySelector('[data-security-cancel]').onclick=()=>dialog.close();
    dialog.querySelector('[data-bio-remove]')?.addEventListener('click',()=>{bridge.biometricClear();dialog.close();hooks?.present('Biometria removida.');});
    installEye(dialog);
    dialog.querySelector('form').addEventListener('submit',async event=>{
      event.preventDefault();
      if(busy)return;
      const form=event.target, alert=form.querySelector('[role=alert]');
      const phrase=form.elements.phrase.value;
      const secret=email?form.elements.emergency.value:'';
      if(email&&(secret.trim().length<16||secret!==form.elements['emergency-confirm'].value||secret===phrase)){
        alert.textContent='Crie uma senha de emergência diferente, com pelo menos 16 caracteres, e repita-a.';return;
      }
      busy=true;
      try {
        const envelope=parseEnvelope(bridge.readVault());
        const verified=await derive(phrase,envelope.sourceSalt);
        await crypto.subtle.decrypt(descriptor(envelope.iv),verified,envelope.cipher);
        if(email){
          const sourceSalt=crypto.getRandomValues(new Uint8Array(16)),iv=crypto.getRandomValues(new Uint8Array(12));
          const recovery=await derive(secret,sourceSalt);
          const payload=await crypto.subtle.encrypt(descriptor(iv,'Allembra recovery key v1'),recovery,encoder.encode(phrase));
          bridge.draftRecoveryEmail(form.elements.email.value,JSON.stringify({version:1,purpose:'recovery-key',rounds,salt:encode(sourceSalt),iv:encode(iv),payload:encode(new Uint8Array(payload))}));
          dialog.close();
        } else bridge.biometricSave(phrase);
        form.reset();
      } catch {alert.textContent='Frase incorreta ou cofre indisponível.';}
      finally{busy=false;}
    });
    dialog.showModal();
  }
  globalThis.allembraBackupStatus=message=>hooks?.present(message);
  globalThis.AllembraStore={
    start(state,callbacks){
      current=state;hooks=callbacks;
      lockedEnvelope=bridge.readVault();
      gate(lockedEnvelope?'unlock':'setup',lockedEnvelope);
    },
    save,settings:securitySettings
  };
})();
