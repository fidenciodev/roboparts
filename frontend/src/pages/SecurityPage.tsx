import { useEffect, useRef, useState } from "react";
import { ShieldCheck, UserRound, MailPlus, Activity } from "lucide-react";
import { FormField } from "../components/FormField";
import { useAuth } from "../hooks/useAuth";
import { ApiError } from "../services/api";
import * as security from "../services/securityService";

const eventNames:Record<string,string>={LOGIN_FAILED:"Falha de login",LOGIN_RATE_LIMITED:"Limite de tentativas atingido",MFA_FAILED:"Código do autenticador rejeitado",MFA_PASSWORD_FAILED:"Senha rejeitada nas configurações de segurança"};

export function SecurityPage() {
  const auth=useAuth();const [status,setStatus]=useState<security.SecurityStatus|null>(null);
  const [staff,setStaff]=useState<security.Employee[]>([]);const [invites,setInvites]=useState<security.Invitation[]>([]);
  const [activity,setActivity]=useState<security.SecurityEvents|null>(null);
  const [password,setPassword]=useState("");const [code,setCode]=useState("");const [email,setEmail]=useState("");
  const [setup,setSetup]=useState<security.MfaSetup|null>(null);const [recovery,setRecovery]=useState<string[]>([]);
  const [created,setCreated]=useState<security.CreatedInvitation|null>(null);
  const [pending,setPending]=useState(false);const [error,setError]=useState<string|null>(null);const [notice,setNotice]=useState<string|null>(null);
  const lock=useRef(false);const alert=useRef<HTMLDivElement>(null);
  useEffect(()=>{
    const controller=new AbortController();
    void security.getSecurity(controller.signal).then(async result=>{
      if(controller.signal.aborted)return;setStatus(result);
      if(result.manager) {
        const [people,invitations,events]=await Promise.all([security.employees(controller.signal),security.invitations(controller.signal),security.events(controller.signal)]);
        if(!controller.signal.aborted){setStaff(people);setInvites(invitations);setActivity(events);}
      }
    }).catch(cause=>{if(!controller.signal.aborted)setError(cause instanceof ApiError?cause.message:"Não foi possível carregar a segurança.");});
    return()=>controller.abort();
  },[]);
  async function run(action:()=>Promise<void>) {
    if(lock.current)return;lock.current=true;setPending(true);setError(null);setNotice(null);
    try {await action();}
    catch(cause){setError(cause instanceof ApiError?cause.message:"Não foi possível concluir. Tente novamente.");requestAnimationFrame(()=>alert.current?.focus());}
    finally{setPassword("");setCode("");lock.current=false;setPending(false);}
  }
  async function refreshTeam() { const [people,invites]=await Promise.all([security.employees(),security.invitations()]);setStaff(people);setInvites(invites); }
  return <div className="security-page">
    <header><p className="eyebrow">PROTEÇÃO DA CONTA E DA EQUIPE</p><h1>Segurança</h1><p>Cuide do seu acesso e acompanhe as proteções do RoboParts.</p></header>
    {error&&<div ref={alert} className="auth-feedback auth-feedback--error" role="alert" tabIndex={-1}><p>{error}</p></div>}
    {notice&&<div className="auth-feedback auth-feedback--success" role="status"><p>{notice}</p></div>}
    {!status&&!error&&<p role="status">Carregando segurança…</p>}
    {status&&<section className="security-card"><h2><ShieldCheck size={22}/> Autenticação em duas etapas</h2>
      <p>{status.mfa.enabled?`Ativada. Você tem ${status.mfa.remainingRecoveryCodes} códigos de recuperação disponíveis.`:"Adicione um aplicativo autenticador para proteger o login, mesmo se alguém descobrir sua senha."}</p>
      {recovery.length>0?<div className="security-secret"><h3>Guarde seus códigos de recuperação</h3><p>Eles aparecem somente agora. Cada um pode ser usado uma vez se você perder o celular. Guarde-os em um local privado.</p>
        <textarea aria-label="Seus códigos de recuperação" readOnly rows={8} value={recovery.join("\n")}/>
        <button className="secondary-button" type="button" onClick={()=>setRecovery([])}>Já guardei os códigos</button></div>:null}
      {!status.mfa.enabled&&!setup&&<form onSubmit={event=>{event.preventDefault();void run(async()=>{const result=await security.setupMfa(password);setSetup(result);});}}><fieldset disabled={pending}>
        <FormField id="security-password" name="password" label="Senha atual" type="password" autoComplete="current-password" value={password} minLength={12} maxLength={128} required onChange={event=>setPassword(event.target.value)}/>
        <button className="primary-button" type="submit">{pending?"Preparando…":"Configurar autenticador"}</button>
      </fieldset></form>}
      {!status.mfa.enabled&&setup&&<div className="security-secret"><h3>Adicionar ao aplicativo autenticador</h3>
        <p>No aplicativo autenticador do seu celular, escolha adicionar uma conta por chave de configuração. Use o nome RoboParts, a chave abaixo e códigos baseados em tempo.</p>
        <FormField id="mfa-secret" label="Chave de configuração" value={setup.secret} readOnly autoComplete="off"/>
        <p>Confirme em até 10 minutos. Não envie esta chave a outras pessoas.</p>
        <form onSubmit={event=>{event.preventDefault();void run(async()=>{const result=await security.confirmMfa(code);setRecovery(result.recoveryCodes);setSetup(null);setStatus({...status,mfa:{enabled:true,remainingRecoveryCodes:result.recoveryCodes.length}});setNotice("Autenticação em duas etapas ativada. As outras sessões perderam o acesso.");});}}><fieldset disabled={pending}>
          <FormField id="mfa-confirm" name="code" label="Código do autenticador" value={code} inputMode="numeric" autoComplete="one-time-code" pattern="[0-9]{6}" maxLength={6} required onChange={event=>setCode(event.target.value.replace(/[^0-9]/g,""))}/>
          <button className="primary-button" type="submit">{pending?"Confirmando…":"Ativar duas etapas"}</button>
          <button className="secondary-button" type="button" onClick={()=>{setSetup(null);setCode("");}}>Cancelar configuração</button>
        </fieldset></form>
      </div>}
      {status.mfa.enabled&&recovery.length===0&&<form onSubmit={event=>{event.preventDefault();void run(async()=>{await security.disableMfa(password,code);setStatus({...status,mfa:{enabled:false,remainingRecoveryCodes:0}});setNotice("Autenticação em duas etapas desativada. As outras sessões perderam o acesso.");});}}><fieldset disabled={pending}>
        <p>Para desativar, confirme sua senha e um código do aplicativo ou de recuperação.</p>
        <FormField id="disable-password" name="password" label="Senha atual" type="password" autoComplete="current-password" required minLength={12} maxLength={128} value={password} onChange={event=>setPassword(event.target.value)}/>
        <FormField id="disable-code" name="code" label="Código do autenticador ou de recuperação" autoComplete="one-time-code" value={code} maxLength={80} required onChange={event=>setCode(event.target.value)}/>
        <button className="secondary-button" type="submit" disabled={pending}>{pending?"Desativando…":"Desativar duas etapas"}</button>
      </fieldset></form>}
    </section>}
    {status?.manager&&<>
      <section className="security-card"><h2><MailPlus size={22}/> Convites individuais</h2><p>Um convite vale por três dias e funciona uma vez, somente para o e-mail indicado.</p>
        <form onSubmit={event=>{event.preventDefault();void run(async()=>{setCreated(null);const result=await security.createInvitation(email.trim().toLowerCase());setCreated(result);setEmail("");await refreshTeam();});}}><fieldset disabled={pending}>
          <FormField id="invitation-email" label="E-mail do novo funcionário" type="email" inputMode="email" value={email} required maxLength={254} onChange={event=>setEmail(event.target.value)}/>
          <button type="submit" className="primary-button">{pending?"Aguarde…":"Gerar convite"}</button>
        </fieldset></form>
        {created&&<div className="security-secret"><h3>Convite para {created.email}</h3><p>Copie agora e compartilhe somente com essa pessoa. Ela usará o convite no campo Código de acesso do cadastro.</p>
          <textarea readOnly rows={2} aria-label="Código do convite" value={created.token}/><button className="secondary-button" type="button" onClick={()=>setCreated(null)}>Já copiei o convite</button></div>}
        <ul className="security-list">{invites.map(invite=><li key={invite.id}><span><strong>{invite.email}</strong><small>{invite.consumed?"Usado":invite.revoked?"Revogado":new Date(invite.expiresAt).getTime()<Date.now()?"Expirado":`Válido até ${new Date(invite.expiresAt).toLocaleString("pt-BR")}`}</small></span>
          {!invite.revoked&&!invite.consumed&&<button className="secondary-button" type="button" disabled={pending} onClick={()=>void run(async()=>{await security.revokeInvitation(invite.id);await refreshTeam();})}>Revogar convite</button>}</li>)}</ul>
      </section>
      <section className="security-card"><h2><UserRound size={22}/> Acessos da equipe</h2><p>Bloquear encerra o acesso das sessões existentes. Os registros de trabalho são preservados.</p>
        <ul className="security-list">{staff.map(employee=><li key={employee.id}><span><strong>{employee.name}</strong><small>{employee.email} · {employee.active?"Ativo":"Bloqueado"}{!employee.verified?" · E-mail não confirmado":""}</small></span>
          <button type="button" className="secondary-button" disabled={pending||employee.id===auth.user?.id} onClick={()=>void run(async()=>{await security.activeEmployee(employee.id,!employee.active);await refreshTeam();setNotice(employee.active?"Acesso bloqueado. As sessões anteriores perderam o acesso.":"Acesso reativado. O funcionário precisa entrar novamente.");})}>{employee.id===auth.user?.id?"Sua conta":employee.active?"Bloquear acesso":"Reativar acesso"}</button></li>)}</ul>
      </section>
      <section className="security-card"><h2><Activity size={22}/> Alertas de segurança</h2><p>Atividade das últimas 24 horas. Os identificadores são protegidos para evitar expor e-mails nos registros.</p>
        <button type="button" className="secondary-button" disabled={pending} onClick={()=>void run(async()=>setActivity(await security.events()))}>Atualizar alertas</button>
        {activity?.summary.length===0&&<p>Nenhum alerta registrado nas últimas 24 horas.</p>}
        <ul>{activity?.summary.map(row=><li key={row.event}>{eventNames[row.event]??row.event}: <strong>{row.total}</strong></li>)}</ul>
        <ul className="security-list">{activity?.recent.map((row,index)=><li key={index}><span><strong>{eventNames[row.event]??row.event}</strong><small>Conta {row.account} · {new Date(row.occurred_at).toLocaleString("pt-BR")}</small></span></li>)}</ul>
      </section>
    </>}
  </div>;
}
