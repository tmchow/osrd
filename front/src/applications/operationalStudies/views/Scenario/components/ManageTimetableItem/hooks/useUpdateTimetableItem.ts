import { isEmpty, isEqual } from 'lodash';
import { useTranslation } from 'react-i18next';
import { useSelector } from 'react-redux';
import { v4 as uuidV4 } from 'uuid';

import { useScenarioContext } from 'applications/operationalStudies/hooks/useScenarioContext';
import { updatePacedTrainExceptionsList } from 'applications/operationalStudies/views/Scenario/components/ManageTimetableItem/helpers/buildPacedTrainException';
import { MANAGE_TIMETABLE_ITEM_TYPES } from 'applications/operationalStudies/views/Scenario/consts';
import type { PacedTrainException } from 'common/api/osrdEditoastApi';
import { useStoreDataForRollingStockSelector } from 'modules/rollingStock/components/RollingStockSelector/useStoreDataForRollingStockSelector';
import {
  findExceptionWithOccurrenceId,
  hasNoChangeGroups,
} from 'modules/timetableItem/helpers/pacedTrain';
import {
  createExceptions,
  deleteExceptions,
  storePacedTrain,
  updateExceptions,
} from 'modules/timetableItem/helpers/updateTimetableItemHelpers';
import { setSuccess } from 'reducers/main';
import { clearAddedExceptionsList } from 'reducers/osrdconf/operationalStudiesConf';
import {
  getName,
  getStartTime,
  getOperationalStudiesConf,
} from 'reducers/osrdconf/operationalStudiesConf/selectors';
import type { TimetableItem, TrainId, TimetableItemToEditData } from 'reducers/osrdconf/types';
import { updateSelectedTrainId, updateTrainIdUsedForProjection } from 'reducers/simulationResults';
import { getTrainIdUsedForProjection } from 'reducers/simulationResults/selectors';
import { useAppDispatch } from 'store';
import {
  extractEditoastIdFromPacedTrainId,
  extractPacedTrainIdFromOccurrenceId,
  formatEditoastIdToIndexedOccurrenceId,
  formatEditoastIdToPacedTrainId,
  isOccurrenceId,
} from 'utils/trainId';

import checkCurrentConfig from '../helpers/checkCurrentConfig';
import {
  formatOccurrenceException,
  formatPacedTrainPayload,
  formatPacedTrainWithDetailsToPacedTrainPayload,
} from '../helpers/formatTimetableItemPayload';

