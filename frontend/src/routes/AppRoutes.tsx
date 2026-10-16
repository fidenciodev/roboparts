import { Route, Routes } from "react-router";
import { AppLayout } from "../components/AppLayout";
import { OverviewPage } from "../pages/OverviewPage";
import { SystemPage } from "../pages/SystemPage";
import { NotFoundPage } from "../pages/NotFoundPage";
import { LoginPage } from "../pages/LoginPage";
import { RegisterPage } from "../pages/RegisterPage";
import { VerifyEmailPage } from "../pages/VerifyEmailPage";
import { ForgotPasswordPage } from "../pages/ForgotPasswordPage";
import { MfaLoginPage } from "../pages/MfaLoginPage";
import { SecurityPage } from "../pages/SecurityPage";
import { ProtectedRoute, PublicRoute } from "./ProtectedRoute";
import { RobotsPage } from "../pages/RobotsPage";
import { RobotDetailPage } from "../pages/RobotDetailPage";
import { ChecklistsPage } from "../pages/ChecklistsPage";
import { ChecklistDetailPage } from "../pages/ChecklistDetailPage";
import { HistoryPage } from "../pages/HistoryPage";

export function AppRoutes() {
  return (
    <Routes>
      <Route element={<PublicRoute />}>
        <Route path="entrar" element={<LoginPage />} />
        <Route path="cadastro" element={<RegisterPage />} />
        <Route path="confirmar-email" element={<VerifyEmailPage />} />
        <Route path="esqueci-senha" element={<ForgotPasswordPage />} />
        <Route path="segundo-fator" element={<MfaLoginPage />} />
      </Route>
      <Route element={<ProtectedRoute />}>
        <Route element={<AppLayout />}>
          <Route index element={<OverviewPage />} />
          <Route path="sistema" element={<SystemPage />} />
          <Route path="seguranca" element={<SecurityPage />} />
          <Route path="robos" element={<RobotsPage />} />
          <Route path="robos/:id" element={<RobotDetailPage />} />
          <Route path="checklists" element={<ChecklistsPage />} />
          <Route path="checklists/:id" element={<ChecklistDetailPage />} />
          <Route path="historico" element={<HistoryPage />} />
          <Route path="*" element={<NotFoundPage />} />
        </Route>
      </Route>
    </Routes>
  );
}
