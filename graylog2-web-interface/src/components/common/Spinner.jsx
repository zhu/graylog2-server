// @flow
import * as React from 'react';
import PropTypes from 'prop-types';
import styled from 'styled-components';

import Icon from './Icon';

const Wrapper: React.ComponentType<{ visible: boolean }> = styled.span`
  visibility: ${({ visible }) => (visible ? 'visible' : 'hidden')};
`;

type Props = {
  delay?: number,
  name?: string,
  text?: string,
}

type State = {
  delayFinished: boolean
}

/**
 * Simple spinner to use while waiting for something to load.
 */
class Spinner extends React.Component<Props, State> {
  delayTimeout = undefined

  static propTypes = {
    /** Delay in ms before displaying the spinner */
    delay: PropTypes.number,
    /** Name of the Icon to use. */
    name: PropTypes.string,
    /** Text to show while loading. */
    text: PropTypes.string,
  };

  static defaultProps = {
    name: 'spinner',
    text: 'Loading...',
    delay: 200,
  };

  constructor(props: Props) {
    super(props);
    this.state = {
      delayFinished: false,
    };
  }

  componentDidMount() {
    const { delay } = this.props;
    this.delayTimeout = window.setTimeout(() => {
      this.setState({
        delayFinished: true,
      });
    }, delay);
  }

  componentWillUnmount() {
    if (this.delayTimeout) {
      clearTimeout(this.delayTimeout);
    }
  }

  render() {
    const { name, text, ...rest } = this.props;
    const { delayFinished } = this.state;

    return <Wrapper visible={delayFinished}><Icon name={name} spin {...rest} /> {text}</Wrapper>;
  }
}

export default Spinner;
