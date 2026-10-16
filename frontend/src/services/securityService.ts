import { ApiError, apiGet, apiRequest, refreshCsrf } from "./api";
export interface SecurityStatus { manager:boolean; mfa:{enabled:boolean;remainingRecoveryCodes:number} }
export interface Employee { id:string;name:string;email:string;active:boolean;verified:boolean }
export interface Invitation { id:string;email:string;expiresAt:string;revoked:boolean;consumed:boolean }
export interface CreatedInvitation { id:string;email:string;token:string;expiresAt:string }
export interface MfaSetup { secret:string;uri:string;expiresAt:string }
export interface SecurityEvents { summary:{event:string;total:number}[];recent:{event:string;account:string;occurred_at:string}[] }
type RecordValue=Record<string,unknown>;
const record=(value:unknown):value is RecordValue=>typeof value==="object"&&value!==null&&!Array.isArray(value);
const text=(value:unknown):value is string=>typeof value==="string"&&value.length>0;
const date=(value:unknown)=>text(value)&&Number.isFinite(Date.parse(value));
function checked<T>(value:unknown,valid:boolean):T {
  if(!valid)throw new ApiError("O backend enviou uma configuração de segurança inválida. Atualize a página ou avise o responsável.");
  return value as T;
}
export async function getSecurity(signal?:AbortSignal) {
  const value=await apiGet("/api/security",signal);
  return checked<SecurityStatus>(value,record(value)&&typeof value.manager==="boolean"&&record(value.mfa)&&typeof value.mfa.enabled==="boolean"&&typeof value.mfa.remainingRecoveryCodes==="number"&&Number.isInteger(value.mfa.remainingRecoveryCodes)&&value.mfa.remainingRecoveryCodes>=0);
}
export async function employees(signal?:AbortSignal) {
  const value=await apiGet("/api/security/employees",signal);
  return checked<Employee[]>(value,Array.isArray(value)&&value.every(row=>record(row)&&text(row.id)&&text(row.name)&&text(row.email)&&typeof row.active==="boolean"&&typeof row.verified==="boolean"));
}
export async function invitations(signal?:AbortSignal) {
  const value=await apiGet("/api/security/invitations",signal);
  return checked<Invitation[]>(value,Array.isArray(value)&&value.every(row=>record(row)&&text(row.id)&&text(row.email)&&date(row.expiresAt)&&typeof row.revoked==="boolean"&&typeof row.consumed==="boolean"));
}
export async function events(signal?:AbortSignal) {
  const value=await apiGet("/api/security/events",signal);
  return checked<SecurityEvents>(value,record(value)&&Array.isArray(value.summary)&&value.summary.every(row=>record(row)&&text(row.event)&&typeof row.total==="number"&&row.total>=0)&&Array.isArray(value.recent)&&value.recent.every(row=>record(row)&&text(row.event)&&text(row.account)&&date(row.occurred_at)));
}
export async function activeEmployee(id:string,active:boolean) { await apiRequest(`/api/security/employees/${encodeURIComponent(id)}`,{method:"POST",body:{active}}); }
export async function createInvitation(email:string) {
  const value=await apiRequest("/api/security/invitations",{method:"POST",body:{email}});
  return checked<CreatedInvitation>(value,record(value)&&text(value.id)&&text(value.email)&&text(value.token)&&/^[A-Za-z0-9_-]{43}$/.test(value.token)&&date(value.expiresAt));
}
export async function revokeInvitation(id:string) { await apiRequest(`/api/security/invitations/${encodeURIComponent(id)}/revoke`,{method:"POST"}); }
export async function setupMfa(password:string) {
  const value=await apiRequest("/api/security/mfa/setup",{method:"POST",body:{password}});
  return checked<MfaSetup>(value,record(value)&&text(value.secret)&&/^[A-Z2-7]{32}$/.test(value.secret)&&text(value.uri)&&value.uri.startsWith("otpauth://totp/")&&date(value.expiresAt));
}
export async function confirmMfa(code:string) {
  const value=await apiRequest("/api/security/mfa/confirm",{method:"POST",body:{code}});
  const result=checked<{recoveryCodes:string[]}>(value,record(value)&&Array.isArray(value.recoveryCodes)&&value.recoveryCodes.length===8&&new Set(value.recoveryCodes).size===8&&value.recoveryCodes.every(item=>typeof item==="string"&&/^(?:[a-f0-9]{4}-){7}[a-f0-9]{4}$/.test(item)));
  await refreshCsrf();return result;
}
export async function disableMfa(password:string,code:string) {
  await apiRequest("/api/security/mfa/disable",{method:"POST",body:{password,code}});await refreshCsrf();
}
