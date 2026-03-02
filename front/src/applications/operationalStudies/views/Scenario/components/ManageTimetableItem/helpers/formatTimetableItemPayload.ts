import { compact } from 'lodash';
import { v4 as uuidV4 } from 'uuid';

import type { PacedTrainWithPaced } from 'applications/operationalStudies/types';
import type { PacedTrainException, TrainSchedule } from 'common/api/osrdEditoastApi';
import getStepLocation from 'modules/pathfinding/helpers/getStepLocation';
import {
  findExceptionWithOccurrenceId,
  isPacedTrainBase,
} from 'modules/timetableItem/helpers/pacedTrain';
import type { PacedTrainWithDetails } from 'modules/timetableItem/types';
import type { TimetableItemToEditData, OperationalStudiesConfState } from 'reducers/osrdconf/types';
import { kmhToMs } from 'utils/physics';
import { extractOccurrenceIndexFromOccurrenceId, isIndexedOccurrenceId } from 'utils/trainId';

import {
  generatePacedTrainException,
  updatePacedTrainExceptionsList,
  checkChangeGroups,
} from './buildPacedTrainException';
import formatMargin from './formatMargin';
import formatSchedule from './formatSchedule';

export function formatTimetableItemPayload(
  osrdconf: OperationalStudiesConfState,
  // TODO TS2 : remove this when rollingStockName will replace rollingStockId in the store
  rollingStockName: string
): {
  newTrainSchedulePayload: TrainSchedule;
  updatedExceptions: PacedTrainException[] | undefined;
  originalExceptions: PacedTrainException[] | undefined;
} {
  return {
    newTrainSchedulePayload: {
      category: osrdconf.category,
      comfort: osrdconf.rollingStockComfort,
      constraint_distribution: osrdconf.constraintDistribution,
      initial_speed: osrdconf.initialSpeed ? kmhToMs(osrdconf.initialSpeed) : 0,
      labels: osrdconf.labels,
      margins: formatMargin(compact(osrdconf.pathSteps)),
      options: {
        use_electrical_profiles: osrdconf.usingElectricalProfiles,
        use_speed_limits_for_simulation: osrdconf.usingSpeedLimits,
        stops_at_end_of_block: false,
      },
      path: compact(osrdconf.pathSteps).map((step) => ({
        id: step.id,
        location: getStepLocation(step.location),
      })),
      power_restrictions: osrdconf.powerRestriction,
      rolling_stock_name: rollingStockName,
      schedule: formatSchedule(compact(osrdconf.pathSteps)),
      speed_limit_tag: osrdconf.speedLimitByTag,
      start_time: osrdconf.startTime.toISOString(),
      train_name: osrdconf.name,
    },
    updatedExceptions: undefined,
    originalExceptions: undefined,
  };
}

// Format a PacedTrainWithDetails to a PacedTrain payload by keeping only the
// necessary properties and formatting the date fields to ISO strings.
export function formatPacedTrainWithDetailsToPacedTrainPayload(
  pacedTrainWithDetails: PacedTrainWithDetails
): TrainSchedule {
  return {
    category: pacedTrainWithDetails.category,
    comfort: pacedTrainWithDetails.comfort,
    constraint_distribution: pacedTrainWithDetails.constraint_distribution,
    initial_speed: pacedTrainWithDetails.initial_speed,
    labels: pacedTrainWithDetails.labels,
    margins: pacedTrainWithDetails.margins,
    options: pacedTrainWithDetails.options,
    paced: pacedTrainWithDetails.paced
      ? {
          time_window: pacedTrainWithDetails.paced.timeWindow.toISOString(),
          interval: pacedTrainWithDetails.paced.interval.toISOString(),
          // This data is used as payload to create/update train schedule and shouldn't have exceptions inside
          // since exceptions have their own endpoints for that
          exceptions: [],
        }
      : undefined,
    path: pacedTrainWithDetails.path,
    power_restrictions: pacedTrainWithDetails.power_restrictions,
    // Rollingstock is missing when just created a train from nge or with import
    rolling_stock_name: pacedTrainWithDetails.rollingStock?.name ?? '',
    schedule: pacedTrainWithDetails.schedule,
    speed_limit_tag: pacedTrainWithDetails.speed_limit_tag,
    start_time: pacedTrainWithDetails.startTime.toISOString(),
    train_name: pacedTrainWithDetails.name,
  };
}

/**
 * Used when editing an occurrence of a paced train.
 * Computes the updated and original exceptions for the occurrence being modified,
 * without touching the base train payload.
 */
