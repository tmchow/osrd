import type { Geometry } from 'geojson';
import { isNil } from 'lodash';
import type {
  ColorSpecification,
  DataDrivenPropertyValueSpecification,
  FilterSpecification,
} from 'maplibre-gl';
import { Source, type LayerProps } from 'react-map-gl/maplibre';

import { MAP_URL } from 'common/Map/const';
import type { Theme } from 'types';

import getKPLabelLayerProps from './getKPLabelLayerProps';
import OrderedLayer from '../OrderedLayer';

type OperationalPointsProps = {
  colors: Theme;
  layerOrder: number;
  infraID: number | undefined;
  operationnalPointId?: string;
  highlightedArea?: Geometry;
  highlightedOperationalPoints?: number[];
};

function getColorByHighlighted(data: {
  highlightedArea?: Geometry;
  highlightedOperationalPoints?: number[];
  inColor: string;
  outColor: string;
}): DataDrivenPropertyValueSpecification<ColorSpecification> {
  if (data.highlightedOperationalPoints && data.highlightedOperationalPoints.length > 0)
    return [
      'case',
      ['in', ['get', 'extensions_sncf_ci'], ['literal', data.highlightedOperationalPoints]],
      data.inColor,
      data.outColor,
    ];
  if (data.highlightedArea)
    return ['case', ['within', data.highlightedArea], data.inColor, data.outColor];
  return data.inColor;
}

function getFilterHighlighted(data: {
  highlightedArea?: Geometry;
  highlightedOperationalPoints?: number[];
}): FilterSpecification {
  if (data.highlightedOperationalPoints && data.highlightedOperationalPoints.length > 0)
    return ['in', ['get', 'extensions_sncf_ci'], ['literal', data.highlightedOperationalPoints]];
  if (data.highlightedArea) return ['within', data.highlightedArea];
  return true;
}

const OperationalPointsLayer = ({
  colors,
  layerOrder,
  infraID,
  operationnalPointId,
  highlightedArea,
  highlightedOperationalPoints,
}: OperationalPointsProps) => {
  if (isNil(infraID)) return null;

  const point: LayerProps = {
    type: 'circle',
    'source-layer': 'operational_points',
    minzoom: 8,
    paint: {
      'circle-stroke-color': getColorByHighlighted({
        highlightedArea,
        highlightedOperationalPoints,
        inColor: colors.op.circle,
        outColor: colors.muted.color,
      }),
      'circle-stroke-width': 2,
      'circle-color': 'rgba(255, 255, 255, 0)',
      'circle-radius': 3,
    },
  };

  const name: LayerProps = {
    type: 'symbol',
    'source-layer': 'operational_points',
    minzoom: 9.5,
    layout: {
      'text-field': [
        'concat',
        ['get', 'extensions_identifier_name'],
        ' / ',
        ['get', 'extensions_sncf_trigram'],
        [
          'case',
          ['in', ['get', 'extensions_sncf_ch'], ['literal', ['BV', '00']]],
          '',
          ['concat', ' ', ['get', 'extensions_sncf_ch']],
        ],
      ],
      'text-font': [
        'case',
        ['==', ['get', 'id'], operationnalPointId || ''],
        ['literal', ['Roboto Bold']],
        ['literal', ['Roboto Condensed']],
      ],
      'text-size': 12,
      'text-anchor': 'left',
      'text-justify': 'left',
      'text-allow-overlap': false,
      'text-ignore-placement': false,
      'text-offset': [0.75, 0.1],
      'text-max-width': 32,
    },
    paint: {
      'text-color': getColorByHighlighted({
        highlightedArea,
        highlightedOperationalPoints,
        inColor: colors.op.text,
        outColor: colors.muted.color,
      }),
      'text-halo-width': 2,
      'text-halo-color': colors.op.halo,
      'text-halo-blur': 1,
    },
  };

  const yardName: LayerProps = {
    type: 'symbol',
    'source-layer': 'operational_points',
    minzoom: 9.5,
    filter: ['!', ['in', ['get', 'extensions_sncf_ch'], ['literal', ['BV', '00']]]],
    layout: {
      'text-field': '{extensions_sncf_ch_long_label}',
      'text-font': ['Roboto Condensed'],
      'text-size': 10,
      'text-anchor': 'left',
      'text-justify': 'left',
      'text-allow-overlap': false,
      'text-ignore-placement': false,
      'text-offset': [0.85, 1.9],
      'text-max-width': 32,
    },
    paint: {
      'text-color': getColorByHighlighted({
        highlightedArea,
        highlightedOperationalPoints,
        inColor: colors.op.minitext,
        outColor: colors.muted.color,
      }),
      'text-halo-width': 2,
      'text-halo-color': colors.op.halo,
      'text-halo-blur': 1,
    },
  };

  const trigram: LayerProps = {
    type: 'symbol',
    'source-layer': 'operational_points',
    maxzoom: 9.5,
    minzoom: 7,
    layout: {
      'text-field': [
        'concat',
        ['get', 'extensions_sncf_trigram'],
        ' ',
        [
          'case',
          ['in', ['get', 'extensions_sncf_ch'], ['literal', ['BV', '00']]],
          '',
          ['get', 'extensions_sncf_ch'],
        ],
      ],
      'text-font': [
        'case',
        ['==', ['get', 'id'], operationnalPointId || ''],
        ['literal', ['Roboto Bold']],
        ['literal', ['Roboto Condensed']],
      ],
      'text-size': 11,
      'text-anchor': 'left',
      'text-allow-overlap': false,
      'text-ignore-placement': false,
      'text-offset': [0.75, 0.1],
    },
    filter: getFilterHighlighted({ highlightedArea, highlightedOperationalPoints }),
    paint: {
      'text-color': colors.op.text,
      'text-halo-width': 2,
      'text-halo-color': colors.op.halo,
      'text-halo-blur': 1,
    },
  };

  return (
    <Source
      id="osrd_operational_point_geo"
      type="vector"
      url={`${MAP_URL}/layer/operational_points/mvt/geo/?infra=${infraID}`}
    >
      <OrderedLayer {...point} id="chartis/osrd_operational_point/geo" layerOrder={layerOrder} />
      <OrderedLayer
        {...yardName}
        id="chartis/osrd_operational_point_yardname/geo"
        layerOrder={layerOrder}
      />
      <OrderedLayer
        {...name}
        id="chartis/osrd_operational_point_name_short/geo"
        layerOrder={layerOrder}
      />
      <OrderedLayer
        {...trigram}
        id="chartis/osrd_operational_point_name/geo"
        layerOrder={layerOrder}
      />
      <OrderedLayer
        {...getKPLabelLayerProps({
          colors,
          minzoom: 9.5,
          sourceTable: 'operational_points',
          highlightedArea,
        })}
        id="chartis/osrd_operational_point_kp/geo"
        layerOrder={layerOrder}
      />
    </Source>
  );
};

export default OperationalPointsLayer;
