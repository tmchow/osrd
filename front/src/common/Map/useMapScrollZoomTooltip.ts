import { type RefObject, useCallback, useEffect, useRef, useState } from 'react';

import type { MapRef } from 'react-map-gl/maplibre';

type UseMapScrollZoomTooltipProps = {
  enabled: boolean;
  mapIsLoaded: boolean;
  mapRef: RefObject<MapRef | null>;
};

type TooltipState = { isVisible: boolean; isFading: boolean };

const HIDE_DELAY_MS = 1000;
const FADE_DURATION_MS = 500;

const useMapScrollZoomTooltip = ({
  enabled,
  mapIsLoaded,
  mapRef,
}: UseMapScrollZoomTooltipProps): TooltipState => {
  const [tooltipState, setTooltipState] = useState<TooltipState>({
    isVisible: false,
    isFading: false,
  });

  const timeoutRef = useRef<number | null>(null);

  const clearTooltipTimeout = useCallback(() => {
    if (timeoutRef.current) window.clearTimeout(timeoutRef.current);
    timeoutRef.current = null;
  }, []);

  useEffect(() => {
    if (!enabled || !mapIsLoaded) return undefined;
    const canvas = mapRef.current?.getMap().getCanvas();
    if (!canvas) return undefined;

    const handleWheel = (event: WheelEvent) => {
      if (event.shiftKey || event.ctrlKey || event.metaKey) return;

      setTooltipState({ isVisible: true, isFading: false });
      clearTooltipTimeout();

      timeoutRef.current = window.setTimeout(() => {
        setTooltipState({ isVisible: true, isFading: true });
        timeoutRef.current = window.setTimeout(() => {
          setTooltipState({ isVisible: false, isFading: false });
          timeoutRef.current = null;
        }, FADE_DURATION_MS);
      }, HIDE_DELAY_MS);
    };

    canvas.addEventListener('wheel', handleWheel, { passive: true });
    return () => {
      canvas.removeEventListener('wheel', handleWheel);
      clearTooltipTimeout();
    };
  }, [clearTooltipTimeout, enabled, mapIsLoaded, mapRef]);

  return tooltipState;
};

export default useMapScrollZoomTooltip;
