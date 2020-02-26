// @flow strict
import React, { useCallback, useState, useRef } from 'react';
import URI from 'urijs';
import history from 'util/History';

import BootstrapModalForm from 'components/bootstrap/BootstrapModalForm';
import { Checkbox, ControlLabel, FormGroup, HelpBlock, MenuItem } from 'components/graylog';
import Input from 'components/bootstrap/Input';
import { Icon } from 'components/common';
import Routes from 'routing/Routes';
import View from 'views/logic/views/View';
import queryTitle from 'views/logic/queries/QueryTitle';

export type UntypedBigDisplayModeQuery = {|
  tabs?: string,
  interval?: string,
  refresh?: string,
|};

type Configuration = {
  refreshInterval: number,
  queryTabs?: Array<number>,
  queryCycleInterval?: number,
};

type ConfigurationModalProps = {
  modalRef: BootstrapModalForm,
  onSave: (Configuration) => void,
  show: boolean,
  view: View,
};

const getAvailableTabs = view => view.search.queries.keySeq().map((query, idx) => [
  idx,
  queryTitle(view, query.id),
]).toJS();

const ConfigurationModal = ({ onSave, modalRef, view, show }: ConfigurationModalProps) => {
  const availableTabs = getAvailableTabs(view);
  const initial = {
    refreshInterval: 10,
    queryCycleInterval: 30,
    queryTabs: availableTabs.map(([idx]) => idx),
  };
  const [refreshInterval, setRefreshInterval] = useState(initial.refreshInterval);
  const [queryTabs, setQueryTabs] = useState(initial.queryTabs);
  const [queryCycleInterval, setQueryCycleInterval] = useState(initial.queryCycleInterval);
  const _addQueryTab = useCallback(idx => setQueryTabs([...queryTabs, idx]), [queryTabs, setQueryTabs]);
  const _removeQueryTab = useCallback(idx => setQueryTabs(queryTabs.filter(tab => tab !== idx)), [queryTabs, setQueryTabs]);
  const _onSave = useCallback(() => onSave({
    refreshInterval,
    queryTabs,
    queryCycleInterval,
  }), [onSave, refreshInterval, queryTabs, queryCycleInterval]);
  const _cleanState = () => {
    setRefreshInterval(initial.refreshInterval);
    setQueryCycleInterval(initial.queryCycleInterval);
    setQueryTabs(initial.queryTabs);
  };

  return (
    <BootstrapModalForm ref={modalRef}
                        show={show}
                        title="Configuring Full Screen"
                        onModalClose={_cleanState}
                        onSubmitForm={_onSave}
                        submitButtonText="Save"
                        bsSize="large">
      <Input autoFocus
             id="refresh-interval"
             type="number"
             min="1"
             name="refresh-interval"
             label="Refresh Interval"
             help="After how many seconds should the dashboard refresh?"
             onChange={({ target: { value } }) => setRefreshInterval(value ? Number.parseInt(value, 10) : value)}
             required
             step={1}
             value={refreshInterval} />
      {/*
      <FormGroup>
        <ControlLabel>Tabs</ControlLabel>
        <ul>
          {availableTabs.map(([idx, title]) => (
            <li key={`${idx}-${title}`}>
              <Checkbox inline
                        checked={queryTabs.includes(idx)}
                        onChange={event => (event.target.checked ? _addQueryTab(idx) : _removeQueryTab(idx))}>
                {title}
              </Checkbox>
            </li>
          ))}
        </ul>
        <HelpBlock>
            Select the query tabs to include in rotation.
        </HelpBlock>
      </FormGroup>

      <Input id="query-cycle-interval"
             type="number"
             min="1"
             name="query-cycle-interval"
             label="Tab cycle interval"
             help="After how many seconds should the next tab be shown?"
             onChange={({ target: { value } }) => setQueryCycleInterval(value ? Number.parseInt(value, 10) : value)}
             required
             step="1"
             value={queryCycleInterval} /> */}
    </BootstrapModalForm>
  );
};

const redirectToBigDisplayMode = (view: View, config: UntypedBigDisplayModeQuery): void => {
  console.log(view, config);
  history.push(
    new URI(Routes.pluginRoute('DASHBOARDS_TV_VIEWID')(view.id))
      .search(config)
      .toString(),
  );
};

const createQueryFromConfiguration = (
  { queryCycleInterval, queryTabs, refreshInterval }: Configuration,
  view: View,
): UntypedBigDisplayModeQuery => {
  const basicConfiguration = {
    interval: Number(queryCycleInterval).toString(),
    refresh: Number(refreshInterval).toString(),
  };
  const allQueryIndices = view.search.queries.toIndexedSeq().map((_, v) => v).toJS();
  return !queryTabs || allQueryIndices.join(',') === queryTabs.join(',')
    ? basicConfiguration
    : { ...basicConfiguration, tabs: queryTabs.join(',') };
};

type Props = {
  disabled?: boolean,
  show?: boolean,
  view: View,
};

const BigDisplayModeConfiguration = ({ disabled, show, view }: Props) => {
  const modal = useRef<BootstrapModalForm>();
  const onSave = (config: Configuration) => redirectToBigDisplayMode(view, createQueryFromConfiguration(config, view));

  return (
    <React.Fragment>
      <ConfigurationModal view={view} onSave={onSave} modalRef={modal} show={show} />
      <MenuItem disabled={disabled} onSelect={() => modal.current.open()}>
        <Icon name="desktop" /> Full Screen
      </MenuItem>
    </React.Fragment>
  );
};

BigDisplayModeConfiguration.defaultProps = {
  disabled: false,
  show: false,
};

export default BigDisplayModeConfiguration;
