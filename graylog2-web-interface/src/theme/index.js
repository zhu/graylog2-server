// @flow strict
import PropTypes from 'prop-types';

import breakpoints, { breakpointPropTypes } from './breakpoints';
import colors, { colorsPropTypes } from './colors';
import fonts, { fontsPropTypes } from './fonts';
import utils, { utilsPropTypes } from './utils';
import zIndices, { zIndicesPropTypes } from './zIndices';
import type { ThemeInterface } from './types';

const themePropTypes = PropTypes.shape({
  breakpoints: breakpointPropTypes,
  colors: colorsPropTypes,
  fonts: fontsPropTypes,
  utils: utilsPropTypes,
  zIndices: zIndicesPropTypes,
});

export {
  breakpoints,
  colors,
  fonts,
  utils,
  zIndices,
  themePropTypes,
};

export type { ThemeInterface };
