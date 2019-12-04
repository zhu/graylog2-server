// @flow
import React from 'react';
import styled, { keyframes, css } from 'styled-components';
import PropTypes from 'prop-types';
import { Transition } from 'react-transition-group';

export const Wrapper = styled.div`
  will-change: opacity;
  transition: opacity ${(props): number => props.duration}ms ease-in;
  transition-delay: ${(props): number => props.delay}ms;

  opacity: 0;
  
  ${(props): string => {
    switch (props.state) {
      case 'entered':
        return `
          opacity: 1;
          will-change: auto;
        `;
      default:
        return '';
    }
  }}
`;
type Props = {
  duration?: number,
  delay?: number,
  children: React.Node,
  className: string
}

const OnLoadTransition = ({ children, duration, delay, className, ...rest }: Props) => {
  return (
    <Transition in appear timeout={0} mountOnEnter>
      {(state: 'entering' | 'entered' | 'exiting' | 'exited'): JSX.Element => {
        return (
          <Wrapper duration={duration}
                   delay={delay}
                   className={className}
                   {...rest}
                   state={state}>
            {children}
          </Wrapper>
        );
      }}
    </Transition>
  );
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
