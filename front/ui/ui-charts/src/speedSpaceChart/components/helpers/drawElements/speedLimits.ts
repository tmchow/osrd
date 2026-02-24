import type { DrawFunctionParams } from '../../../types';
import { ERROR_30, ERROR_60, MARGINS, WARNING_30 } from '../../const';
import {
  clearCanvas,
  maxPositionValue,
  maxSpeedValue,
  convertMToKm,
  positionToPosX,
} from '../../utils';

const { CURVE_MARGIN_TOP, MARGIN_RIGHT, MARGIN_LEFT, MARGIN_BOTTOM, MARGIN_TOP } = MARGINS;
const GRADIENT_HEIGHT = 16;
const TRAIN_LENGTH_MARKER_HEIGHT = 8;

export const drawSpeedLimits = ({ ctx, width, height, store }: DrawFunctionParams) => {
  const { mrsp, speedLimitCurves, trainLength, ratioX, leftOffset } = store;

  if (!mrsp) return;

  clearCanvas(ctx, width, height);

  ctx.save();
  ctx.translate(leftOffset, 0);

  const realHeight = height - MARGIN_BOTTOM - MARGIN_TOP;
  const maxSpeed = maxSpeedValue(store);
  const maxPosition = maxPositionValue(store.speeds);

  ctx.lineCap = 'round';

  const posToPosX = (position: number): number =>
    positionToPosX(position, maxPosition, width, ratioX);
  const speedToPosY = (speed: number): number =>
    realHeight - (speed / maxSpeed) * (realHeight - CURVE_MARGIN_TOP) + MARGIN_TOP;

  let previousBoundaryX = posToPosX(0);
  let previousSpeedY: number | null = null;
  for (let i = 0; i < mrsp.values.length; i++) {
    const { speed, isTemporary } = mrsp.values[i];

    const isLastValue = i == mrsp.values.length - 1;
    const currentBoundary = isLastValue ? maxPosition : mrsp.boundaries[i];
    const currentBoundaryX = posToPosX(currentBoundary);

    const speedY = speedToPosY(speed);
    // Draw vertical line joining 2 speed limits
    if (previousSpeedY !== null) {
      ctx.beginPath();
      ctx.strokeStyle = ERROR_30.alpha(0.5).hex();
      ctx.lineWidth = 0.5;
      ctx.moveTo(previousBoundaryX, previousSpeedY);
      ctx.lineTo(previousBoundaryX, speedY);
      ctx.stroke();
    }

    const speedColor = isTemporary ? WARNING_30 : ERROR_60;

    // Draw gradient
    const gradient = ctx.createLinearGradient(0, speedY, 0, speedY - GRADIENT_HEIGHT);
    if (isTemporary) {
      gradient.addColorStop(0, speedColor.alpha(0.23).hex());
    } else {
      gradient.addColorStop(0, speedColor.alpha(0.15).hex());
    }
    gradient.addColorStop(1, speedColor.alpha(0).hex());
    ctx.fillStyle = gradient;
    ctx.rect(previousBoundaryX, speedY, currentBoundaryX - previousBoundaryX, -GRADIENT_HEIGHT);
    ctx.fill();

    // Handle train length (only if next speed is higher than current speed)
    let extendedBoundaryX: number | null = null;
    if (!isLastValue && mrsp.values[i + 1].speed > speed) {
      extendedBoundaryX = posToPosX(
        Math.min(currentBoundary + convertMToKm(trainLength), maxPosition)
      );
    }

    // Draw main line
    ctx.beginPath();
    ctx.lineWidth = 1;
    ctx.strokeStyle = speedColor.hex();
    ctx.moveTo(previousBoundaryX, speedY);
    ctx.lineTo(extendedBoundaryX ?? currentBoundaryX, speedY);

    // Draw a small vertical line to mark the end of the train's length.
    if (extendedBoundaryX !== null) {
      ctx.moveTo(extendedBoundaryX, speedY - TRAIN_LENGTH_MARKER_HEIGHT / 2);
      ctx.lineTo(extendedBoundaryX, speedY + TRAIN_LENGTH_MARKER_HEIGHT / 2);
    }
    ctx.stroke();

    // Update previous values to current ones
    previousBoundaryX = currentBoundaryX;
    previousSpeedY = speedY;
  }

  ctx.restore();

  if (speedLimitCurves && speedLimitCurves.length > 0) {
    ctx.save();
    ctx.translate(leftOffset, 0);

    for (const curve of speedLimitCurves) {
      const { xs, ys } = curve;

      if (xs.length === 0 || xs.length !== ys.length) {
        continue;
      }

      const x0 = xs[0];
      const y0 = ys[0];

      ctx.beginPath();
      ctx.moveTo(posToPosX(x0), speedToPosY(y0));
      ctx.lineWidth = 2;
      ctx.strokeStyle = WARNING_30.hex();

      for (let i = 1; i < xs.length; i++) {
        const x = xs[i];
        const y = ys[i];
        ctx.lineTo(posToPosX(x), speedToPosY(y));
      }

      ctx.stroke();
    }

    ctx.restore();
  }

  // Prevent overlapping with y axis
  ctx.clearRect(0, 0, MARGIN_LEFT, height);
  ctx.clearRect(width - MARGIN_RIGHT, 0, MARGIN_RIGHT, height);
};
