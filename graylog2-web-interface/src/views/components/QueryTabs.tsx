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
import { useRef, useState } from 'react';
import * as Immutable from 'immutable';
import PropTypes from 'prop-types';
import styled, { css } from 'styled-components';
import { SizeMe } from 'react-sizeme';

import { Col, Row, Nav, NavItem, MenuItem } from 'components/graylog';
import { ModifiedNavDropdown as NavDropdown } from 'components/graylog/NavDropdown';
import { Icon } from 'components/common';
import QueryTitleEditModal from 'views/components/queries/QueryTitleEditModal';
import Query, { QueryId } from 'views/logic/queries/Query';
import type { TitlesMap } from 'views/stores/TitleTypes';
import ViewState from 'views/logic/views/ViewState';

import QueryTabsInner from './QueryTabsInner';

type Props = {
  onRemove: (queryId: string) => Promise<void> | Promise<ViewState>,
  onSelect: (queryId: string) => Promise<Query> | Promise<string>,
  onTitleChange: (queryId: string, newTitle: string) => Promise<TitlesMap>,
  queries: Array<QueryId>,
  selectedQueryId: string,
  titles: Immutable.Map<string, string>,
};

const StyledRow = styled(Row)`
  margin-bottom: 0;
`;

const QueryTabs = ({ onTitleChange, ...props }:Props) => {
  const queryTitleEditModal = useRef<QueryTitleEditModal | undefined | null>();

  const openTitleEditModal = (activeQueryTitle: string) => {
    if (queryTitleEditModal) {
      queryTitleEditModal.current.open(activeQueryTitle);
    }
  };

  return (
    <StyledRow>
      <Col>
        <SizeMe monitorWidth>
          {({ size }) => {
            console.log(size.width);

            return size.width && <QueryTabsInner {...props} wrapperWidth={size.width} openTitleEditModal={openTitleEditModal} />;
          }}
        </SizeMe>

        {/*
          The title edit modal can't be part of the QueryTitle component,
          due to the react bootstrap tabs keybindings.
          The input would always lose the focus when using the arrow keys.
        */}
        <QueryTitleEditModal onTitleChange={(newTitle: string) => onTitleChange(props.selectedQueryId, newTitle)}
                             ref={queryTitleEditModal} />
      </Col>
    </StyledRow>
  );
};

export default QueryTabs;
