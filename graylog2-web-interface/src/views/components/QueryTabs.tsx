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
import QueryTitle from 'views/components/queries/QueryTitle';
import QueryTitleEditModal from 'views/components/queries/QueryTitleEditModal';
import Query, { QueryId } from 'views/logic/queries/Query';
import type { TitlesMap } from 'views/stores/TitleTypes';
import ViewState from 'views/logic/views/ViewState';

type Props = {
  onRemove: (queryId: string) => Promise<void> | Promise<ViewState>,
  onSelect: (queryId: string) => Promise<Query> | Promise<string>,
  onTitleChange: (queryId: string, newTitle: string) => Promise<TitlesMap>,
  queries: Array<QueryId>,
  selectedQueryId: string,
  titles: Immutable.Map<string, string>,
};

interface QueryTabItems {
  navItems: Array<React.ReactNode>,
  menuItems: Array<React.ReactNode>,
  lockedItem: React.ReactNode
}

const StyledRow = styled(Row)`
  margin-bottom: 0;
`;

const StyledQueryNav = styled(Nav)(({ theme }) => css`
  &.nav.nav-tabs {
    border-bottom: 0;
    display: flex;
    white-space: nowrap;
    z-index: 2; /* without it renders under widget management icons */
    position: relative;
    margin-bottom: -1px;
    padding-left: 15px;

    > li {
      > a {
        color: ${theme.colors.variant.dark.default};
        border: none;
        
        :hover,
        :active,
        :focus {
          transition: color 150ms ease-in-out;
          background: transparent;
          color: ${theme.colors.variant.dark.primary};
        }
      }
    }

    > li.active {
      z-index: 1;
      display: flex;
      flex-direction: column;
      align-items: center;
      margin-bottom: -3px;

      > a {
        padding: 9px 15px;
        border: 1px solid ${theme.colors.variant.lighter.default};
        border-bottom: none;
        background-color: ${theme.colors.global.contentBackground};
        color: ${theme.colors.variant.darkest.primary};

        :hover,
        :active,
        :focus {
          border: 1px solid ${theme.colors.variant.lighter.default};
          border-bottom: none;
          color: ${theme.colors.variant.darkest.primary};
        }
      }
    }

    > li.query-tabs-more,
    > li.query-tabs-more a {
      cursor: pointer;
    }
  }
`);

const adjustTabs = (maxWidth: number, setHiddenTabs, lockedTab: number) => {
  const dashboardTabs = document.querySelector('#dashboard-tabs') as HTMLElement;

  console.log({ dashboardTabs, maxWidth });

  if (dashboardTabs) {
    const tabItems = dashboardTabs.querySelectorAll(':scope > li:not(.dropdown):not(.query-tabs-new):not(.locked)') as NodeListOf<HTMLElement>;
    const lockedItem = dashboardTabs.querySelector('li.locked') as HTMLElement;
    const moreBtn = dashboardTabs.querySelector('li.query-tabs-more') as HTMLElement;
    const newBtn = dashboardTabs.querySelector('li.query-tabs-new') as HTMLElement;
    const hiddenItems = new Set<number>();

    console.log({ tabItems, lockedItem, moreBtn, newBtn });

    if (lockedItem) {
      lockedItem.classList.remove('hidden');
    }

    let currentWidth = (moreBtn?.offsetWidth ?? 0) + (newBtn?.offsetWidth ?? 0) + (lockedItem?.offsetWidth ?? 0);

    tabItems.forEach((tabItem: HTMLElement, idx) => {
      tabItem.classList.remove('hidden');
      currentWidth += tabItem.offsetWidth;

      console.log(idx, { maxWidth, currentWidth, tabWidth: tabItem.offsetWidth, classList: tabItem.classList });

      if (currentWidth >= maxWidth || idx === lockedTab) {
        tabItem.classList.add('hidden');
        hiddenItems.add(idx);
      }
    });

    console.log({ hiddenItems });

    setHiddenTabs(hiddenItems);
  }
};

