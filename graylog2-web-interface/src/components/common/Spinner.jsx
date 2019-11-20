import React from 'react';
import PropTypes from 'prop-types';

import Icon from './Icon';

/**
 * Simple spinner to use while waiting for something to load.
 */
class Spinner extends React.Component {
  static propTypes = {
    /** Name of the Icon to use. */
    name: PropTypes.string,
    /** Text to show while loading. */
    text: PropTypes.string,
  };

  static defaultProps = {
    name: 'spinner',
    text: 'Loading...',
  };

  constructor(props) {
    super(props);
    this.state = {
      delayFinished: false,
    };
  }

  componentDidMount(): void {
    this.delayTimeout = window.setTimeout((): void => {
      this.setState({
        delayFinished: true,
      });
    }, 200);
  }

  render() {
    const { name, text, ...rest } = this.props;
    const { delayFinished } = this.state;

    if (!delayFinished) return <span />;

    return <span><Icon name={name} spin {...rest} /> {text}</span>;
  }
}

export default Spinner;
