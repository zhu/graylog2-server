// @flow strict
import React, { useEffect, useState } from 'react';
import PropTypes from 'prop-types';
import { withRouter } from 'react-router';

import Version from 'util/Version';
import connect from 'stores/connect';
import StoreProvider from 'injection/StoreProvider';

import OnLoadTransition from 'components/onloadtransition/OnLoadTransition';

const SystemStore = StoreProvider.getStore('System');

type Props = {
  system?: {
    version: string,
    hostname: string
  },
  location: {
    pathname: string
  },
};

const Footer = ({ system, location: { pathname } }: Props) => {
  const [jvm, setJvm] = useState();
  useEffect(() => {
    let mounted = true;
    SystemStore.jvm().then((jvmInfo) => {
      if (mounted) {
        setJvm(jvmInfo);
      }
    });

    return () => {
      mounted = false;
    };
  }, []);

  if (!(system && jvm)) {
    return (
      <OnLoadTransition id="footer" delay={300} key={pathname}>
        Graylog {Version.getFullVersion()}
      </OnLoadTransition>
    );
  }

  return (
    <OnLoadTransition id="footer" delay={300} key={pathname}>
      Graylog {system.version} on {system.hostname} ({jvm.info})
    </OnLoadTransition>
  );
};

Footer.propTypes = {
  system: PropTypes.shape({
    version: PropTypes.string,
    hostname: PropTypes.string,
  }),
  location: PropTypes.shape({
    pathname: PropTypes.string.isRequired,
  }).isRequired,
};

Footer.defaultProps = {
  system: undefined,
};

export default connect(withRouter(Footer), { system: SystemStore });
