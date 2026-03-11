import React from 'react';

import type { Store } from '../../types';
import { drawPantographPositions } from '../helpers/drawElements/pantographPositions';
import { useCanvas } from '../hooks';

type PantographPositionsLayerProps = {
  width: number;
  height: number;
  store: Store;
};

const PantographPositionsLayer = ({ width, height, store }: PantographPositionsLayerProps) => {
  const canvas = useCanvas(drawPantographPositions, { width, height, store });

  return (
    <canvas
      id="pantograph-positions-layer"
      className="absolute rounded-t-xl"
      ref={canvas}
      width={width}
      height={height}
    />
  );
};

export default PantographPositionsLayer;
