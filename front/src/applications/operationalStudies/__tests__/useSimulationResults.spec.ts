import { skipToken } from '@reduxjs/toolkit/query';
import { renderHook } from '@testing-library/react';
import { beforeEach, describe, expect, it, vi } from 'vitest';

import useSimulationResults from '../hooks/useSimulationResults';

const {
  mockUseTranslation,
  tMock,
  mockUseSelector,
  mockUseScenarioContext,
  mockUseSelectedTimetableItem,
  mockGetTrainPathQuery,
  mockGetTrainSimulationQuery,
  mockGetRollingStockQuery,
  mockGetPathPropertiesQuery,
  mockFormatPowerRestrictionRangesWithHandled,
  mockPreparePathPropertiesData,
  mockFindExceptionWithOccurrenceId,
  mockExtractOccurrenceDetailsFromPacedTrain,
  mockComputeIndexedOccurrenceStartTime,
  mockExtractOccurrenceIndexFromOccurrenceId,
  mockIsOccurrenceId,
  mockDurationParse,
} = vi.hoisted(() => ({
  mockUseTranslation: vi.fn(),
  tMock: vi.fn((key: string) => key),
  mockUseSelector: vi.fn(),
  mockUseScenarioContext: vi.fn(),
  mockUseSelectedTimetableItem: vi.fn(),

  mockGetTrainPathQuery: vi.fn(),
  mockGetTrainSimulationQuery: vi.fn(),
  mockGetRollingStockQuery: vi.fn(),
  mockGetPathPropertiesQuery: vi.fn(),

  mockFormatPowerRestrictionRangesWithHandled: vi.fn(),
  mockPreparePathPropertiesData: vi.fn(),

  mockFindExceptionWithOccurrenceId: vi.fn(),
  mockExtractOccurrenceDetailsFromPacedTrain: vi.fn(),
  mockComputeIndexedOccurrenceStartTime: vi.fn(),
  mockExtractOccurrenceIndexFromOccurrenceId: vi.fn(),
  mockIsOccurrenceId: vi.fn(),

  mockDurationParse: vi.fn(),
}));

vi.mock('react-i18next', () => ({
  useTranslation: mockUseTranslation,
}));

vi.mock('react-redux', () => ({
  useSelector: mockUseSelector,
}));

vi.mock('../hooks/useScenarioContext', () => ({
  useScenarioContext: mockUseScenarioContext,
}));

vi.mock('modules/timetableItem/hooks/useSelectedTimetableItem', () => ({
  default: mockUseSelectedTimetableItem,
}));

vi.mock('common/api/osrdEditoastApi', () => ({
  osrdEditoastApi: {
    endpoints: {
      getTrainPath: {
        useQuery: mockGetTrainPathQuery,
      },
      getTrainSimulation: {
        useQuery: mockGetTrainSimulationQuery,
      },
      getRollingStockNameByRollingStockName: {
        useQuery: mockGetRollingStockQuery,
      },
      postInfraByInfraIdPathProperties: {
        useQuery: mockGetPathPropertiesQuery,
      },
    },
  },
}));

vi.mock('modules/powerRestriction/helpers/formatPowerRestrictionRangesWithHandled', () => ({
  default: mockFormatPowerRestrictionRangesWithHandled,
}));

vi.mock('../utils', () => ({
  preparePathPropertiesData: mockPreparePathPropertiesData,
}));

vi.mock('modules/timetableItem/helpers/pacedTrain', () => ({
  extractOccurrenceDetailsFromPacedTrain: mockExtractOccurrenceDetailsFromPacedTrain,
  findExceptionWithOccurrenceId: mockFindExceptionWithOccurrenceId,
  computeIndexedOccurrenceStartTime: mockComputeIndexedOccurrenceStartTime,
}));

vi.mock('utils/trainId', () => ({
  extractOccurrenceIndexFromOccurrenceId: mockExtractOccurrenceIndexFromOccurrenceId,
  formatEditoastIdToPacedTrainId: (id: string) => id,
  isOccurrenceId: mockIsOccurrenceId,
}));

vi.mock('utils/duration', () => ({
  Duration: {
    parse: mockDurationParse,
  },
}));

