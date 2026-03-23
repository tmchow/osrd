import { useState } from 'react';

import type { CellContext } from '@tanstack/react-table';

import CellPlaceholder from './CellPlaceholder';
import { MarginUnit } from './consts';
import type { MarginUnitType, MarginValue, TimesStopsRowNew } from './types';

const UnitToggle = ({
  value,
  onChange,
}: {
  value: MarginUnitType;
  onChange: (unit: MarginUnitType) => void;
}) => (
  <div className="margin-cell-unit-selection">
    <button
      className={`margin-cell-unit ${value === MarginUnit.percent ? 'margin-cell-unit-active' : ''}`}
      onClick={() => onChange(MarginUnit.percent)}
    >
      %
    </button>
    <button
      className={`margin-cell-unit ${value === MarginUnit.minPer100km ? 'margin-cell-unit-active' : ''}`}
      onClick={() => onChange(MarginUnit.minPer100km)}
    >
      min/
      <br />
      100km
    </button>
  </div>
);

const MarginCellEditable = ({
  getValue,
  onCommit,
}: CellContext<TimesStopsRowNew, MarginValue | undefined> & {
  onCommit?: (value: MarginValue | null) => void;
}) => {
  const [unit, setUnit] = useState<MarginUnitType>(getValue()?.unit ?? MarginUnit.percent);
  const [value, setValue] = useState<number | null>(getValue()?.value ?? null);

  if (value === null) {
    return <CellPlaceholder onClick={() => setValue(0)} />;
  }

  return (
    <div className="margin-cell-editable">
      <input
        type="number"
        className="margin-cell-input"
        value={value}
        style={{ width: `${Math.max(1, String(value).length)}ch` }}
        onChange={(e) => {
          if (e.target.value.length === 0) setValue(null);
          const parsed = parseFloat(e.target.value);
          if (!isNaN(parsed)) setValue(parsed);
        }}
        onKeyDown={(e) => {
          if (['e', 'E', '+', '-', '.'].includes(e.key)) e.preventDefault();
          if (e.key === 'Enter') e.currentTarget.blur();
          if (e.key === 'Escape') {
            setValue(getValue()?.value ?? null);
            e.currentTarget.blur();
          }
        }}
        onWheel={(e) => e.currentTarget.blur()}
        onBlur={() => onCommit?.(value !== null ? { value, unit } : null)}
      />
      <UnitToggle value={unit} onChange={(u) => {
        setUnit(u);
        onCommit?.(value !== null ? { value, unit: u } : null);
      }} />
    </div>
  );
};

const MarginCellReadOnly = ({
  showPolarity = false,
  ...props
}: CellContext<TimesStopsRowNew, MarginValue | undefined> & {
  showPolarity?: boolean;
}) => {
  const marginInSeconds = props.getValue()?.value ?? 0;
  const isZero = marginInSeconds === 0;
  const polarity = marginInSeconds >= 0 ? '+' : '-';
  const abs = Math.abs(marginInSeconds);
  const minutes = String(Math.floor(abs / 60)).padStart(2, '0');
  const seconds = String(Math.floor(abs % 60)).padStart(2, '0');

  return (
    <>
      {showPolarity && <span className="margin-cell-polarity">{!isZero && polarity}</span>}
      <span className="mono">{minutes}</span>
      <span className="margin-cell-unit-letter">m</span>
      <span className="mono">{seconds}</span>
      <span className="margin-cell-unit-letter">s</span>
    </>
  );
};

const MarginCell = ({
  editable = false,
  showPolarity = false,
  onCommit,
  ...props
}: CellContext<TimesStopsRowNew, MarginValue | undefined> & {
  editable?: boolean;
  showPolarity?: boolean;
  onCommit?: (value: MarginValue | null) => void;
}) =>
  editable ? (
    <MarginCellEditable {...props} onCommit={onCommit} />
  ) : (
    <MarginCellReadOnly showPolarity={showPolarity} {...props} />
  );

export default MarginCell;
