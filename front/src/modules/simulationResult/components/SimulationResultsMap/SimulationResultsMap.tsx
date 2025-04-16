import { useCallback, useEffect, useMemo, useState, useRef } from 'react';

import bbox from '@turf/bbox';
import { lineString, point } from '@turf/helpers';
import lineLength from '@turf/length';
import lineSlice from '@turf/line-slice';
import type { MapLayerMouseEvent } from 'maplibre-gl';
import type { MapRef } from 'react-map-gl/maplibre';
import { useSelector } from 'react-redux';

import captureMap from 'applications/operationalStudies/helpers/captureMap';
import { useScenarioContext } from 'applications/operationalStudies/hooks/useScenarioContext';
import type {
  PathPropertiesFormatted,
  SimulationResponseSuccess,
} from 'applications/operationalStudies/types';
import type { PathfindingResultSuccess } from 'common/api/osrdEditoastApi';
import BaseMap from 'common/Map/BaseMap';
import MapButtons from 'common/Map/Buttons/MapButtons';
import MapMarkers, { type MapMarker } from 'common/Map/components/MapMarkers';
import TrainOnMap, {
  type TimetableItemCurrentInfo,
} from 'common/Map/components/TrainOnMap/TrainOnMap';
import { removeSearchItemMarkersOnMap } from 'common/Map/utils';
import { computeBBoxViewport } from 'common/Map/WarpedMap/core/helpers';
import { useInfraID } from 'common/osrdContext';
import { LAYER_GROUPS_ORDER, LAYERS } from 'config/layerOrder';
import getPointOnPathCoordinates from 'modules/pathfinding/helpers/getPointOnPathCoordinates';
import getTrackLengthCumulativeSums from 'modules/pathfinding/helpers/getTrackLengthCumulativeSums';
import { MARKER_TYPE } from 'modules/trainschedule/components/ManageTrainSchedule/ManageTrainScheduleMap/ItineraryMarkers';
import { updateViewport, type Viewport } from 'reducers/map';
import { getMap } from 'reducers/map/selectors';
import type { TimetableItemId } from 'reducers/osrdconf/types';
import { getIsPlaying } from 'reducers/simulationResults/selectors';
import { useAppDispatch } from 'store';
import { isoDateWithTimezoneToSec } from 'utils/date';
import { kmToM, mmToM, msToKmh } from 'utils/physics';

import getSelectedTrainHoverPositions from './getSelectedTrainHoverPositions';
import { useChartSynchronizer } from '../ChartSynchronizer';
import Itinerary from './RenderItinerary';
import { interpolateOnPosition } from '../ChartHelpers/ChartHelpers';

const MAP_ID = 'simulation-result-map';

type SimulationResultMapProps = {
  pathfindingResult?: PathfindingResultSuccess;
  pathsfindingResult?: PathfindingResultSuccess[];
  geometry?: PathPropertiesFormatted['geometry'];
  geometries?: PathPropertiesFormatted['geometry'][];
  timetableItemSimulation?: SimulationResponseSuccess & {
    timetableItemId: TimetableItemId;
    startTime: string;
  };
  setMapCanvas?: (mapCanvas: string) => void;
};

