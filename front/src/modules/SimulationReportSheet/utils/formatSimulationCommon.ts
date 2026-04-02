import type { StdcmViaPathStep } from 'reducers/osrdconf/types';

type ConsistChangeParameters = { totalMass?: number; totalLength?: number };

type ConsistChangeContext = {
  consistBefore: ConsistChangeParameters;
  consistAfter: ConsistChangeParameters;
};

export function getConsistChangesAroundStep(
  opId: string,
  simulationPathSteps: StdcmViaPathStep[],
  initialConsist: ConsistChangeParameters
): ConsistChangeContext | undefined {
  const stepIndex = simulationPathSteps.findIndex((step) => step.operationalPoint?.id === opId);

  if (stepIndex === -1) return undefined;

  const consistChangeForThisStep = simulationPathSteps[stepIndex].consistChange;

  if (!consistChangeForThisStep) return undefined;

  const { totalLength, totalMass } = consistChangeForThisStep;
  const consistAfter = { totalLength, totalMass };

  let consistBefore: ConsistChangeParameters = initialConsist;
  for (let i = stepIndex - 1; i >= 0; i--) {
    if (simulationPathSteps[i].consistChange) {
      consistBefore = simulationPathSteps[i].consistChange!;
      break;
    }
  }

  return { consistBefore: consistBefore, consistAfter: consistAfter };
}
