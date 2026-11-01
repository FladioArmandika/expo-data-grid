import { ExpoDataGridViewProps } from './ExpoDataGrid.types';

// ExpoDataGridView is not available on the web platform.
export default function ExpoDataGridView(_props: ExpoDataGridViewProps) {
  throw new Error('ExpoDataGridView is not available on the web platform.');
}