const useUpdateTimetableItem = (
  setIsWorking: (isWorking: boolean) => void,
  setDisplayTimetableItemManagement: (type: string) => void,
  upsertTimetableItems: (timetableItems: TimetableItem[]) => void,
  setTimetableItemIdToEdit: (timetableItemToEditData?: TimetableItemToEditData) => void,
  timetableItemToEditData?: TimetableItemToEditData,
  selectedTrainId?: TrainId
) => {
  const { t } = useTranslation('operational-studies', { keyPrefix: 'manageTimetableItem' });
  const dispatch = useAppDispatch();

  const { timetableId } = useScenarioContext();

  const confName = useSelector(getName);
  const simulationConf = useSelector(getOperationalStudiesConf);
  const trainIdUsedForProjection = useSelector(getTrainIdUsedForProjection);
  const startTime = useSelector(getStartTime);
  const { rollingStock } = useStoreDataForRollingStockSelector({
    rollingStockId: simulationConf.rollingStockID,
  });

  return async function submitConfUpdateTrainSchedules() {
    if (
      !timetableItemToEditData ||
      !checkCurrentConfig(simulationConf, t, dispatch, rollingStock?.name)
    )
      return;

    setIsWorking(true);

    const { timetableItemId } = timetableItemToEditData;

    // ========== user is editing an occurrence ==========
    if (timetableItemToEditData.occurrenceId) {
      const { generatedException, occurrenceIndex } = formatOccurrenceException(
        simulationConf,
        rollingStock!.name,
        timetableItemToEditData as TimetableItemToEditData & {
          occurrenceId: NonNullable<TimetableItemToEditData['occurrenceId']>;
        }
      );

      const existingException = findExceptionWithOccurrenceId(
        timetableItemToEditData.originalPacedTrain.paced?.exceptions ?? [],
        timetableItemToEditData.occurrenceId
      );

      let finalException: PacedTrainException = {
        ...generatedException,
        key: existingException?.key ?? uuidV4(),
        occurrence_index: occurrenceIndex,
      };
      if (existingException) {
        // disabled is not included in changeGroups, so we need to check separately
        if (isEmpty(generatedException) && !existingException.disabled) {
          // Exception should be deleted as there is no change
          // TODO_EXCEPTION: remove `!` when using TrainScheduleException type
          await deleteExceptions(dispatch, [existingException.id!]);
        } else {
          // Exception already exists -> update it with the existing id
          const toUpdate: PacedTrainException = {
            ...generatedException,
            id: existingException.id,
            // TODO_EXCEPTION: remove this when drop key in the model
            key: existingException.key,
            occurrence_index: occurrenceIndex,
          };
          await updateExceptions(dispatch, [toUpdate], timetableItemId);
          finalException = toUpdate;
        }
      } else {
        // No existing exception -> create it
        const exceptionToCreate: PacedTrainException = {
          ...generatedException,
          // TODO_EXCEPTION: remove this when drop key in the model
          key: uuidV4(),
          occurrence_index: occurrenceIndex,
        };
        const [created] = await createExceptions(
          dispatch,
          [exceptionToCreate],
          timetableItemId,
          timetableId
        );
        finalException = { ...exceptionToCreate, id: created.id };
      }

      const updatedExceptions = updatePacedTrainExceptionsList(
        timetableItemToEditData.originalPacedTrain.paced?.exceptions ?? [],
        finalException,
        timetableItemToEditData.occurrenceId
      );
      const formattedPacedTrain = formatPacedTrainWithDetailsToPacedTrainPayload(
        timetableItemToEditData.originalPacedTrain
      );

      upsertTimetableItems([
        {
          ...formattedPacedTrain,
          id: timetableItemId,
          train_schedule_set_id: timetableItemToEditData.originalPacedTrain.train_schedule_set_id,
          paced: formattedPacedTrain.paced
            ? { ...formattedPacedTrain.paced, exceptions: updatedExceptions }
            : undefined,
        },
      ]);
    } else {
      // ========== user is editing the whole paced train or transforming from an unique train ==========
      const {
        newTrainSchedulePayload: trainSchedule,
        originalExceptions,
        updatedExceptions,
      } = formatPacedTrainPayload(simulationConf, rollingStock!.name, timetableItemToEditData);

      const exceptionsToDeleteIds =
        simulationConf.editingItemType === 'uniqueTrain'
          ? (timetableItemToEditData.originalPacedTrain.paced?.exceptions
              .map((e) => e.id)
              .filter((id): id is number => id != null) ?? [])
          : [];

      // Delete exceptions marked for deletion (e.g. when switching from paced to unique train)
      if (exceptionsToDeleteIds.length > 0) {
        await deleteExceptions(dispatch, exceptionsToDeleteIds);
      }

      // Handle exceptions create/update/delete before storing the paced train
      let finalExceptions = originalExceptions ?? [];

      if (updatedExceptions) {
        const exceptionsToUpdate = updatedExceptions.filter((updatedException) => {
          const original = originalExceptions?.find((o) => o.id === updatedException.id);
          if (!original) return false;
          if (hasNoChangeGroups(updatedException)) return false;
          return !isEqual(original, updatedException);
        });

        const exceptionsToCreate = updatedExceptions.filter(
          (updatedException) => !originalExceptions?.some((o) => o.id === updatedException.id)
        );

        const exceptionsToDelete = (originalExceptions ?? []).filter(
          (original) =>
            original.id != null &&
            (!updatedExceptions.some((u) => u.id === original.id) ||
              updatedExceptions.some(
                (u) =>
                  u.id === original.id &&
                  hasNoChangeGroups(u) &&
                  (u.disabled === false || u.disabled === undefined)
              ))
        );

        if (exceptionsToDelete.length > 0) {
          await deleteExceptions(
            dispatch,
            exceptionsToDelete.map((e) => e.id!)
          );
        }

        if (exceptionsToUpdate.length > 0) {
          await updateExceptions(dispatch, exceptionsToUpdate, timetableItemId);
        }

        let createdExceptions: PacedTrainException[] = [];
        if (exceptionsToCreate.length > 0) {
          const created = await createExceptions(
            dispatch,
            exceptionsToCreate,
            timetableItemId,
            timetableId
          );

          // TODO: remove this part when the back will be done inserting the new exception format in TrainSchedule
          createdExceptions = created.map((exceptionNewModel) => {
            const {
              change_groups,
              train_schedule_id: _train_schedule_id,
              timetable_id: _timetable_id,
              ...restExceptions
            } = exceptionNewModel;
            return {
              ...change_groups,
              ...restExceptions,
              // TODO_EXCEPTION: remove this when drop key in the model
              key: restExceptions.id.toString(),
            };
          });
        }

        // Build final exceptions list with created ids
        let createIndex = 0;
        finalExceptions = updatedExceptions
          .filter((ex) => !exceptionsToDelete.some((d) => d.id === ex.id))
          .map((ex) => {
            if (!ex.id) return createdExceptions[createIndex++] ?? ex;
            return ex;
          });
      }

      await storePacedTrain(
        timetableItemId,
        {
          ...trainSchedule,
          train_schedule_set_id: timetableItemToEditData.originalPacedTrain.train_schedule_set_id,
          ...(trainSchedule.paced && {
            paced: { ...trainSchedule.paced, exceptions: finalExceptions },
          }),
        },
        dispatch,
        upsertTimetableItems
      );
    }

    // if the selected TimetableItem is an Occurrence of the edited PacedTrain, keep it selected
    // else select the first Occurrence by default
    const trainIdToSelect =
      (selectedTrainId &&
        isOccurrenceId(selectedTrainId) &&
        extractEditoastIdFromPacedTrainId(extractPacedTrainIdFromOccurrenceId(selectedTrainId)) ===
          timetableItemId) ||
      !timetableItemToEditData.originalPacedTrain.paced
        ? selectedTrainId
        : formatEditoastIdToIndexedOccurrenceId({
            pacedTrainId: timetableItemId,
            occurrenceIndex: 0,
          });

    // dispatch success and update the selected train id
    dispatch(
      setSuccess({
        title:
          simulationConf.editingItemType === 'uniqueTrain'
            ? t('pacedTrainUpdated')
            : t('uniqueTrainUpdated'),
        text: `${confName}: ${startTime.toLocaleString()}`,
      })
    );
    dispatch(updateSelectedTrainId(trainIdToSelect));

    // if the updated train was just transformed from pacedTrain to uniqueTrain
    // and one of the occurrences was used for the projection, update the projectedTrainId
    if (
      trainIdUsedForProjection &&
      isOccurrenceId(trainIdUsedForProjection) &&
      trainIdUsedForProjection.includes(`_${timetableItemId}_`) &&
      !timetableItemToEditData.originalPacedTrain.paced
    ) {
      dispatch(updateTrainIdUsedForProjection(formatEditoastIdToPacedTrainId(timetableItemId)));
    }

    // close the modal
    dispatch(clearAddedExceptionsList());
    setDisplayTimetableItemManagement(MANAGE_TIMETABLE_ITEM_TYPES.none);
    setTimetableItemIdToEdit(undefined);
  };
};

export default useUpdateTimetableItem;
