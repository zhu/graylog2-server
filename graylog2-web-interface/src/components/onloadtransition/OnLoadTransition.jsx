// @flow
import React from 'react';
import styled, { keyframes, css } from 'styled-components';
import PropTypes from 'prop-types';

const fadeIn = keyframes`
  0% {
      opacity: 0;
  }
  100% {
      opacity: 1;
  }
`;

type TransitionProps = {
  duration: number,
  delay: number,
}

const Transition = styled.div(({ duration, delay }: TransitionProps) => css`
  animation: ${duration}ms ease-out ${delay}ms 1 ${fadeIn};
`);

type Props = {
  duration?: number,
  delay?: number,
  children: React.Node,
  className: string
}

const OnLoadTransition = ({ children, duration, delay, className, ...rest }: Props) => {
  return (<Transition duration={duration} delay={delay} className={className} {...rest}>{children}</Transition>);
};

OnLoadTransition.propTypes = {
  delay: PropTypes.number,
  duration: PropTypes.number,
  children: PropTypes.node.isRequired,
  className: PropTypes.string,
};

OnLoadTransition.defaultProps = {
  delay: 0,
  duration: 300,
  className: '',
};

export default OnLoadTransition;
