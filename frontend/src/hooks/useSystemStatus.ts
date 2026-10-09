import { useQuery } from "@tanstack/react-query";
import { getSystemStatus } from "../services/systemService";

export function useSystemStatus() {
  return useQuery({
    queryKey: ["system", "status"],
    queryFn: ({ signal }) => getSystemStatus(signal),
    retry: false,
    staleTime: 30_000,
    refetchOnWindowFocus: false,
  });
}