const QueryTabs = ({ onRemove, onSelect, onTitleChange, queries, selectedQueryId, titles }:Props) => {
  const queryTitleEditModal = useRef<QueryTitleEditModal | undefined | null>();
  const [openedMore, setOpenedMore] = useState<boolean>(false);
  const maxWidth = useRef<number>(0);
  const [dashboardTabs, setDashboardTabs] = useState<QueryTabItems>({ navItems: [], menuItems: [], lockedItem: null });
  const [lockedTab, setLockedTab] = useState<number>(undefined);
  const [hiddenTabs, setHiddenTabs] = useState<Set<number>>(new Set());

  const openTitleEditModal = (activeQueryTitle: string) => {
    if (queryTitleEditModal) {
      queryTitleEditModal.current.open(activeQueryTitle);
    }
  };

  const queryTabs = React.useCallback((): QueryTabItems => {
    const navItems = [];
    const menuItems = [];
    let lockedItem = null;

    Array.from(queries).forEach((id, index) => {
      const title = titles.get(id, `Page#${index + 1}`);
      const isActive = id === selectedQueryId;
      const tabTitle = (
        <QueryTitle active={isActive}
                    id={id}
                    onClose={() => onRemove(id)}
                    openEditModal={openTitleEditModal}
                    key={`menutitle-${id}`}
                    title={title} />
      );

      const isLocked = lockedTab === index;

      lockedItem = isLocked ? (
        <NavItem eventKey={`locked-${id}`}
                 key={`locked-${id}`}
                 onClick={() => {}}
                 className="locked active">
          {tabTitle}
        </NavItem>
      ) : null;

      const navItem = (
        <NavItem eventKey={`nav-${id}`}
                 key={`nav-${id}`}
                 className={`${isActive ? 'active' : ''}`}
                 onClick={() => {
                   setLockedTab(undefined);
                   onSelect(id);
                 }}>
          {tabTitle}
        </NavItem>
      );

      const menuItem = (
        <MenuItem eventKey={`menu-${id}`}
                  key={`menu-${id}`}
                  className={`${hiddenTabs.has(index) ? '' : 'hidden'}`}
                  onClick={() => {
                    setLockedTab(index);
                    onSelect(id);
                  }}>
          {tabTitle}
        </MenuItem>
      );

      navItems.push(navItem);
      menuItems.push(menuItem);
    });

    return { navItems, menuItems, lockedItem };
  }, [hiddenTabs, lockedTab, onRemove, onSelect, queries, selectedQueryId, titles]);

  const newTab = (
    <NavItem key="new"
             eventKey="new"
             onClick={() => onSelect('new')}
             className="query-tabs-new">
      <Icon name="plus" />
    </NavItem>
  );

  const _adjustTabs = () => {
    setDashboardTabs(queryTabs());
    adjustTabs(maxWidth.current, setHiddenTabs, lockedTab);
  };

  React.useEffect(() => {
    setTimeout(() => {
      requestAnimationFrame(_adjustTabs);
    });
  }, [lockedTab, selectedQueryId]);

  React.useEffect(() => {
    setDashboardTabs(queryTabs());
  }, [queryTabs, hiddenTabs]);

  return (
    <StyledRow>
      <Col>
        <SizeMe>
          {({ size }) => {
            if (size.width !== maxWidth.current) {
              maxWidth.current = size.width;
            }

            return (
              <StyledQueryNav bsStyle="tabs"
                              activeKey={selectedQueryId}
                              id="dashboard-tabs"
                              key="dashboard-tabs">

                {dashboardTabs.navItems}
                {dashboardTabs.menuItems.length
                && (
                <NavDropdown eventKey="more"
                             title={<Icon name="ellipsis-h" />}
                             className="query-tabs-more"
                             id="query-tabs-more"
                             noCaret
                             pullRight
                             active={openedMore}
                             open={openedMore}
                             onToggle={(isOpened) => setOpenedMore(isOpened)}>
                  {dashboardTabs.menuItems}
                </NavDropdown>
                )}

                {typeof lockedTab !== 'undefined' && dashboardTabs.lockedItem}

                {newTab}
              </StyledQueryNav>
            );
          }}
        </SizeMe>

        {/*
          The title edit modal can't be part of the QueryTitle component,
          due to the react bootstrap tabs keybindings.
          The input would always lose the focus when using the arrow keys.
        */}
        <QueryTitleEditModal onTitleChange={(newTitle: string) => onTitleChange(selectedQueryId, newTitle)}
                             ref={queryTitleEditModal} />
      </Col>
    </StyledRow>
  );
};

QueryTabs.propTypes = {
  onRemove: PropTypes.func.isRequired,
  onSelect: PropTypes.func.isRequired,
  onTitleChange: PropTypes.func.isRequired,
  queries: PropTypes.object.isRequired,
  selectedQueryId: PropTypes.string.isRequired,
  titles: PropTypes.object.isRequired,
};

export default QueryTabs;
