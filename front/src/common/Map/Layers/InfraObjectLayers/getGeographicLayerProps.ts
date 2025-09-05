import type { Geometry } from 'geojson';
import type { ColorSpecification, DataDrivenPropertyValueSpecification } from 'maplibre-gl';
import type { LineLayerSpecification } from 'react-map-gl/maplibre';

import type { Theme } from 'types';

function getColorByHighlighted(data: {
  highlightedArea?: Geometry;
  highlightedTracksections?: string[];
  inColor: string;
  outColor: string;
}): DataDrivenPropertyValueSpecification<ColorSpecification> {
  if (data.highlightedTracksections && data.highlightedTracksections.length > 0)
    return [
      'case',
      ['in', ['get', 'id'], ['literal', data.highlightedTracksections]],
      data.inColor,
      data.outColor,
    ];
  if (data.highlightedArea)
    return ['case', ['within', data.highlightedArea], data.inColor, data.outColor];
  return data.inColor;
}

export default function geoMainLayer(
  theme: Theme,
  bigger = false,
  highlightedArea: Geometry | undefined = undefined,
  highlightedTracksections: string[] | undefined = undefined
): Omit<LineLayerSpecification, 'source'> {
  return {
    id: 'geoMainLayer',
    type: 'line',
    minzoom: 5,
    paint: {
      'line-color': getColorByHighlighted({
        highlightedArea,
        highlightedTracksections,
        inColor: theme.track.major,
        outColor: theme.muted.color,
      }),
      'line-width': bigger ? 4 : 1,
    },
  };
}
