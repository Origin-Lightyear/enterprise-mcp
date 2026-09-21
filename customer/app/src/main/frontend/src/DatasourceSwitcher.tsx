import { Button, Dropdown } from 'antd';
import { ChevronDown, Database } from 'lucide-react';
import { useState } from 'react';
import type { Datasource } from './types';

export function DatasourceSwitcher({ datasources, currentProfileName, loading, onSwitch }: {
  datasources: Datasource[];
  currentProfileName?: string;
  loading: boolean;
  onSwitch: (datasource: Datasource) => void;
}) {
  const [open, setOpen] = useState(false);
  const modeledDatasources = datasources.filter((item) => item.modelingInUse);
  const current = modeledDatasources.find((item) => item.name === currentProfileName);

  return <Dropdown
    open={open}
    onOpenChange={setOpen}
    trigger={['click']}
    menu={{
      items: modeledDatasources.map((item) => ({
        key: item.name,
        label: `${item.name} (${item.database})`,
        disabled: item.name === currentProfileName,
      })),
      onClick: ({ key }) => {
        const selected = modeledDatasources.find((item) => item.name === key);
        if (selected) {
          setOpen(false);
          onSwitch(selected);
        }
      },
    }}
  >
    <Button className="datasource-switcher" aria-label="切换数据源" title="切换数据源" loading={loading}>
      <Database size={14}/><span>{current ? `${current.name} · ${current.database}` : '选择已建模数据源'}</span><ChevronDown size={14}/>
    </Button>
  </Dropdown>;
}
