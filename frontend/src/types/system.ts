export type ServiceState = "UP" | "DOWN";

export interface SystemStatus {
  application: string;
  status: ServiceState;
  database: {
    status: ServiceState;
    version: string;
  };
  migrations: {
    version: string;
    description: string;
  };
}