describe('useSimulationResults', () => {
  const baseTrain = {
    id: 'train-1',
    start_time: '2026-03-16T08:00:00.000Z',
    rolling_stock_name: 'fast-rs',
    path: { id: 'path-1' },
    paced: undefined,
  };

  const pacedTimetableItem = {
    ...baseTrain,
    id: 'paced-train-1',
    paced: {
      interval: 'PT15M',
      exceptions: [{ key: 'exception-1' }],
    },
  };

  const rollingStock = { name: 'fast-rs', effort_curves: [] };

  const pathfindingSuccess = {
    status: 'success',
    path: {
      track_section_ranges: [{ track_section: 'TS1', begin: 0, end: 1000 }],
    },
  };

  const simulationSuccess = {
    status: 'success',
    electrical_profiles: ['profile-1'],
  };

  const rawPathProperties = {
    curves: [],
    electrifications: [],
  };

  const preparedPathProperties = {
    curves: [],
    electrifications: [],
    slopes: [],
    operationalPoints: [],
    voltages: [],
  };

  const powerRestrictions = [{ start: 0, end: 10, handled: true }];

  const renderUseSimulationResults = () => renderHook(() => useSimulationResults());

  const createQueryResult = <T>(currentData?: T, isFetching = false) => ({
    currentData,
    isFetching,
  });

  beforeEach(() => {
    vi.clearAllMocks();

    mockUseTranslation.mockReturnValue({
      t: tMock,
    });

    mockUseScenarioContext.mockReturnValue({
      infraId: 12,
      electricalProfileSetId: 34,
    });

    mockUseSelector.mockReturnValue('train-1');
    mockUseSelectedTimetableItem.mockReturnValue(baseTrain);

    mockIsOccurrenceId.mockReturnValue(false);
    mockFindExceptionWithOccurrenceId.mockReturnValue(undefined);
    mockExtractOccurrenceDetailsFromPacedTrain.mockReturnValue({});
    mockExtractOccurrenceIndexFromOccurrenceId.mockReturnValue(2);
    mockComputeIndexedOccurrenceStartTime.mockReturnValue(new Date('2026-03-16T08:30:00.000Z'));
    mockDurationParse.mockReturnValue('parsed-interval');

    mockGetTrainPathQuery.mockReturnValue(createQueryResult(pathfindingSuccess));
    mockGetTrainSimulationQuery.mockReturnValue(createQueryResult(simulationSuccess));
    mockGetRollingStockQuery.mockReturnValue(createQueryResult(rollingStock));
    mockGetPathPropertiesQuery.mockReturnValue(createQueryResult(rawPathProperties));

    mockPreparePathPropertiesData.mockReturnValue(preparedPathProperties);
    mockFormatPowerRestrictionRangesWithHandled.mockReturnValue(powerRestrictions);
  });

  describe('when no train can be resolved', () => {
    it('returns no results and skips dependent train queries when no selected train id is available', () => {
      mockUseSelector.mockReturnValue(undefined);

      mockGetTrainPathQuery.mockReturnValue(createQueryResult(undefined));
      mockGetTrainSimulationQuery.mockReturnValue(createQueryResult(undefined));
      mockGetRollingStockQuery.mockReturnValue(createQueryResult(undefined));
      mockGetPathPropertiesQuery.mockReturnValue(createQueryResult(undefined));

      const { result } = renderUseSimulationResults();

      expect(result.current).toEqual({
        results: undefined,
        isSimulationDataLoading: false,
      });

      expect(mockGetTrainPathQuery).toHaveBeenCalledWith(skipToken);
      expect(mockGetTrainSimulationQuery).toHaveBeenCalledWith(skipToken);
      expect(mockGetRollingStockQuery).toHaveBeenCalledWith(skipToken);
      expect(mockGetPathPropertiesQuery).toHaveBeenCalledWith(skipToken);
    });

    it('returns no results when no timetable item is available', () => {
      mockUseSelectedTimetableItem.mockReturnValue(undefined);

      const { result } = renderUseSimulationResults();

      expect(result.current).toEqual({
        results: undefined,
        isSimulationDataLoading: false,
      });
    });
  });

  describe('when all required data is available', () => {
    it('returns a valid simulation result for a standard train', () => {
      const { result } = renderUseSimulationResults();

      expect(mockGetTrainPathQuery).toHaveBeenCalledWith({
        id: 'train-1',
        infraId: 12,
        exceptionKey: undefined,
      });

      expect(mockGetTrainSimulationQuery).toHaveBeenCalledWith({
        id: 'train-1',
        infraId: 12,
        electricalProfileSetId: 34,
        exceptionKey: undefined,
      });

      expect(mockGetRollingStockQuery).toHaveBeenCalledWith({
        rollingStockName: 'fast-rs',
      });

      expect(mockGetPathPropertiesQuery).toHaveBeenCalledWith({
        infraId: 12,
        pathPropertiesInput: {
          track_section_ranges: pathfindingSuccess.path.track_section_ranges,
        },
      });

      expect(mockPreparePathPropertiesData).toHaveBeenCalledWith(
        simulationSuccess.electrical_profiles,
        rawPathProperties,
        pathfindingSuccess,
        baseTrain.path,
        tMock
      );

      expect(mockFormatPowerRestrictionRangesWithHandled).toHaveBeenCalledWith({
        selectedTimetableItem: baseTrain,
        selectedTrainRollingStock: rollingStock,
        pathfindingResult: pathfindingSuccess,
        pathProperties: preparedPathProperties,
      });

      expect(result.current).toEqual({
        results: {
          isValid: true,
          train: baseTrain,
          rollingStock,
          simulation: simulationSuccess,
          path: pathfindingSuccess,
          pathProperties: preparedPathProperties,
          powerRestrictions,
        },
        isSimulationDataLoading: false,
      });
    });

    it('returns an empty powerRestrictions array when the formatter returns null', () => {
      mockFormatPowerRestrictionRangesWithHandled.mockReturnValue(null);

      const { result } = renderUseSimulationResults();

      expect(result.current).toEqual({
        results: {
          isValid: true,
          train: baseTrain,
          rollingStock,
          simulation: simulationSuccess,
          path: pathfindingSuccess,
          pathProperties: preparedPathProperties,
          powerRestrictions: [],
        },
        isSimulationDataLoading: false,
      });
    });
  });

  describe('when data is incomplete', () => {
    it('returns an invalid result when rolling stock is missing', () => {
      mockGetRollingStockQuery.mockReturnValue(createQueryResult(undefined));

      const { result } = renderUseSimulationResults();

      expect(result.current).toEqual({
        results: {
          isValid: false,
          train: baseTrain,
          rollingStock: undefined,
        },
        isSimulationDataLoading: false,
      });

      expect(mockPreparePathPropertiesData).not.toHaveBeenCalled();
      expect(mockFormatPowerRestrictionRangesWithHandled).not.toHaveBeenCalled();
    });

    it('returns an invalid result when path properties are missing', () => {
      mockGetPathPropertiesQuery.mockReturnValue(createQueryResult(undefined));

      const { result } = renderUseSimulationResults();

      expect(result.current).toEqual({
        results: {
          isValid: false,
          train: baseTrain,
          rollingStock,
        },
        isSimulationDataLoading: false,
      });

      expect(mockPreparePathPropertiesData).not.toHaveBeenCalled();
      expect(mockFormatPowerRestrictionRangesWithHandled).not.toHaveBeenCalled();
    });

    it('returns an invalid result with prepared pathProperties when simulation is not successful', () => {
      mockGetTrainSimulationQuery.mockReturnValue(
        createQueryResult({
          status: 'failed',
        })
      );

      const { result } = renderUseSimulationResults();

      expect(mockPreparePathPropertiesData).toHaveBeenCalledWith(
        undefined,
        rawPathProperties,
        pathfindingSuccess,
        baseTrain.path,
        tMock
      );

      expect(result.current).toEqual({
        results: {
          isValid: false,
          train: baseTrain,
          rollingStock,
          pathProperties: preparedPathProperties,
        },
        isSimulationDataLoading: false,
      });

      expect(mockFormatPowerRestrictionRangesWithHandled).not.toHaveBeenCalled();
    });
  });

  describe('when handling paced train occurrences', () => {
    it('returns no results when the selected occurrence is disabled', () => {
      const disabledException = {
        key: 'exception-1',
        disabled: true,
        start_time: { value: '2026-03-16T09:00:00.000Z' },
      };

      mockUseSelector.mockReturnValue('occurrence-1');
      mockUseSelectedTimetableItem.mockReturnValue(pacedTimetableItem);
      mockIsOccurrenceId.mockReturnValue(true);
      mockFindExceptionWithOccurrenceId.mockReturnValue(disabledException);

      const { result } = renderUseSimulationResults();

      expect(mockGetTrainPathQuery).toHaveBeenCalledWith({
        id: 'occurrence-1',
        infraId: 12,
        exceptionKey: 'exception-1',
      });

      expect(mockGetTrainSimulationQuery).toHaveBeenCalledWith({
        id: 'occurrence-1',
        infraId: 12,
        electricalProfileSetId: 34,
        exceptionKey: 'exception-1',
      });

      expect(result.current).toEqual({
        results: undefined,
        isSimulationDataLoading: false,
      });

      expect(mockPreparePathPropertiesData).not.toHaveBeenCalled();
      expect(mockFormatPowerRestrictionRangesWithHandled).not.toHaveBeenCalled();
    });

    it('builds the selected occurrence from exception data and passes exceptionKey to queries', () => {
      const activeException = {
        key: 'exception-1',
        disabled: false,
        start_time: { value: '2026-03-16T09:00:00.000Z' },
      };

      const extractedOccurrenceDetails = {
        labels: ['updated-label'],
        rolling_stock_name: 'fast-rs',
      };

      mockUseSelector.mockReturnValue('occurrence-1');
      mockUseSelectedTimetableItem.mockReturnValue(pacedTimetableItem);
      mockIsOccurrenceId.mockReturnValue(true);
      mockFindExceptionWithOccurrenceId.mockReturnValue(activeException);
      mockExtractOccurrenceDetailsFromPacedTrain.mockReturnValue(extractedOccurrenceDetails);

      const { result } = renderUseSimulationResults();

      const expectedOccurrenceTrain = {
        ...pacedTimetableItem,
        ...extractedOccurrenceDetails,
        start_time: '2026-03-16T09:00:00.000Z',
        id: 'occurrence-1',
      };

      expect(mockExtractOccurrenceDetailsFromPacedTrain).toHaveBeenCalledWith(
        pacedTimetableItem,
        activeException
      );

      expect(mockGetTrainPathQuery).toHaveBeenCalledWith({
        id: 'occurrence-1',
        infraId: 12,
        exceptionKey: 'exception-1',
      });

      expect(mockGetTrainSimulationQuery).toHaveBeenCalledWith({
        id: 'occurrence-1',
        infraId: 12,
        electricalProfileSetId: 34,
        exceptionKey: 'exception-1',
      });

      expect(mockGetRollingStockQuery).toHaveBeenCalledWith({
        rollingStockName: 'fast-rs',
      });

      expect(result.current.results).toMatchObject({
        isValid: true,
        train: expectedOccurrenceTrain,
      });
    });

    it('computes the occurrence start time when the exception does not define one', () => {
      const exceptionWithoutStartTime = {
        key: 'exception-1',
        disabled: false,
      };

      mockUseSelector.mockReturnValue('occurrence-42');
      mockUseSelectedTimetableItem.mockReturnValue(pacedTimetableItem);
      mockIsOccurrenceId.mockReturnValue(true);
      mockFindExceptionWithOccurrenceId.mockReturnValue(exceptionWithoutStartTime);
      mockExtractOccurrenceIndexFromOccurrenceId.mockReturnValue(42);
      mockComputeIndexedOccurrenceStartTime.mockReturnValue(new Date('2026-03-16T18:30:00.000Z'));

      const { result } = renderUseSimulationResults();

      expect(mockExtractOccurrenceIndexFromOccurrenceId).toHaveBeenCalledWith('occurrence-42');
      expect(mockDurationParse).toHaveBeenCalledWith('PT15M');

      expect(mockComputeIndexedOccurrenceStartTime).toHaveBeenCalledWith(
        new Date(pacedTimetableItem.start_time),
        'parsed-interval',
        42
      );

      expect(result.current.results).toMatchObject({
        isValid: true,
        train: {
          id: 'occurrence-42',
          start_time: '2026-03-16T18:30:00.000Z',
        },
      });
    });
  });

  describe('loading state', () => {
    it('returns loading=true when one dependency query is still fetching', () => {
      mockGetTrainPathQuery.mockReturnValue(createQueryResult(pathfindingSuccess, true));

      const { result } = renderUseSimulationResults();

      expect(result.current.isSimulationDataLoading).toBe(true);
    });
  });
});
