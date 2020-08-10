// @flow strict
import * as React from 'react';
import * as Immutable from 'immutable';
import { useEffect, useState } from 'react';
import PropTypes from 'prop-types';

import { createGRN } from 'logic/permissions/GRN';
import { Spinner } from 'components/common';
import EntityShareState from 'logic/permissions/EntityShareState';
import { EntityShareActions } from 'stores/permissions/EntityShareStore';
import { type EntitySharePayload } from 'actions/permissions/EntityShareActions';
import BootstrapModalConfirm from 'components/bootstrap/BootstrapModalConfirm';

import EntityShareSettings from './EntityShareSettings';

type Props = {
  description: string,
  entityId: string,
  entityTitle: string,
  entityType: string,
  onClose: () => void,
};

const EntityShareModal = ({ description, entityId, entityType, entityTitle, onClose }: Props) => {
  const [entityShareState, setEntityShareState] = useState<?EntityShareState>();
  const [disableSubmit, setDisableSubmit] = useState(false);
  const entityGRN = createGRN(entityId, entityType);

  useEffect(() => {
    EntityShareActions.prepare(entityGRN).then(setEntityShareState);
  }, [entityGRN]);

  const _handleSave = () => {
    setDisableSubmit(true);
    const payload: EntitySharePayload = {
      selected_grantee_capabilities: entityShareState?.selectedGranteeCapabilities ?? Immutable.Map(),
    };

    return EntityShareActions.update(entityGRN, payload).then(onClose);
  };

  return (
    <BootstrapModalConfirm confirmButtonDisabled={disableSubmit}
                           confirmButtonText="Save"
                           cancelButtonText="Discard changes"
                           onConfirm={_handleSave}
                           onModalClose={onClose}
                           showModal
                           title={<>Sharing: {entityType}: <i>{entityTitle}</i></>}>
      <>
        {entityShareState ? (
          <EntityShareSettings description={description}
                               entityGRN={entityGRN}
                               setEntityShareState={setEntityShareState}
                               entityShareState={entityShareState}
                               setDisableSubmit={setDisableSubmit} />
        ) : (
          <Spinner />
        )}
      </>
    </BootstrapModalConfirm>
  );
};

EntityShareModal.propTypes = {
  description: PropTypes.string.isRequired,
  entityId: PropTypes.string.isRequired,
  entityTitle: PropTypes.string.isRequired,
  entityType: PropTypes.string.isRequired,
  onClose: PropTypes.func.isRequired,
};

export default EntityShareModal;
