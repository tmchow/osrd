import { type RefObject, useEffect } from 'react';

import type { MapWheelEvent, PointLike } from 'maplibre-gl';
import type { MapRef } from 'react-map-gl/maplibre';

type UseScrollZoomOnShiftProps = {
  enabled: boolean;
  mapIsLoaded: boolean;
  mapRef: RefObject<MapRef | null>;
};

const useScrollZoomOnShift = ({ enabled, mapIsLoaded, mapRef }: UseScrollZoomOnShiftProps) => {
  useEffect(() => {
    if (!enabled || !mapIsLoaded) return undefined;

    const mapInstance = mapRef.current?.getMap();
    if (!mapInstance) return undefined;

    const handleWheel = (event: MapWheelEvent) => {
      const nativeEvent = event.originalEvent;
      if (!nativeEvent?.shiftKey) return;
      nativeEvent.preventDefault();
      event.preventDefault();

      const rect = mapInstance.getCanvas().getBoundingClientRect();
      const point: PointLike = [nativeEvent.clientX - rect.left, nativeEvent.clientY - rect.top];
      const around = mapInstance.unproject(point);
      const rawDelta = nativeEvent.deltaY !== 0 ? nativeEvent.deltaY : nativeEvent.deltaX;
      if (rawDelta === 0) return;

      const nextZoom = mapInstance.getZoom() - rawDelta * 0.0025;
      mapInstance.easeTo({ zoom: nextZoom, around, duration: 0 });
    };

    mapInstance.on('wheel', handleWheel);
    return () => {
      mapInstance.off('wheel', handleWheel);
    };
  }, [enabled, mapIsLoaded, mapRef]);
};

export default useScrollZoomOnShift;
