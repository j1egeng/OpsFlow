import { BrowserRouter, Navigate, Outlet, Route, Routes, useLocation } from 'react-router-dom'
import { AuthProvider, useAuth } from './auth/AuthContext'
import { AppShell } from './components/AppShell'
import { Brand, Spinner } from './components/ui'
import { AccountPage } from './pages/AccountPage'
import { LoginPage } from './pages/LoginPage'
import { UsersPage } from './pages/UsersPage'
import { ServicesPage } from './pages/ServicesPage'
import { ServiceDetailsPage } from './pages/ServiceDetailsPage'
import { I18nProvider } from './i18n/I18nContext'
import { IncidentsPage } from './pages/IncidentsPage'
import { IncidentDetailsPage } from './pages/IncidentDetailsPage'
import { ChangesPage } from './pages/ChangesPage'
import { ChangeDetailsPage } from './pages/ChangeDetailsPage'
import { DashboardPage } from './pages/DashboardPage'
import { AuditLogsPage } from './pages/AuditLogsPage'

function ProtectedRoute() {
  const { user, loading } = useAuth()
  const location = useLocation()
  if (loading) return <div className="app-loading"><Brand /><Spinner label="正在恢复工作台" /></div>
  return user ? <Outlet /> : <Navigate to="/login" replace state={{ from: location.pathname }} />
}

function AdminRoute() {
  const { user } = useAuth()
  return user?.roles.includes('ADMINISTRATOR') ? <Outlet /> : <Navigate to="/account" replace />
}

function HomeRoute() {
  const { user } = useAuth()
  return <Navigate to={user?.roles.includes('ADMINISTRATOR') ? '/users' : '/account'} replace />
}

function LoginRoute() {
  const { loading } = useAuth()
  return loading ? <div className="app-loading"><Brand /><Spinner label="正在恢复工作台" /></div> : <LoginPage />
}

export default function App() {
  return <I18nProvider><BrowserRouter><AuthProvider><Routes>
    <Route path="/login" element={<LoginRoute />} />
    <Route element={<ProtectedRoute />}><Route element={<AppShell />}>
      <Route index element={<HomeRoute />} />
      <Route path="/account" element={<AccountPage />} />
      <Route element={<AdminRoute />}>
        <Route path="/users" element={<UsersPage />} />
        <Route path="/audit-logs" element={<AuditLogsPage />} />
      </Route>
      <Route path="/services" element={<ServicesPage />} />
      <Route path="/services/:id" element={<ServiceDetailsPage />} />
      <Route path="/incidents" element={<IncidentsPage />} />
      <Route path="/incidents/:id" element={<IncidentDetailsPage />} />
      <Route path="/changes" element={<ChangesPage />} />
      <Route path="/changes/:id" element={<ChangeDetailsPage />} />
      <Route path="/dashboard" element={<DashboardPage />} />
      <Route path="*" element={<HomeRoute />} />
    </Route></Route>
  </Routes></AuthProvider></BrowserRouter></I18nProvider>
}
