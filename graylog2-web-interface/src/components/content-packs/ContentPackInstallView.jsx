import PropTypes from 'prop-types';
import React from 'react';

import { Row, Col } from 'components/graylog';
import { Timestamp } from 'components/common';
import DateTime from 'logic/datetimes/DateTime';

import 'components/content-packs/ContentPackDetails.css';
import ContentPackInstallEntityList from './ContentPackInstallEntityList';

const ContentPackInstallView = (props) => {
  const {
    install: {
      comment,
      created_at: createdAt,
      created_by: createdBy,
      entities,
    },
  } = props;
  return (
    <div>
      <Row>
        <Col smOffset={1} sm={10}>
          <h3>General information</h3>
          <dl className="deflist">
            <dt>Comment:</dt>
            <dd>{comment}</dd>
            <dt>Installed by:</dt>
            <dd>{createdBy}&nbsp;</dd>
            <dt>Installed at:</dt>
            <dd><Timestamp dateTime={createdAt} format={DateTime.Formats.COMPLETE} /></dd>
          </dl>
        </Col>
      </Row>
      <Row>
        <Col smOffset={1} sm={10}>
          <ContentPackInstallEntityList entities={entities} />
        </Col>
      </Row>
    </div>
  );
};

ContentPackInstallView.propTypes = {
  install: PropTypes.object.isRequired,
};

export default ContentPackInstallView;
