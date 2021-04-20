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
import { withSize } from 'react-sizeme';
import PropTypes from 'prop-types';

import { NavItem } from 'components/graylog';

const QueryDashboardTab = ({ id, onSelect, tabTitle }: {id: string, onSelect?: (string)=>void, tabTitle: React.ReactNode}) => {
  return (
    <NavItem eventKey={id}
             onClick={() => onSelect(id)}>
      {tabTitle}
    </NavItem>
  );
};

QueryDashboardTab.propTypes = {
  id: PropTypes.string.isRequired,
  onSelect: PropTypes.func,
  tabTitle: PropTypes.oneOfType([
    PropTypes.string,
    PropTypes.node,
  ]).isRequired,
};

QueryDashboardTab.defaultProps = {
  onSelect: () => {},
};

export default withSize()(QueryDashboardTab);
