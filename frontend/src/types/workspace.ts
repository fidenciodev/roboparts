export interface TreeNode {
 id: string; parentId: string | null; kind: "CATEGORY" | "COMPONENT"; name: string; description: string;
 quantity: number; required: boolean; position: number; archived: boolean;
}
export interface Robot { id: string; name: string; description: string; archived: boolean; version: number; createdAt: string; nodes: TreeNode[] }
export interface ChecklistItem {
 id: string; parentId: string | null; kind: "CATEGORY" | "COMPONENT"; name: string; description: string;
 quantity: number; required: boolean; position: number; takenQuantity: number; checkedByName: string | null; checkedAt: string | null;
}
export type ChecklistStatus = "PENDING" | "IN_PROGRESS" | "COMPLETED" | "RETURNING" | "RETURNED" | "CANCELLED";
export interface Movement { id: string; itemId: string; itemName: string; delta: number; resultingQuantity: number; userName: string; createdAt: string }
export interface Checklist {
 id: string; robotId: string; robotName: string; robotDescription: string; status: ChecklistStatus; version: number;
 startedBy: string; startedByName: string; createdAt: string; finalizedAt: string | null; finalizedByName: string | null;
 total: number; completed: number; pending: number; items: ChecklistItem[]; movements: Movement[];
}
export interface HistoryEntry { id: string; userId: string; userName: string; action: string; details: string; resourceType: string | null; resourceId: string | null; createdAt: string }
export interface Dashboard { robots: number; robotsInUse: number; inProgress: number; completed: number; pendingComponents: number; recent: HistoryEntry[] }
export interface NodeDraft { kind: TreeNode["kind"]; name: string; description: string; parentId: string | null; quantity: number; required: boolean; position: number; archived: boolean }
