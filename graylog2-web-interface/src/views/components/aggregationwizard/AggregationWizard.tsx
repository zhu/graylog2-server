/*
 * Copyright (C) 2020 Graylog, Inc.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the Server Side Public License, version 1,
 * as published by MongoDB, Inc.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * Server Side Public License for more details.
 *
 * You should have received a copy of the Server Side Public License
 * along with this program. If not, see
 * <http://www.mongodb.com/licensing/server-side-public-license>.
 */
import * as React from 'react';
import { useState } from 'react';
import styled from 'styled-components';
import { isEmpty } from 'lodash';
import { EditWidgetComponentProps } from 'views/types';

import AggregationWidgetConfig from 'views/logic/aggregationbuilder/AggregationWidgetConfig';
import Series from 'views/logic/aggregationbuilder/Series';

import AggregationActionSelect from './AggregationActionSelect';
import ActionConfigurationContainer from './actionConfiguration/ActionConfigurationContainer';
import VisualizationConfiguration from './actionConfiguration/VisualizationConfiguration';
import GroupByConfiguration from './actionConfiguration/GroupByConfiguration';
import MetricConfiguration from './actionConfiguration/MetricConfiguration';
import SortConfiguration from './actionConfiguration/SortConfiguration';

export type CreateAggregationAction = (config: AggregationWidgetConfig, onConfigChange: (newConfig: AggregationWidgetConfig) => void) => AggregationAction;

export type AggregationAction = {
  title: string,
  key: string,
  isConfigured: boolean,
  multipleUse: boolean
  onCreate: () => void,
  onDeleteAll?: () => void,
  component: React.ComponentType<{
    config: AggregationWidgetConfig,
    onConfigChange: (newConfig: AggregationWidgetConfig) => void
  }>,
}

const createVisualizationAction: CreateAggregationAction = (config, onConfigChange) => ({
  title: 'Visualization',
  key: 'visualization',
  multipleUse: false,
  isConfigured: !isEmpty(config.visualization),
  onCreate: () => onConfigChange(config),
  component: VisualizationConfiguration,
});

const createMetricAction: CreateAggregationAction = (config, onConfigChange) => ({
  title: 'Metric',
  key: 'metric',
  multipleUse: true,
  isConfigured: !isEmpty(config.series),
  onCreate: () => onConfigChange(config.toBuilder().series([Series.createDefault()]).build()),
  onDeleteAll: () => onConfigChange(config.toBuilder().series([]).build()),
  component: MetricConfiguration,
});

const createGroupByAction: CreateAggregationAction = (config, onConfigChange) => ({
  title: 'Group By',
  key: 'groupBy',
  multipleUse: true,
  isConfigured: !isEmpty(config.rowPivots) || !isEmpty(config.columnPivots),
  onCreate: () => onConfigChange(config),
  onDeleteAll: () => onConfigChange(config.toBuilder().rowPivots([]).columnPivots([]).build()),
  component: GroupByConfiguration,
});

const createSortAction: CreateAggregationAction = (config, onConfigChange) => ({
  title: 'Sort',
  key: 'sort',
  multipleUse: false,
  isConfigured: !isEmpty(config.sort),
  onCreate: () => onConfigChange(config),
  onDeleteAll: () => onConfigChange(config.toBuilder().sort([]).build()),
  component: SortConfiguration,
});

const _createAggregationActions: (
  config: AggregationWidgetConfig,
  onConfigChange: (newConfig: AggregationWidgetConfig) => void
) => Array<AggregationAction> = (config, onConfigChange) => ([
  createVisualizationAction(config, onConfigChange),
  createGroupByAction(config, onConfigChange),
  createMetricAction(config, onConfigChange),
  createSortAction(config, onConfigChange),
]);

const _initialConfiguredAggregationActions = (config: AggregationWidgetConfig, aggregationActions: Array<AggregationAction>) => {
  return aggregationActions.reduce((configuredActions, action) => {
    if (action.isConfigured) {
      configuredActions.push(action.key);
    }

    return configuredActions;
  }, []);
};

const Wrapper = styled.div`
  height: 100%;
  display: flex;
`;

const Controls = styled.div`
  height: 100%;
  min-width: 300px;
  max-width: 500px;
  flex: 1;
`;

const Visualization = styled.div`
  height: 100%;
  flex: 3;
`;

const Section = styled.div`
  margin-bottom: 10px;

  :last-child {
    margin-bottom: 0;
  }
`;

const SectionHeadline = styled.div`
  margin-bottom: 5px;
`;

const AggregationWizard = ({ onChange, config, children }: EditWidgetComponentProps<AggregationWidgetConfig>) => {
  const aggregationActions = _createAggregationActions(config, onChange);
  const [configuredAggregationActions, setConfiguredAggregationActions] = useState(_initialConfiguredAggregationActions(config, aggregationActions));

  const _onActionCreate = (actionKey: string) => {
    if (actionKey) {
      setConfiguredAggregationActions([...configuredAggregationActions, actionKey]);
    }
  };

  return (
    <Wrapper>
      <Controls>
        <Section data-testid="add-action-section">
          <SectionHeadline>Add an Action</SectionHeadline>
          <AggregationActionSelect onActionCreate={_onActionCreate}
                                   aggregationActions={aggregationActions} />
        </Section>
        <Section data-testid="configure-actions-section">
          <SectionHeadline>Configured Actions</SectionHeadline>
          <div>
            {configuredAggregationActions.map((actionKey) => {
              const aggregationAction = aggregationActions.find((action) => action.key === actionKey);
              const AggregationActionComponent = aggregationAction.component;

              const onDeleteAll = () => {
                if (typeof aggregationAction.onDeleteAll !== 'function') {
                  return;
                }

                const newConfiguredActions = configuredAggregationActions.filter((action) => action !== aggregationAction.key);

                setConfiguredAggregationActions(newConfiguredActions);

                if (aggregationAction.isConfigured) {
                  aggregationAction.onDeleteAll();
                }
              };

              return (
                <ActionConfigurationContainer title={aggregationAction.title}
                                              onDeleteAll={onDeleteAll}
                                              isPermanentAction={aggregationAction.onDeleteAll === undefined}
                                              key={aggregationAction.key}>
                  <AggregationActionComponent config={config} onConfigChange={onChange} />
                </ActionConfigurationContainer>
              );
            })}
          </div>
        </Section>
      </Controls>
      <Visualization>
        {children}
      </Visualization>
    </Wrapper>
  );
};

export default AggregationWizard;