const SimulationResultMap = ({
  pathfindingResult,
  pathsfindingResult,
  geometry,
  geometries,
  timetableItemSimulation,
  setMapCanvas,
}: SimulationResultMapProps) => {
  const dispatch = useAppDispatch();

  const infraID = useInfraID();
  const { getTrackSectionsByIds } = useScenarioContext();
  const { viewport, mapSearchMarker, mapStyle, showOSM, terrain3DExaggeration, layersSettings } =
    useSelector(getMap);
  const isPlaying = useSelector(getIsPlaying);

  const mapRef = useRef<MapRef>(null);
  const [selectedTrainHoverPosition, setSelectedTrainHoverPosition] =
    useState<TimetableItemCurrentInfo>();
  const [mapMarkers, setMapMarkers] = useState<MapMarker[]>([]);

  const geojsonPath = useMemo(() => geometry && lineString(geometry.coordinates), [geometry]);

  const geojsonPaths = useMemo(
    () => geometries?.map((geo) => lineString(geo.coordinates)) || [],
    [geometries]
  );

  useEffect(() => {
    const getPathItemsCoordinates = async (paths: PathfindingResultSuccess[]) => {
      const allMarkers: MapMarker[] = [];

      for (const path of paths) {
        const trackIds = path.track_section_ranges.map((range) => range.track_section);
        const tracks = await getTrackSectionsByIds(trackIds);
        const tracksLengthCumulativeSums = getTrackLengthCumulativeSums(path.track_section_ranges);

        const markers = path.path_item_positions.map((position, index) => {
          let pointType = MARKER_TYPE.VIA;
          const indexToUse = path.path_item_positions.length > 1 ? index + 1 : index;
          if (index === 0) {
            pointType = MARKER_TYPE.ORIGIN;
          } else if (index === path.path_item_positions.length - 1) {
            pointType = MARKER_TYPE.DESTINATION;
          }
          return {
            coordinates: getPointOnPathCoordinates(
              tracks,
              path.track_section_ranges,
              tracksLengthCumulativeSums,
              position
            ),
            pointType,
            markerIndex: indexToUse,
          };
        });

        allMarkers.push(...markers);
      }

      setMapMarkers(allMarkers);
    };

    const pathsToProcess: PathfindingResultSuccess[] = [];

    if (pathfindingResult) {
      pathsToProcess.push(pathfindingResult);
    }

    if (pathsfindingResult && pathsfindingResult.length > 0) {
      pathsToProcess.push(...pathsfindingResult);
    }

    if (pathsToProcess.length > 0) {
      getPathItemsCoordinates(pathsToProcess);
    }
  }, [pathfindingResult, pathsfindingResult]);

  const interactiveLayerIds = useMemo(
    () => (geojsonPath ? ['geojsonPath', 'main-train-path'] : []),
    [geojsonPath]
  );

  const { updateTimePosition } = useChartSynchronizer(
    (_, positionValues) => {
      if (timetableItemSimulation && geojsonPath) {
        const selectedTrainPosition = getSelectedTrainHoverPositions(
          geojsonPath,
          positionValues,
          timetableItemSimulation.timetableItemId
        );
        setSelectedTrainHoverPosition(selectedTrainPosition);
      }
    },
    'simulation-result-map',
    [geojsonPath, timetableItemSimulation]
  );

  const updateViewportChange = useCallback(
    (value: Partial<Viewport>) => dispatch(updateViewport(value, undefined)),
    [dispatch]
  );

  const resetPitchBearing = () => {
    updateViewportChange({
      bearing: 0,
      pitch: 0,
    });
  };

  const onPathHover = (e: MapLayerMouseEvent) => {
    if (!isPlaying && e && geojsonPath && timetableItemSimulation) {
      const line = lineString(geojsonPath.geometry.coordinates);
      const cursorPoint = point(e.lngLat.toArray());

      const startCoordinates = geojsonPath.geometry.coordinates[0];

      const start = point(startCoordinates);
      const sliced = lineSlice(start, cursorPoint, line);
      const positionLocal = kmToM(lineLength(sliced, { units: 'kilometers' }));

      const baseSpeedData = timetableItemSimulation.base.speeds.map((speed, i) => ({
        speed: msToKmh(speed),
        position: mmToM(timetableItemSimulation.base.positions[i]),
        time: timetableItemSimulation.base.times[i],
      }));
      const timePositionLocal = interpolateOnPosition(
        { speed: baseSpeedData },
        positionLocal,
        isoDateWithTimezoneToSec(timetableItemSimulation.startTime)
      );

      if (timePositionLocal instanceof Date) {
        updateTimePosition(timePositionLocal);
      } else {
        throw new Error('Map onFeatureHover, try to update TimePositionValue with incorrect input');
      }
    }
  };

  useEffect(() => {
    if (geojsonPath) {
      const newViewport = computeBBoxViewport(bbox(geojsonPath), viewport);
      updateViewportChange(newViewport);
    }
  }, [geojsonPath]);

  return (
    <>
      <MapButtons
        map={mapRef.current ?? undefined}
        resetPitchBearing={resetPitchBearing}
        bearing={viewport.bearing}
        withMapKeyButton
        viewPort={viewport}
        isNewButtons
      />
      <BaseMap
        mapId={MAP_ID}
        mapRef={mapRef}
        cursor="pointer"
        infraId={infraID}
        interactiveLayerIds={interactiveLayerIds}
        mapSearchMarker={mapSearchMarker}
        mapStyle={mapStyle}
        onClick={() => {
          removeSearchItemMarkersOnMap(dispatch);
        }}
        onIdle={() => {
          captureMap(viewport, MAP_ID, setMapCanvas, geometry);
        }}
        onMouseEnter={onPathHover}
        showOSM={showOSM}
        viewPort={viewport}
        updatePartialViewPort={updateViewportChange}
        terrain3DExaggeration={terrain3DExaggeration}
        layersSettings={layersSettings}
      >
        {geojsonPaths.map((geojsonPathFromPaths, index) => (
          <Itinerary
            key={index}
            geojsonPath={geojsonPathFromPaths}
            layerOrder={LAYER_GROUPS_ORDER[LAYERS.PATH.GROUP]}
            idSuffix={index}
          />
        ))}
        {geojsonPath && (
          <Itinerary
            geojsonPath={geojsonPath}
            layerOrder={LAYER_GROUPS_ORDER[LAYERS.PATH.GROUP]}
            idSuffix={geojsonPath.properties?.id}
          />
        )}

        <MapMarkers markers={mapMarkers} />
        {geojsonPath && selectedTrainHoverPosition && timetableItemSimulation && (
          <TrainOnMap
            trainInfo={selectedTrainHoverPosition}
            geojsonPath={geojsonPath}
            viewport={viewport}
            timetableItemSimulation={timetableItemSimulation}
          />
        )}
      </BaseMap>
    </>
  );
};

export default SimulationResultMap;
