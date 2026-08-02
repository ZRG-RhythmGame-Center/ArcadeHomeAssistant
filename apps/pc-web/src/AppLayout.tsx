import { Outlet, Link, useLocation } from 'react-router-dom';

export function AppLayout() {
  const location = useLocation();
  const tabs = [
    { to: '/audio', label: '音频' },
    { to: '/files', label: '文件' },
    { to: '/launcher', label: '启动器' },
    { to: '/power', label: '电源' },
    { to: '/settings', label: '设置' },
  ];

  return (
    <div className="app-shell">
      <header className="app-header">
        <h2 className="app-title">maimai home</h2>
        <nav className="app-nav">
          {tabs.map((tab) => {
            const active = location.pathname.startsWith(tab.to);
            return (
              <Link
                key={tab.to}
                to={tab.to}
                className={active ? 'app-nav-link app-nav-link--active' : 'app-nav-link'}
              >
                {tab.label}
              </Link>
            );
          })}
        </nav>
      </header>
      <main className="app-main">
        <Outlet />
      </main>
    </div>
  );
}
