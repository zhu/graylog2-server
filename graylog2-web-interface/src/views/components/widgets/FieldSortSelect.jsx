// @flow strict
import * as React from 'react';
import PropTypes from 'prop-types';
import * as Immutable from 'immutable';

import CustomPropTypes from 'views/components/CustomPropTypes';

import Direction from 'views/logic/aggregationbuilder/Direction';
import FieldTypeMapping from 'views/logic/fieldtypes/FieldTypeMapping';
import SortConfig from 'views/logic/aggregationbuilder/SortConfig';
import Select from 'views/components/Select';

type Props = {
  fields: Immutable.List<FieldTypeMapping>,
  onChange: (Array<*>) => any,
  sort: Array<SortConfig>,
};

type Option = {
  label: string,
  value: number,
};

const findOptionByLabel = (options: Array<Option>, label: string) => options.find((option) => option.label === label);

const findOptionByValue = (options: Array<Option>, value: number) => options.find((option) => option.value === value);

const currentValue = (sort: Array<SortConfig>, options: Array<Option>) => sort && sort.length > 0 && findOptionByLabel(options, sort[0].field);

const onOptionChange = (options: Array<Option>, onChange, newValue, reason) => {
  if (reason.action === 'clear') {
    return onChange([]);
  }
  const { value } = newValue;
  const option = findOptionByValue(options, value);
  if (!option) {
    return undefined;
  }
  const sortConfig = new SortConfig(SortConfig.PIVOT_TYPE, option.label, Direction.Ascending);
  return onChange([sortConfig]);
};

const FieldSortSelect = ({ fields, onChange, sort }: Props) => {
  const options: Array<Option> = fields.map(
    (field, idx) => ({ label: field.name, value: idx }),
  ).toJS();
  return (
    <Select placeholder="None: click to add fields"
            onChange={(newValue, reason) => onOptionChange(options, onChange, newValue, reason)}
            options={options}
            isClearable
            value={currentValue(sort, options)} />
  );
};

FieldSortSelect.propTypes = {
  onChange: PropTypes.func.isRequired,
  fields: CustomPropTypes.FieldListType.isRequired,
  sort: PropTypes.array.isRequired,
};

export default FieldSortSelect;
