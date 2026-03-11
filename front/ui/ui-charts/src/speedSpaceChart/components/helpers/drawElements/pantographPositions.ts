import type { DrawFunctionParams } from '../../../types';
import { MARGINS } from '../../const';
import { clearCanvas, maxPositionValue, convertMToKm, positionToPosX } from '../../utils';

const { CURVE_MARGIN_TOP, MARGIN_RIGHT, MARGIN_LEFT, MARGIN_BOTTOM, MARGIN_TOP } = MARGINS;

export const drawPantographPositions = ({ ctx, width, height, store }: DrawFunctionParams) => {
  const { pantographPositions, ratioX, leftOffset } = store;

  if (pantographPositions.length === 0) {
    return;
  }

  clearCanvas(ctx, width, height);

  ctx.save();
  ctx.translate(leftOffset, 0);

  const realHeight = height - MARGIN_BOTTOM - MARGIN_TOP;
  const maxPosition = maxPositionValue(store.speeds);

  const posToPosX = (position: number): number =>
    positionToPosX(convertMToKm(position), maxPosition, width, ratioX);
  const posToPosY = (position: number): number =>
    realHeight - position * (realHeight - CURVE_MARGIN_TOP) + MARGIN_TOP;

  ctx.beginPath();
  ctx.moveTo(posToPosX(pantographPositions[0].x), posToPosY(pantographPositions[0].y));
  let prevY = pantographPositions[0].y;
  for (const { x, y } of pantographPositions.slice(1, -1)) {
    if (prevY == y) {
      continue;
    }
    prevY = y;
    ctx.lineTo(posToPosX(x), posToPosY(y));
  }
  ctx.lineTo(
    posToPosX(pantographPositions[pantographPositions.length - 1].x),
    posToPosY(pantographPositions[pantographPositions.length - 1].y)
  );
  ctx.stroke();

  ctx.restore();

  // Prevent overlapping with y axis
  ctx.clearRect(0, 0, MARGIN_LEFT, height);
  ctx.clearRect(width - MARGIN_RIGHT, 0, MARGIN_RIGHT, height);
};
