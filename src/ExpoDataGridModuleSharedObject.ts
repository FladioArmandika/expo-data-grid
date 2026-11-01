import { SharedObject, useReleasingSharedObject } from 'expo';

import ExpoDataGridModule from './ExpoDataGridModule';

export declare class ExpoDataGridModuleSharedObject extends SharedObject {
  count: number;
}

/**
 * Creates a new ExpoDataGridModuleSharedObject instance.
 * You are responsible for releasing it from memory by calling `release()` when done.
 */
export function createExpoDataGridModuleSharedObject(): ExpoDataGridModuleSharedObject {
  return new ExpoDataGridModule.ExpoDataGridModuleSharedObject();
}

/**
 * A hook that creates a ExpoDataGridModuleSharedObject instance and automatically
 * releases it when the component unmounts.
 */
export function useExpoDataGridModuleSharedObject(): ExpoDataGridModuleSharedObject {
  return useReleasingSharedObject(() => new ExpoDataGridModule.ExpoDataGridModuleSharedObject(), []);
}
