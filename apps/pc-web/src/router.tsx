import { Navigate, createBrowserRouter } from 'react-router-dom';
import { AppLayout } from './AppLayout';
import { AudioPage } from './pages/AudioPage';
import { FilesPage } from './pages/FilesPage';
import { PowerPage } from './pages/PowerPage';

/**
 * LAN-only app: no authentication, no route guards. Every request from any
 * device on the same local network is trusted, so the router maps URLs
 * directly to pages without checking any pairing state.
 */
export const router = createBrowserRouter([
  {
    path: '/',
    element: <AppLayout />,
    children: [
      { index: true, element: <Navigate to="/audio" replace /> },
      { path: 'audio', element: <AudioPage /> },
      { path: 'files', element: <FilesPage /> },
      { path: 'power', element: <PowerPage /> },
      { path: '*', element: <Navigate to="/audio" replace /> },
    ],
  },
]);
