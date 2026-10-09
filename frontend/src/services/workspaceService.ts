import { ApiError, apiRequest } from "./api";
import type { Robot, Checklist, Dashboard, HistoryEntry, TreeNode, ChecklistItem, NodeDraft } from "../types/workspace";
const object = (x: unknown): x is Record<string, unknown> => typeof x === "object" && x !== null;
const text = (x: unknown): x is string => typeof x === "string";
const integer = (x: unknown) => typeof x === "number" && Number.isSafeInteger(x);
const date = (x: unknown) => text(x) && Number.isFinite(Date.parse(x));
const nullableText = (x: unknown) => x === null || text(x);
const array = (x: unknown, check: (x: unknown) => boolean) => Array.isArray(x) && x.every(check);
const node = (x: unknown): x is TreeNode => object(x) && text(x.id) && nullableText(x.parentId) && ["CATEGORY","COMPONENT"].includes(String(x.kind)) &&
 text(x.name) && text(x.description) && integer(x.quantity) && Number(x.quantity)>0 && typeof x.required==="boolean" && integer(x.position) && typeof x.archived==="boolean";
const item = (x: unknown): x is ChecklistItem => object(x) && node({...x,archived:false}) && integer(x.takenQuantity) &&
 Number(x.takenQuantity)>=0 && Number(x.takenQuantity)<=Number(x.quantity) && nullableText(x.checkedByName) && (x.checkedAt===null || date(x.checkedAt));
const robot = (x: unknown): x is Robot => object(x) && text(x.id) && text(x.name) && text(x.description) && typeof x.archived==="boolean" && integer(x.version) && date(x.createdAt) && array(x.nodes,node);
const checklist = (x: unknown): x is Checklist => object(x) && text(x.id) && text(x.robotId) && text(x.robotName) && text(x.robotDescription) &&
 ["PENDING","IN_PROGRESS","COMPLETED","RETURNING","RETURNED","CANCELLED"].includes(String(x.status)) && integer(x.version) && text(x.startedBy) && text(x.startedByName) &&
 date(x.createdAt) && (x.finalizedAt===null||date(x.finalizedAt)) && nullableText(x.finalizedByName) && integer(x.total) && integer(x.completed) && integer(x.pending) &&
 array(x.items,item) && array(x.movements,m=>object(m)&&text(m.id)&&text(m.itemId)&&text(m.itemName)&&integer(m.delta)&&integer(m.resultingQuantity)&&text(m.userName)&&date(m.createdAt));
const history = (x: unknown): x is HistoryEntry => object(x)&&text(x.id)&&text(x.userId)&&text(x.userName)&&text(x.action)&&text(x.details)&&nullableText(x.resourceType)&&nullableText(x.resourceId)&&date(x.createdAt);
const dashboard = (x: unknown): x is Dashboard => object(x)&&integer(x.robots)&&integer(x.robotsInUse)&&integer(x.inProgress)&&integer(x.completed)&&integer(x.pendingComponents)&&array(x.recent,history);
function read<T>(value: unknown, check: (x: unknown) => x is T): T {
 if(!check(value))throw new ApiError("A API enviou dados incompatíveis. Atualize a página ou verifique o backend.");
 return value;
}
async function get<T>(url: string, check: (x: unknown) => x is T, signal?: AbortSignal) { return read(await apiRequest(url,{signal}),check); }
async function post<T>(url: string, body: unknown, check: (x: unknown) => x is T) { return read(await apiRequest(url,{method:"POST",body}),check); }
const list = <T>(check: (x: unknown)=>x is T) => (x: unknown): x is T[] => array(x,check);
export const getRobots = (page: number,signal?: AbortSignal) => get("/api/robots?page="+page+"&limit=20",list(robot),signal);
export const getRobot = (id: string,signal?: AbortSignal) => get("/api/robots/"+id,robot,signal);
export const createRobot = (name: string,description: string) => post("/api/robots",{name,description},robot);
export const updateRobot = (r: Robot,changes: Partial<Pick<Robot,"name"|"description"|"archived">>) => post("/api/robots/"+r.id,{name:r.name,description:r.description,archived:r.archived,...changes,expectedVersion:r.version},robot);
export const addNode = (r: Robot,draft: NodeDraft) => post("/api/robots/"+r.id+"/nodes",{...draft,expectedVersion:r.version},robot);
export const updateNode = (r: Robot,n: TreeNode,changes: Partial<NodeDraft>) => post("/api/robots/"+r.id+"/nodes/"+n.id,{...n,...changes,expectedVersion:r.version},robot);
export const startChecklist = (r: Robot,requestId: string) => post("/api/robots/"+r.id+"/checklists",{requestId,expectedVersion:r.version},checklist);
export const getChecklists = (page: number,robotId: string,signal?: AbortSignal) => get("/api/checklists?page="+page+"&limit=20"+(robotId?"&robotId="+encodeURIComponent(robotId):""),list(checklist),signal);
export const getChecklist = (id: string,signal?: AbortSignal) => get("/api/checklists/"+id,checklist,signal);
export const moveItem = (c: Checklist,itemId: string,quantity: number,requestId: string) => post("/api/checklists/"+c.id+"/items/"+itemId+"/movements",{quantity,requestId,expectedVersion:c.version},checklist);
export const finalizeChecklist = (c: Checklist) => post("/api/checklists/"+c.id+"/finalize",{expectedVersion:c.version},checklist);
export const cancelChecklist = (c: Checklist) => post("/api/checklists/"+c.id+"/cancel",{expectedVersion:c.version},checklist);
export const getDashboard = (signal?: AbortSignal) => get("/api/dashboard",dashboard,signal);
export const getHistory = (page: number,mine: boolean,signal?: AbortSignal) => get("/api/history?page="+page+"&limit=20&mine="+mine,list(history),signal);
