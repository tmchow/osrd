import { useEffect, useMemo, useState } from 'react';

import dayjs from 'dayjs';
import { useSelector } from 'react-redux';

import {
  osrdEditoastApi,
  type PathfindingResultSuccess,
  type Property,
} from 'common/api/osrdEditoastApi';
import { useInfraID } from 'common/osrdContext';
import useSpeedSpaceChart from 'modules/simulationResult/components/SpeedSpaceChart/useSpeedSpaceChart';
import { getOperationalStudiesElectricalProfileSetId } from 'reducers/osrdconf/operationalStudiesConf/selectors';
import type {
  PacedTrainResponseWithPacedTrainId,
  TimetableItemId,
  TrainId,
} from 'reducers/osrdconf/types';
import { getSelectedTrainId } from 'reducers/simulationResults/selectors';
import { Duration } from 'utils/duration';
import {
  formatEditoastTrainIdToPacedTrainId,
  formatEditoastTrainIdToTrainScheduleId,
  formatOccurrenceIdToEditoastTrainId,
  formatTrainScheduleIdToEditoastTrainId,
  getOccurrenceIndexFromOccurrenceId,
  isOccurrence,
  isTrainSchedule,
} from 'utils/trainId';

import type { PathPropertiesFormatted, SimulationResultsData } from '../types';

type useSimulationProps = {
  selectedTimetableItemIds: TimetableItemId[];
};

/**
 * Prepare data to be used in simulation results
 */
