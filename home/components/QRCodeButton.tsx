import { NavigationProp, useNavigation } from '@react-navigation/native';
import * as React from 'react';

import { ModalStackRoutes } from '../navigation/Navigation.types';
import {
  alertWithCameraPermissionInstructions,
  requestCameraPermissionsAsync,
} from '../utils/PermissionUtils';
import ListItem from './ListItem';

type Props = React.ComponentProps<typeof ListItem>;

function QRCodeButton(props: Props) {
  const navigation = useNavigation<NavigationProp<ModalStackRoutes>>();

  const handlePressAsync = async () => {
    if (await requestCameraPermissionsAsync()) {
      navigation.navigate('QRCode');
    } else {
      await alertWithCameraPermissionInstructions();
    }
  };
  return (
    <ListItem
      icon="qr-code-outline"
      title="Scan QR Code"
      subtitle="Open your projects without typing"
      onPress={handlePressAsync}
      last
      {...props}
    />
  );
}

export default QRCodeButton;
