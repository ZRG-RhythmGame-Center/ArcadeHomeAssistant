import { RouterProvider } from 'react-router-dom';
import { router } from './router';
import { useEventStream } from './hooks/useEventStream';

/**
 * Root application component.
 *
 * Owns the single realtime event stream subscription via `useEventStream()`
 * so router-driven page navigation never tears down the WebSocket.
 * Renders the configured react-router instance.
 */
export function App() {
  useEventStream();
  return <RouterProvider router={router} />;
}
