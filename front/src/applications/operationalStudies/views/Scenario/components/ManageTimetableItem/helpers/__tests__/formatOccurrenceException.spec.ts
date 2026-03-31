import { describe, expect, it } from 'vitest';

import type { PacedTrainException } from 'common/api/osrdEditoastApi';
import type { PacedTrainWithPacedWithDetails } from 'modules/timetableItem/types';
import { defaultMapSettings } from 'reducers/commonMap';
import type {
  AddedExceptionId,
  IndexedOccurrenceId,
  OperationalStudiesConfState,
} from 'reducers/osrdconf/types';
import { Duration } from 'utils/duration';

import { formatOccurrenceException } from '../formatTimetableItemPayload';

/**
 * `formatOccurrenceException` computes the diff (change groups) between an edited
 * occurrence (represented by `osrdconf` + `rollingStockName`) and the base paced
 * train (`timetableItemToEditData.originalPacedTrain`).
 *
 * Domain context
 * ──────────────
 * • **Indexed occurrence** — auto-generated from cadence (interval × time_window).
 *   Identified by an `IndexedOccurrenceId` (e.g. `indexedoccurrence_238_0`).
 *   start_time = base + index × interval ; name = computeOccurrenceName(baseName, index).
 *   Modifying one creates/updates an exception.
 *   Reverting all changes → empty diff `{}` → the caller should delete the exception
 *   (the occurrence reverts to the base paced train values).
 *
 * • **Added exception** — a manually added occurrence, not part of the cadence.
 *   Identified by an `AddedExceptionId` (e.g. `exception_238_42`).
 *   Always includes `start_time` in the diff (it has no natural cadence position).
 *   Its default name is `baseName/+`. Can be fully deleted (the occurrence disappears).
 *
 * Returns:
 *   `generatedException` — change groups that differ from the base (`{}` = no diff)
 *   `occurrenceIndex`    — numeric index for indexed occurrences, `undefined` for added exceptions
 *
 * It does NOT look up or return any pre-existing exception from paced.exceptions.
 * That responsibility belongs to findExceptionWithOccurrenceId / useUpdateTimetableItem.
 */

