// @flow strict
import PropTypes from 'prop-types';

export type ZIndices = {
dropdown: number,
  navigation: number,
  sidebar: number,
  modal: number,
  modalContent: number,
  searchSpinner: number,
};

export const zIndicesPropTypes = PropTypes.shape({
  dropdown: PropTypes.number.isRequired,
  navigation: PropTypes.number.isRequired,
  sidebar: PropTypes.number.isRequired,
  modal: PropTypes.number.isRequired,
  modalContent: PropTypes.number.isRequired,
  searchSpinner: PropTypes.number.isRequired,
});

const zIndices: ZIndices = {
  dropdown: 10,
  navigation: 20,
  sidebar: 30,
  modal: 80,
  modalContent: 90,
  searchSpinner: 100,
};

export default zIndices;
