import { Layout } from 'antd';
import { Database, GitBranch } from 'lucide-react';

const { Header } = Layout;

export function ConsoleHeader({ active, onModeling, onDatasources, actions }: {
  active: 'modeling' | 'datasources';
  onModeling: () => void;
  onDatasources: () => void;
  actions?: React.ReactNode;
}) {
  return <Header className="topbar">
    <div className="brand inverse"><Database size={21}/><strong>Wren</strong></div>
    <nav aria-label="管理控制台主导航">
      <button type="button" title="Modeling" aria-label="Modeling" className={active === 'modeling' ? 'active' : ''} onClick={onModeling}><GitBranch size={15}/><span>Modeling</span></button>
      <button type="button" title="数据源" aria-label="数据源" className={active === 'datasources' ? 'active' : ''} onClick={onDatasources}><Database size={15}/><span>数据源</span></button>
    </nav>
    {actions}
  </Header>;
}