describe('formatOccurrenceException', () => {
  // ===========================================================================
  // Shared fixtures
  // ===========================================================================

  /**
   * Form state (`osrdconf`) matching the base paced train for indexed occurrence 0.
   *   - name: 'test 1' = computeOccurrenceName('test', 0)
   *   - startTime: 12:45 = base + 0 × 1h
   */
  const baseOsrdconf: OperationalStudiesConfState = {
    timetableID: 184,
    rollingStockName: 'rollingStock1',
    rollingStockID: 1,
    infraID: 2,
    infraIsLocked: false,
    name: 'test 1',
    startTime: new Date('2025-06-02T12:45:00.000Z'),
    initialSpeed: 0,
    labels: [],
    rollingStockComfort: 'STANDARD',
    category: { main_category: 'FREIGHT_TRAIN' },
    pathSteps: [
      {
        id: '0-0',
        location: {
          operational_point: { trigram: 'WS', secondary_code: 'BV', type: 'trigram' },
          local_track_name: null,
        },
        name: 'West_station',
        arrival: null,
        stopFor: null,
        theoreticalMargin: '0%',
        positionOnPath: 0,
        coordinates: [-0.38775000008590166, 49.50000120103261],
      },
      {
        id: '1-1',
        location: {
          operational_point: { trigram: 'SS', secondary_code: 'BV', type: 'trigram' },
          local_track_name: null,
        },
        name: 'South_station',
        arrival: null,
        stopFor: null,
        receptionSignal: 'OPEN',
        positionOnPath: 49103000,
        coordinates: [-0.16408630124250465, 49.46600036530178],
      },
    ],
    mapSettings: defaultMapSettings,
    constraintDistribution: 'MARECO',
    usingElectricalProfiles: true,
    usingSpeedLimits: true,
    stopsAtEndOfBlock: false,
    powerRestriction: [],
    speedLimitByTag: undefined,
    timeWindow: Duration.parse('PT3H'),
    interval: Duration.parse('PT1H'),
    editingItemType: 'pacedTrain',
    addedExceptions: [],
  };

  const rollingStockName = 'DUAL-MODE_RS_E2Ee';

  /**
   * Original paced train as stored in the backend.
   *   - Base name: 'test', start: 12:45, interval: 1h, time_window: 3h
   *   - Generates 3 indexed occurrences:
   *       index 0 → 'test 1' @ 12:45
   *       index 1 → 'test 3' @ 13:45
   *       index 2 → 'test 5' @ 14:45
   */
  const baseTimetableItemToEditData: {
    timetableItemId: number;
    originalPacedTrain: PacedTrainWithPacedWithDetails;
  } = {
    timetableItemId: 238,
    originalPacedTrain: {
      id: 238,
      train_schedule_set_id: 1000,
      name: 'test',
      startTime: new Date('2025-06-02T12:45:00.000Z'),
      category: { main_category: 'FREIGHT_TRAIN' },
      comfort: 'STANDARD',
      constraint_distribution: 'MARECO',
      initial_speed: 0,
      labels: [],
      margins: { boundaries: [], values: ['0%'] },
      options: {
        stops_at_end_of_block: false,
        use_electrical_profiles: true,
        use_speed_limits_for_simulation: true,
      },
      paced: {
        timeWindow: Duration.parse('PT3H'),
        interval: Duration.parse('PT1H'),
        exceptions: [],
      },
      path: [
        {
          id: '0-0',
          location: {
            operational_point: { trigram: 'WS', secondary_code: 'BV', type: 'trigram' },
            local_track_name: null,
          },
        },
        {
          id: '1-1',
          location: {
            operational_point: { trigram: 'SS', secondary_code: 'BV', type: 'trigram' },
            local_track_name: null,
          },
        },
      ],
      power_restrictions: [],
      schedule: [],
      speed_limit_tag: null,
      stopsCount: 1,
      speedLimitTag: null,
      rollingStockName: 'DUAL-MODE_RS_E2Ee',
      rollingStock: {
        id: 1,
        railjson_version: '3.3',
        name: 'DUAL-MODE_RS_E2Ee',
        metadata: {
          detail: 'dual-mode',
          family: '',
          type: '',
          grouping: '',
          series: '',
          subseries: '',
          unit: '',
          number: '',
          reference: 'dual-mode',
        },
        effort_curves: {
          modes: { thermal: { is_electric: false } },
          default_mode: 'thermal',
        },
        length: 350.0,
        max_speed: 44.44444444444444,
        base_power_class: '5',
        mass: 900000.0,
        loading_gauge: 'G1',
        power_restrictions: { C2: '1', C1: '3' },
        locked: false,
        supported_signaling_systems: [
          { type: 'BAL' },
          { type: 'BAPR' },
          { type: 'TVM300' },
          { type: 'TVM430' },
        ],
        liveries: [],
        primary_category: 'FREIGHT_TRAIN',
        other_categories: [],
      },
      summary: {
        isValid: true,
        duration: Duration.parse('PT1H32M12.133S'),
        pathLength: '101.0 km',
        mechanicalEnergyConsumed: 131,
        pathItemTimes: {
          base: [0, 5532133],
          provisional: [0, 5532133],
          final: [0, 5532133],
        },
        pathItemRespect: { margins: [true, true], times: [true, true] },
      },
    },
  };

  // ===========================================================================
  //  INDEXED OCCURRENCES  (auto-generated from cadence)
  // ===========================================================================

  describe('indexed occurrence', () => {
    // -------------------------------------------------------------------------
    // occurrenceIndex extraction
    // -------------------------------------------------------------------------

    describe('occurrenceIndex', () => {
      it('should return the numeric index extracted from the IndexedOccurrenceId', () => {
        const { occurrenceIndex } = formatOccurrenceException(
          { ...baseOsrdconf },
          rollingStockName,
          {
            ...baseTimetableItemToEditData,
            occurrenceId: 'indexedoccurrence_238_0' as IndexedOccurrenceId,
          }
        );
        expect(occurrenceIndex).toBe(0);
      });

      it('should return the correct index for a non-first occurrence', () => {
        const { occurrenceIndex } = formatOccurrenceException(
          { ...baseOsrdconf, startTime: new Date('2025-06-02T13:45:00.000Z'), name: 'test 3' },
          rollingStockName,
          {
            ...baseTimetableItemToEditData,
            occurrenceId: 'indexedoccurrence_238_1' as IndexedOccurrenceId,
          }
        );
        expect(occurrenceIndex).toBe(1);
      });
    });

    // -------------------------------------------------------------------------
    // No modification → empty diff
    // The caller uses an empty diff to delete an existing exception, making the
    // occurrence revert to the base paced train values.
    // -------------------------------------------------------------------------

    describe('when unmodified (occurrence matches the base paced train)', () => {
      it('should return an empty diff for an occurrence identical to the base', () => {
        const { generatedException } = formatOccurrenceException(
          { ...baseOsrdconf },
          rollingStockName,
          {
            ...baseTimetableItemToEditData,
            occurrenceId: 'indexedoccurrence_238_0' as IndexedOccurrenceId,
          }
        );
        expect(generatedException).toEqual({});
      });

      it('should ignore pre-existing exceptions — diff is always against the base paced train, not the current exception', () => {
        // Even if occurrence 0 already has an exception with rolling_stock_category,
        // submitting base-identical values produces an empty diff.
        const timetableItemData = {
          ...baseTimetableItemToEditData,
          originalPacedTrain: {
            ...baseTimetableItemToEditData.originalPacedTrain,
            paced: {
              ...baseTimetableItemToEditData.originalPacedTrain.paced,
              exceptions: [
                {
                  key: 'pre-existing',
                  occurrence_index: 0,
                  rolling_stock_category: { value: { main_category: 'INTERCITY_TRAIN' } },
                  id: 99,
                } as PacedTrainException,
              ],
            },
          },
        };

        const { generatedException } = formatOccurrenceException(
          { ...baseOsrdconf },
          rollingStockName,
          { ...timetableItemData, occurrenceId: 'indexedoccurrence_238_0' as IndexedOccurrenceId }
        );

        expect(generatedException).toEqual({});
      });

      it('should not include start_time when it matches base + index × interval', () => {
        // occurrence 2 → 12:45 + 2×1h = 14:45
        const { generatedException } = formatOccurrenceException(
          {
            ...baseOsrdconf,
            startTime: new Date('2025-06-02T14:45:00.000Z'),
            name: 'test 5', // computeOccurrenceName('test', 2)
          },
          rollingStockName,
          {
            ...baseTimetableItemToEditData,
            occurrenceId: 'indexedoccurrence_238_2' as IndexedOccurrenceId,
          }
        );
        expect(generatedException).not.toHaveProperty('start_time');
        expect(generatedException).not.toHaveProperty('train_name');
      });
    });

    // -------------------------------------------------------------------------
    // Reverting all changes → empty diff
    // Simulates: user modified an occurrence, then set every field back to base values.
    // The caller will use the empty diff to delete the exception, making the
    // occurrence revert to the base paced train.
    // -------------------------------------------------------------------------

    describe('when all changes are reverted to base values', () => {
      it('should return an empty diff when every field matches the base for occurrence 1', () => {
        const { generatedException } = formatOccurrenceException(
          {
            ...baseOsrdconf,
            category: { main_category: 'FREIGHT_TRAIN' },
            labels: [],
            speedLimitByTag: undefined,
            initialSpeed: 0,
            startTime: new Date('2025-06-02T13:45:00.000Z'), // base + 1×1h
            name: 'test 3', // computeOccurrenceName('test', 1)
          },
          rollingStockName,
          {
            ...baseTimetableItemToEditData,
            occurrenceId: 'indexedoccurrence_238_1' as IndexedOccurrenceId,
          }
        );
        expect(generatedException).toEqual({});
      });
    });

    // -------------------------------------------------------------------------
    // Modifying a single field → creates a diff with one change group
    // -------------------------------------------------------------------------

    describe('modifying a single field (creates one change group)', () => {
      it('should detect rolling_stock_category change', () => {
        const { generatedException } = formatOccurrenceException(
          { ...baseOsrdconf, category: { main_category: 'HIGH_SPEED_TRAIN' } },
          rollingStockName,
          {
            ...baseTimetableItemToEditData,
            occurrenceId: 'indexedoccurrence_238_0' as IndexedOccurrenceId,
          }
        );
        expect(generatedException).toMatchObject({
          rolling_stock_category: { value: { main_category: 'HIGH_SPEED_TRAIN' } },
        });
        // Only the changed field should appear
        expect(generatedException).not.toHaveProperty('start_time');
        expect(generatedException).not.toHaveProperty('train_name');
        expect(generatedException).not.toHaveProperty('labels');
      });

      it('should not include rolling_stock_category when category is unchanged', () => {
        const { generatedException } = formatOccurrenceException(
          { ...baseOsrdconf },
          rollingStockName,
          {
            ...baseTimetableItemToEditData,
            occurrenceId: 'indexedoccurrence_238_0' as IndexedOccurrenceId,
          }
        );
        expect(generatedException).not.toHaveProperty('rolling_stock_category');
      });

      it('should detect labels change', () => {
        const { generatedException } = formatOccurrenceException(
          { ...baseOsrdconf, labels: ['express'] },
          rollingStockName,
          {
            ...baseTimetableItemToEditData,
            occurrenceId: 'indexedoccurrence_238_0' as IndexedOccurrenceId,
          }
        );
        expect(generatedException).toMatchObject({
          labels: { value: ['express'] },
        });
      });

      it('should detect speed_limit_tag change (from null to a value)', () => {
        const { generatedException } = formatOccurrenceException(
          { ...baseOsrdconf, speedLimitByTag: 'V160' },
          rollingStockName,
          {
            ...baseTimetableItemToEditData,
            occurrenceId: 'indexedoccurrence_238_0' as IndexedOccurrenceId,
          }
        );
        expect(generatedException).toMatchObject({
          speed_limit_tag: { value: 'V160' },
        });
      });

      it('should not detect speed_limit_tag when set back to the base value', () => {
        const { generatedException } = formatOccurrenceException(
          { ...baseOsrdconf, speedLimitByTag: undefined },
          rollingStockName,
          {
            ...baseTimetableItemToEditData,
            occurrenceId: 'indexedoccurrence_238_0' as IndexedOccurrenceId,
          }
        );
        expect(generatedException).not.toHaveProperty('speed_limit_tag');
      });

      it('should detect initial_speed change', () => {
        const { generatedException } = formatOccurrenceException(
          { ...baseOsrdconf, initialSpeed: 30 }, // 30 km/h → converted to m/s
          rollingStockName,
          {
            ...baseTimetableItemToEditData,
            occurrenceId: 'indexedoccurrence_238_0' as IndexedOccurrenceId,
          }
        );
        expect(generatedException).toHaveProperty('initial_speed');
      });

      it('should detect rolling_stock change', () => {
        const { generatedException } = formatOccurrenceException(
          { ...baseOsrdconf },
          'OTHER_RS', // different from base 'DUAL-MODE_RS_E2Ee'
          {
            ...baseTimetableItemToEditData,
            occurrenceId: 'indexedoccurrence_238_0' as IndexedOccurrenceId,
          }
        );
        expect(generatedException).toMatchObject({
          rolling_stock: { rolling_stock_name: 'OTHER_RS' },
        });
      });

      it('should detect start_time change (differs from base + index × interval)', () => {
        // occurrence 0 → expected 12:45, we pass 13:00
        const { generatedException } = formatOccurrenceException(
          { ...baseOsrdconf, startTime: new Date('2025-06-02T13:00:00.000Z') },
          rollingStockName,
          {
            ...baseTimetableItemToEditData,
            occurrenceId: 'indexedoccurrence_238_0' as IndexedOccurrenceId,
          }
        );
        expect(generatedException).toMatchObject({
          start_time: { value: '2025-06-02T13:00:00.000Z' },
        });
      });

      it('should detect train_name change (differs from computed occurrence name)', () => {
        // computeOccurrenceName('test', 0) = 'test 1', we pass 'express 42'
        const { generatedException } = formatOccurrenceException(
          { ...baseOsrdconf, name: 'express 42' },
          rollingStockName,
          {
            ...baseTimetableItemToEditData,
            occurrenceId: 'indexedoccurrence_238_0' as IndexedOccurrenceId,
          }
        );
        expect(generatedException).toMatchObject({
          train_name: { value: 'express 42' },
        });
      });
    });

    // -------------------------------------------------------------------------
    // Modifying multiple fields at once
    // -------------------------------------------------------------------------

    describe('modifying multiple fields at once', () => {
      it('should include all changed fields and none of the unchanged ones', () => {
        const { generatedException } = formatOccurrenceException(
          {
            ...baseOsrdconf,
            category: { main_category: 'HIGH_SPEED_TRAIN' },
            labels: ['vip'],
            // startTime and name match occurrence 1 → no diff for those
            startTime: new Date('2025-06-02T13:45:00.000Z'),
            name: 'test 3', // computeOccurrenceName('test', 1)
          },
          rollingStockName,
          {
            ...baseTimetableItemToEditData,
            occurrenceId: 'indexedoccurrence_238_1' as IndexedOccurrenceId,
          }
        );

        expect(generatedException).toMatchObject({
          rolling_stock_category: { value: { main_category: 'HIGH_SPEED_TRAIN' } },
          labels: { value: ['vip'] },
        });
        expect(generatedException).not.toHaveProperty('start_time');
        expect(generatedException).not.toHaveProperty('train_name');
        expect(generatedException).not.toHaveProperty('speed_limit_tag');
      });

      it('should not be affected by exceptions belonging to other occurrence indices', () => {
        // occurrence 0 has a pre-existing exception with speed_limit_tag;
        // editing occurrence 2 should not see that
        const timetableItemData = {
          ...baseTimetableItemToEditData,
          originalPacedTrain: {
            ...baseTimetableItemToEditData.originalPacedTrain,
            paced: {
              ...baseTimetableItemToEditData.originalPacedTrain.paced,
              exceptions: [
                {
                  key: 'other-occurrence',
                  occurrence_index: 0,
                  speed_limit_tag: { value: 'V100' },
                } as PacedTrainException,
              ],
            },
          },
        };

        const { generatedException, occurrenceIndex } = formatOccurrenceException(
          {
            ...baseOsrdconf,
            labels: ['new-label'],
            startTime: new Date('2025-06-02T14:45:00.000Z'),
            name: 'test 5', // computeOccurrenceName('test', 2)
          },
          rollingStockName,
          { ...timetableItemData, occurrenceId: 'indexedoccurrence_238_2' as IndexedOccurrenceId }
        );

        expect(occurrenceIndex).toBe(2);
        expect(generatedException).toMatchObject({ labels: { value: ['new-label'] } });
        expect(generatedException).not.toHaveProperty('speed_limit_tag');
      });
    });
  });

  // ===========================================================================
  //  ADDED EXCEPTIONS  (manually added occurrences, not part of the cadence)
  // ===========================================================================

  describe('added exception', () => {
    // -------------------------------------------------------------------------
    // occurrenceIndex — always undefined for added exceptions
    // -------------------------------------------------------------------------

    describe('occurrenceIndex', () => {
      it('should return undefined (added exceptions have no cadence index)', () => {
        const { occurrenceIndex } = formatOccurrenceException(
          { ...baseOsrdconf, startTime: new Date('2025-06-02T16:00:00.000Z'), name: 'test/+' },
          rollingStockName,
          { ...baseTimetableItemToEditData, occurrenceId: 'exception_238_42' as AddedExceptionId }
        );
        expect(occurrenceIndex).toBeUndefined();
      });
    });

    // -------------------------------------------------------------------------
    // start_time — always present
    // An added exception has no natural position in the cadence, so the diff
    // must always carry its start_time.
    // -------------------------------------------------------------------------

    describe('start_time (always present)', () => {
      it('should always include start_time even when no other field is changed', () => {
        const { generatedException } = formatOccurrenceException(
          { ...baseOsrdconf, startTime: new Date('2025-06-02T16:00:00.000Z'), name: 'test/+' },
          rollingStockName,
          { ...baseTimetableItemToEditData, occurrenceId: 'exception_238_42' as AddedExceptionId }
        );
        expect(generatedException).toMatchObject({
          start_time: { value: '2025-06-02T16:00:00.000Z' },
        });
      });

      it('should include start_time even when it coincides with the base paced train start time', () => {
        // 12:45 is also the base start time, but an added exception always carries it
        const { generatedException } = formatOccurrenceException(
          { ...baseOsrdconf, name: 'test/+' },
          rollingStockName,
          { ...baseTimetableItemToEditData, occurrenceId: 'exception_238_42' as AddedExceptionId }
        );
        expect(generatedException).toHaveProperty('start_time');
      });
    });

    // -------------------------------------------------------------------------
    // train_name — default pattern is "baseName/+"
    // -------------------------------------------------------------------------

    describe('train_name', () => {
      it('should not include train_name when it matches the default "baseName/+" pattern', () => {
        const { generatedException } = formatOccurrenceException(
          { ...baseOsrdconf, startTime: new Date('2025-06-02T16:00:00.000Z'), name: 'test/+' },
          rollingStockName,
          { ...baseTimetableItemToEditData, occurrenceId: 'exception_238_42' as AddedExceptionId }
        );
        expect(generatedException).not.toHaveProperty('train_name');
      });

      it('should include train_name when the user gives a custom name', () => {
        const { generatedException } = formatOccurrenceException(
          {
            ...baseOsrdconf,
            startTime: new Date('2025-06-02T16:00:00.000Z'),
            name: 'special departure',
          },
          rollingStockName,
          { ...baseTimetableItemToEditData, occurrenceId: 'exception_238_42' as AddedExceptionId }
        );
        expect(generatedException).toMatchObject({
          train_name: { value: 'special departure' },
        });
      });
    });

    // -------------------------------------------------------------------------
    // Modifying other fields on an added exception
    // -------------------------------------------------------------------------

    describe('modifying fields', () => {
      it('should detect rolling_stock_category change alongside the mandatory start_time', () => {
        const { generatedException } = formatOccurrenceException(
          {
            ...baseOsrdconf,
            startTime: new Date('2025-06-02T16:00:00.000Z'),
            name: 'test/+',
            category: { main_category: 'HIGH_SPEED_TRAIN' },
          },
          rollingStockName,
          { ...baseTimetableItemToEditData, occurrenceId: 'exception_238_42' as AddedExceptionId }
        );
        expect(generatedException).toMatchObject({
          start_time: { value: '2025-06-02T16:00:00.000Z' },
          rolling_stock_category: { value: { main_category: 'HIGH_SPEED_TRAIN' } },
        });
        expect(generatedException).not.toHaveProperty('train_name');
      });

      it('should detect rolling_stock change', () => {
        const { generatedException } = formatOccurrenceException(
          {
            ...baseOsrdconf,
            startTime: new Date('2025-06-02T16:00:00.000Z'),
            name: 'test/+',
          },
          'ANOTHER_RS',
          { ...baseTimetableItemToEditData, occurrenceId: 'exception_238_42' as AddedExceptionId }
        );
        expect(generatedException).toMatchObject({
          start_time: { value: '2025-06-02T16:00:00.000Z' },
          rolling_stock: { rolling_stock_name: 'ANOTHER_RS' },
        });
      });

      it('should include multiple change groups alongside start_time', () => {
        const { generatedException } = formatOccurrenceException(
          {
            ...baseOsrdconf,
            startTime: new Date('2025-06-02T17:30:00.000Z'),
            name: 'extra train',
            labels: ['priority'],
            speedLimitByTag: 'V200',
          },
          rollingStockName,
          { ...baseTimetableItemToEditData, occurrenceId: 'exception_238_42' as AddedExceptionId }
        );
        expect(generatedException).toMatchObject({
          start_time: { value: '2025-06-02T17:30:00.000Z' },
          train_name: { value: 'extra train' },
          labels: { value: ['priority'] },
          speed_limit_tag: { value: 'V200' },
        });
      });
    });
  });

  // ===========================================================================
  //  ERROR CASES
  // ===========================================================================

  describe('error cases', () => {
    it('should throw when the original train has no paced field', () => {
      const timetableItemData = {
        ...baseTimetableItemToEditData,
        originalPacedTrain: {
          ...baseTimetableItemToEditData.originalPacedTrain,
          paced: undefined,
        },
      };

      expect(() =>
        formatOccurrenceException({ ...baseOsrdconf }, rollingStockName, {
          ...timetableItemData,
          occurrenceId: 'indexedoccurrence_238_0' as IndexedOccurrenceId,
        })
      ).toThrow(
        `PacedTrain payload (built from train ${baseTimetableItemToEditData.originalPacedTrain.id}) should have a paced field.`
      );
    });
  });
});
