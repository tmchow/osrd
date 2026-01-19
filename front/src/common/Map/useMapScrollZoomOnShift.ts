import { type RefObject, useEffect } from 'react';

import type { MapRef } from 'react-map-gl/maplibre';

import { clamp } from 'utils/numbers';

const WHEEL_ZOOM_FACTOR = 0.0025;
const MAX_ZOOM_DELTA_PER_EVENT = 0.75;

type UseMapScrollZoomOnShiftProps = {
  enabled: boolean;
  mapIsLoaded: boolean;
  mapRef: RefObject<MapRef | null>;
};

const useMapScrollZoomOnShift = ({
  enabled,
  mapIsLoaded,
  mapRef,
}: UseMapScrollZoomOnShiftProps) => {
  useEffect(() => {
    if (!enabled || !mapIsLoaded) return undefined;

    const mapInstance = mapRef.current?.getMap();
    const canvas = mapInstance?.getCanvas();
    if (!mapInstance || !canvas) return undefined;

    const handleWheel = (event: WheelEvent) => {
      if (!event.shiftKey || event.ctrlKey || event.metaKey) return;

      event.preventDefault();
      event.stopPropagation();

      // On macOS, Shift+scroll converts deltaY to deltaX (horizontal scroll).
      // MapLibre's native scroll zoom only handles deltaY, so we zoom manually
      // using whichever axis carries the scroll delta.
      const rawDelta = event.deltaY !== 0 ? event.deltaY : event.deltaX;
      if (rawDelta === 0) return;

      const rect = canvas.getBoundingClientRect();
      const around = mapInstance.unproject([
        event.clientX - rect.left,
        event.clientY - rect.top,
      ] as [number, number]);
      const zoomDelta = clamp(-rawDelta * WHEEL_ZOOM_FACTOR, [
        -MAX_ZOOM_DELTA_PER_EVENT,
        MAX_ZOOM_DELTA_PER_EVENT,
      ]);
      const nextZoom = clamp(mapInstance.getZoom() + zoomDelta, [
        mapInstance.getMinZoom(),
        mapInstance.getMaxZoom(),
      ]);

      mapInstance.easeTo({ zoom: nextZoom, around, duration: 0 });
    };

    // passive: false is required to call preventDefault()
    canvas.addEventListener('wheel', handleWheel, { passive: false });
    return () => canvas.removeEventListener('wheel', handleWheel);
  }, [enabled, mapIsLoaded, mapRef]);
};

export default useMapScrollZoomOnShift;