const useSimulationResults = ({
  selectedTimetableItemIds,
}: useSimulationProps): SimulationResultsData => {
  const infraId = useInfraID();
  const electricalProfileSetId = useSelector(getOperationalStudiesElectricalProfileSetId);
  const selectedTrainId = useSelector(getSelectedTrainId);
  const selectedTimetableItemIdsAsTrainsIds = selectedTimetableItemIds as TrainId[];
  const [getTrainSchedulePath] = osrdEditoastApi.endpoints.getTrainScheduleByIdPath.useLazyQuery();
  const [getPacedTrainPath] = osrdEditoastApi.endpoints.getPacedTrainByIdPath.useLazyQuery();
  const [paths, setPaths] = useState<PathfindingResultSuccess[]>([]);
  const [pathsProperties, setPathsProperties] = useState<PathPropertiesFormatted[]>();
  const [postPathProperties] =
    osrdEditoastApi.endpoints.postInfraByInfraIdPathProperties.useLazyQuery();

  useEffect(() => {
    const pathsData = async () => {
      const asyncPathData = selectedTimetableItemIdsAsTrainsIds.map((originalId) => {
        if (isTrainSchedule(originalId)) {
          return getTrainSchedulePath({
            id: formatTrainScheduleIdToEditoastTrainId(originalId),
            infraId: infraId!,
          }).unwrap();
        }
        return getPacedTrainPath({
          id: formatOccurrenceIdToEditoastTrainId(originalId),
          infraId: infraId!,
        }).unwrap();
      });

      const asyncPathsData = (await Promise.all(asyncPathData)).filter(
        (path) => path.status === 'success'
      );
      setPaths(asyncPathsData as PathfindingResultSuccess[]);
    };

    pathsData();
  }, [selectedTimetableItemIds]);

  const editoastSelectedTrainId = useMemo(() => {
    if (!selectedTrainId) return undefined;
    if (isTrainSchedule(selectedTrainId)) {
      return formatTrainScheduleIdToEditoastTrainId(selectedTrainId);
    }
    return formatOccurrenceIdToEditoastTrainId(selectedTrainId);
  }, [selectedTrainId]);

  const { data: selectedTrainSchedule } = osrdEditoastApi.endpoints.getTrainScheduleById.useQuery(
    {
      id: editoastSelectedTrainId!,
    },
    { skip: !editoastSelectedTrainId || (selectedTrainId && !isTrainSchedule(selectedTrainId)) }
  );

  const { data: selectedPacedTrain } = osrdEditoastApi.endpoints.getPacedTrainById.useQuery(
    {
      id: editoastSelectedTrainId!,
    },
    { skip: !editoastSelectedTrainId || (selectedTrainId && !isOccurrence(selectedTrainId)) }
  );

  const { data: rawTrainSchedulePath } =
    osrdEditoastApi.endpoints.getTrainScheduleByIdPath.useQuery(
      {
        id: editoastSelectedTrainId!,
        infraId: infraId!,
      },
      {
        skip:
          !editoastSelectedTrainId ||
          !infraId ||
          (selectedTrainId && !isTrainSchedule(selectedTrainId)),
      }
    );

  const { data: rawPacedTrainPath } = osrdEditoastApi.endpoints.getPacedTrainByIdPath.useQuery(
    {
      id: editoastSelectedTrainId!,
      infraId: infraId!,
    },
    {
      skip:
        !editoastSelectedTrainId || !infraId || (selectedTrainId && !isOccurrence(selectedTrainId)),
    }
  );

  const path = useMemo(() => {
    if (!selectedTrainId) return undefined;

    if (isTrainSchedule(selectedTrainId)) {
      return rawTrainSchedulePath?.status === 'success' ? rawTrainSchedulePath : undefined;
    }
    return rawPacedTrainPath?.status === 'success' ? rawPacedTrainPath : undefined;
  }, [selectedTrainId, rawTrainSchedulePath, rawPacedTrainPath]);

  const { data: selectedTrainScheduleSimulation } =
    osrdEditoastApi.endpoints.getTrainScheduleByIdSimulation.useQuery(
      { id: editoastSelectedTrainId!, infraId: infraId!, electricalProfileSetId },
      {
        skip:
          !editoastSelectedTrainId ||
          !infraId ||
          (selectedTrainId && !isTrainSchedule(selectedTrainId)),
      }
    );

  const { data: selectedPacedTrainSimulation } =
    osrdEditoastApi.endpoints.getPacedTrainByIdSimulation.useQuery(
      { id: editoastSelectedTrainId!, infraId: infraId!, electricalProfileSetId },
      {
        skip:
          !editoastSelectedTrainId ||
          !infraId ||
          (selectedTrainId && !isOccurrence(selectedTrainId)),
      }
    );

  const selectedTimetableItemSimulationData = useMemo(() => {
    if (!selectedTrainId) return undefined;

    if (isTrainSchedule(selectedTrainId)) {
      return selectedTrainSchedule
        ? {
            selectedTimetableItem: {
              ...selectedTrainSchedule,
              id: formatEditoastTrainIdToTrainScheduleId(selectedTrainSchedule.id),
            },
            selectedTimetableItemSimulation: selectedTrainScheduleSimulation,
            selectedTimetableItemStartTime: selectedTrainSchedule?.start_time,
          }
        : undefined;
    }
    if (!selectedPacedTrain) return undefined;

    const selectedOccurrenceIndex = getOccurrenceIndexFromOccurrenceId(selectedTrainId);
    const pacedTrainStepInMs = Duration.parse(selectedPacedTrain.paced.step).ms;

    const selectedOccurrenceStartTime: string = dayjs(selectedPacedTrain.start_time)
      .add(selectedOccurrenceIndex * pacedTrainStepInMs, 'ms')
      .toISOString();

    const updatedSelectedPacedTrain: PacedTrainResponseWithPacedTrainId = {
      ...selectedPacedTrain,
      id: formatEditoastTrainIdToPacedTrainId(selectedPacedTrain.id),
      start_time: selectedOccurrenceStartTime,
    };

    return {
      selectedTimetableItem: updatedSelectedPacedTrain,
      selectedTimetableItemSimulation: selectedPacedTrainSimulation,
      selectedTimetableItemStartTime: selectedOccurrenceStartTime,
    };
  }, [
    selectedTrainId,
    selectedTrainSchedule,
    selectedPacedTrain,
    selectedTrainScheduleSimulation,
    selectedPacedTrainSimulation,
  ]);

  const speedSpaceChart = useSpeedSpaceChart(
    selectedTimetableItemSimulationData?.selectedTimetableItem,
    path,
    selectedTimetableItemSimulationData?.selectedTimetableItemSimulation,
    selectedTimetableItemSimulationData?.selectedTimetableItemStartTime
  );

  useEffect(() => {
    const fetchAllPathProperties = async () => {
      if (paths && paths.length < 1) setPathsProperties(undefined);
      if (!infraId) return;

      const asyncPathProperties = paths.map((pathfindingResult) => {
        const pathPropertiesParams = {
          infraId,
          props: [
            'electrifications',
            'geometry',
            'operational_points',
            'curves',
            'slopes',
          ] as Property[],
          pathPropertiesInput: {
            track_section_ranges: pathfindingResult.track_section_ranges,
          },
        };
        return postPathProperties(pathPropertiesParams).unwrap();
      });

      const resolvedProperties = await Promise.all(asyncPathProperties);
      setPathsProperties(resolvedProperties as unknown as PathPropertiesFormatted[]);
    };

    fetchAllPathProperties();
  }, [infraId, paths]);

  if (!selectedTrainId)
    return {
      selectedTimetableItemPowerRestrictions: [],
    };

  return {
    selectedTimetableItem: selectedTimetableItemSimulationData?.selectedTimetableItem,
    selectedTimetableItemRollingStock: speedSpaceChart?.rollingStock,
    selectedTimetableItemPowerRestrictions: speedSpaceChart?.formattedPowerRestrictions || [],
    timetableItemSimulation: speedSpaceChart?.simulation,
    pathProperties: speedSpaceChart?.formattedPathProperties,
    pathsProperties,
    pathLength: path?.length,
    path,
    paths,
  };
};

export default useSimulationResults;
