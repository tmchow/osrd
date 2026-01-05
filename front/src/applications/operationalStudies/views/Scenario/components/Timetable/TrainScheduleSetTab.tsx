import { useMemo, type PropsWithChildren } from 'react';

import { Checkbox } from '@osrd-project/ui-core';
import {
  Beaker,
  Broadcast,
  DesktopDownload,
  DeviceDesktop,
  Duplicate,
  LinkExternal,
  NoEntry,
  Pencil,
  TriangleDown,
  TriangleRight,
  Verified,
} from '@osrd-project/ui-icons';
import cx from 'classnames';
import { noop } from 'lodash';
import { useTranslation } from 'react-i18next';

import type { TrainScheduleSet } from 'common/api/osrdEditoastApi';
import MenuTriggerButton, { type MenuProps } from 'common/MenuTriggerButton';

import { computeTimetablePackageName, isSandbox } from './utils';

type TrainScheduleSetTabProps = PropsWithChildren<{
  trainScheduleSet: TrainScheduleSet;
  catalogName?: string;
  handleClickPackage: (id: number) => void;
  handleSelectPackage: () => void;
  isSelectMode: boolean;
  isSelected: boolean;
  isIndeterminate: boolean;
  isTrainListOpen: boolean;
}>;

const TrainScheduleSetTab = ({
  trainScheduleSet,
  catalogName,
  handleClickPackage,
  handleSelectPackage,
  isSelectMode,
  isSelected,
  isIndeterminate,
  isTrainListOpen,
  children,
}: TrainScheduleSetTabProps) => {
  const { t } = useTranslation('operational-studies', { keyPrefix: 'main.timetable.packages' });

  const menuProps: MenuProps = useMemo(
    () => ({
      items: [
        trainScheduleSet.published
          ? {
              title: t('transformToLocalCopy'),
              icon: <DesktopDownload />,
              onClick: noop,
              disabled: true,
            }
          : {
              title: t('publishToCatalog'),
              icon: <Verified />,
              onClick: noop,
              disabled: true,
            },
        trainScheduleSet.published
          ? {
              title: t('edit'),
              icon: <LinkExternal />,
              onClick: noop,
              disabled: true,
            }
          : {
              title: t('editName'),
              icon: <Pencil />,
              onClick: noop,
              disabled: true,
            },
        {
          title: t('duplicate'),
          icon: <Duplicate />,
          onClick: () => noop,
          disabled: true,
        },
        {
          title: t('removeFromScenario'),
          icon: <NoEntry />,
          onClick: noop,
          disabled: true,
        },
      ],
    }),
    [trainScheduleSet]
  );

  return (
    <>
      <div className={cx('package-tab-container', { sandbox: isSandbox(trainScheduleSet) })}>
        <div
          className="package-tab"
          role="button"
          tabIndex={0}
          onClick={() => handleClickPackage(trainScheduleSet.id)}
        >
          {isSelectMode && (
            <Checkbox
              label=""
              checked={isSelected}
              isIndeterminate={isIndeterminate}
              onChange={handleSelectPackage}
              small
            />
          )}
          {isTrainListOpen ? (
            <TriangleDown size="lg" className="package-collapse-icon" />
          ) : (
            <TriangleRight size="lg" className="package-expand-icon" />
          )}
          {isSandbox(trainScheduleSet) && <Beaker className="package-status" />}
          {!isSandbox(trainScheduleSet) &&
            (trainScheduleSet.published ? (
              <Broadcast className="package-status" />
            ) : (
              <DeviceDesktop className="package-status" />
            ))}
          <span>
            {isSandbox(trainScheduleSet)
              ? t('sandbox')
              : computeTimetablePackageName(trainScheduleSet.name!, catalogName)}
          </span>
        </div>
        <MenuTriggerButton
          buttonProps={{
            disabled: isSandbox(trainScheduleSet),
          }}
          menuProps={menuProps}
        />
      </div>
      {isTrainListOpen && children}
    </>
  );
};

export default TrainScheduleSetTab;