export function formatOccurrenceException(
  osrdconf: OperationalStudiesConfState,
  rollingStockName: string,
  timetableItemToEditData: TimetableItemToEditData & {
    occurrenceId: NonNullable<TimetableItemToEditData['occurrenceId']>;
  }
): {
  updatedExceptions: PacedTrainException[];
  originalExceptions: PacedTrainException[];
} {
  const { newTrainSchedulePayload: baseTrain } = formatTimetableItemPayload(
    osrdconf,
    rollingStockName
  );

  const newPacedTrain: Omit<PacedTrainWithPaced, 'train_schedule_set_id'> = {
    ...baseTrain,
    paced: {
      time_window: osrdconf.timeWindow.toISOString(),
      interval: osrdconf.interval.toISOString(),
      exceptions: [],
    },
  };

  const originalPacedTrain = formatPacedTrainWithDetailsToPacedTrainPayload(
    timetableItemToEditData.originalPacedTrain
  );

  if (!isPacedTrainBase(originalPacedTrain))
    throw new Error(
      `PacedTrain payload (built from train ${timetableItemToEditData.originalPacedTrain.id}) should have a paced field.`
    );

  const { occurrenceId } = timetableItemToEditData;
  const occurrenceIndex = isIndexedOccurrenceId(occurrenceId)
    ? extractOccurrenceIndexFromOccurrenceId(occurrenceId)
    : undefined;

  const baseException = generatePacedTrainException(
    newPacedTrain,
    originalPacedTrain,
    occurrenceIndex
  );

  const existingException = findExceptionWithOccurrenceId(
    timetableItemToEditData.originalPacedTrain.paced?.exceptions ?? [],
    occurrenceId
  );

  const updatedExceptions = updatePacedTrainExceptionsList(
    (timetableItemToEditData.originalPacedTrain.paced?.exceptions ?? []) as PacedTrainException[],
    {
      ...baseException,
      // Preserve the existing exception id to PUT instead of POST
      ...(existingException?.id !== undefined && { id: existingException.id }),
      // TODO_EXCEPTION: remove this when drop key in the model
      key: existingException?.key ?? uuidV4(),
      occurrence_index: occurrenceIndex,
    },
    occurrenceId
  );

  return {
    updatedExceptions,
    originalExceptions: (timetableItemToEditData.originalPacedTrain.paced?.exceptions ??
      []) as PacedTrainException[],
  };
}

/**
 * Used when creating and editing a paced train (not an occurrence).
 * @param osrdconf paced train fields that were modified by user
 * @param timetableItemToEditData the existing paced train we're editing
 */
export function formatPacedTrainPayload(
  osrdconf: OperationalStudiesConfState,
  // TODO TS2 : remove this when rollingStockName will replace rollingStockId in the store
  rollingStockName: string,
  timetableItemToEditData?: TimetableItemToEditData
): {
  newTrainSchedulePayload: TrainSchedule;
  updatedExceptions: PacedTrainException[] | undefined;
  originalExceptions: PacedTrainException[] | undefined;
  exceptionsToDeleteIds?: number[];
} {
  const { newTrainSchedulePayload: baseTrain } = formatTimetableItemPayload(
    osrdconf,
    rollingStockName
  );

  if (osrdconf.editingItemType === 'uniqueTrain')
    return {
      newTrainSchedulePayload: baseTrain,
      updatedExceptions: undefined,
      originalExceptions: undefined,
      exceptionsToDeleteIds:
        timetableItemToEditData?.originalPacedTrain.paced?.exceptions
          .map((exception) => exception.id)
          .filter((id): id is number => id !== null && id !== undefined) ?? [],
    };

  const newPacedTrain: Omit<PacedTrainWithPaced, 'train_schedule_set_id'> = {
    ...baseTrain,
    paced: {
      time_window: osrdconf.timeWindow.toISOString(),
      interval: osrdconf.interval.toISOString(),
      // This data is used as payload to create/update train schedule and shouldn't have exceptions inside
      // since exceptions have their own endpoints for that
      exceptions: [],
    },
  };

  const newAddedExceptions = osrdconf.addedExceptions.map(({ key, startTime }) => ({
    key,
    start_time: { value: startTime.toISOString() },
  }));

  // ========== user is creating a new paced train ==========
  if (!timetableItemToEditData || !timetableItemToEditData.originalPacedTrain.paced) {
    return {
      newTrainSchedulePayload: newPacedTrain,
      updatedExceptions: newAddedExceptions,
      originalExceptions: undefined,
    };
  }

  const originalPacedTrain = formatPacedTrainWithDetailsToPacedTrainPayload(
    timetableItemToEditData.originalPacedTrain
  );
  if (!isPacedTrainBase(originalPacedTrain))
    throw new Error(
      `PacedTrain payload (built from train ${timetableItemToEditData.originalPacedTrain.id}) should have a paced field.`
    );

  const hasPacedTrainSettingsChanged =
    osrdconf.timeWindow.toISOString() !==
      timetableItemToEditData.originalPacedTrain.paced.timeWindow.toISOString() ||
    osrdconf.interval.toISOString() !==
      timetableItemToEditData.originalPacedTrain.paced.interval.toISOString();

  // delete all existing exceptions if cadence/duration changed, else keep them and add the new ones
  const updatedExceptions: PacedTrainException[] = hasPacedTrainSettingsChanged
    ? []
    : [
        ...checkChangeGroups(
          newPacedTrain,
          newPacedTrain.paced,
          (timetableItemToEditData.originalPacedTrain.paced?.exceptions ??
            []) as PacedTrainException[]
        ),
        ...newAddedExceptions,
      ];

  return {
    newTrainSchedulePayload: newPacedTrain,
    updatedExceptions,
    originalExceptions: timetableItemToEditData.originalPacedTrain.paced?.exceptions,
  };
}
